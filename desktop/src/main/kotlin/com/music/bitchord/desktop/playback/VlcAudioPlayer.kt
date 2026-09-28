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
class VlcAudioPlayer {

    private val factory = MediaPlayerFactory("--no-video-title-show", "--quiet")
    private val player: MediaPlayer = factory.mediaPlayers().newMediaPlayer()

    var onTime: ((Long) -> Unit)? = null
    var onLength: ((Long) -> Unit)? = null
    var onPlayingChanged: ((Boolean) -> Unit)? = null
    var onFinished: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

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
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                onPlayingChanged?.invoke(false)
                onFinished?.invoke()
            }

            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                onTime?.invoke(newTime)
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                onLength?.invoke(newLength)
            }

            override fun error(mediaPlayer: MediaPlayer) {
                onError?.invoke("libvlc reported a playback error")
            }
        })
    }

    /** What the player is doing right now, for callers that want to ask rather than wait. */
    val isPlaying: Boolean get() = player.status().isPlaying()

    val timeMs: Long get() = player.status().time()

    val lengthMs: Long get() = player.status().length()

    fun play(url: String, headers: Map<String, String>) {
        val options = buildList {
            headers["Referer"]?.let { add(":http-referrer=$it") }
            headers["User-Agent"]?.let { add(":http-user-agent=$it") }
        }
        player.media().play(url, *options.toTypedArray())
    }

    fun pause() {
        player.controls().setPause(true)
    }

    fun resume() {
        player.controls().setPause(false)
    }

    fun stop() {
        player.controls().stop()
    }

    fun seekTo(fraction: Float) {
        player.controls().setPosition(fraction.coerceIn(0f, 1f))
    }

    /** VLC's own scale, where 100 is unity. */
    fun setVolume(percent: Int) {
        player.audio().setVolume(percent.coerceIn(0, 100))
    }

    fun release() {
        runCatching { player.release() }
        runCatching { factory.release() }
    }
}
