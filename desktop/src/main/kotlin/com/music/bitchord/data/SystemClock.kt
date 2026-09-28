package com.music.bitchord.data

/**
 * `android.os.SystemClock.elapsedRealtime()`'s desktop equivalent: monotonic
 * milliseconds from an arbitrary origin, which is what the timing in the
 * resolve path wants — `System.currentTimeMillis()` would jump whenever the
 * machine's clock is adjusted.
 *
 * Kept under the Android name so the ported files differ from upstream by an
 * import line and nothing else.
 */
object SystemClock {
    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000
}
