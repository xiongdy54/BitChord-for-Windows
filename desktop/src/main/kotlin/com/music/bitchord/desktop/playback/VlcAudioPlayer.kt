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

    override fun play(url: String, headers: Map<String, String>, startAtMs: Long) {
        val options = buildList {
            headers["Referer"]?.let { add(":http-referrer=$it") }
            headers["User-Agent"]?.let { add(":http-user-agent=$it") }
            // Input options apply at open time — the only race-free way to
            // land mid-media, which a stream swap mid-track needs (a
            // play-then-seek would have sound out before the seek landed).
            if (startAtMs > 0) add(":start-time=${startAtMs / 1000.0}")
        }
        statsReported = false
        player.media().play(withBasicAuth(url, headers), *options.toTypedArray())
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
