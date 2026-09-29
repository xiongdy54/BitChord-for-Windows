package com.music.bitchord.desktop

import com.music.bitchord.data.Http
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.desktop.playback.VlcAudioPlayer
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.retry
import com.music.bitchord.desktop.resources.shelf_recents
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.jetbrains.compose.resources.getString
import java.util.Locale

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
}

private suspend fun probe(args: Array<String>) {
    val query = args.firstOrNull()?.takeIf { it.isNotBlank() } ?: "周杰伦 晴天"
    InnerTubeXResolver.init()

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
                    println("     - ${item.title} · ${item.subtitle}")
                }
            }
        }
        .onFailure { println("home failed: ${it.javaClass.simpleName}: ${it.message}") }

    println()
    println("── search: $query ────────────────────")
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

    val song = songs.firstSong()
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
    repeat(15) { second ->
        delay(1_000)
        println("  t+${second + 1}s  playing=${vlc.isPlaying}  time=${vlc.timeMs}ms")
    }
    vlc.stop()
    vlc.release()
}

private fun List<SearchResult>.firstSong(): Song? =
    firstNotNullOfOrNull {
        when (it) {
            is SearchResult.TopTrack -> it.song
            is SearchResult.Track -> it.song
            is SearchResult.Browse -> null
        }
    }

private fun SearchResult.describe(): String = when (this) {
    is SearchResult.TopTrack -> "TOP  ${song.title} — ${song.artist} ${song.durationText.orEmpty()} [${song.videoId}]"
    is SearchResult.Track -> "SONG ${song.title} — ${song.artist} ${song.durationText.orEmpty()} [${song.videoId}]"
    is SearchResult.Browse -> "ITEM $item"
}

private fun String.abbreviate(): String = if (length > 180) take(180) + "…" else this
