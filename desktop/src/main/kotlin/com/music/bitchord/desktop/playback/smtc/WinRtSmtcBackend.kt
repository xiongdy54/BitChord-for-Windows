package com.music.bitchord.desktop.playback.smtc

import com.music.bitchord.data.TrackLog
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/*
 * ABI facts, every one of them read out of the Windows SDK installed on this
 * machine (10.0.26100.0) rather than remembered:
 *
 * - `um/SystemMediaTransportControlsInterop.h` — the interop factory's IID and
 *   its single `GetForWindow` method, the only way a Win32 (unpackaged) app
 *   claims a SystemMediaTransportControls.
 * - `winrt/impl/windows.media.0.h` — the ABI blocks whose virtual slots this
 *   file numbers, and the GUIDs and enum values quoted below.
 * - `winrt/impl/windows.foundation.0.h` / `windows.storage.streams.0.h` — the
 *   Uri factory and the RandomAccessStreamReference statics used for artwork.
 *
 * WinRT interfaces are COM: slots 0-5 of every vtable are IUnknown's three plus
 * IInspectable's three, and the first interface method therefore lives at slot
 * 6. Every GUID below is the little-endian binary layout MIDL produces, built
 * by [iid] from its canonical text.
 */

/** combase's WinRT activation surface; the whole of what this backend needs from it. */
private interface Combase : StdCallLibrary {
    fun RoInitialize(threadingModel: Int): Int

    // The HSTRING parameters are handles — pointer-sized, carried as [Pointer].
    // Only WindowsCreateString sees a wide-character source string.
    fun RoGetActivationFactory(classId: Pointer, iid: Pointer, factoryOut: Pointer): Int
    fun RoActivateInstance(classId: Pointer, instanceOut: Pointer): Int
    fun WindowsCreateString(source: WString, length: Int, hstringOut: Pointer): Int
    fun WindowsDeleteString(hstring: Pointer): Int
}

/**
 * The one production [SmtcBackend]: `Windows.Media.SystemMediaTransportControls`
 * reached through hand-marshalled COM.
 *
 * Every call runs on a single dedicated thread that [attach] initializes with
 * `RoInitialize(MTA)` — the app's other threads belong to AWT, which holds an
 * STA on the EDT, and a WinRT apartment may not be re-initialized in a
 * different mode, so borrowing any AWT thread is a guaranteed
 * `RPC_E_CHANGED_MODE`. The one thread this backend owns keeps its own MTA for
 * its whole life. Event callbacks (button presses, scrubber drags) arrive back
 * on Windows's own RPC threads — [onButton] and [onSeekRequested] fire there,
 * and whoever consumes them hops the rest of the way.
 *
 * Failure is a mode, not an exception: any HRESULT that is not S_OK during
 * [attach] leaves [isAttached] false and every later verb does nothing. A
 * machine without the overlay — or a test — loses the system surface and
 * nothing else. Nothing here throws into the player's path, which is why the
 * HRESULTs are logged rather than propagated: the overlay is a surface for
 * playback, never a precondition of it.
 */
class WinRtSmtcBackend : SmtcBackend {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bitchord-smtc").apply { isDaemon = true }
    }

    private val combase: Combase by lazy { Native.load("combase", Combase::class.java) }

    @Volatile
    override var isAttached: Boolean = false
        private set

    @Volatile
    override var onButton: ((SmtcButton) -> Unit)? = null

    @Volatile
    override var onSeekRequested: ((Long) -> Unit)? = null

    // The long-lived COM objects, built once in [attach] and kept for the
    // process: the control itself, its second interface (timeline and seek
    // requests), the display updater, its music properties, the timeline
    // property bag, and the two activation factories artwork needs.
    private var control: Pointer? = null
    private var control2: Pointer? = null
    private var updater: Pointer? = null
    private var music: Pointer? = null
    private var timeline: Pointer? = null
    private var uriFactory: Pointer? = null
    private var streamReferenceStatics: Pointer? = null

    private var buttonHandler: Handler? = null
    private var seekHandler: Handler? = null
    private var buttonToken: Memory? = null
    private var seekToken: Memory? = null

    override fun attach(hwndHandle: Long) {
        // [SmtcSession] calls this once and then reads [isAttached]; the build
        // is answered synchronously (bounded) so the first state push after
        // this returns lands on a live overlay rather than vanishing.
        val done = CountDownLatch(1)
        executor.execute {
            try {
                build(hwndHandle)
                buildOk = true
            } catch (t: Throwable) {
                TrackLog.w(TAG, "smtc attach failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                done.countDown()
            }
        }
        isAttached = done.await(ATTACH_WAIT_SECONDS, TimeUnit.SECONDS) && buildOk
    }

    /** Set only when the whole of [build] finished; the latch alone says "the attempt finished". */
    @Volatile
    private var buildOk = false

    /**
     * The attach sequence, in the order the ABI demands. Each step checks its
     * HRESULT; the first failure stops the sequence and the backend stays
     * detached ([isAttached] false), which [SmtcSession] reads before every
     * push.
     */
    private fun build(hwndHandle: Long) {
        combase.RoInitialize(RO_INIT_MULTITHREADED).also { hr ->
            // S_FALSE (1) means "already initialized on this thread" — both are fine.
            check(hr == S_OK || hr == S_FALSE) { "RoInitialize: 0x${hr.toUInt().toString(16)}" }
        }

        // The interop interface is requested from the *runtime class's* factory
        // — the interop type itself is not a class, and asking for it by name
        // answers REGDB_E_CLASSNOTREG.
        val interop = activationFactory("Windows.Media.SystemMediaTransportControls", IID_INTEROP)
            ?: error("no interop factory")
        val controlOut = Memory(Native.POINTER_SIZE.toLong())
        checkHr(
            call(
                interop, SLOT_INTEROP_GET_FOR_WINDOW,
                Pointer.createConstant(hwndHandle), IID_SMTC, controlOut,
            ),
            "GetForWindow",
        )
        control = controlOut.getPointer(0) ?: error("GetForWindow produced no control")
        val ctrl = control!!

        // The overlay itself, and the buttons this player can answer. Stop is
        // left off on purpose: there is no "stop" in the app's own surface, so
        // the overlay advertising one would promise a verb that does nothing.
        putInt(ctrl, SLOT_PUT_IS_ENABLED, 1)
        putInt(ctrl, SLOT_PUT_IS_PLAY_ENABLED, 1)
        putInt(ctrl, SLOT_PUT_IS_PAUSE_ENABLED, 1)
        putInt(ctrl, SLOT_PUT_IS_PREVIOUS_ENABLED, 1)
        putInt(ctrl, SLOT_PUT_IS_NEXT_ENABLED, 1)

        // Button presses — overlay buttons and the keyboard's media keys are
        // the same event in Windows's plumbing.
        buttonHandler = Handler(EVENT_BUTTON_HANDLER) { self, _, args ->
            when (val button = callIntOut(args, SLOT_ARGS_GET_BUTTON)) {
                BUTTON_PLAY -> onButton?.invoke(SmtcButton.PLAY)
                BUTTON_PAUSE -> onButton?.invoke(SmtcButton.PAUSE)
                BUTTON_STOP -> onButton?.invoke(SmtcButton.STOP)
                BUTTON_NEXT -> onButton?.invoke(SmtcButton.NEXT)
                BUTTON_PREVIOUS -> onButton?.invoke(SmtcButton.PREVIOUS)
                // Channel up/down, record, fast-forward, rewind: buttons the
                // app has no action for. The event is acknowledged and dropped.
                else -> TrackLog.d(TAG, "smtc button $button ignored")
            }
            S_OK
        }
        val buttonTokenOut = Memory(8)
        checkHr(call(ctrl, SLOT_ADD_BUTTON_PRESSED, buttonHandler!!.self, buttonTokenOut), "add_ButtonPressed")
        buttonToken = buttonTokenOut

        // Timeline properties and the scrubber's seek requests live on the
        // second interface of the same object.
        val control2Out = Memory(Native.POINTER_SIZE.toLong())
        checkHr(queryInterface(ctrl, IID_SMTC2, control2Out), "QueryInterface(SMTC2)")
        control2 = control2Out.getPointer(0) ?: error("no SMTC2 interface")
        val ctrl2 = control2!!

        seekHandler = Handler(EVENT_SEEK_HANDLER) { _, _, args ->
            val ticks = Memory(8)
            call(args, SLOT_ARGS_GET_REQUESTED_POSITION, ticks)
            val ms = ticks.getLong(0) / TICKS_PER_MS
            onSeekRequested?.invoke(ms)
            S_OK
        }
        val seekTokenOut = Memory(8)
        checkHr(call(ctrl2, SLOT_ADD_SEEK_REQUESTED, seekHandler!!.self, seekTokenOut), "add_PlaybackPositionChangeRequested")
        seekToken = seekTokenOut

        // The metadata half: a music-typed updater and its properties bag.
        val updaterOut = Memory(Native.POINTER_SIZE.toLong())
        checkHr(call(ctrl, SLOT_GET_DISPLAY_UPDATER, updaterOut), "get_DisplayUpdater")
        updater = updaterOut.getPointer(0) ?: error("no display updater")
        putInt(updater!!, SLOT_UPDATER_PUT_TYPE, MEDIA_TYPE_MUSIC)
        val musicOut = Memory(Native.POINTER_SIZE.toLong())
        checkHr(call(updater!!, SLOT_UPDATER_GET_MUSIC, musicOut), "get_MusicProperties")
        music = musicOut.getPointer(0) ?: error("no music properties")

        // The timeline property bag, one instance reused for every push — it
        // is a plain property holder, created empty and written per push.
        val timelineInspectable = Memory(Native.POINTER_SIZE.toLong())
        val classId = hstring("Windows.Media.SystemMediaTransportControlsTimelineProperties")
        // hstring() may hand back null (the empty string is a null handle); a
        // class id is never empty, so this is only the type system's cut of it.
        val classIdHandle = requireNotNull(classId)
        checkHr(combase.RoActivateInstance(classIdHandle, timelineInspectable), "RoActivateInstance(TimelineProperties)")
        deleteString(classId)
        val timelineOut = Memory(Native.POINTER_SIZE.toLong())
        val timelineRaw = timelineInspectable.getPointer(0)
        checkHr(
            if (timelineRaw != null) queryInterface(timelineRaw, IID_TIMELINE, timelineOut) else E_NOTIMPL,
            "QueryInterface(TimelineProperties)",
        )
        timeline = timelineOut.getPointer(0) ?: error("no timeline properties")
    }

    override fun setPlaybackStatus(status: Int) {
        withControl { putInt(it, SLOT_PUT_PLAYBACK_STATUS, status) }
    }

    override fun setMetadata(title: String?, artist: String?, artworkUrl: String?) {
        val upd = updater ?: return
        val props = music ?: return
        val titleH = hstring(title.orEmpty())
        val artistH = hstring(artist.orEmpty())
        try {
            putPointer(props, SLOT_MUSIC_PUT_TITLE, titleH)
            putPointer(props, SLOT_MUSIC_PUT_ARTIST, artistH)
        } finally {
            deleteString(titleH)
            deleteString(artistH)
        }
        // Artwork: a URL the system fetches itself. The two temporaries (a
        // Uri and a stream reference) are released as soon as the updater has
        // taken its own reference — a track change makes a pair, and a queue
        // is thousands of tracks over a session.
        if (artworkUrl != null) {
            artworkReference(artworkUrl)?.let { reference ->
                try {
                    putPointer(upd, SLOT_UPDATER_PUT_THUMBNAIL, reference)
                } finally {
                    call(reference, SLOT_RELEASE)
                }
            }
        } else {
            call(upd, SLOT_UPDATER_CLEAR_ALL)
        }
        call(upd, SLOT_UPDATER_UPDATE)
    }

    /** A `RandomAccessStreamReference` over an artwork URL, or null if the chain failed. */
    private fun artworkReference(url: String): Pointer? {
        val uriFactory = runCatching {
            uriFactory ?: activationFactory("Windows.Foundation.Uri", IID_URI_FACTORY)?.also { uriFactory = it }
        }.getOrNull() ?: return null
        val streamStatics = runCatching {
            streamReferenceStatics
                ?: activationFactory(
                    "Windows.Storage.Streams.RandomAccessStreamReference",
                    IID_STREAM_REFERENCE_STATICS,
                )?.also { streamReferenceStatics = it }
        }.getOrNull() ?: return null
        val urlH = hstring(url) ?: return null
        try {
            val uriOut = Memory(Native.POINTER_SIZE.toLong())
            if (call(uriFactory, SLOT_URI_CREATE, urlH, uriOut) != S_OK) return null
            val uri = uriOut.getPointer(0) ?: return null
            try {
                val refOut = Memory(Native.POINTER_SIZE.toLong())
                if (call(streamStatics, SLOT_STATICS_CREATE_FROM_URI, uri, refOut) != S_OK) return null
                return refOut.getPointer(0)
            } finally {
                call(uri, SLOT_RELEASE)
            }
        } finally {
            deleteString(urlH)
        }
    }

    override fun setTimeline(positionMs: Long, durationMs: Long) {
        val tl = timeline ?: return
        // A length nobody knows has no scrubber to place — the push is skipped
        // and lands when the engine reports the media's length.
        if (durationMs <= 0L) return
        putLong(tl, SLOT_TL_PUT_START_TIME, 0)
        putLong(tl, SLOT_TL_PUT_END_TIME, durationMs * TICKS_PER_MS)
        putLong(tl, SLOT_TL_PUT_MIN_SEEK, 0)
        putLong(tl, SLOT_TL_PUT_MAX_SEEK, durationMs * TICKS_PER_MS)
        putLong(tl, SLOT_TL_PUT_POSITION, positionMs * TICKS_PER_MS)
        control2?.let { call(it, SLOT_UPDATE_TIMELINE, tl) }
    }

    override fun release() {
        if (!isAttached) return
        isAttached = false
        executor.execute {
            try {
                control?.let {
                    putInt(it, SLOT_PUT_PLAYBACK_STATUS, STATUS_CLOSED)
                    putInt(it, SLOT_PUT_IS_ENABLED, 0)
                    buttonToken?.let { token -> call(it, SLOT_REMOVE_BUTTON_PRESSED, token) }
                }
                control2?.let { ctrl2 ->
                    seekToken?.let { token -> call(ctrl2, SLOT_REMOVE_SEEK_REQUESTED, token) }
                }
            } catch (t: Throwable) {
                TrackLog.w(TAG, "smtc release failed: ${t.message}")
            }
        }
        executor.shutdown()
    }

    // ── platform plumbing ────────────────────────────────────────────────────

    /**
     * Runs [block] on the WinRT thread. After [release] the executor is shut
     * down and late pushes from a dying composition are dropped rather than
     * rejected.
     */
    private fun withControl(block: (Pointer) -> Unit) {
        val ctrl = control ?: return
        if (!isAttached) return
        runCatching { executor.execute { runCatching { block(ctrl) } } }
    }

    private fun activationFactory(classId: String, iidMemory: Memory): Pointer? {
        val h = hstring(classId) ?: return null
        try {
            val out = Memory(Native.POINTER_SIZE.toLong())
            val hr = combase.RoGetActivationFactory(h, iidMemory, out)
            if (hr != S_OK) {
                TrackLog.w(TAG, "RoGetActivationFactory($classId): 0x${hr.toUInt().toString(16)}")
                return null
            }
            return out.getPointer(0)
        } finally {
            deleteString(h)
        }
    }

    /** An HSTRING handle over [s], or null — a null handle is the empty string to every put. */
    private fun hstring(s: String): Pointer? {
        val out = Memory(Native.POINTER_SIZE.toLong())
        if (combase.WindowsCreateString(WString(s), s.length, out) != S_OK) return null
        return out.getPointer(0)
    }

    private fun deleteString(h: Pointer?) {
        if (h != null) runCatching { combase.WindowsDeleteString(h) }
    }

    /** Calls the [slot] on [instance]'s vtable with [args] and returns the HRESULT. */
    private fun call(instance: Pointer, slot: Int, vararg args: Any?): Int =
        slotFn(instance, slot).invokeInt((listOf(instance) + args).toTypedArray())

    private fun slotFn(instance: Pointer, slot: Int): Function =
        Function.getFunction(
            instance.getPointer(0).getPointer(slot.toLong() * Native.POINTER_SIZE),
            // x64 has one calling convention; the marker is for 32-bit builds this app does not ship.
            Function.C_CONVENTION,
        )

    private fun putInt(instance: Pointer, slot: Int, value: Int) {
        checkHr(call(instance, slot, value), "slot $slot")
    }

    private fun putLong(instance: Pointer, slot: Int, value: Long) {
        checkHr(call(instance, slot, value), "slot $slot")
    }

    private fun putPointer(instance: Pointer, slot: Int, value: Pointer?) {
        checkHr(call(instance, slot, value), "slot $slot")
    }

    private fun queryInterface(instance: Pointer, iidMemory: Memory, out: Memory): Int =
        call(instance, SLOT_QUERY_INTERFACE, iidMemory, out)

    /** Calls the [slot]'s `get*(int32* out)` and reads the figure back. */
    private fun callIntOut(instance: Pointer, slot: Int): Int {
        val out = Memory(4)
        call(instance, slot, out)
        return out.getInt(0)
    }

    private fun checkHr(hr: Int, what: String) {
        check(hr == S_OK) { "$what: 0x${hr.toUInt().toString(16)}" }
    }

    /**
     * A hand-built COM object good enough to be an event handler.
     *
     * WinRT events take an `ITypedEventHandler<TSender, TArgs>` — seven
     * virtuals on top of IInspectable, only the last of which does anything.
     * The object is a [Memory] holding a pointer to a vtable of JNA callback
     * trampolines. Its reference count never moves off two, on purpose: the
     * runtime's Release can never free it (there is nothing to free — the
     * storage is this JVM's), and the Java side holds it for exactly as long
     * as the subscription does.
     *
     * `QueryInterface` answers exactly what the runtime needs and refuses the
     * rest: IUnknown, IInspectable, the handler's own parameterized interface
     * ([interfaceBytes] — the runtime type-checks its event sink) and
     * [IAgileObject][IID_IAGILE_OBJECT_BYTES], the "call me from any
     * apartment" yes a handler subscribed off an MTA must give. The weak
     * reference family asks too and is safe to refuse — measured: the
     * subscription lands without it. IMarshal above all must not be claimed:
     * a yes there sends the runtime into marshalling calls this object cannot
     * answer, and the subscription dies.
     *
     * Both the [Memory]s and the callbacks are held as fields — the moment any
     * of them is collected, the next event call through the stale vtable is a
     * crash.
     */
    private class Handler(
        val interfaceBytes: ByteArray,
        val onInvoke: (self: Pointer, sender: Pointer, args: Pointer) -> Int,
    ) {

        private val qi: Callback = object : Callback {
            @Suppress("unused", "UNUSED_PARAMETER")
            fun callback(self: Pointer, riid: Pointer, out: Pointer): Int {
                val asked = riid.getByteArray(0, 16)
                val accepted = asked.contentEquals(IID_IUNKNOWN_BYTES) ||
                    asked.contentEquals(IID_IINSPECTABLE_BYTES) ||
                    asked.contentEquals(IID_IAGILE_OBJECT_BYTES) ||
                    asked.contentEquals(interfaceBytes)
                if (!accepted) return E_NOINTERFACE
                out.setPointer(0, self)
                return S_OK
            }
        }
        private val addRef: Callback = object : Callback {
            @Suppress("unused", "UNUSED_PARAMETER")
            fun callback(self: Pointer): Int = 2
        }
        private val release: Callback = object : Callback {
            @Suppress("unused", "UNUSED_PARAMETER")
            fun callback(self: Pointer): Int = 2
        }
        private val getIids: Callback = object : Callback {
            @Suppress("unused", "UNUSED_PARAMETER")
            fun callback(self: Pointer, count: Pointer, iids: Pointer): Int {
                count.setInt(0, 0)
                return S_OK
            }
        }
        private val getRuntimeClassName: Callback = object : Callback {
            @Suppress("unused", "UNUSED_PARAMETER")
            fun callback(self: Pointer, className: Pointer): Int = E_NOTIMPL
        }
        private val getTrustLevel: Callback = object : Callback {
            @Suppress("unused", "UNUSED_PARAMETER")
            fun callback(self: Pointer, trustLevel: Pointer): Int {
                trustLevel.setInt(0, 0)
                return S_OK
            }
        }
        private val invoke: Callback = object : Callback {
            @Suppress("unused")
            fun callback(self: Pointer, sender: Pointer, args: Pointer): Int =
                runCatching { onInvoke(self, sender, args) }.getOrDefault(S_OK)
        }

        val self: Pointer = Memory(Native.POINTER_SIZE.toLong())

        init {
            val vtable = Memory(7L * Native.POINTER_SIZE)
            listOf(qi, addRef, release, getIids, getRuntimeClassName, getTrustLevel, invoke)
                .forEachIndexed { index, callback ->
                    vtable.setPointer(
                        (index * Native.POINTER_SIZE).toLong(),
                        CallbackReference.getFunctionPointer(callback),
                    )
                }
            self.setPointer(0, vtable)
        }
    }

    companion object {
        private const val TAG = "BitChord"

        private const val S_OK = 0
        private const val S_FALSE = 1
        private const val E_NOTIMPL = 0x80004001.toInt()
        private const val E_NOINTERFACE = 0x80004002.toInt()
        private const val RO_INIT_MULTITHREADED = 1

        /** IUnknown, in the byte layout [iid] produces — read back for QueryInterface. */
        private val IID_IUNKNOWN_BYTES = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0xC0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46.toByte(),
        )

        /** IInspectable: af86e2e0-b12d-4c6a-9c5a-d7aa65101e90. */
        private val IID_IINSPECTABLE_BYTES = byteArrayOf(
            0xE0.toByte(), 0xE2.toByte(), 0x86.toByte(), 0xAF.toByte(), 0x2D.toByte(), 0xB1.toByte(), 0x6A.toByte(), 0x4C.toByte(),
            0x9C.toByte(), 0x5A, 0xD7.toByte(), 0xAA.toByte(), 0x65, 0x10, 0x1E, 0x90.toByte(),
        )

        /** IAgileObject: 94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90 — the "call me from any apartment" yes. */
        private val IID_IAGILE_OBJECT_BYTES = byteArrayOf(
            0x94.toByte(), 0x2B, 0xEA.toByte(), 0x94.toByte(), 0xCC.toByte(), 0xE9.toByte(), 0xE0.toByte(), 0x49.toByte(),
            0xC0.toByte(), 0xFF.toByte(), 0xEE.toByte(), 0x64, 0xCA.toByte(), 0x8F.toByte(), 0x5B, 0x90.toByte(),
        )

        /** Windows.Foundation.TimeSpan's unit. */
        private const val TICKS_PER_MS = 10_000L

        /** Bounded so a wedged COM layer delays startup by this much, not forever. */
        private const val ATTACH_WAIT_SECONDS = 2L

        // ── GUIDs (canonical text; [iid] lays the bytes out) ──

        /** `um/SystemMediaTransportControlsInterop.h`. */
        private const val IID_INTEROP_TEXT = "ddb0472d-c911-4a1f-86d9-dc3d71a95f5a"

        /** `winrt/impl/windows.media.0.h`, `guid_v<…ISystemMediaTransportControls>`. */
        private const val IID_SMTC_TEXT = "99FA3FF4-1742-42A6-902E-087D41F965EC"

        /** `…ISystemMediaTransportControls2`. */
        private const val IID_SMTC2_TEXT = "EA98D2F6-7F3C-4AF2-A586-72889808EFB1"

        /** `…ISystemMediaTransportControlsTimelineProperties`. */
        private const val IID_TIMELINE_TEXT = "5125316A-C3A2-475B-8507-93534DC88F15"

        /** `winrt/impl/windows.foundation.0.h`, `IUriRuntimeClassFactory`. */
        private const val IID_URI_FACTORY_TEXT = "44A9796F-723E-4FDF-A218-033E75B0C084"

        /** `winrt/impl/windows.storage.streams.0.h`, `IRandomAccessStreamReferenceStatics`. */
        private const val IID_STREAM_REFERENCE_STATICS_TEXT = "857309DC-3FBF-4E7D-986F-EF3B1A07A964"

        private val IID_INTEROP by lazy { iid(IID_INTEROP_TEXT) }
        private val IID_SMTC by lazy { iid(IID_SMTC_TEXT) }
        private val IID_SMTC2 by lazy { iid(IID_SMTC2_TEXT) }
        private val IID_TIMELINE by lazy { iid(IID_TIMELINE_TEXT) }
        private val IID_URI_FACTORY by lazy { iid(IID_URI_FACTORY_TEXT) }
        private val IID_STREAM_REFERENCE_STATICS by lazy { iid(IID_STREAM_REFERENCE_STATICS_TEXT) }

        // The two event handlers this backend subscribes are parameterized
        // `ITypedEventHandler` instantiations; their IIDs are derivable from
        // the type signatures (WinRT's UUIDv5-style SHA-1 over the pinterface
        // namespace) but the derivation's exact byte order lives nowhere in
        // the headers, so these were read off the runtime itself: the QI log
        // during a first run named exactly these two, and a parameterized
        // interface's version-5 shape confirms what they are.
        //
        // Button: ITypedEventHandler<SMTC, SMTCButtonPressedEventArgs>.
        // Seek:   ITypedEventHandler<SMTC2, SMTCPlaybackPositionChangeRequestedEventArgs>.
        private val EVENT_BUTTON_HANDLER by lazy { iidBytes("0557E996-7B23-5BAE-AA81-EA0D671143A4") }
        private val EVENT_SEEK_HANDLER by lazy { iidBytes("44E34F15-BDC0-50A7-ACE4-39E91FB753F1") }

        /** [iid]'s layout as a plain array, for QueryInterface comparisons. */
        private fun iidBytes(text: String): ByteArray {
            val memory = iid(text)
            return memory.getByteArray(0, 16)
        }

        /**
         * A GUID in MIDL's binary layout: the three leading fields
         * little-endian, the trailing eight bytes verbatim.
         *
         * Every field parses through [Long] — the canonical texts all have
         * their high bits set somewhere, and radix parsing through [Int] or
         * [Short] throws on exactly those.
         */
        private fun iid(text: String): Memory {
            val hex = text.replace("-", "")
            val memory = Memory(16)
            memory.setInt(0, hex.substring(0, 8).toLong(16).toInt())
            memory.setShort(4, hex.substring(8, 12).toLong(16).toShort())
            memory.setShort(6, hex.substring(12, 16).toLong(16).toShort())
            hex.substring(16).chunked(2).forEachIndexed { index, byte ->
                memory.setByte((8 + index).toLong(), byte.toLong(16).toByte())
            }
            return memory
        }

        // ── vtable slots. Slot 0-5 are IUnknown + IInspectable everywhere. ──

        private const val SLOT_QUERY_INTERFACE = 0
        private const val SLOT_RELEASE = 2

        /** The interop factory's single method. */
        private const val SLOT_INTEROP_GET_FOR_WINDOW = 6

        // ISystemMediaTransportControls (windows.media.0.h, `abi<…>` block):
        // 6 get/7 put PlaybackStatus, 8 get DisplayUpdater, 9 get SoundLevel,
        // 10 get/11 put IsEnabled, 12-31 the per-button get/put pairs (play,
        // stop, pause, record, fast-forward, rewind, previous, next, channel
        // up, channel down), 32/33 ButtonPressed, 34/35 PropertyChanged.
        private const val SLOT_PUT_PLAYBACK_STATUS = 7
        private const val SLOT_GET_DISPLAY_UPDATER = 8
        private const val SLOT_PUT_IS_ENABLED = 11
        private const val SLOT_PUT_IS_PLAY_ENABLED = 13
        private const val SLOT_PUT_IS_PAUSE_ENABLED = 17
        private const val SLOT_PUT_IS_PREVIOUS_ENABLED = 25
        private const val SLOT_PUT_IS_NEXT_ENABLED = 27
        private const val SLOT_ADD_BUTTON_PRESSED = 32
        private const val SLOT_REMOVE_BUTTON_PRESSED = 33

        // ISystemMediaTransportControls2 — unlike classic COM, WinRT interfaces
        // do not share one vtable down an inheritance chain: the QI'd
        // interface's vtable carries only its OWN fifteen methods after the six
        // IInspectable slots. UpdateTimelineProperties therefore lives at 12
        // here, not at 42 where summing the base's methods would put it.
        private const val SLOT_UPDATE_TIMELINE = 12
        private const val SLOT_ADD_SEEK_REQUESTED = 13
        private const val SLOT_REMOVE_SEEK_REQUESTED = 14

        // ISystemMediaTransportControlsDisplayUpdater: 6/7 Type, 8/9
        // AppMediaId, 10/11 Thumbnail, 12 MusicProperties, 13/14
        // Video/ImageProperties, 15 CopyFromFileAsync, 16 ClearAll, 17 Update.
        private const val SLOT_UPDATER_PUT_TYPE = 7
        private const val SLOT_UPDATER_PUT_THUMBNAIL = 11
        private const val SLOT_UPDATER_GET_MUSIC = 12
        private const val SLOT_UPDATER_CLEAR_ALL = 16
        private const val SLOT_UPDATER_UPDATE = 17

        // IMusicDisplayProperties: 6/7 Title, 8/9 AlbumArtist, 10/11 Artist.
        private const val SLOT_MUSIC_PUT_TITLE = 7
        private const val SLOT_MUSIC_PUT_ARTIST = 11

        // ISystemMediaTransportControlsTimelineProperties, TimeSpan ticks:
        // 6/7 StartTime, 8/9 EndTime, 10/11 MinSeekTime, 12/13 MaxSeekTime,
        // 14/15 Position.
        private const val SLOT_TL_PUT_START_TIME = 7
        private const val SLOT_TL_PUT_END_TIME = 9
        private const val SLOT_TL_PUT_MIN_SEEK = 11
        private const val SLOT_TL_PUT_MAX_SEEK = 13
        private const val SLOT_TL_PUT_POSITION = 15

        // IUriRuntimeClassFactory: 6 CreateUri.
        private const val SLOT_URI_CREATE = 6

        // IRandomAccessStreamReferenceStatics: 6 CreateFromFile, 7
        // CreateFromUri, 8 CreateFromStream.
        private const val SLOT_STATICS_CREATE_FROM_URI = 7

        // Event args' getters are each their own interface's slot 6.
        private const val SLOT_ARGS_GET_BUTTON = 6
        private const val SLOT_ARGS_GET_REQUESTED_POSITION = 6

        // Windows.Media.MediaPlaybackType.Music.
        private const val MEDIA_TYPE_MUSIC = 1

        // Windows.Media.MediaPlaybackStatus.Closed, for the release path.
        private const val STATUS_CLOSED = 0

        // Windows.Media.SystemMediaTransportControlsButton.
        private const val BUTTON_PLAY = 0
        private const val BUTTON_PAUSE = 1
        private const val BUTTON_STOP = 2
        private const val BUTTON_NEXT = 6
        private const val BUTTON_PREVIOUS = 7
    }
}
