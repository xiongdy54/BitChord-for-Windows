package com.music.bitchord.desktop

import com.music.bitchord.data.DebugLog
import com.music.bitchord.data.Http
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.desktop.playback.AudioStats
import com.music.bitchord.desktop.playback.LibVlc
import com.music.bitchord.desktop.playback.VLC_BAND_CENTERS_HZ
import com.music.bitchord.desktop.playback.VlcAudioPlayer
import com.music.bitchord.desktop.playback.equalizerTuning
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.retry
import com.music.bitchord.desktop.resources.shelf_recents
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.jetbrains.compose.resources.getString
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.roundToInt
import kotlin.system.exitProcess

/**
 * Headless smoke test for the ported data layer: browse the home feed, search
 * for a track, resolve a stream URL for the first song found and fetch a slice
 * of it. The point is to prove the whole chain — Innertube, InnerTubeX,
 * NewPipe — works from this JVM before any UI or player depends on it.
 *
 *     ./gradlew -p desktop probe
 *     ./gradlew -p desktop probe -PprobeQuery="Radiohead"
 */
fun main(args: Array<String>) {
    runBlocking { probe(args) }
    // The CEF threads (and the resolver's scope) are not daemons: without this
    // the JVM hangs around after the last line is printed, holding the `cef/`
    // cache directory — and the next probe's CEF then fails to start at all
    // (Chromium's one-instance-per-user-data-dir lock). The app has a real
    // shutdown path for this; the probe's is a hard exit.
    com.music.bitchord.auth.CefRuntime.shutdown()
    exitProcess(0)
}

private suspend fun probe(args: Array<String>) {
    val query = args.firstOrNull()?.takeIf { it.isNotBlank() } ?: "陈奕迅 致明日的舞"
    InnerTubeXResolver.init()

    // Speak as whoever the app is signed in as — the probe is the app's headless
    // shadow, and every resolver verdict depends on the session. Without this a
    // probe run reads as an anonymous stranger, which is exactly the identity
    // YouTube's bot walls refuse first.
    com.music.bitchord.auth.AuthStore().activeSession?.let { stored ->
        com.music.bitchord.data.innertube.Innertube.cookie = stored.cookie
        val profile = stored.profiles.firstOrNull { it.profileId == stored.activeProfileId }
        com.music.bitchord.data.innertube.Innertube.selectChannel(
            profile?.pageId, profile?.dataSyncId, profile?.authUser,
        )
        println("session restored: ${stored.email.ifBlank { stored.name }}")
    }

    // Headless scan of the local music folders — the slice-11 probe. No
    // network, no window: walk, filter, read, print. `bitchord.probeLocalScan`
    // names one folder to scan (the app's own setting is deliberately not
    // read, so a probe run leaves no mark and works before any folder is
    // configured); timings and a sample of the rows go to stdout.
    System.getProperty("bitchord.probeLocalScan")?.takeIf { it.isNotBlank() }?.let { path ->
        println("── local scan: $path ─────────────────")
        val started = System.currentTimeMillis()
        val session = com.music.bitchord.data.metadata.LocalMediaMeta.Session.open()
        try {
            val songs = com.music.bitchord.data.LocalMediaRepository.scan(listOf(path)) {
                session.read(it)
            }
            val elapsed = System.currentTimeMillis() - started
            DebugLog.d("BitChord", "probeLocalScan: ${songs.size} songs in ${elapsed}ms from $path")
            println("songs=${songs.size} elapsed=${elapsed}ms")
            val albums = songs.mapNotNull { it.albumName }.distinct().size
            val artists = songs.map { it.artist }.distinct().size
            val dated = songs.count { it.localDateAddedSeconds != null }
            val withDuration = songs.count { it.durationText != null }
            println("artists=$artists albums=$albums withDuration=$withDuration withDates=$dated")
            songs.take(12).forEach { song ->
                println(
                    "  ${song.title} — ${song.artist}" +
                        " [${song.albumName ?: "-"} · ${song.durationText ?: "-"} · ${song.videoId}]",
                )
            }
        } finally {
            session.close()
        }
        return
    }

    // The motion-artwork probe — the slice-13 one. `bitchord.probeCanvas`
    // names a videoId: the track's metadata is resolved the way the player
    // resolves it, the four-provider chain runs against it, and the verdict —
    // which provider answered, what it answered, and whether the answer
    // survives the strict re-check — goes to stdout. The catalogue search is
    // network behaviour with no visual half, so this is the probe that can
    // prove the chain before any window is opened at all.
    System.getProperty("bitchord.probeCanvas")?.takeIf { it.isNotBlank() }?.let { videoId ->
        println("── canvas resolve: $videoId ───────────")
        val song = YtMusicRepository.trackLinks(videoId).getOrNull()
        if (song == null) {
            println("metadata resolve failed; the chain needs a title and an artist to search on")
            return
        }
        println(
            "track: ${song.title} — ${song.artist}" +
                (song.albumName?.let { " · $it" } ?: ""),
        )
        val started = System.currentTimeMillis()
        val found = com.music.bitchord.data.canvas.CanvasRepository.canvasFor(song)
        val elapsed = System.currentTimeMillis() - started
        if (found == null) {
            println("canvas: none (${elapsed}ms)")
        } else {
            println("canvas: source=${found.source} in ${elapsed}ms")
            println("  url: ${found.url}")
            found.fallbackUrl?.let { println("  fallback: $it") }
            println("  title=${found.title} artist=${found.artist} album=${found.album}")
            println("  matches track: ${found.matches(song.title, song.artist, song.albumName)}")
        }
        DebugLog.d("BitChord", "probeCanvas: $videoId -> ${found?.url ?: "none"}")
        return
    }

    // The PoToken-minting probe. `bitchord.probePoToken` names a videoId: the
    // hidden CEF browser boots BotGuard and mints for it, and the whole story —
    // browser up, minter created, token bytes, elapsed — goes to stdout. This is
    // the one probe that can prove the CEF BotGuard port before any playback
    // depends on it, because the mint itself needs no GUI.
    System.getProperty("bitchord.probePoToken")?.takeIf { it.isNotBlank() }?.let { videoId ->
        println("── poToken mint: $videoId ────────────")
        val started = System.currentTimeMillis()
        try {
            val provider = com.music.bitchord.data.innertube.potoken.PoTokenCefProvider
            // The session's visitor data is what the mint binds to; the probe
            // fetches one the same way the resolver's warm path does.
            val visitor = com.music.bitchord.data.innertube.Innertube.ensureVisitorData()
            println("visitorData: ${visitor?.take(12)}…")
            val (streamingPot, playerPot) = provider.poToken(videoId, visitor.orEmpty())
            println("streaming pot: ${streamingPot.take(16)}… (${streamingPot.length} chars)")
            println("player pot:    ${playerPot.take(16)}… (${playerPot.length} chars)")
            println("elapsed=${System.currentTimeMillis() - started}ms — MINT OK")
        } catch (e: Exception) {
            println("MINT FAILED after ${System.currentTimeMillis() - started}ms: ${e::class.simpleName}: ${e.message}")
        }
        return
    }

    // The bundled-libVLC probe. No network, no window: where libvlc actually
    // came from (the app's own bundle, or the machine's installed VLC when
    // the bundle is absent), the version it reports, and a synthetic WAV run
    // through the real engine at volume zero — demuxer, decoder, audio
    // output and event wiring, all from whatever libvlc the app resolved.
    // The headless proof that the `syncLibVlc` bundle is a libvlc that plays
    // before any window has to be opened to find out.
    if (System.getProperty("bitchord.probeLibVlc") == "true") {
        println("── libVLC bundle ─────────────────────")
        println("bundle: ${LibVlc.bundledDir?.path ?: "(none — vlcj falls back to the installed VLC)"}")
        val factory = LibVlc.newFactory("--quiet")
        try {
            println("jna.library.path: ${System.getProperty("jna.library.path") ?: "(unset)"}")
            println("libvlc: ${factory.application().version()}")
            val wav = File.createTempFile("bitchord-probe-", ".wav")
            try {
                writeSineWav(wav, seconds = 1.2)
                val vlc = VlcAudioPlayer()
                try {
                    vlc.setVolume(0) // the beep nobody hears — the output path still runs
                    val done = CountDownLatch(1)
                    var stats: AudioStats? = null
                    vlc.onAudioStats = { stats = it }
                    vlc.onFinished = { done.countDown() }
                    vlc.onError = { done.countDown() }
                    vlc.play(wav.absolutePath)
                    val finished = done.await(8, TimeUnit.SECONDS)
                    println("sine wav: finished=$finished stats=$stats endpoints=${vlc.audioOutputDevices().size}")
                    if (!finished) println("sine wav: TIMED OUT — libvlc never reported the end of media")
                } finally {
                    vlc.release()
                }
            } finally {
                wav.delete()
            }
        } finally {
            factory.release()
        }
        return
    }

    // No network wanted: the engine's own view of the machine's sound
    // endpoints, id and all — the settings dialog's radio list is exactly
    // this list.
    if (System.getProperty("bitchord.probeAudioDevices") == "true") {
        println("── audio output devices ──────────────")
        val vlc = VlcAudioPlayer()
        val devices = vlc.audioOutputDevices()
        if (devices.isEmpty()) {
            println("(none enumerated)")
        }
        devices.forEach { println("  ${it.name}  [${it.id}]") }
        vlc.release()
        return
    }

    // The equalizer-mapping probe — the slice-15 one. `bitchord.probeEqualizer`
    // names a preset ("rock") or seven comma band values ("4,2.5,-1,…"): the
    // curve model renders it, the mapper samples it at libvlc's ten band
    // centres, and the whole table goes to stdout — the mapping maths is
    // scriptable where the listening is not. A VLC present on the machine
    // then receives the tuning for real, proving the native path accepts it.
    System.getProperty("bitchord.probeEqualizer")?.takeIf { it.isNotBlank() }?.let { spec ->
        println("── equalizer mapping: $spec ──────────")
        val preset = com.music.bitchord.playback.EqualizerPreset.entries.firstOrNull {
            it.name.equals(spec.replace(' ', '_'), ignoreCase = true)
        }
        val bands: List<Float> = when {
            preset != null && preset != com.music.bitchord.playback.EqualizerPreset.CUSTOM ->
                preset.bands
            else -> spec.split(",").mapNotNull { it.trim().toFloatOrNull() }
        }
        if (bands.size != com.music.bitchord.playback.EqLayout.MANUAL_COUNT) {
            println("need a preset name or ${com.music.bitchord.playback.EqLayout.MANUAL_COUNT} comma values, got: $bands")
            return
        }
        val curve = com.music.bitchord.playback.manualCurve(bands)
        println("model bands (Hz/dB):")
        com.music.bitchord.playback.EqLayout.MANUAL_BANDS_HZ.forEachIndexed { i, hz ->
            println("  ${label(hz)}: ${formatDb(bands.getOrElse(i) { 0f })}")
        }
        println("model preamp: ${formatDb(curve.preampDb)}")
        val tuning = equalizerTuning(curve, enabled = true)
        println("vlc bands (libvlc 3.x centres):")
        VLC_BAND_CENTERS_HZ.forEachIndexed { i, hz ->
            println("  ${label(hz)}: ${formatDb(tuning.bandAmps.getOrElse(i) { 0f })}")
        }
        println("vlc preamp: ${formatDb(tuning.preampDb)}")
        println("flat check: ${equalizerTuning(com.music.bitchord.playback.EqCurve.FLAT, true)}")
        val vlc = VlcAudioPlayer()
        vlc.setEqualizer(tuning)
        println("applied to a live libvlc player: ok")
        // Off is a detach, not a flat equalizer — push it and release.
        vlc.setEqualizer(null)
        vlc.release()
        return
    }

    // The string pipeline, checked before anything that needs the network: the
    // app's own strings.xml has to reach Res.string.* in every locale it ships.
    println("── strings ───────────────────────────")
    println("machine locale = ${Locale.getDefault()}")
    Locale.setDefault(Locale.US)
    println("en: recents=${getString(Res.string.shelf_recents)} retry=${getString(Res.string.retry)}")
    Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
    println("zh: recents=${getString(Res.string.shelf_recents)} retry=${getString(Res.string.retry)}")

    println("── home ──────────────────────────────")
    YtMusicRepository.home()
        .onSuccess { feed ->
            println("shelves=${feed.shelves.size} continuation=${feed.continuation != null}")
            feed.shelves.take(6).forEach { shelf ->
                println("  【${shelf.title}】 ${shelf.items.size} items")
                shelf.items.take(3).forEach { item ->
                    // The two id fields decide what a click can do: a videoId plays,
                    // a browseId opens a collection — and the home feed's cards are
                    // mostly collections.
                    println("     - ${item.title} · ${item.subtitle}")
                    println("       videoId=${item.videoId} browseId=${item.browseId}")
                }
            }
        }
        .onFailure { println("home failed: ${it.javaClass.simpleName}: ${it.message}") }

    println()
    println("── collection from the home feed ─────")
    val collection = YtMusicRepository.home().getOrNull()?.shelves
        ?.firstNotNullOfOrNull { shelf -> shelf.items.firstOrNull { it.browseId != null } }
    if (collection?.browseId == null) {
        println("no collection on the feed to open")
    } else {
        println("browseId=${collection.browseId} (${collection.title})")
        YtMusicRepository.browseSongs(collection.browseId)
            .onSuccess { page ->
                println("songs=${page.songs.size}")
                page.songs.take(4).forEach { println("  ${it.title} — ${it.artist} [${it.videoId}]") }
            }
            .onFailure { println("browseSongs failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    val songs = YtMusicRepository.search(query, SearchFilter.SONGS)
        .onSuccess { rows ->
            println("${rows.size} rows")
            rows.take(10).forEach { println("  " + it.describe()) }
        }
        .onFailure { println("search failed: ${it.javaClass.simpleName}: ${it.message}") }
        .getOrNull()
        .orEmpty()

    println()
    println("── search (all): $query ──────────────")
    YtMusicRepository.search(query, SearchFilter.ALL)
        .onSuccess { rows ->
            println("${rows.size} rows")
            rows.take(6).forEach { println("  " + it.describe()) }
        }
        .onFailure { println("search all failed: ${it.javaClass.simpleName}: ${it.message}") }

    val song = if (System.getProperty("bitchord.probeVideoId") != null) {
        // Search does not always answer with Track rows (regional answers,
        // consent walls); a caller who already knows the videoId skips the
        // guessing game entirely.
        Song(System.getProperty("bitchord.probeVideoId"), "Probe track", "Probe artist", null)
    } else {
        songs.firstSong()
    }
    println()
    if (song == null) {
        println("── stream resolve ── skipped, no song in the results above")
        return
    }
    println("── stream resolve: ${song.title} — ${song.artist} [${song.videoId}] ──")

    val started = System.currentTimeMillis()
    val url = runCatching { StreamResolver.resolve(song.videoId) }
        .onFailure { println("resolve failed: ${it.javaClass.simpleName}: ${it.message}") }
        .getOrNull() ?: return
    println("resolved in ${System.currentTimeMillis() - started}ms")
    println("url: ${url.abbreviate()}")

    val headers = StreamResolver.mediaHeadersFor(url)
    println("media headers: ${headers.keys}")

    // Independent proof the URL serves bytes: a small ranged GET wearing the
    // same headers the player will send. `resolve` has already probed it once;
    // this shows what came back.
    val request = Request.Builder()
        .url(url)
        .header("Range", "bytes=0-65535")
        .apply { headers.forEach { (key, value) -> header(key, value) } }
        .build()
    runCatching {
        Http.client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes()?.size ?: 0
            println("fetch: HTTP ${response.code} · $bytes bytes · ${response.header("Content-Type")}")
        }
    }.onFailure { println("fetch failed: ${it.javaClass.simpleName}: ${it.message}") }

    if (System.getProperty("bitchord.probePlay") == "true") {
        playForAWhile(url, headers)
    }
}

/**
 * Hands the URL to libvlc and watches the clock. The player's own time is the
 * proof: it only advances while audio is actually being decoded and sent to
 * the output, so a track that is refused comes back as `playing=false` and a
 * clock stuck at zero.
 *
 * `bitchord.probeSwitchDevice=<id>` names an endpoint to switch to five
 * seconds in — the answer to "does the swap reach the live stream" is in
 * whether the clock keeps advancing through the switch without an error.
 */
private suspend fun playForAWhile(url: String, headers: Map<String, String>) {
    println()
    println("── playback (libvlc) ─────────────────")
    val vlc = VlcAudioPlayer()
    vlc.onPlayingChanged = { println("vlc: playing=$it") }
    vlc.onLength = { println("vlc: length=${it}ms") }
    vlc.onError = { println("vlc: error $it") }
    vlc.setVolume(60)
    vlc.play(url, headers)
    val switchTo = System.getProperty("bitchord.probeSwitchDevice")
    repeat(15) { second ->
        delay(1_000)
        if (second == 5 && switchTo != null) {
            // `default` is the null id — the way back to the system default,
            // which the enumeration itself has no entry for.
            val id = switchTo.takeUnless { it == "default" }
            println("vlc: switching output device -> ${id ?: "system default"}")
            vlc.setAudioOutputDevice(id)
        }
        println("  t+${second + 1}s  playing=${vlc.isPlaying}  time=${vlc.timeMs}ms")
    }
    vlc.stop()
    vlc.release()
}

/**
 * A 440 Hz sine, 16-bit mono at 44.1 kHz, written through the JDK's own WAV
 * writer — a real container for the demuxer to open and the decoder to feed,
 * at an amplitude the probe's volume zero makes a formality anyway.
 */
private fun writeSineWav(file: File, seconds: Double) {
    val sampleRate = 44_100f
    val format = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate, 16, 1, 2, sampleRate, false)
    val frames = (sampleRate * seconds).toInt()
    val pcm = ByteArray(frames * 2)
    for (i in 0 until frames) {
        val sample = (Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 2_000).toInt()
        pcm[2 * i] = (sample and 0xFF).toByte()
        pcm[2 * i + 1] = ((sample shr 8) and 0xFF).toByte()
    }
    AudioSystem.write(
        AudioInputStream(java.io.ByteArrayInputStream(pcm), format, frames.toLong()),
        AudioFileFormat.Type.WAVE,
        file,
    )
}

private fun List<SearchResult>.firstSong(): Song? =
    firstNotNullOfOrNull {
        when (it) {
            is SearchResult.TopTrack -> it.song
            is SearchResult.Track -> it.song
            is SearchResult.Browse -> null
        }
    }

/** "60", "2.5k", "16k" — the equalizer table's frequency column. */
private fun label(hz: Float): String = when {
    hz < 1_000f -> "${hz.roundToInt()}Hz"
    hz % 1_000f == 0f -> "${(hz / 1_000f).roundToInt()}k"
    else -> String.format(Locale.US, "%.2fk", hz / 1_000f)
}

/** Signed decibels, one decimal — the table's gain column. */
private fun formatDb(db: Float): String =
    String.format(Locale.US, "%+.1f dB", db)


private fun SearchResult.describe(): String = when (this) {
    is SearchResult.TopTrack -> "TOP  ${song.title} — ${song.artist} ${song.durationText.orEmpty()} [${song.videoId}]"
    is SearchResult.Track -> "SONG ${song.title} — ${song.artist} ${song.durationText.orEmpty()} [${song.videoId}]"
    is SearchResult.Browse -> "ITEM $item"
}

private fun String.abbreviate(): String = if (length > 180) take(180) + "…" else this
