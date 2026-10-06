package com.music.bitchord.data

/**
 * `android.util.Log`, minus the release build.
 *
 * Desktop stand-in: writes to stdout instead of logcat. No BuildConfig here,
 * so the gate is the `bitchord.debug` system property, defaulting to on —
 * every desktop build is a development build for now.
 *
 * Everything printed also lands in a small ring ([LogRing]), because stdout
 * is not retrievable after the fact — the row menu's "copy log" action hands
 * the last lines to the clipboard, which is how a user files what went wrong
 * without a console open.
 *
 * Import as `import com.music.bitchord.data.DebugLog as Log` to drop in
 * without touching call sites.
 */
object DebugLog {
    private val enabled: Boolean =
        System.getProperty("bitchord.debug")?.toBooleanStrictOrNull() ?: true

    private val ring = LogRing()

    fun d(tag: String, message: String) = log("D/$tag: $message")

    fun i(tag: String, message: String) = log("I/$tag: $message")

    fun w(tag: String, message: String) = log("W/$tag: $message")

    fun w(tag: String, message: String, error: Throwable) =
        log("W/$tag: $message\n${error.stackTraceToString()}")

    fun e(tag: String, message: String) = log("E/$tag: $message")

    fun e(tag: String, message: String, error: Throwable) =
        log("E/$tag: $message\n${error.stackTraceToString()}")

    /** The ring's contents, oldest line first — what the "copy log" action copies. */
    fun dump(): String = ring.dump()

    private fun log(line: String) {
        if (!enabled) return
        println(line)
        ring.record(line)
    }
}

/**
 * The last [capacity] log lines, in the order they were printed.
 *
 * Both members synchronize: log lines arrive from the player's queue
 * dispatcher, the IO pool and the UI thread, and a copy-log click can race a
 * write that is mid-line.
 */
class LogRing(private val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0) { "a ring that keeps nothing is not a ring" }
    }

    private val lines = ArrayDeque<String>()

    @Synchronized
    fun record(line: String) {
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast(line)
    }

    /** Oldest to newest, one line per row, without a trailing break. */
    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
