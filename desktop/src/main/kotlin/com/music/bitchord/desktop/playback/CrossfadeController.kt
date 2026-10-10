package com.music.bitchord.desktop.playback

import com.music.bitchord.data.SystemClock
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A real crossfade: two tracks audible at once, the outgoing one falling as the
 * incoming one rises, the way Spotify and Apple Music do it. Rewritten from
 * upstream's `CrossfadeController.kt` in desktop dialect — the portable state
 * machine of the original, with the Automix/smart-fade family left out (that is
 * its own later slice; every guard and constant here is the original's).
 *
 * ## Why there are two engines
 *
 * One engine renders one stream at a time, so at a track boundary there is
 * exactly one source and the gain it can be given is either 1 (no fade) or 0
 * (silence). A single-engine volume ramp never sounds like a crossfade: it
 * dips to silence at the join and climbs back out, leaving a hole where the
 * blend should be. Overlap needs a second decoder; there is no way around it.
 *
 * ## Which engine plays what
 *
 * Two peers, not an engine and a helper. At any instant one of them *is* the
 * session — the one the [PlayerController] points its `engine` field at, and
 * the only one the rest of the app ever sees — and the other is idle. They
 * swap roles at every transition. The crucial word is **incoming**: the
 * *outgoing* track on the second engine would force a moment where both
 * render the same audio (upstream measured the misalignment at 9–41ms, heard
 * as the last instant of the old track played twice at the head of every
 * crossfade — structural, not tunable). Loading the *incoming* track on the
 * standby removes it outright: the two engines never hold the same audio, so
 * there is nothing to align and no seam to hide. The incoming track is simply
 * already playing, from exactly the right position, when its fader starts to
 * move.
 *
 * The desktop's engines hold a URL each rather than a queue, so "arming" is
 * smaller than upstream's queue copy: the standby is pointed at the next
 * row's own stream (usually already resolved by the read-ahead) and opened
 * through [AudioEngine.prepare] — muted, paused, cued at the top — which is
 * the `setMediaItems` + `playWhenReady = false` + `prepare` of the original
 * in two verbs.
 *
 * ## The handoff
 *
 * Finishing a transition is a **role swap** rather than a seek: nothing is
 * re-buffered, nothing is re-sought, and no audio is rendered twice.
 * [onHandoff] is what performs it — the controller moves its `engine` field,
 * its listeners and its bookkeeping onto the incoming engine, which is
 * upstream's `adoptPlayer` at the same moment of the same blend. It fires as
 * the incoming track's first note sounds, not at the end of the blend, so the
 * queue row, the metadata and the UI all flip to the incoming song the moment
 * it becomes audible rather than trailing the song on its way out.
 *
 * The outgoing engine's *advance* is forgotten there — the desktop form of
 * upstream truncating the session player's queue so its natural end cannot
 * start the incoming song a second time: the retired engine's end-of-media is
 * re-bound to this class instead ([onOutgoingTailEnded]), where it reads as
 * the tail being spent and nothing else.
 *
 * ## Curve
 *
 * `sin`/`cos`: `sin²+cos²=1` exactly, so two tracks fading past each other
 * hold constant *power* the whole way through and the transition has no dip
 * in the middle. That is the standard crossfade law, and it is what makes a
 * long crossfade sound like a blend instead of a dip. The gains multiply the
 * listener's own volume figure — an engine's volume *is* the session volume
 * on this platform, where upstream's was a fade multiplier beside it — so
 * both sides are written as 0..100 integers (the quantization stair that
 * follows is noted in the design record, and is inaudible at 30ms ticks).
 */
class CrossfadeController(
    private val scope: CoroutineScope,
    /** The engine the session is on right now. Moves at every [onHandoff]. */
    private val active: () -> AudioEngine,
    /** The idle engine, which the next transition will load the incoming track onto. */
    private val standby: () -> AudioEngine,
    /**
     * Moves the session onto the engine that has just started the incoming
     * track: the `engine` field, the app's listeners and everything
     * [PlayerController] books against a track change.
     *
     * Called once per transition, at the instant the incoming track becomes
     * audible. After it returns, [active] must answer `incoming` and [standby]
     * must answer `outgoing` — this class re-reads neither during a
     * transition, but everything else in the player does.
     */
    private val onHandoff: (outgoing: AudioEngine, incoming: AudioEngine) -> Unit,
    /**
     * The session's playing flag, as the player's own snapshot holds it. Used
     * where upstream read `playWhenReady` — the listener's intent, which a
     * buffering stall does not withdraw — rather than the engine's momentary
     * `isPlaying`, so a network hiccup mid-arming is not mistaken for a pause.
     */
    private val sessionPlaying: () -> Boolean,
    /** The session track's known length, or zero when none has reported yet. */
    private val durationMs: () -> Long,
    /** Whether a row follows the current one, wrap included — the queue's answer, not a guess. */
    private val hasNext: () -> Boolean,
    /** Whether repeat-one is on: a track would be crossfaded into itself. */
    private val repeatOne: () -> Boolean,
    /** The videoId of the row that would play next, read without moving the needle. */
    private val nextVideoId: () -> String?,
    /** The listener's own volume figure, 0..100 — what the curves multiply. */
    private val userVolume: () -> Int,
    /**
     * Resolves the incoming row to a stream URL. This is the one network walk
     * arming can need; the read-ahead has usually finished it already.
     */
    private val resolve: suspend (String) -> String,
    /** The headers a media fetch for a resolved URL must carry. */
    private val headersFor: (String) -> Map<String, String>,
    /**
     * True while the player is mid-swap between two versions of the current
     * track. That swap lands an audition on its own throwaway engine here —
     * it does not touch the pair — but its *landing* replays the current
     * stream on the session engine, which would tear the blend apart, so the
     * two must never run at once. Checked once at the top of
     * [considerAutoTransition], since [Phase.IDLE] is the only phase that can
     * still be preempted for free; the other direction is guarded where the
     * swap starts, which waits for [isTransitioning] to clear.
     */
    private val versionSwapActive: () -> Boolean,
    /**
     * The clock. [SystemClock] in production; a test's own hand for the two
     * wall-clock waits ([ARM_TIMEOUT_MS], [BAIL_MS]).
     */
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private enum class Phase {
        /** Nothing in flight; watching for the next transition. */
        IDLE,

        /**
         * The standby engine is resolving, opening and buffering the incoming
         * track to its cue point. Silent, and nothing has been committed:
         * abandoning here costs only the standby's decoder.
         */
        ARMING,

        /** Incoming track rising on one engine, outgoing falling on the other. */
        FADING,

        /**
         * The sleep timer is due at this track's end: the track is being
         * faded to silence on its own, over the same span an ordinary
         * transition would have used, but nothing is being loaded to follow
         * it. See [beginSleepFadeOut].
         */
        SLEEP_FADE,

        /** Something interrupted the fade; the outgoing track is being ramped away. */
        BAILING,
    }

    private var phase = Phase.IDLE

    /**
     * The engine the session was on when this transition began — the one
     * whose track is being left. Held explicitly rather than re-read through
     * [standby], because [onHandoff] moves it out from under that name
     * halfway through the fade and the ramp has to keep driving the same two
     * engines it started with.
     */
    private var outgoing: AudioEngine? = null

    /** The engine carrying the track arriving. Becomes the session at [onHandoff]. */
    private var incoming: AudioEngine? = null

    /**
     * Whether [onHandoff] has run for the transition in flight, which is what
     * decides who owns what if it has to be unwound: before it, [outgoing] is
     * the session and [incoming] is a silent scratch engine; after it, they
     * have traded places.
     */
    private var handedOff = false

    private var tickerJob: Job? = null

    /**
     * The resolve-and-prepare half of arming, on its own job so a bail while
     * the URL is being fetched does not leave a standby being opened for a
     * transition that no longer exists.
     */
    private var armJob: Job? = null

    /** Length of the transition in flight, in ms. Fixed when it begins. */
    private var fadeMs = 0L

    /**
     * Where the fade window ends, in the session track's position ms. Standard
     * mode sets this to the track's own duration — the only mode there is
     * here, upstream's Automix anchor having been left with the Automix
     * family — and [driveArming] watches this field rather than re-deriving
     * the fade point from the duration on every tick.
     */
    private var fadeEndMs = 0L

    private var armDeadline = 0L

    /**
     * Arming's readiness, as the standby itself reports it: its length has
     * settled, which for libvlc happens once the input has opened far enough
     * to know what it is feeding on. The one thing a transition waits on —
     * there is nothing to align here, the two engines hold different songs.
     */
    private var standbyReady = false
    private var standbyLengthMs = 0L

    /** The URL the standby was armed with, for the log line and nobody else. */
    private var standbyUrl: String? = null

    /**
     * The gain the outgoing track was last written at, as a fraction of the
     * listener's volume. An engine's volume is write-only (0..100 out), so
     * the ramp-out of a bail scales from here rather than from a read that
     * does not exist.
     */
    private var outgoingGain = 1f

    /**
     * The outgoing engine has reached its own end. Reported through
     * [onOutgoingTailEnded] — the advance it used to carry was forgotten at
     * the handoff — and read by [driveFade] the way upstream read
     * `STATE_ENDED`: a tail that ran out is a blend that is over, whatever
     * the progress figure says.
     */
    private var outgoingEnded = false

    /**
     * Whether the tick's pause-shadow is what paused the outgoing engine, as
     * opposed to the engine having run out. Only what *this class* paused
     * may be resumed by it — resuming an engine that ended would start the
     * old track over from the top.
     */
    private var shadowPaused = false

    /**
     * Gain the outgoing track was at when the fade was interrupted, so the
     * ramp out starts from where it actually is rather than from full volume.
     */
    private var bailFromGain = 0f
    private var bailStartedAt = 0L

    /** Length of the fade-out driven by [Phase.SLEEP_FADE], in ms. Fixed when it begins. */
    private var sleepFadeMs = 0L

    /**
     * Where the track was when [beginSleepFadeOut] started, in its own
     * position ms. Progress is measured from here rather than off a clock,
     * for the same reason [driveFade] measures off the incoming track's
     * position: a pause should park the fade-out where it stands, not keep
     * counting down underneath a silent, stopped player.
     */
    private var sleepFadeStartPositionMs = 0L
    private var sleepFadeStartGain = 1f

    /**
     * Test seam: when true, [start]'s collector never launches the tick loop
     * and the state machine moves only when [tickForTest] says so — the
     * PlayerController tests drive the fakes by hand and must not race a
     * wall-clock ticker for the same objects.
     */
    internal var manualTicks = false

    /** One tick, by hand. The loop's own body, for tests that drive it manually. */
    internal fun tickForTest() = tick()

    /**
     * The length the incoming track reported while it was being armed, for
     * the handoff's bookkeeping: the scrubber's duration belongs to the
     * incoming track from the first note, and the engine it came from only
     * reports through the app's listeners once it *is* the session.
     */
    val incomingDurationMs: Long get() = standbyLengthMs

    /**
     * True while a transition is armed or running.
     *
     * For callers about to do something that would otherwise fight this class
     * for the session engine mid-blend — the quality swap is the one that
     * does, since its landing replays the current stream, tearing the source
     * the fade is half-way through. Doing that to either engine mid-transition
     * breaks the blend rather than merely delaying it, so such a caller should
     * wait for this to clear rather than proceed anyway.
     */
    fun isTransitioning(): Boolean = phase != Phase.IDLE

    /**
     * Starts watching. The tick loop comes up with the setting, not before:
     * with the crossfade at zero nothing in this class can happen (the
     * sleep-fade branch reads the same setting for its span), and a ticker
     * that only exists when the feature can act is 4Hz of engine polling the
     * rest of the app never pays for.
     *
     * `collect` and not the `collectLatest` its sibling [PlayerController.watchSleepTimer]
     * wears, deliberately: collectLatest cancels the running block when the
     * flow emits again, and this block *is* the fade's heartbeat — a slider
     * dragged mid-blend would strand both engines at whatever gains the last
     * tick left them. Re-emitting here only re-checks an idempotent start,
     * and a fade the listener no longer wants is stopped by [driveFade]'s
     * setting-switched-off check instead, with a finish rather than a freeze.
     */
    fun start() {
        scope.launch {
            AppSettings.crossfadeSeconds.collect { seconds ->
                if (seconds > 0 && !manualTicks) ensureTicker()
            }
        }
    }

    private fun ensureTicker() {
        if (tickerJob != null) return
        tickerJob = scope.launch {
            while (isActive) {
                tick()
                delay(
                    when (phase) {
                        Phase.IDLE -> IDLE_STEP_MS
                        Phase.ARMING -> ARM_STEP_MS
                        Phase.FADING -> FADE_STEP_MS
                        Phase.SLEEP_FADE -> FADE_STEP_MS
                        Phase.BAILING -> BAIL_STEP_MS
                    },
                )
            }
        }
    }

    fun release() {
        tickerJob?.cancel()
        tickerJob = null
        armJob?.cancel()
        armJob = null
        active().setVolume(userVolume())
        phase = Phase.IDLE
    }

    // ---- Entry points -------------------------------------------------------

    /**
     * A skip the listener asked for: drop any blend in flight and get out of
     * the way.
     *
     * Crossfade is deliberately a property of tracks *running out*, not of
     * being changed. Blending a manual skip means the song just left behind
     * stays audible over the one that was asked for, which reads as the app
     * ignoring the button rather than as a transition — the point of pressing
     * next is usually to stop hearing the current track.
     *
     * Called before the skip is carried out, so the outgoing track is already
     * on its way down as the new one starts. A queue *replacement* asks for
     * this through the same door (the resolve that starts any row runs it
     * first), which is upstream's playlist-changed bail: the tail still
     * playing then belongs to a session that no longer exists.
     */
    fun onSkipRequested() {
        if (phase != Phase.IDLE) bail()
    }

    /**
     * The outgoing engine reached its own end. Bound at the handoff in place
     * of the advance it used to carry — a retired engine's end of media is
     * the tail being spent, never a queue event.
     */
    fun onOutgoingTailEnded() {
        outgoingEnded = true
    }

    /**
     * The session engine's own end of media, reported by the advance before
     * it decides what the queue does. Answers whether the report belongs to a
     * fade this class is running: a sleep fade's track ending is the fade's
     * own completion, not a queue event, and the ordinary advance that would
     * otherwise treat it as one is exactly what the sleep timer exists to
     * prevent. Anything else is somebody's track really finishing — say so
     * and let the advance proceed.
     */
    fun onSessionEnded(): Boolean {
        if (phase != Phase.SLEEP_FADE) return false
        outgoingEnded = true
        return true
    }

    // ---- Ticker -------------------------------------------------------------

    private fun tick() {
        // A pause has to take the other engine with it, or one half of the
        // blend carries on alone over a stopped one. Mirrored every tick
        // rather than handled as an event, so the sleep timer, the pause
        // button and anything else that stops the session get the same
        // treatment for free — and mirrored back on resume, so a blend paused
        // for a verse resumes as the blend it was. Only what the shadow
        // paused is resumed by the shadow: an engine that *ended* must not be
        // started over.
        val out = outgoing
        val into = incoming
        if ((phase == Phase.FADING || phase == Phase.BAILING) && out != null && into != null) {
            if (!sessionPlaying() && out.isPlaying) {
                out.pause()
                shadowPaused = true
            } else if (sessionPlaying() && shadowPaused && !outgoingEnded) {
                out.resume()
                shadowPaused = false
            }
        }

        when (phase) {
            Phase.IDLE -> considerAutoTransition()
            Phase.ARMING -> driveArming()
            Phase.FADING -> driveFade()
            Phase.SLEEP_FADE -> driveSleepFade()
            Phase.BAILING -> driveBail()
        }
    }

    /** Arms a crossfade as the playing track runs out. */
    private fun considerAutoTransition() {
        // Not while nothing is playing — and not on the engine's momentary
        // word for it, but on the snapshot's, which a buffering stall does
        // not withdraw.
        if (!sessionPlaying()) return
        // Not while a version swap is in the air — see [versionSwapActive].
        if (versionSwapActive()) return
        // Nothing to transition *into*.
        if (!hasNext()) return

        val duration = durationMs()
        if (duration <= 0L) return

        // Repeating one track would crossfade it into itself, so nothing is
        // armed; the queue behind the loop has not moved, and what sits after
        // it still plays the moment repeat-one comes off.
        if (repeatOne()) return

        val fade = fadeFor(duration)
        if (fade <= 0L) return

        val remaining = duration - active().timeMs
        if (SleepTimer.afterTrack.value) {
            // The sleep timer is due when this track ends. There is no next
            // track to arm a crossfade into, so run the same fade-out this
            // pair would otherwise have used against silence instead — see
            // [beginSleepFadeOut]. No arm-lead margin: nothing is buffering.
            if (remaining > fade) return
            beginSleepFadeOut(fade)
            return
        }
        // Arm early: the standby has to resolve, open the incoming track and
        // buffer to its cue point, and that work has to be finished by the
        // time the fade is due rather than started then.
        if (remaining > fade + ARM_LEAD_MS) return

        begin(fade)
    }

    /**
     * Arms the standby engine with the incoming track and leaves it holding
     * the stream at its cue point, silently.
     *
     * Nothing is committed here. The standby is a scratch engine until
     * [startFade] runs, so a queue edit, a skip or a pause arriving during
     * arming costs nothing but the decoder it was holding — and on this
     * platform, not even that: the queue itself is never copied, so there is
     * nothing to reconcile if AutoPlay appends mid-arm (upstream's
     * [reconcileQueue] problem does not exist where the queue lives in one
     * place).
     *
     * The cue point is reached by *starting there* rather than by seeking —
     * [AudioEngine.prepare] takes the position as an input option — and
     * standard fades always cue at the top, upstream's Automix mix-in anchor
     * having left with the Automix family.
     */
    private fun begin(fade: Long): Boolean {
        val out = active()
        val into = standby()
        if (out === into) return false
        val videoId = nextVideoId() ?: return false

        fadeMs = fade
        fadeEndMs = durationMs()
        armDeadline = now() + ARM_TIMEOUT_MS
        handedOff = false
        outgoingEnded = false
        shadowPaused = false
        outgoingGain = 1f
        outgoing = out
        incoming = into
        standbyReady = false
        standbyLengthMs = 0L
        standbyUrl = null
        armJob?.cancel()

        TrackLog.d(TAG, "arm fade=${fade}ms end=${fadeEndMs}ms at=${out.timeMs}ms")

        // Muted before anything else, so the incoming track's first decoded
        // frame is silent no matter what the engine's own default is. Started
        // for real in [startFade].
        into.setVolume(0)
        // What arming waits on, as the standby itself says it: a length means
        // the input opened. The app's listeners stay on the session until the
        // handoff, so these two are this class's own, and the handoff's
        // re-bind replaces them.
        into.onLength = { ms ->
            standbyLengthMs = ms
            standbyReady = true
        }
        into.onError = { bail() }
        phase = Phase.ARMING
        armJob = scope.launch {
            val url = try {
                resolve(videoId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                TrackLog.w(TAG, "crossfade arm resolve failed for $videoId: ${error.message}")
                null
            }
            // The transition can have been abandoned while the URL was being
            // fetched — a skip, a pause, the track simply ending. A standby
            // prepared for a transition nobody is waiting on would sit there
            // holding a decoder until the next arm recycled it.
            if (url == null || phase != Phase.ARMING) return@launch
            standbyUrl = url
            into.prepare(url, headersFor(url), startAtMs = 0L)
        }
        return true
    }

    /**
     * Waits for the standby to have the incoming track ready at its cue
     * point, and for the outgoing track to reach the fade.
     *
     * There is nothing to align here — the two engines hold different songs —
     * so this is only ever waiting on a buffer.
     */
    private fun driveArming() {
        val out = outgoing ?: return bail()
        if (!stillWorthFading()) return bail()
        // Paused while armed: the transition is no longer imminent, and
        // holding a prepared decoder open against a stopped player is worse
        // than arming again when playback resumes.
        if (!sessionPlaying()) return bail()

        val expired = now() > armDeadline
        // A standby that never got the incoming track ready has nothing to
        // fade up. Give up and let the queue move on plainly rather than
        // fading into silence.
        if (expired && !standbyReady) return bail()

        // Wait for the track to actually reach the fade point. [fadeEndMs] is
        // the track's own duration.
        val atFadePoint = fadeEndMs <= 0L || fadeEndMs - out.timeMs <= fadeMs
        if (!atFadePoint) return
        if (standbyReady) startFade()
    }

    /**
     * Starts the incoming track and moves the session onto it.
     *
     * The handoff happens *here*, as the first note sounds, not at the end of
     * the blend. Everything hanging off the session engine — queue row,
     * metadata, the system media controls, the UI — flips to the incoming
     * song the moment it becomes audible, rather than trailing the song on
     * its way out. From this point [outgoing] is the idle engine, still
     * audible, being faded away.
     */
    private fun startFade() {
        val out = outgoing ?: return bail()
        val into = incoming ?: return bail()
        // An arm that never got its URL has nothing to start. Unreachable via
        // [driveArming]'s ready check alone in practice; cheap to say.
        if (standbyUrl == null) return bail()

        TrackLog.d(TAG, "handoff cue=${into.timeMs}ms out=${out.timeMs}ms")
        handedOff = true
        onHandoff(out, into)
        // Zero again before the first sound: the open that arming performed
        // is free to have re-seated the engine's gain, and the fade's first
        // written step is up to a tick away — the incoming track must not
        // spend that tick at whatever volume it was left holding (upstream
        // re-asserts the same zero beside playWhenReady).
        into.setVolume(0)
        // After the swap, not before: the playing event a resume reports has
        // to land on the app's listeners, which only exist on this engine
        // once the handoff has bound them. (Upstream ordered it the other way
        // because Media3 reports asynchronously regardless; libvlc and the
        // fakes both answer a resume synchronously.)
        into.resume()
        phase = Phase.FADING
    }

    /**
     * The crossfade proper.
     *
     * Driven off the *incoming* track's position rather than off a clock, so
     * a pause parks the transition where it stands and resuming picks it back
     * up — no timer to reconcile, and neither engine left hanging at half
     * volume while the other waits. The cue it is measured from is always the
     * top here; upstream subtracted an Automix mix-in point that no longer
     * exists.
     */
    private fun driveFade() {
        val out = outgoing ?: return bail()
        val player = incoming ?: return bail()
        // The incoming track gets the same say over the length as the outgoing
        // one did, so a long crossfade into a short track tightens rather than
        // swallowing it. Its duration is often still unknown when the fade
        // starts — the stream is only being opened — so this is read every
        // tick and simply narrows the span once the answer arrives.
        val remainingIncoming = standbyLengthMs.takeIf { it > 0L }?.coerceAtLeast(0L)
        val incomingCap = remainingIncoming?.div(3) ?: Long.MAX_VALUE
        val span = minOf(fadeMs, incomingCap).coerceAtLeast(1L)
        val elapsed = player.timeMs.coerceAtLeast(0L)
        val progress = (elapsed.toFloat() / span).coerceIn(0f, 1f)

        val vol = userVolume()
        player.setVolume((riseGain(progress) * vol).roundToInt().coerceIn(0, 100))
        outgoingGain = fallGain(progress)
        out.setVolume((outgoingGain * vol).roundToInt().coerceIn(0, 100))

        // Whichever comes first: the fade running its course, the old track
        // genuinely ending, or the setting that armed this fade being
        // switched off mid-blend.
        val done = progress >= 1f || outgoingEnded ||
            AppSettings.crossfadeSeconds.value <= 0
        if (done) finish()
    }

    /**
     * Starts [Phase.SLEEP_FADE]: the track playing right now is faded to
     * silence on its own, over `fade` ms — the same span a real transition
     * into the next track would have spent on this side of the blend — and
     * then paused.
     *
     * Deliberately not [begin] with a no-op standby. [begin] resolves and
     * opens a second stream so it is ready to take over at the handoff; none
     * of that should happen here — there is no handoff coming, and preparing
     * a second decoder just to throw it away the moment the fade ends wastes
     * exactly the work this exists to skip.
     */
    private fun beginSleepFadeOut(fade: Long) {
        val out = active()
        sleepFadeMs = fade.coerceAtLeast(1L)
        sleepFadeStartPositionMs = out.timeMs
        sleepFadeStartGain = outgoingGain
        outgoing = out
        incoming = null
        handedOff = false
        TrackLog.d(TAG, "sleep timer due: fading out over ${sleepFadeMs}ms instead of transitioning")
        phase = Phase.SLEEP_FADE
    }

    /**
     * The fade-out proper. Mirrors [driveFade]'s curve on the outgoing side —
     * same [fallGain], same position-driven progress — but there is no
     * incoming track to weigh it against, so the span is simply [sleepFadeMs].
     */
    private fun driveSleepFade() {
        val out = outgoing ?: return finishSleepFade()
        // The listener turned the timer off mid-fade — nothing left to do but
        // hand the track back at full volume and let ordinary transition
        // logic resume next tick.
        if (!SleepTimer.afterTrack.value) {
            out.setVolume(userVolume())
            outgoingGain = 1f
            outgoing = null
            phase = Phase.IDLE
            return
        }
        val elapsed = (out.timeMs - sleepFadeStartPositionMs).coerceAtLeast(0L)
        val progress = (elapsed.toFloat() / sleepFadeMs).coerceIn(0f, 1f)
        val vol = userVolume()
        out.setVolume((sleepFadeStartGain * fallGain(progress) * vol).roundToInt().coerceIn(0, 100))
        outgoingGain = sleepFadeStartGain * fallGain(progress)
        // [outgoingEnded] is what the engine's own end arrives as — see
        // [onSessionEnded]; a position figure that stalls a hair short of the
        // span must not hang the fade on it.
        if (progress >= 1f || outgoingEnded) finishSleepFade()
    }

    /** Pauses the track the fade-out was run on and retires the timer that asked for it. */
    private fun finishSleepFade() {
        outgoing?.pause()
        outgoing?.setVolume(userVolume())
        outgoingGain = 1f
        SleepTimer.cancel()
        outgoing = null
        outgoingEnded = false
        phase = Phase.IDLE
    }

    /** Ramps the outgoing track away rather than cutting it, so an interruption has no click in it. */
    private fun driveBail() {
        val out = outgoing
        if (out == null) {
            finish()
            return
        }
        val progress = (now() - bailStartedAt).toFloat() / BAIL_MS
        if (progress < 1f) {
            val vol = userVolume()
            out.setVolume((bailFromGain * fallGain(progress) * vol).roundToInt().coerceIn(0, 100))
            outgoingGain = bailFromGain * fallGain(progress)
            return
        }
        finish()
    }

    // ---- Lifecycle of a transition -----------------------------------------

    /**
     * Abandons whatever is in flight.
     *
     * What has to be put back depends entirely on whether [startFade] got as
     * far as swapping the roles. Before the handoff the session engine is
     * untouched and the standby is a silent scratch engine, so there is
     * nothing to unwind at all — [finish] just retires it. After the handoff
     * the session has already moved and cannot be moved back (the incoming
     * track is playing and has been announced), so the only thing left is to
     * take the outgoing track away gracefully.
     */
    private fun bail() {
        if (phase == Phase.IDLE || phase == Phase.BAILING) return
        TrackLog.d(TAG, "bail from $phase")
        armJob?.cancel()
        armJob = null
        if (!handedOff) {
            // Nothing was ever audible; no ramp to run.
            finish()
            return
        }
        // Full volume on the track the listener asked for — it must not spend
        // the rest of its first seconds still rising from wherever the blend
        // left it — and a glide, not a snap, on the one leaving: a volume cut
        // mid-note is a click, and the ramp exists to avoid exactly that.
        incoming?.setVolume(userVolume())
        bailFromGain = outgoingGain
        bailStartedAt = now()
        phase = Phase.BAILING
    }

    private fun finish() {
        armJob?.cancel()
        armJob = null

        if (handedOff) {
            // The roles have already traded: the incoming engine is the
            // session and owns the queue from here, and the outgoing one is
            // spare.
            incoming?.setVolume(userVolume())
            outgoing?.let(::retire)
        } else {
            // The transition never became audible, so the session engine
            // never moved and the standby is the one to throw away.
            outgoing?.setVolume(userVolume())
            incoming?.let(::retire)
        }

        outgoing = null
        incoming = null
        handedOff = false
        standbyReady = false
        standbyLengthMs = 0L
        standbyUrl = null
        outgoingEnded = false
        shadowPaused = false
        outgoingGain = 1f
        fadeMs = 0L
        fadeEndMs = 0L
        phase = Phase.IDLE
    }

    /** Still a next track, still switched on — the setting that armed this one has not moved. */
    private fun stillWorthFading(): Boolean =
        AppSettings.crossfadeSeconds.value > 0 && hasNext()

    /**
     * Puts an engine back in the drawer: emptied of media, silent no longer.
     *
     * The volume matters as much as the stopping. An engine left at the gain
     * it faded out on is the next transition's *incoming* engine, and it
     * would arrive already turned down — so the reset is part of retiring it,
     * not part of preparing it.
     */
    private fun retire(engine: AudioEngine) {
        engine.stop()
        engine.setVolume(userVolume())
    }

    // ---- Numbers ------------------------------------------------------------

    private fun configuredFadeMs(): Long = AppSettings.crossfadeSeconds.value * 1000L

    /**
     * The configured length, kept off tracks too short to spend it on. A fade
     * that swallows a third of a song stops being a transition and starts
     * being the arrangement.
     */
    private fun fadeFor(duration: Long): Long {
        val configured = configuredFadeMs()
        if (duration <= 0L) return configured
        return minOf(configured, duration / 3).coerceAtLeast(0L)
    }

    /** Equal-power pair: [riseGain]² + [fallGain]² = 1, so the blend never dips. */
    private fun riseGain(progress: Float): Float =
        sin(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    private fun fallGain(progress: Float): Float =
        cos(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    private companion object {
        const val TAG = "BitChordCrossfade"

        /** Ramp used when a fade is interrupted. */
        const val BAIL_MS = 120L

        /**
         * Head start the standby gets to resolve, open the incoming track and
         * buffer to its cue point.
         *
         * Sized for a *stream being opened*, which is the only thing arming
         * waits on now — there is no alignment to converge. Usually instant,
         * as the next track has normally been read ahead onto disk by the
         * time it matters, but a cold one has to be resolved and fetched, and
         * a transition that arrives before its incoming track is ready is one
         * that gets dropped.
         */
        const val ARM_LEAD_MS = 4_000L

        /**
         * Longest a transition will wait on an incoming track that will not
         * become ready — the resolve included, which is inside the same
         * window here where upstream spent it on buffering alone. Past this
         * the queue is left to move on plainly, which is a missed crossfade
         * rather than a broken one.
         */
        const val ARM_TIMEOUT_MS = 12_000L

        /** Per-phase tick steps, verbatim from upstream. */
        const val IDLE_STEP_MS = 250L
        const val ARM_STEP_MS = 40L
        const val FADE_STEP_MS = 30L
        const val BAIL_STEP_MS = 15L
    }
}
