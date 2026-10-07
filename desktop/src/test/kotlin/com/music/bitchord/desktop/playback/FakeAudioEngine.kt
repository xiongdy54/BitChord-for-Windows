package com.music.bitchord.desktop.playback

/**
 * An [AudioEngine] that makes no sound.
 *
 * Every behaviour here is the one libvlc reports, so the fake is a statement of
 * what the real engine does rather than a convenience: `finished` clears playing
 * *and* fires [AudioEngine.onFinished], `play` starts playing, and a tick moves
 * the engine's own position before reporting it.
 */
class FakeAudioEngine : AudioEngine {
    override var onTime: ((Long) -> Unit)? = null
    override var onLength: ((Long) -> Unit)? = null
    override var onPlayingChanged: ((Boolean) -> Unit)? = null
    override var onFinished: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var onAudioStats: ((AudioStats) -> Unit)? = null

    /** What the engine reports once the input has opened, for the stats tests. */
    fun reportStats(codecName: String?, bitrateKbps: Int?, sampleRateHz: Int?, channels: Int?) {
        onAudioStats?.invoke(AudioStats(codecName, bitrateKbps, sampleRateHz, channels))
    }
    override var isPlaying: Boolean = false
    override var timeMs: Long = 0L
    val playedUrls = mutableListOf<String>()

    /** Every volume the controller asked for, in order — including the re-seat on a new row. */
    val volumes = mutableListOf<Int>()

    /** Every fraction the controller asked the *engine* to seek to. Empty is the norm: a row
     * change restarts the clock, so a seek inside one is work thrown away. */
    val seekFractions = mutableListOf<Float>()

    /** What libvlc's `finished` event does: clear playing, then tell us. */
    fun finish() {
        isPlaying = false; onPlayingChanged?.invoke(false); onFinished?.invoke()
    }

    fun tick(ms: Long) { timeMs = ms; onTime?.invoke(ms) }

    /** What libvlc's `lengthChanged` event does: report the media's total. */
    fun length(ms: Long) { onLength?.invoke(ms) }

    override fun play(url: String, headers: Map<String, String>, startAtMs: Long) {
        playedUrls += url
        if (startAtMs != 0L) playedStartMs += startAtMs
        isPlaying = true; onPlayingChanged?.invoke(true)
        onPlayHook?.invoke()
    }

    /** Positions the controller asked to start from. Empty is the norm: a row change restarts the clock. */
    val playedStartMs = mutableListOf<Long>()

    /** Whether [release] ran — the audition's teardown, once per swap. */
    var releasedEngine = false

    /**
     * Fired at the end of [play] — the audition tests' handoff: the real
     * engine's proof arrives through events on vlcj's thread, and this is
     * where a test scripts the same arrival.
     */
    var onPlayHook: (() -> Unit)? = null

    override fun pause() { isPlaying = false; onPlayingChanged?.invoke(false) }
    override fun resume() { isPlaying = true; onPlayingChanged?.invoke(true) }
    override fun stop() { isPlaying = false; onPlayingChanged?.invoke(false) }
    override fun seekTo(fraction: Float) { seekFractions += fraction }
    override fun setVolume(percent: Int) { volumes += percent }
    override fun release() { releasedEngine = true }
}
