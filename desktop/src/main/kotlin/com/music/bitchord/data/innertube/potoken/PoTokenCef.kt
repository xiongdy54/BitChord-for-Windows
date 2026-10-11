package com.music.bitchord.data.innertube.potoken

import com.music.bitchord.auth.CefRuntime
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.desktop.resources.Res
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.cef.CefSettings
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.browser.CefRequestContext
import org.cef.callback.CefCallback
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefMessageRouterHandler
import org.cef.handler.CefRequestContextHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * BotGuard PoTokens, minted in a hidden CEF browser — the desktop's shape of the
 * Android build's hidden WebView, whose `po_token.html` this serves verbatim.
 *
 * Why any of this exists: YouTube now gates direct stream URLs behind a proof of
 * origin. A client without a PoToken is answered with either a bot check
 * ("Sign in to confirm that you're not a bot") or SABR-only streams, and this
 * playback engine speaks neither. Declaring a WEB_BOTGUARD provider (see
 * [com.music.bitchord.data.innertube.InnerTubeXResolver]) is what turns
 * InnerTubeX's catalog back onto the direct-URL clients — the same turn the
 * phone makes.
 *
 * The browser is deliberately invisible: an offscreen-rendered browser, created
 * and never attached to any UI, the way the Android WebView never joins a view
 * hierarchy. Its page is served by this session's own resource handler under
 * `https://www.youtube.com` — the `loadDataWithBaseURL` trick the Android
 * original uses: a real origin, a body we control, and no
 * Content-Security-Policy of YouTube's to fight (the live site ships
 * `require-trusted-types-for`, which would block the interpreter's
 * `new Function`). The page makes no requests of its own; the two BotGuard
 * round-trips (Create, GenerateIT) are plain OkHttp calls from this side,
 * exactly as on Android.
 *
 * Answers travel back over the CEF message router — the same `window.cefQuery`
 * channel the sign-in probe uses, injected into every frame of this session's
 * own client.
 */
internal class PoTokenCefSession private constructor() : CefDisplayHandlerAdapter(), CefMessageRouterHandler {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, t -> onFatal(PoTokenException(t.message ?: "$t")) },
    )

    /** Completes with this session once the minter exists; fails with why not. */
    private val initResult = CompletableDeferred<PoTokenCefSession>()

    private var client: CefClient? = null
    private var browser: CefBrowser? = null
    private var router: CefMessageRouter? = null
    private var context: CefRequestContext? = null

    @Volatile var isDead: Boolean = false
        private set

    @Volatile private lateinit var expirationInstant: Instant

    private val tokenContinuations = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val requestCounter = AtomicLong()

    /** Armed before the browser exists: the page's first act can never miss it. */
    @Volatile private var pageReadySignal = CompletableDeferred<Unit>()
    @Volatile private var botguardSignal: CompletableDeferred<String>? = null
    @Volatile private var minterSignal: CompletableDeferred<Unit>? = null

    // ── Initialization ──────────────────────────────────────────────────────

    /**
     * Creates the client and the hidden browser. Both belong on the AWT thread —
     * the same home the sign-in window's browser has — so this runs there too.
     */
    private suspend fun openBrowser() = withContext(Dispatchers.Main) {
        val app = CefRuntime.appOrThrow()
        // Read and arm the page before the browser exists: the first request
        // for it can otherwise arrive before the bytes are ready, and the
        // resource handler must never block or throw on the browser's thread.
        val html = pageHtml()
        val view = app.createClient().also {
            it.addMessageRouter(CefMessageRouter.create().also(::asRouter))
            it.addDisplayHandler(this@PoTokenCefSession)
        }
        client = view
        val requestContext = CefRequestContext.createContext(
            PageContextHandler(CompletableFuture.completedFuture(html)),
        )
        context = requestContext
        browser = view.createBrowser(POTOKEN_URL, true, false, requestContext).also {
            // An offscreen browser has no canvas to be displayed: without this
            // call the native side is never created and no page ever loads.
            it.createImmediately()
        }
    }

    /**
     * Boots BotGuard and creates the minter, following the Android sequence:
     * page ready → Create challenge → runBotGuard → GenerateIT →
     * createPoTokenMinter. Each step's answer arrives over the message router.
     */
    private suspend fun obtainBotguard() {
        pageReadySignal.await()

        botguardSignal = CompletableDeferred()
        makeBotguardServiceRequest(
            "https://www.youtube.com/api/jnn/v1/Create",
            "[ \"$REQUEST_KEY\" ]",
        ) { responseBody ->
            val parsed = runCatching { parseChallengeData(responseBody) }.getOrElse {
                onFatal(PoTokenException("parseChallengeData failed: ${it.message}"))
                return@makeBotguardServiceRequest
            }
            eval(
                """try {
                    var data = $parsed;
                    runBotGuard(data).then(function (result) {
                        window.webPoSignalOutput = result.webPoSignalOutput;
                        window.cefQuery({ request: JSON.stringify({ kind: 'bg', data: String(result.botguardResponse) }), persistent: false, onSuccess: function () {}, onFailure: function () {} });
                    }, function (error) {
                        window.cefQuery({ request: JSON.stringify({ kind: 'error', data: String(error) + "\n" + (error.stack || '') }), persistent: false, onSuccess: function () {}, onFailure: function () {} });
                    });
                } catch (error) {
                    window.cefQuery({ request: JSON.stringify({ kind: 'error', data: String(error) + "\n" + (error.stack || '') }), persistent: false, onSuccess: function () {}, onFailure: function () {} });
                }""",
            )
        }
        val botguardResponse = botguardSignal!!.await()

        minterSignal = CompletableDeferred()
        makeBotguardServiceRequest(
            "https://www.youtube.com/api/jnn/v1/GenerateIT",
            "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]",
        ) { responseBody ->
            val (token, expiresInSeconds) = runCatching { parseIntegrityTokenData(responseBody) }.getOrElse {
                onFatal(PoTokenException("parseIntegrityTokenData failed: ${it.message}"))
                return@makeBotguardServiceRequest
            }
            TrackLog.d(TAG, "integrity token parsed; expires in $expiresInSeconds s")
            // Ten minutes of margin, as on Android.
            expirationInstant = Instant.now().plusSeconds(expiresInSeconds).minus(10, ChronoUnit.MINUTES)
            eval(
                """try {
                    window.integrityToken = $token;
                    createPoTokenMinter(window.webPoSignalOutput, window.integrityToken).then(function () {
                        window.cefQuery({ request: JSON.stringify({ kind: 'minter' }), persistent: false, onSuccess: function () {}, onFailure: function () {} });
                    }, function (error) {
                        window.cefQuery({ request: JSON.stringify({ kind: 'error', data: String(error) + "\n" + (error.stack || '') }), persistent: false, onSuccess: function () {}, onFailure: function () {} });
                    });
                } catch (error) {
                    window.cefQuery({ request: JSON.stringify({ kind: 'error', data: String(error) + "\n" + (error.stack || '') }), persistent: false, onSuccess: function () {}, onFailure: function () {} });
                }""",
            )
        }
        minterSignal!!.await()
        if (initResult.complete(this)) TrackLog.d(TAG, "minter created; ready to mint")
    }

    // ── Minting ─────────────────────────────────────────────────────────────

    val isExpired: Boolean
        get() = ::expirationInstant.isInitialized && Instant.now().isAfter(expirationInstant)

    suspend fun generatePoToken(identifier: String): String {
        if (isDead) throw PoTokenException("the PoToken browser is dead — recreate the session")
        val requestKey = "$identifier#${requestCounter.incrementAndGet()}"
        val answer = CompletableDeferred<String>()
        tokenContinuations[requestKey] = answer
        try {
            val token = withTimeoutOrNull(GENERATE_TIMEOUT_MS) {
                eval(
                    """(function () {
                        var requestKey = "$requestKey";
                        try {
                            var u8Identifier = ${stringToU8(identifier)};
                            obtainPoToken(u8Identifier).then(function (poTokenU8) {
                                window.cefQuery({ request: JSON.stringify({ kind: 'token', key: requestKey, data: String(poTokenU8) }), persistent: false, onSuccess: function () {}, onFailure: function () {} });
                            }, function (error) {
                                window.cefQuery({ request: JSON.stringify({ kind: 'error', key: requestKey, data: String(error) + "\n" + (error.stack || '') }), persistent: false, onSuccess: function () {}, onFailure: function () {} });
                            });
                        } catch (error) {
                            window.cefQuery({ request: JSON.stringify({ kind: 'error', key: requestKey, data: String(error) + "\n" + (error.stack || '') }), persistent: false, onSuccess: function () {}, onFailure: function () {} });
                        }
                    })()""",
                )
                answer.await()
            }
            // A null here is this timeout, not a caller's cancellation: a
            // renderer that never answers is wedged or dead, and the provider
            // recreates the session from scratch rather than reusing it.
            if (token == null) {
                isDead = true
                throw PoTokenException("poToken generation timed out after ${GENERATE_TIMEOUT_MS}ms")
            }
            return token
        } finally {
            tokenContinuations.remove(requestKey)
        }
    }

    // ── Message router ──────────────────────────────────────────────────────

    override fun onQuery(
        browser: CefBrowser,
        frame: CefFrame,
        queryId: Long,
        request: String,
        persisted: Boolean,
        callback: CefQueryCallback,
    ): Boolean {
        val message = runCatching { Json.parseToJsonElement(request).jsonObject }.getOrNull()
        if (message == null) {
            TrackLog.w(TAG, "unparsable bridge message: ${request.take(120)}")
            callback.success("")
            return true
        }
        when (message.kind()) {
            "ready" -> pageReadySignal?.complete(Unit)
            "bg" -> botguardSignal?.complete(message.data())
            "minter" -> minterSignal?.complete(Unit)
            "token" -> tokenContinuations.remove(message.key())?.complete(message.data())
            "error" -> {
                val failure = PoTokenException(message.data().ifBlank { "bridge error" })
                val key = message.key()
                if (key != null) {
                    tokenContinuations.remove(key)?.completeExceptionally(failure)
                } else {
                    onFatal(failure)
                }
            }
        }
        callback.success("")
        return true
    }

    override fun onQueryCanceled(browser: CefBrowser, frame: CefFrame, queryId: Long) {
        // Nothing here cancels a query on purpose; a frame teardown mid-mint
        // surfaces as an init or generation timeout instead.
    }

    // ── Console / display handler ───────────────────────────────────────────

    override fun onConsoleMessage(
        browser: CefBrowser,
        severity: CefSettings.LogSeverity,
        message: String,
        source: String,
        line: Int,
    ): Boolean {
        TrackLog.d(TAG, "JS: $message")
        // An uncaught error after the static page executed means Google's
        // remotely-served program misbehaved — transient (the next session
        // rebuilds), not a broken browser engine.
        if (message.contains("Uncaught")) {
            onFatal(PoTokenException(message))
        }
        return false
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    // ── Plumbing ────────────────────────────────────────────────────────────

    /** The two BotGuard round-trips, from this side like on Android. */
    private fun makeBotguardServiceRequest(
        url: String,
        data: String,
        handleResponseBody: suspend (String) -> Unit,
    ) {
        scope.launch {
            val request = okhttp3.Request.Builder()
                .post(data.toRequestBody("application/json+protobuf".toMediaType()))
                .headers(
                    mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json",
                        "Content-Type" to "application/json+protobuf",
                        "x-goog-api-key" to GOOGLE_API_KEY,
                        "x-user-agent" to "grpc-web-javascript/0.1",
                    ).toHeaders(),
                )
                .url(url)
                .build()
            val (code, body) = Http.client.newCall(request).execute().use { response ->
                response.code to response.body?.string()
            }
            // An empty 200 body is a failure too — the murkier error would
            // otherwise surface later, inside the JS.
            if (body.isNullOrEmpty()) {
                onFatal(PoTokenException("invalid botguard response (code=$code, empty body)"))
            } else {
                handleResponseBody(body)
            }
        }
    }

    private suspend fun eval(js: String) {
        withContext(Dispatchers.Main) {
            browser?.executeJavaScript(js, POTOKEN_URL, 0)
        }
    }

    private fun onFatal(error: Throwable) {
        TrackLog.e(TAG, "PoToken browser fatal: ${error.message}")
        isDead = true
        close()
        initResult.completeExceptionally(error)
    }

    /**
     * Stops the hidden browser. Every CEF object here was created on the AWT
     * thread, so teardown is posted there — callers arrive from IO coroutines
     * and from CEF's own threads, and none of them needs to wait for it.
     */
    fun close() {
        javax.swing.SwingUtilities.invokeLater {
            runCatching { browser?.close(true) }
            runCatching { router?.dispose() }
            runCatching { client?.dispose() }
            runCatching { context?.dispose() }
        }
        scope.cancel()
        tokenContinuations.values.forEach { it.completeExceptionally(PoTokenException("session closed")) }
        tokenContinuations.clear()
    }

    // CefNative, which the query-handler interface extends: the native side
    // hangs a pointer off the handler it drives, stored and returned exactly as
    // CEF's own CefClient does. Nothing on the Kotlin half ever reads it.
    private val nativeRefs = HashMap<String, Long>()

    override fun setNativeRef(identifier: String?, nativeRef: Long) {
        if (identifier != null && nativeRef != 0L) nativeRefs[identifier] = nativeRef
    }

    override fun getNativeRef(identifier: String?): Long = nativeRefs[identifier] ?: 0L

    /** Wires this session in as the query handler of [messageRouter]. */
    private fun asRouter(messageRouter: CefMessageRouter) {
        router = messageRouter
        messageRouter.addHandler(this, false)
    }

    /** Serves this session's page for the magic URL; everything else passes through. */
    private inner class PageContextHandler(
        private val html: CompletableFuture<ByteArray>,
    ) : CefRequestContextHandlerAdapter() {
        override fun getResourceRequestHandler(
            browser: CefBrowser,
            frame: CefFrame,
            request: CefRequest,
            isNavigation: Boolean,
            isDownload: Boolean,
            requestInitiator: String?,
            disableDefaultHandling: BoolRef,
        ): CefResourceRequestHandler? =
            if (request.url.startsWith(POTOKEN_URL)) PageRequestHandler(html) else null
    }

    private inner class PageRequestHandler(
        private val html: CompletableFuture<ByteArray>,
    ) : CefResourceRequestHandlerAdapter() {
        override fun getResourceHandler(
            browser: CefBrowser,
            frame: CefFrame,
            request: CefRequest,
        ): CefResourceHandler? = PageResourceHandler(html)
    }

    /**
     * The page itself — a real origin (`https://www.youtube.com`) with a body
     * this session controls. Served fully from memory, so the browser never
     * touches the network for it, exactly like the Android WebView's
     * `blockNetworkLoads`.
     */
    private inner class PageResourceHandler(
        private val html: CompletableFuture<ByteArray>,
    ) : CefResourceHandlerAdapter() {
        private var position = 0

        override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
            position = 0
            callback.Continue()
            return true
        }

        override fun getResponseHeaders(response: CefResponse, length: IntRef, redirectUrl: StringRef) {
            val body = html.get()
            response.status = 200
            response.mimeType = "text/html"
            length.set(body.size)
        }

        override fun readResponse(
            dataOut: ByteArray,
            bytesToRead: Int,
            bytesRead: IntRef,
            callback: CefCallback,
        ): Boolean {
            val body = html.get()
            if (position >= body.size) return false
            val n = minOf(bytesToRead, body.size - position)
            System.arraycopy(body, position, dataOut, 0, n)
            bytesRead.set(n)
            position += n
            return true
        }
    }

    companion object {
        private const val TAG = "PoTokenCef"

        // YouTube's BotGuard service constants — the same pair the Android
        // original and every BgUtils port carries.
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"

        /** The URL this session's resource handler answers with its own page. */
        private const val POTOKEN_URL = "https://www.youtube.com/bitchord/potoken"

        // Init does network round-trips plus JS execution; a page that has not
        // finished after this long has a wedged renderer or a dead network.
        private const val INIT_TIMEOUT_MS = 45_000L

        /** A live browser mints a poToken in well under a second. */
        private const val GENERATE_TIMEOUT_MS = 15_000L

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Creates a session and boots BotGuard inside it. The browser work runs
         * on the AWT thread; this sequence waits on the message-router answers.
         * A session that has not finished after [INIT_TIMEOUT_MS] is closed and
         * its failure reported.
         */
        suspend fun new(): PoTokenCefSession {
            var session: PoTokenCefSession? = null
            try {
                return withTimeoutOrNull(INIT_TIMEOUT_MS) {
                    val fresh = PoTokenCefSession()
                    session = fresh
                    fresh.openBrowser()
                    fresh.scope.launch { fresh.obtainBotguard() }
                    fresh.initResult.await()
                } ?: run {
                    session?.let { runCatching { it.close() } }
                    throw PoTokenException("PoToken browser init timed out after ${INIT_TIMEOUT_MS}ms")
                }
            } catch (e: CancellationException) {
                session?.let { runCatching { it.close() } }
                throw e
            } catch (e: Throwable) {
                session?.let { runCatching { it.close() } }
                if (e is PoTokenException) throw e
                throw PoTokenException(e.message ?: "$e")
            }
        }

        /** The page with the bridge's auto-start appended, as served in memory. */
        private suspend fun pageHtml(): ByteArray {
            val html = Res.readBytes("files/po_token.html").decodeToString()
            // The last statement of the page hands control to this side — the
            // same one-line append the Android build makes before loading.
            val withStart = html.replaceFirst(
                "</script>",
                "\ntry { window.cefQuery({ request: JSON.stringify({ kind: 'ready' }), persistent: false, onSuccess: function () {}, onFailure: function () {} }); } catch (e) {}</script>",
            )
            return withStart.encodeToByteArray()
        }

        // ── Challenge parsing — the JavaScriptUtil port ─────────────────────

        /** The raw Create answer, rewritten into a JSON object the page can embed. */
        private fun parseChallengeData(rawChallengeData: String): String {
            val scrambled = Json.parseToJsonElement(rawChallengeData).jsonArray
            val challengeData = if (scrambled.size > 1 && scrambled[1].jsonPrimitive.isString) {
                Json.parseToJsonElement(descramble(scrambled[1].jsonPrimitive.content)).jsonArray
            } else {
                scrambled[0].jsonArray
            }
            val messageId = challengeData[0].jsonPrimitive.content
            val interpreterHash = challengeData[3].jsonPrimitive.content
            val program = challengeData[4].jsonPrimitive.content
            val globalName = challengeData[5].jsonPrimitive.content
            val clientExperimentsStateBlob = challengeData[7].jsonPrimitive.content

            val safeScript = challengeData[1]
                .takeIf { it !is JsonNull }
                ?.jsonArray
                ?.find { it.jsonPrimitive.isString }
            val trustedResourceUrl = challengeData[2]
                .takeIf { it !is JsonNull }
                ?.jsonArray
                ?.find { it.jsonPrimitive.isString }

            return json.encodeToString(
                JsonObject.serializer(),
                JsonObject(
                    mapOf(
                        "messageId" to JsonPrimitive(messageId),
                        "interpreterJavascript" to JsonObject(
                            mapOf(
                                "privateDoNotAccessOrElseSafeScriptWrappedValue" to (safeScript ?: JsonNull),
                                "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue" to (trustedResourceUrl ?: JsonNull),
                            ),
                        ),
                        "interpreterHash" to JsonPrimitive(interpreterHash),
                        "program" to JsonPrimitive(program),
                        "globalName" to JsonPrimitive(globalName),
                        "clientExperimentsStateBlob" to JsonPrimitive(clientExperimentsStateBlob),
                    ),
                ),
            )
        }

        /** The raw GenerateIT answer as a JS `Uint8Array` literal and a lifetime. */
        private fun parseIntegrityTokenData(raw: String): Pair<String, Long> {
            val data = Json.parseToJsonElement(raw).jsonArray
            return base64ToU8(data[0].jsonPrimitive.content) to data[1].jsonPrimitive.long
        }

        private fun stringToU8(identifier: String): String =
            "new Uint8Array([" + identifier.encodeToByteArray()
                .joinToString(separator = ",") { it.toUByte().toString() } + "])"

        /** poToken bytes, comma-joined by JavaScript's Uint8Array::toString, as base64url. */
        private fun u8ToBase64(poToken: String): String {
            val bytes = poToken.split(",").map { it.toUByte().toByte() }.toByteArray()
            return Base64.getEncoder().encodeToString(bytes)
                .replace("+", "-")
                .replace("/", "_")
        }

        private fun descramble(scrambledChallenge: String): String =
            base64ToBytes(scrambledChallenge)
                .map { (it + 97).toByte() }
                .toByteArray()
                .decodeToString()

        private fun base64ToU8(base64: String): String =
            "new Uint8Array([" + base64ToBytes(base64)
                .joinToString(separator = ",") { it.toUByte().toString() } + "])"

        /** YouTube's base64 flavour: `-`, `_` and trailing `.` for padding. */
        private fun base64ToBytes(base64: String): ByteArray {
            val normalized = base64
                .replace('-', '+')
                .replace('_', '/')
                .replace('.', '=')
            return runCatching { Base64.getDecoder().decode(normalized) }
                .getOrElse { throw PoTokenException("cannot base64 decode") }
        }

        private fun JsonObject.kind(): String? = (this["kind"] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.data(): String = (this["data"] as? JsonPrimitive)?.contentOrNull ?: ""

        private fun JsonObject.key(): String? = (this["key"] as? JsonPrimitive)?.contentOrNull
    }
}
