package com.music.bitchord.data

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One OkHttp client for the whole app.
 *
 * This matters: googlevideo binds a stream URL to the connection context of
 * the `player` request that minted it — address family, most of all. Innertube,
 * NewPipe's own signature fetches and every [com.music.bitchord.data.innertube.StreamResolver]
 * probe share this one client so DNS and connection pooling are identical for
 * all of them.
 *
 * Playback is the exception on desktop, and a live hazard: libvlc fetches the
 * media over its own stack, so a stream URL resolved here arrives from a
 * different connection than the one that minted it. If a track 403s in the
 * player but not in a probe, that is where to look first.
 *
 * OkHttp's stock [Dispatcher] allows only 5 requests in flight to a single
 * host at a time, which a resolve running alongside a browse will queue
 * behind; sized well past anything this app drives concurrently, so the queue
 * is never the reason a request was slow.
 */
object Http {

    // ---- Temporary data-usage instrumentation --------------------------
    //
    // Every request funnels through this one client, so a network
    // interceptor here sees every byte the app receives, regardless of which
    // subsystem asked for it. Categorised by host — googlevideo.com is
    // YouTube's own audio bytes (and StreamResolver's probes,
    // distinguishable by their small actual read against a 2MB Range ask),
    // a canvas provider's CDN is motion artwork, youtube.com / youtubei is
    // API/extraction traffic. Each line is one response with its actual
    // transferred bytes and the running total for its host.
    //
    // Off by default: wrapping every response body in a counting source and
    // writing a line per request is not free.
    private const val USAGE_LOGGING_ENABLED = false
    private const val USAGE_TAG = "BCDataUsage"
    private val usageTotals = ConcurrentHashMap<String, AtomicLong>()

    private class CountingSource(source: Source, private val onClose: (Long) -> Unit) :
        ForwardingSource(source) {
        private var bytes = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = super.read(sink, byteCount)
            if (read > 0) bytes += read
            return read
        }

        override fun close() {
            super.close()
            onClose(bytes)
        }
    }

    private class CountedBody(
        private val delegate: ResponseBody,
        private val counted: BufferedSource,
    ) : ResponseBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun source() = counted
    }

    private val usageInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val body = response.body
        if (body == null) {
            response
        } else {
            val host = request.url.host
            val range = request.header("Range")
            val counting = CountingSource(body.source()) { bytes ->
                val total = usageTotals.computeIfAbsent(host) { AtomicLong() }.addAndGet(bytes)
                DebugLog.d(
                    USAGE_TAG,
                    "$host ${request.method} ${request.url.encodedPath} " +
                        "range=$range status=${response.code} bytes=$bytes total[$host]=$total",
                )
            }.buffer()
            response.newBuilder().body(CountedBody(body, counting)).build()
        }
    }
    // ---- End temporary instrumentation ----------------------------------

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .apply { if (USAGE_LOGGING_ENABLED) addNetworkInterceptor(usageInterceptor) }
        .build()
}
