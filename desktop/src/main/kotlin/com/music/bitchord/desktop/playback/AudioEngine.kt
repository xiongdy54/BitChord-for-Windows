package com.music.bitchord.desktop.playback

/**
 * The sound card, seen as an interface.
 *
 * What the slice-1 VLC wrapper already was — five callbacks and seven verbs —
 * said out loud, so that the queue can be tested against a fake engine on a
 * machine with no VLC installed. Every member is [VlcAudioPlayer]'s own; the
 * interface adds nothing to it.
 *
 * Callbacks arrive on the thread the engine chooses. libvlc runs them on vlcj's
 * own event dispatcher, not on the UI thread, so whoever consumes them has to
 * treat that as a separate thread — see [PlayerController]'s `queueDispatcher`.
 */
interface AudioEngine {
    /** Playback position moved, in milliseconds. */
    var onTime: ((Long) -> Unit)?

    /** The media's total length settled, in milliseconds. */
    var onLength: ((Long) -> Unit)?

    /** Playing or not — pause, resume, stop and end-of-media all report through here. */
    var onPlayingChanged: ((Boolean) -> Unit)?

    /**
     * The media ran out. This is the queue's advance signal (spec §3.2): the only
     * event that means "that row is finished", as opposed to a pause or a stop.
     */
    var onFinished: (() -> Unit)?

    /** The engine could not play, or stopped being able to. */
    var onError: ((String) -> Unit)?

    /** What the engine is doing right now, for callers that ask rather than wait. */
    val isPlaying: Boolean

    /**
     * Where the engine actually is, in milliseconds.
     *
     * Distinct from the position the UI shows, and not derived from it: the display value is a UI
     * quantity — the engine's ticks are passed straight into it, a row change re-seats it at zero
     * and a scrubber drag writes it — so it can disagree with the media clock by design. The
     * "restart or step back" decision is a claim about the media, so it is made against this.
     */
    val timeMs: Long

    fun play(url: String, headers: Map<String, String> = emptyMap())

    fun pause()

    fun resume()

    fun stop()

    /** Fraction of the media, 0f..1f — the only seek the engine is asked for. */
    fun seekTo(fraction: Float)

    /** 0..100, VLC's own scale where 100 is unity. */
    fun setVolume(percent: Int)

    fun release()
}
