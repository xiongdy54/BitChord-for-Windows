package com.music.bitchord.desktop

import com.music.bitchord.data.AppFiles
import com.music.bitchord.data.DebugLog
import kotlinx.coroutines.CoroutineExceptionHandler
import java.util.concurrent.CancellationException
import kotlin.coroutines.CoroutineContext

/**
 * The app's last-resort coroutine exception reporter, found by kotlinx through
 * its `ServiceLoader` lookup before any uncaught-exception fallback runs.
 *
 * One exception arrives here on every quit and is dropped by name: compose's
 * `Recomposer` ends its effect job with an unwind signal —
 * `CancellationException("Recomposer effect job completed")`, thrown from the
 * job's own completion hook — and when the runner job is already gone (the
 * quit path's dispose order sees to that) nothing catches it. The signal is
 * a stop mark, not a fault; left alone it surfaces as a bare
 * "Exception in thread" line on every shutdown, its stack cut off by the
 * process halt before a printer can say anything.
 *
 * Everything else that reaches this handler is a real uncaught coroutine
 * failure and goes to the same reporting as thread-level uncaughts: the
 * crash file (the after-the-fact record) and [DebugLog] (the in-app ring).
 */
internal class GlobalCoroutineExceptionHandler : CoroutineExceptionHandler {

    override val key: CoroutineContext.Key<*> = CoroutineExceptionHandler

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        if (exception is CancellationException &&
            exception.message == "Recomposer effect job completed"
        ) {
            return
        }
        runCatching {
            AppFiles.file("crash-latest.txt").writeText(
                buildString {
                    appendLine("thread: ${Thread.currentThread().name} (coroutine)")
                    appendLine("at: ${java.time.LocalDateTime.now()}")
                    appendLine(exception.stackTraceToString())
                },
                java.nio.charset.StandardCharsets.UTF_8,
            )
        }
        DebugLog.e("BitChord", "uncaught coroutine failure", exception)
    }
}
