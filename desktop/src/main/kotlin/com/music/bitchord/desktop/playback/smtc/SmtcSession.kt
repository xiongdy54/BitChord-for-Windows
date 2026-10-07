package com.music.bitchord.desktop.playback.smtc

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song

/**
 * The system's media controls, as the player sees them.
 *
 * The desktop build has no Media3 session — upstream's `MediaLibrarySession`
 * gave Android its media keys, its notification and its lockscreen surface for
 * free. What Windows gives instead is SMTC: the media overlay (Win10's Global
 * Media Transport Controls), the keyboard's media keys and the taskbar's
 * thumbnail toolbar, all driven from one `SystemMediaTransportControls` object
 * the app claims per window.
 *
 * This class is the semantics of that object, kept apart from the COM
 * machinery behind [SmtcBackend] so both halves stay honest:
 *
 * - **What to push, and only when it changed.** SMTC verbs are idempotent, but
 *   every one of them crosses a thread boundary and some (metadata) walk the
 *   system off to fetch artwork — so [update] diffs against what was pushed
 *   last and tells the backend nothing on an unchanged picture. A position
 *   tick that moved the clock is the one thing that always goes through,
 *   because the overlay's shown time is the system's own extrapolation from
 *   the last anchor and needs re-anchoring.
 * - **What the track is versus what is playing.** A metadata push rides on the
 *   row's identity (the videoId), not on the push that preceded it — so a
 *   seek, a pause, or a position correction can never leave the overlay
 *   describing one song with another song's picture.
 *
 * The stateless verbs mirror upstream's `SessionPlayer` rewrites
 * (`PlaybackService.kt:6367-6592`): play/pause/stop/next/previous arrive as
 * [onButton] (from the overlay's buttons and the keyboard's media keys, which
 * are the same event in Windows's plumbing), a scrubber drag arrives as
 * [onSeekRequested]. The repeat/shuffle command surface upstream hangs off
 * custom `SessionCommand`s has no SMTC counterpart and is simply absent.
 *
 * Threading: every method may be called from any thread; the backend is
 * responsible for reaching the COM apartment it needs. Callbacks arrive on
 * whatever thread the backend runs its event pump on — see [WinRtSmtcBackend],
 * which receives on Windows's own RPC threads.
 *
 * @param backend the platform half. Every method here degrades to nothing
 *   while [SmtcBackend.isAttached] is false, so a machine where the WinRT
 *   activation fails (or a headless test) loses the overlay and nothing else.
 */
class SmtcSession(private val backend: SmtcBackend) {

    /** A transport button the system pressed. See the class note on threading. */
    var onButton: ((SmtcButton) -> Unit)?
        get() = backend.onButton
        set(value) {
            backend.onButton = value
        }

    /** A position the system's scrubber was dragged to, in milliseconds. */
    var onSeekRequested: ((Long) -> Unit)?
        get() = backend.onSeekRequested
        set(value) {
            backend.onSeekRequested = value
        }

    /**
     * Attach to [hwndHandle] — the raw HWND value of the window the overlay
     * should represent. Idempotent; the second call does nothing.
     */
    fun attach(hwndHandle: Long) {
        if (attached) return
        attached = true
        backend.attach(hwndHandle)
        if (backend.isAttached) {
            TrackLog.d(TAG, "smtc attached")
        }
    }

    private var attached = false

    /** The row whose metadata was pushed last, so a metadata push keys off the song, not off time. */
    private var pushedForVideoId: String? = null
    private var pushedTitle: String? = null
    private var pushedArtist: String? = null
    private var pushedArtwork: String? = null

    /** The last playback status pushed, so idempotent ticks cost nothing. */
    private var pushedStatus: Int? = null

    /** The last timeline pushed — position moves every tick, so it is diffed separately. */
    private var pushedPositionMs = -1L
    private var pushedDurationMs = -1L

    /**
     * Everything the overlay shows, in one call.
     *
     * Each of the four halves (status, metadata, timeline) is diffed against
     * what was pushed last, so a call that changes nothing — a position tick
     * while paused with the same song — is the cheapest thing here. The status
     * mapping is the system's own: a row on the needle that is not playing is
     * Paused, silence with nothing queued is Closed.
     *
     * [positionMs] and [durationMs] are the media clock's own figures; a
     * duration the player has not yet learned (0) is pushed as a timeline with
     * zero span only once — before that the overlay shows no scrubber, which
     * is the honest picture for a track whose length nobody knows.
     */
    fun update(
        song: Song?,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (!backend.isAttached) return
        val status = when {
            song == null -> STATUS_CLOSED
            isPlaying -> STATUS_PLAYING
            else -> STATUS_PAUSED
        }
        if (status != pushedStatus) {
            pushedStatus = status
            backend.setPlaybackStatus(status)
        }
        if (song?.videoId != pushedForVideoId ||
            song?.title != pushedTitle ||
            song?.artist != pushedArtist ||
            song?.thumbnailUrl != pushedArtwork
        ) {
            pushedForVideoId = song?.videoId
            pushedTitle = song?.title
            pushedArtist = song?.artist
            pushedArtwork = song?.thumbnailUrl
            backend.setMetadata(song?.title, song?.artist, song?.thumbnailUrl)
        }
        if (positionMs != pushedPositionMs || durationMs != pushedDurationMs) {
            pushedPositionMs = positionMs
            pushedDurationMs = durationMs
            backend.setTimeline(positionMs, durationMs)
        }
    }

    /** The system asked to move the media clock. [positionMs] is what its scrubber shows. */
    fun seekRequested(positionMs: Long) {
        onSeekRequested?.invoke(positionMs)
    }

    /**
     * Gives the slot up. The overlay drops the app's entry and the media keys
     * go back to whatever else claims them. Idempotent.
     */
    fun release() {
        if (!backend.isAttached) return
        backend.release()
        TrackLog.d(TAG, "smtc released")
    }

    companion object {
        private const val TAG = "BitChord"

        /** Windows.Media.MediaPlaybackStatus.Closed — nothing to control. */
        const val STATUS_CLOSED = 0

        /** Windows.Media.MediaPlaybackStatus.Playing. */
        const val STATUS_PLAYING = 3

        /** Windows.Media.MediaPlaybackStatus.Paused. */
        const val STATUS_PAUSED = 4
    }
}
