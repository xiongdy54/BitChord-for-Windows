package com.music.bitchord.desktop.playback

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

    override fun play(url: String, headers: Map<String, String>) {
        val options = buildList {
            headers["Referer"]?.let { add(":http-referrer=$it") }
            headers["User-Agent"]?.let { add(":http-user-agent=$it") }
        }
        statsReported = false
        player.media().play(url, *options.toTypedArray())
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

    override fun release() {
        runCatching { player.release() }
        runCatching { factory.release() }
    }
}
