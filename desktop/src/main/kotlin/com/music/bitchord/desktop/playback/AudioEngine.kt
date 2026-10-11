package com.music.bitchord.desktop.playback

/**
 * What the decoder is feeding on, as the engine observed it — the desktop
 * form of the ExoPlayer `AnalyticsListener` figures upstream's NerdStats
 * measured. Every field is optional because a stream or an engine can simply
 * not know: a figure nobody reported is left out of the display rather than
 * guessed at.
 */
data class AudioStats(
    /** The codec name the engine reports ("opus", "flac", "mp4a"...). */
    val codecName: String? = null,
    /** The track's own bitrate figure, in kbps, where the engine states one. */
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
)

/**
 * The sound card, seen as an interface.
 *
 * What the slice-1 VLC wrapper already was — five callbacks and seven verbs —
 * said out loud, so that the queue can be tested against a fake engine on a
 * machine with no VLC installed. The output-device pair at the bottom arrived
 * with slice 9 and the equalizer verb with slice 15; all carry defaults, so
 * the count still holds for an engine that only plays. Every core member is
 * [VlcAudioPlayer]'s own; the interface adds nothing to it.
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

    /**
     * The decoder has opened the media far enough to say what it is being fed.
     *
     * Expected once per row, after the first ticks — a stream that never opens
     * reports nothing, which [com.music.bitchord.data.NerdStats] treats as no
     * measurement rather than as a guess.
     */
    var onAudioStats: ((AudioStats) -> Unit)?

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

    /**
     * Starts [url] playing, optionally from [startAtMs] into the media.
     *
     * The engine has no open-and-wait, so a mid-track stream change asks for
     * sound at a position rather than at the top: [startAtMs] is the only way
     * a swap lands where the listener was, without a play-then-seek race
     * (the engine is already making sound the instant it could seek).
     */
    fun play(url: String, headers: Map<String, String> = emptyMap(), startAtMs: Long = 0L)

    /**
     * Opens [url] at [startAtMs] *without sounding*: fetched, decoded to its
     * cue and held there paused, waiting for [resume] to be told to speak.
     * The standby half of a crossfade — a track that is ready to be heard the
     * moment its fader starts to move, and is heard by nobody until then.
     *
     * A default in terms of [play] and [pause] would race — sound out in the
     * gap between the two calls — so engines that cannot open-and-hold are
     * expected to say so in their own implementation. [VlcAudioPlayer]'s is
     * race-free at the input (`:start-paused`, decided at open time beside
     * `:start-time`); this default is the documented shape of the gap for an
     * engine that has no opinion, so the interface keeps asking nothing more
     * of an engine than that it plays.
     */
    fun prepare(url: String, headers: Map<String, String> = emptyMap(), startAtMs: Long = 0L) {
        play(url, headers, startAtMs)
        pause()
    }

    fun pause()

    fun resume()

    fun stop()

    /** Fraction of the media, 0f..1f — the only seek the engine is asked for. */
    fun seekTo(fraction: Float)

    /** 0..100, VLC's own scale where 100 is unity. */
    fun setVolume(percent: Int)

    fun release()

    /**
     * The endpoints the platform's sound settings would list right now, in the
     * order the platform lists them. A blocking native call — callers keep it
     * off the UI thread. The defaults exist so an engine that has no opinion
     * about endpoints (the fake, a future one) reports an empty world rather
     * than a lie: this interface has never required more of an engine than
     * that it plays.
     */
    fun audioOutputDevices(): List<AudioOutputDevice> = emptyList()

    /**
     * Route the sound to [deviceId], or to the platform's own default when
     * null. Whether the change reaches the stream already playing or only the
     * next one is the engine's platform fact — [VlcAudioPlayer]'s KDoc carries
     * what its probe established.
     */
    fun setAudioOutputDevice(deviceId: String?) {}

    /**
     * Sound with [tuning] from now on — the equaliser settings, already mapped
     * onto this engine's own band shape ([equalizerTuning] did that). Null, or
     * a tuning with `enabled == false`, means passthrough: the equalizer comes
     * off, not down.
     *
     * The default exists so an engine with no equalizer of its own (the fake,
     * a future one) receives the settings and ignores them — the same
     * slice-9-decision-4 precedent the output-device pair set, where the
     * interface keeps asking no more of an engine than that it plays.
     */
    fun setEqualizer(tuning: EqualizerTuning?) {}

    /**
     * Play at [rate] from now on — 1.0 is normal speed, the app's own range is
     * 0.5–2.0 ([com.music.bitchord.data.settings.AppSettings.playbackSpeed]).
     * Applies to the media playing and to every media opened after it: the
     * engine keeps the rate across media opens the way it keeps its equalizer,
     * for the same undocumented-persistence reason.
     *
     * The default is the same courtesy the equalizer's default is: an engine
     * with no tempo of its own receives the setting and ignores it.
     */
    fun setSpeed(rate: Float) {}

    /**
     * Skip past stretches of silence in the media. The desktop's libvlc 3.x
     * ships no silence-skip filter, so [VlcAudioPlayer]'s implementation says
     * so and does nothing — the seam is here, the setting persists, and an
     * engine that *can* act (a future direct-rendering one that sees the PCM)
     * overrides it. Upstream's own switch only works under some outputs too
     * ("unavailable on 32-bit float output"); this is the same switch, carried
     * to a platform whose decoder cannot yet.
     */
    fun setSkipSilence(enabled: Boolean) {}
}

/** One endpoint the operating system's sound settings would list, by its own id. */
data class AudioOutputDevice(
    /** The id the engine's platform uses to name this endpoint again. */
    val id: String,
    /** What a user calls it ("Speakers (Realtek…)", "耳机 (…)"). */
    val name: String,
)
