package com.music.bitchord.desktop.playback

import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Playback as the UI sees it: what is playing, where it is, and the four
 * calls a player screen makes. Resolving a track is the slow part — a stream
 * URL has to be minted and proven to serve bytes before libvlc is handed it —
 * so [play] returns immediately and reports progress through [status].
 */
class PlayerController(private val scope: CoroutineScope) {

    private val vlc = VlcAudioPlayer()

    private val _current = MutableStateFlow<Song?>(null)
    val current: StateFlow<Song?> = _current.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    /** True from the tap until the first byte is on its way — what the mini player's spinner reads. */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _volume = MutableStateFlow(80)
    val volume: StateFlow<Int> = _volume.asStateFlow()

    /** Null when there is nothing to report — a resolve in flight or a failure. */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    init {
        vlc.onTime = { _positionMs.value = it }
        vlc.onLength = { _durationMs.value = it }
        vlc.onPlayingChanged = { _playing.value = it }
        vlc.onError = { message ->
            _status.value = message
            _playing.value = false
        }
        vlc.onFinished = { _positionMs.value = _durationMs.value }
        vlc.setVolume(_volume.value)
    }

    fun play(song: Song) {
        if (_current.value?.videoId == song.videoId && (vlc.isPlaying || _loading.value)) return
        _current.value = song
        _positionMs.value = 0
        _durationMs.value = 0
        _status.value = "resolving…"
        _loading.value = true
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { StreamResolver.resolve(song.videoId) } }
                .onSuccess { url ->
                    _status.value = null
                    _loading.value = false
                    vlc.play(url, StreamResolver.mediaHeadersFor(url))
                    vlc.setVolume(_volume.value)
                }
                .onFailure { error ->
                    _loading.value = false
                    _status.value = "resolve failed: ${error.message ?: error.javaClass.simpleName}"
                }
        }
    }

    fun togglePlayPause() {
        if (_current.value == null) return
        if (_playing.value) vlc.pause() else vlc.resume()
    }

    fun seekToFraction(fraction: Float) {
        if (_durationMs.value > 0) _positionMs.value = (fraction * _durationMs.value).toLong()
        vlc.seekTo(fraction)
    }

    fun setVolume(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        _volume.value = clamped
        vlc.setVolume(clamped)
    }

    fun release() {
        vlc.release()
    }
}
