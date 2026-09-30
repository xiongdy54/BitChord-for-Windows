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

    /**
     * `android.os.SystemClock.uptimeMillis()`: the same monotonic clock without
     * deep sleep. The UI's tap windows only ever compare two readings against
     * each other, so on a machine that does not suspend into this clock's
     * account the two are the same number — and keeping both names means the
     * ported call sites stay verbatim.
     */
    fun uptimeMillis(): Long = elapsedRealtime()
}
