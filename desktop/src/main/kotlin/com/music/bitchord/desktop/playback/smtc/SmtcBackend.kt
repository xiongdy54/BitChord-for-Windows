package com.music.bitchord.desktop.playback.smtc

/**
 * What the system media transport controls can be told, said in the app's own
 * words — the seam that keeps the semantic layer ([SmtcSession]) free of every
 * COM detail.
 *
 * The one production implementation is [WinRtSmtcBackend] (Windows.Media's
 * SystemMediaTransportControls, over JNA). Tests hold a fake, which is the
 * whole reason the split exists: what to push, when, is the part worth
 * asserting, and none of it needs a Windows runtime to prove.
 */
interface SmtcBackend {

    /**
     * Claims the machine's SMTC slot for the window whose HWND is [hwndHandle]
     * — the raw pointer value, so the interface stays free of JNA.
     *
     * After this the overlay exists (though it shows nothing until the first
     * metadata and status land), and the media keys on the keyboard route here.
     * Must be safe to call exactly once per backend; a backend whose platform
     * half could not be built reports [isAttached] false and answers every
     * later call with nothing.
     */
    fun attach(hwndHandle: Long)

    /** Whether the platform half exists — every verb below is a no-op when it does not. */
    val isAttached: Boolean

    /** Which row's button presses come back through [onButton] and [onSeekRequested]. */
    var onButton: ((SmtcButton) -> Unit)?

    /** A seek the overlay's scrubber asked for, in milliseconds from the start. */
    var onSeekRequested: ((Long) -> Unit)?

    /**
     * The coarse state the overlay's own glyph shows: [PLAYING], [PAUSED],
     * [STOPPED] or [CLOSED]. The numbers are Windows.Media's own
     * MediaPlaybackStatus values, kept here rather than behind an enum of our
     * own because this is the one place the app speaks the system's dialect
     * and a translation would be a copy with two names.
     */
    fun setPlaybackStatus(status: Int)

    /**
     * What the overlay shows about the track. [artworkUrl], when present, is a
     * https URL the system fetches itself; a backend that cannot show a
     * picture may drop it.
     */
    fun setMetadata(title: String?, artist: String?, artworkUrl: String?)

    /**
     * Where the media clock is: [positionMs] into [durationMs], and the range
     * the overlay's scrubber may travel. The system advances the shown
     * position itself from here — see [SmtcSession] for the cadence.
     */
    fun setTimeline(positionMs: Long, durationMs: Long)

    /** Gives the slot up: buttons go dead and the overlay drops the entry. */
    fun release()
}

/** The transport buttons the overlay and the media keys can press. */
enum class SmtcButton { PLAY, PAUSE, STOP, NEXT, PREVIOUS }
