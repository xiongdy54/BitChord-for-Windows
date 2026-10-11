package com.music.bitchord.desktop.playback

import com.music.bitchord.data.DebugLog
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

/**
 * libvlc, wrapped down to the handful of operations this player needs.
 *
 * The structural difference from the Android build: libvlc fetches the media
 * itself, over its own HTTP stack, where ExoPlayer shared the app's OkHttp
 * pool. Headers the resolve path decided on are forwarded as VLC media
 * options, which is as close as that can get — see [Http]'s note about
 * googlevideo binding a URL to the connection that minted it.
 */
class VlcAudioPlayer : AudioEngine {

    private val factory = MediaPlayerFactory("--no-video-title-show", "--quiet")
    private val player: MediaPlayer = factory.mediaPlayers().newMediaPlayer()

    override var onTime: ((Long) -> Unit)? = null
    override var onLength: ((Long) -> Unit)? = null
    override var onPlayingChanged: ((Boolean) -> Unit)? = null
    override var onFinished: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var onAudioStats: ((AudioStats) -> Unit)? = null

    /**
     * Whether this media has already had its say about what it is. libvlc opens
     * an input lazily, so the track description is not ready at `playing` — the
     * first ticks are the earliest honest moment. One report per media; a row
     * that never opens simply never reports.
     */
    private var statsReported = false

    init {
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) {
                onPlayingChanged?.invoke(true)
            }

            override fun paused(mediaPlayer: MediaPlayer) {
                onPlayingChanged?.invoke(false)
            }

            override fun stopped(mediaPlayer: MediaPlayer) {
                onPlayingChanged?.invoke(false)
                statsReported = false
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                onPlayingChanged?.invoke(false)
                statsReported = false
                onFinished?.invoke()
            }

            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                if (!statsReported) reportAudioStats(mediaPlayer)
                onTime?.invoke(newTime)
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                onLength?.invoke(newLength)
            }

            override fun error(mediaPlayer: MediaPlayer) {
                statsReported = false
                onError?.invoke("libvlc reported a playback error")
            }
        })
    }

    /**
     * Read what libvlc has opened so far and say it once. The audio track can
     * still be absent on the first tick — the flag stays down and the next
     * tick asks again, which is what makes a late-opening stream report rather
     * than a missed one.
     */
    private fun reportAudioStats(mediaPlayer: MediaPlayer) {
        val info = runCatching { mediaPlayer.media().info() }.getOrNull() ?: return
        val track = runCatching { info.audioTracks().firstOrNull() }.getOrNull() ?: return
        statsReported = true
        onAudioStats?.invoke(
            AudioStats(
                codecName = track.codecName(),
                // libvlc states the track bitrate in bits per second, and a
                // container that does not know reports zero — which is no
                // figure, not a slow one.
                bitrateKbps = track.bitRate().takeIf { it > 0 }?.div(1000),
                sampleRateHz = track.rate().takeIf { it > 0 },
                channels = track.channels().takeIf { it > 0 },
            ),
        )
    }

    /** What the player is doing right now, for callers that want to ask rather than wait. */
    override val isPlaying: Boolean get() = player.status().isPlaying()

    override val timeMs: Long get() = player.status().time()

    val lengthMs: Long get() = player.status().length()

    /**
     * The equaliser, held as one cached vlcj [Equalizer] for this player's
     * lifetime. libvlc's equalizer applies *per media player*, and — the
     * undocumented part — there is no read-back to ask a player what it is
     * running (`libvlc_media_player_get_equalizer` does not exist in 3.x), so
     * whether an equalizer survives a media open cannot be observed from the
     * API. vlcj's own `setEqualizer` re-creates the native instance on every
     * call, and nothing in libvlc 3.x's `media_player.c` re-seats the
     * equalizer when an input thread tears down, but the guarantee is not
     * written down anywhere official.
     *
     * So the reapply is defensive by design: [play] and [prepare] push the
     * current tuning again after every media open, the same
     * belt-and-braces the output-device switch's live-stream probe gets for
     * free. Cost is one idempotent native call per track — nothing against a
     * silence where the curve should be.
     */
    private var equalizerCache: uk.co.caprica.vlcj.player.base.Equalizer? = null

    /**
     * The last tuning handed to [setEqualizer], so the defensive reapply in
     * [play]/[prepare] has something to push and a change can be recognised.
     */
    @Volatile
    private var currentTuning: EqualizerTuning? = null

    /**
     * Map the settings onto libvlc's own ten-band equalizer. [tuning] null or
     * disabled detaches the equalizer outright (`setEqualizer(null)` lands as
     * `libvlc_media_player_set_equalizer(player, NULL)` in vlcj 4.12.1 —
     * verified in its bytecode, so passthrough is a real detach and not ten
     * flat filters standing in for one) — the shape of upstream's processor
     * being bypassed rather than fed a flat curve.
     *
     * One cached [Equalizer] object carries the values: its setters validate
     * against libvlc's ±20 dB and throw outside it, which the mapper's clamp
     * already prevented and [runCatching] backstops anyway — an equalizer
     * refusal is a settings hiccup, never a player crash.
     */
    override fun setEqualizer(tuning: EqualizerTuning?) {
        currentTuning = tuning
        runCatching {
            if (tuning == null || !tuning.enabled) {
                equalizerCache = null
                player.audio().setEqualizer(null)
                return
            }
            val equalizer = equalizerCache ?: factory.equalizer().newEqualizer()
                .also { equalizerCache = it }
            equalizer.setPreamp(tuning.preampDb)
            tuning.bandAmps.forEachIndexed { band, amp ->
                // libvlc's band count is fixed; a shorter table simply leaves
                // its tail at whatever it was, and a longer one cannot happen
                // from the mapper.
                if (band < equalizer.bandCount()) equalizer.setAmp(band, amp)
            }
            // Explicit rather than relying on vlcj's change listener: the
            // call is idempotent and proves the values landed.
            player.audio().setEqualizer(equalizer)
        }.onFailure {
            DebugLog.w("audio", "setEqualizer failed: ${it.message}")
        }
    }

    /**
     * The defensive reapply — [currentTuning] pushed again, because libvlc's
     * persistence of an equalizer across media opens is undocumented (see the
     * cache above). Off or never-set pushes a detach, which keeps "the
     * equalizer came off" true across media too.
     */
    private fun reapplyEqualizer() {
        setEqualizer(currentTuning)
    }

    override fun play(url: String, headers: Map<String, String>, startAtMs: Long) {
        val options = buildList {
            addHeaders(headers)
            // Input options apply at open time — the only race-free way to
            // land mid-media, which a stream swap mid-track needs (a
            // play-then-seek would have sound out before the seek landed).
            if (startAtMs > 0) add(":start-time=${startAtMs / 1000.0}")
        }
        statsReported = false
        player.media().play(withBasicAuth(url, headers), *options.toTypedArray())
        reapplyEqualizer()
    }

    /**
     * Opens [url] at [startAtMs] without sounding — the standby half of a
     * crossfade, which needs a track ready to be heard the moment its fader
     * moves and heard by nobody until then.
     *
     * The hold is libvlc's own `:start-paused` input option, applied at open
     * time beside `:start-time` (verified present in the installed
     * libvlccore 3.0.23). Both are input options, so the input never runs
     * free: there is no play-then-pause race here to document around, and the
     * `setVolume(0)` beside it is depth rather than the guarantee — the fade
     * drives this player's volume from its first tick anyway.
     */
    override fun prepare(url: String, headers: Map<String, String>, startAtMs: Long) {
        val options = buildList {
            addHeaders(headers)
            if (startAtMs > 0) add(":start-time=${startAtMs / 1000.0}")
            add(":start-paused")
        }
        statsReported = false
        player.media().play(withBasicAuth(url, headers), *options.toTypedArray())
        reapplyEqualizer()
        player.audio().setVolume(0)
    }

    /** The fetch headers libvlc has switches for — see [withBasicAuth] for the one it has not. */
    private fun MutableList<String>.addHeaders(headers: Map<String, String>) {
        headers["Referer"]?.let { add(":http-referrer=$it") }
        headers["User-Agent"]?.let { add(":http-user-agent=$it") }
    }

    override fun pause() {
        player.controls().setPause(true)
    }

    override fun resume() {
        player.controls().setPause(false)
    }

    override fun stop() {
        player.controls().stop()
    }

    override fun seekTo(fraction: Float) {
        player.controls().setPosition(fraction.coerceIn(0f, 1f))
    }

    /** VLC's own scale, where 100 is unity. */
    override fun setVolume(percent: Int) {
        player.audio().setVolume(percent.coerceIn(0, 100))
    }

    /**
     * The WASAPI endpoints libvlc's active output module (mmdevice on Windows)
     * lists, verbatim from its own enumeration. A blocking native call — the
     * dialog that wants the list makes it on an IO dispatcher. libvlc's
     * lister is the one part of this pair that can refuse (no active aout yet,
     * an odd module); an empty list is what that reads as, never a crash.
     *
     * The "Default" entry mmdevice lists first is left out: it carries an
     * empty id, so it is not a device that can be asked for again — and the
     * session default it stands for is what [setAudioOutputDevice] with null
     * already says.
     */
    override fun audioOutputDevices(): List<AudioOutputDevice> =
        runCatching {
            player.audio().outputDevices()
                ?.filter { !it.getDeviceId().isNullOrBlank() }
                ?.map { AudioOutputDevice(id = it.getDeviceId(), name = it.getLongName()) }
                .orEmpty()
        }.getOrDefault(emptyList())

    /**
     * Route playback to [deviceId]; null asks libvlc for its platform default.
     * The output module is passed as null too — libvlc applies the id to
     * whichever module is already driving the sound, which on Windows is
     * mmdevice and never changes under us.
     *
     * Probed on the reference machine (slice 9 implementation record): the
     * switch reaches the *live* stream — VLC's mmdevice aout re-seats itself on
     * the new endpoint without the media being reopened — and a null id
     * returns the stream to whatever the system default currently is.
     */
    override fun setAudioOutputDevice(deviceId: String?) {
        runCatching {
            player.audio().setOutputDevice(null, deviceId)
        }.onFailure {
            DebugLog.w("audio", "setOutputDevice(${deviceId ?: "default"}) failed: ${it.message}")
        }
    }

    override fun release() {
        runCatching { player.release() }
        runCatching { factory.release() }
    }
}

/**
 * Where a `Basic` credential goes when the fetcher is libvlc.
 *
 * VLC's HTTP access module has no arbitrary-header media option — the header
 * seam [PlayerController] resolves through can carry an `Authorization` line,
 * but there is no `:http-header-…` switch to map it onto, unlike the referrer
 * and user-agent options above. The one channel libvlc 3.x does offer for
 * HTTP Basic auth is the MRL's own userinfo, which the module turns into the
 * `Authorization` header itself. So that is what this mapping does: decode
 * the header's base64 payload and re-embed it as `scheme://user:pass@host/…`,
 * percent-encoded for the parts a password may legitimately contain.
 *
 * A header that is not Basic (nothing else is produced today), a payload that
 * does not decode, or a URL that already carries credentials passes through
 * unchanged — the last so an MRL built by hand is never rewritten.
 */
internal fun withBasicAuth(url: String, headers: Map<String, String>): String {
    val header = headers["Authorization"]?.takeIf { it.startsWith("Basic ", ignoreCase = true) }
        ?: return url
    val credentials = runCatching {
        String(
            java.util.Base64.getDecoder().decode(header.substringAfter(' ').trim()),
            Charsets.UTF_8,
        )
    }.getOrNull() ?: return url
    val separator = credentials.indexOf(':')
    if (separator < 0) return url
    val user = credentials.substring(0, separator)
    val pass = credentials.substring(separator + 1)
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return url
    val rest = url.substring(schemeEnd + 3)
    if (rest.substringBefore('/').contains('@')) return url
    fun encode(part: String) = java.net.URLEncoder.encode(part, Charsets.UTF_8).replace("+", "%20")
    return url.substring(0, schemeEnd + 3) + encode(user) + ":" + encode(pass) + "@" + rest
}
