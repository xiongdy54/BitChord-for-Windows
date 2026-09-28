package com.music.bitchord.data

/**
 * `android.util.Log`, minus the release build.
 *
 * Desktop stand-in: writes to stdout instead of logcat. No BuildConfig here,
 * so the gate is the `bitchord.debug` system property, defaulting to on —
 * every desktop build is a development build for now.
 *
 * Import as `import com.music.bitchord.data.DebugLog as Log` to drop in
 * without touching call sites.
 */
object DebugLog {
    private val enabled: Boolean =
        System.getProperty("bitchord.debug")?.toBooleanStrictOrNull() ?: true

    fun d(tag: String, message: String) {
        if (enabled) println("D/$tag: $message")
    }

    fun i(tag: String, message: String) {
        if (enabled) println("I/$tag: $message")
    }

    fun w(tag: String, message: String) {
        if (enabled) println("W/$tag: $message")
    }

    fun w(tag: String, message: String, error: Throwable) {
        if (enabled) println("W/$tag: $message\n${error.stackTraceToString()}")
    }

    fun e(tag: String, message: String) {
        if (enabled) println("E/$tag: $message")
    }

    fun e(tag: String, message: String, error: Throwable) {
        if (enabled) println("E/$tag: $message\n${error.stackTraceToString()}")
    }
}
