package com.music.bitchord.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.music.bitchord.data.AppFiles
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.checking
import com.music.bitchord.desktop.resources.choose_profile
import com.music.bitchord.desktop.resources.close
import com.music.bitchord.desktop.resources.profile_unavailable
import com.music.bitchord.desktop.resources.sign_in_youtube_music
import com.music.bitchord.desktop.resources.switch_profile_hint
import com.music.bitchord.desktop.resources.use_this_profile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.EnumProgress
import me.friwi.jcefmaven.IProgressHandler
import org.cef.CefApp
import org.cef.CefClient
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefDisplayHandler
import org.cef.handler.CefMessageRouterHandler
import org.jetbrains.compose.resources.stringResource

private const val MUSIC_ORIGIN = "https://music.youtube.com"

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin" +
        "?ltmpl=music&service=youtube&passive=true" +
    "&continue=https%3A%2F%2Fmusic.youtube.com%2F"

/**
 * How long a capture waits for the page to answer the ytcfg probe. The Android
 * original has no bound — `evaluateJavascript` answers or the UI shows
 * "checking" forever on a wedged page — and a modal dialog that can only be
 * closed by hand is a worse place to hang than a full screen was.
 */
private const val PROBE_TIMEOUT_MS = 10_000L

private const val TAG = "BitChord"

/** CEF start-up: binaries downloading/initialising, ready, or failed. */
private enum class Stage { Starting, Ready, Failed }

/**
 * In-app Google sign-in for YouTube Music, and the way to change which channel
 * it listens as — the desktop's shape of the Android full-screen webSession.
 *
 * [WebSessionMode.SIGN_IN] loads the standard Google web login with
 * `continue=music.youtube.com`. The user authenticates directly against
 * accounts.google.com (2FA, passkeys etc. all work — it's the real page). When
 * Google redirects back to music.youtube.com the listener confirms the profile
 * shown by the live page. This deliberately leaves a multiple-channel account
 * enough time to choose its Personal or Brand identity before anything is
 * saved. The browser's local Google cookies are cleared on the way in without
 * visiting Google's logout endpoint, so adding an account cannot invalidate a
 * previously saved session.
 *
 * [WebSessionMode.SWITCH_CHANNEL] keeps those cookies and opens YouTube Music
 * itself, so the listener can use the avatar menu's own Accounts list — the one
 * screen that authoritatively knows which channels exist and which is which.
 * Nothing is taken automatically there: the session is read when they say so,
 * by raising [captureRequest].
 *
 * Either way what is read is the page's own `ytcfg`, not a guess made later
 * from a server-side fetch. The credential itself never passes through app code.
 *
 * The browser is JCEF — a real Chromium — rather than any Compose-rendered web
 * view: Google's login converses best with accounts.google.com when the client
 * is Chromium, and the cookie jar this flow depends on is the CEF manager's
 * (see [BrowserSession]). It lives in a modal [Dialog] of its own because the
 * main window's chrome is a transparent Skia surface, and a heavyweight native
 * child window layered over it would tear — an independent child window
 * sidesteps the airspace problem entirely.
 *
 * @param initialCookie cookie snapshot to install before opening an existing
 *   account's channel picker (SWITCH_CHANNEL only).
 * @param captureRequest a host-raised request to take the session from the
 *   page as it stands. Ignored at its initial value, so arriving in the dialog
 *   doesn't capture anything; the dialog's own〔use this profile〕button is the
 *   usual way a capture is asked for.
 * @param onPageReady told when a signed-in YouTube Music page is available for
 *   confirmation — reaching the origin only enables the button; a
 *   multi-channel login can still be waiting for the listener to choose an
 *   identity on the page itself.
 * @param onCaptureUnavailable told when a capture was asked for and there was
 *   no session to take.
 * @param onCaptured the confirmed session; the caller validates it before
 *   anything is saved.
 * @param onDismiss close — a dismiss is never a capture.
 */
@Composable
fun YtMusicLoginWindow(
    mode: WebSessionMode,
    initialCookie: String?,
    captureRequest: Int,
    onPageReady: (Boolean) -> Unit,
    onCaptureUnavailable: () -> Unit,
    onCaptured: (CapturedSession) -> Unit,
    onDismiss: () -> Unit,
) {
    var stage by remember { mutableStateOf(Stage.Starting) }
    var pageReady by remember { mutableStateOf(false) }
    var captureFailed by remember { mutableStateOf(false) }
    var confirmingProfile by remember { mutableStateOf(false) }
    var browser by remember { mutableStateOf<CefBrowser?>(null) }
    val holder = remember { BrowserHolder() }
    val scope = rememberCoroutineScope()
    val currentOnPageReady by rememberUpdatedState(onPageReady)
    val currentOnUnavailable by rememberUpdatedState(onCaptureUnavailable)
    val currentOnCaptured by rememberUpdatedState(onCaptured)

    /** The〔use this profile〕act, from the button or from a host request. */
    fun requestCapture() {
        val view = browser
        if (view == null) {
            currentOnUnavailable()
            return
        }
        captureFailed = false
        confirmingProfile = true
        scope.launch {
            captureFrom(
                view,
                holder,
                onCaptured = { session ->
                    confirmingProfile = false
                    currentOnCaptured(session)
                },
                onUnavailable = {
                    confirmingProfile = false
                    captureFailed = true
                    currentOnUnavailable()
                },
            )
        }
    }

    // One browser per opening of the dialog. CefApp itself survives — the
    // first open pays the natives download, later opens only make a client.
    // Registered while up so a quit with the dialog open can close the
    // browser before the app's own shutdown walks under it.
    DisposableEffect(Unit) {
        CefRuntime.register(holder)
        onDispose {
            CefRuntime.unregister(holder)
            holder.close()
        }
    }

    LaunchedEffect(Unit) {
        val app = CefRuntime.app()
        if (app == null) {
            stage = Stage.Failed
            return@LaunchedEffect
        }
        // Clearing the local jar is enough to show a fresh Google login.
        // Never visit accounts.google.com/Logout here: that invalidates a
        // previously saved account on Google's server, so adding account B
        // silently breaks account A.
        withContext(Dispatchers.IO) {
            if (mode == WebSessionMode.SIGN_IN) BrowserSession.clearGoogleCookies()
            else initialCookie?.let(BrowserSession::installGoogleCookies)
        }
        // The client and its browser belong on the AWT thread — this
        // coroutine's own dispatcher is the Swing event loop.
        val client = app.createClient().also {
            it.addMessageRouter(CefMessageRouter.create().also(holder::asHandler))
            it.addDisplayHandler(holder)
        }
        holder.client = client
        holder.onAddress = { url ->
            // Reaching the Music origin only enables confirmation. A
            // multi-channel login can still be waiting for the listener to
            // choose an identity on this very page. Capturing automatically
            // here is the race that used to create a fake "Personal" profile
            // and close too soon.
            pageReady = url.startsWith(MUSIC_ORIGIN)
            currentOnPageReady(pageReady)
        }
        browser = client.createBrowser(
            if (mode == WebSessionMode.SIGN_IN) LOGIN_URL else "$MUSIC_ORIGIN/",
            false,
            false,
        )
        holder.browser = browser
        stage = Stage.Ready
    }

    // A host-raised capture request — the counter semantics the Android host
    // owns: every raise beyond the initial value is a new request.
    LaunchedEffect(captureRequest) {
        if (captureRequest == 0) return@LaunchedEffect
        requestCapture()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(max = 880.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.height(640.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(Res.string.close),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = when (mode) {
                                WebSessionMode.SIGN_IN ->
                                    stringResource(Res.string.sign_in_youtube_music)
                                WebSessionMode.SWITCH_CHANNEL ->
                                    stringResource(Res.string.choose_profile)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (pageReady) {
                            Text(
                                text = when {
                                    confirmingProfile -> stringResource(Res.string.checking)
                                    captureFailed ->
                                        stringResource(Res.string.profile_unavailable)
                                    else -> stringResource(Res.string.switch_profile_hint)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    }
                    if (pageReady) {
                        TextButton(
                            onClick = ::requestCapture,
                            enabled = !confirmingProfile,
                        ) {
                            Text(
                                if (confirmingProfile) {
                                    stringResource(Res.string.checking)
                                } else {
                                    stringResource(Res.string.use_this_profile)
                                },
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (stage) {
                        Stage.Starting -> CircularProgressIndicator(Modifier.size(28.dp))
                        Stage.Failed ->
                            // No dedicated string of its own — the failure is
                            // technical, rare, and the raw cause says more
                            // than a one-liner this build would invent.
                            Text(
                                text = CefRuntime.lastError ?: "CEF unavailable",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp),
                            )
                        Stage.Ready -> {
                            val view = browser
                            if (view != null) {
                                SwingPanel(
                                    factory = { view.uiComponent },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Takes the session from [browser], if it is holding one.
 *
 * The cookie comes from the CEF jar — HttpOnly included — and the identity
 * from the live page's `ytcfg`, fetched over the message router whose answer
 * is the stringified config object. A page without a signed-in identity (mid
 * login, an error page, a redirect that hasn't landed) is a legal "not yet",
 * not an error: the dialog stays open rather than saving something that
 * cannot sign a request. See [AuthStore.hasApiSid].
 */
private suspend fun captureFrom(
    browser: CefBrowser,
    holder: BrowserHolder,
    onCaptured: (CapturedSession) -> Unit,
    onUnavailable: () -> Unit,
) {
    val cookies = withContext(Dispatchers.IO) { BrowserSession.readMusicCookies() }
    if (cookies == null || !AuthStore.hasApiSid(cookies)) {
        onUnavailable()
        return
    }
    val answer = holder.armProbe()
    browser.executeJavaScript(YTCFG_PROBE_QUERY, MUSIC_ORIGIN, 0)
    val raw = withTimeoutOrNull(PROBE_TIMEOUT_MS) { answer.await() }
    val config = raw.parseConfig()
    val loggedIn = config?.get("loggedIn").let {
        it is JsonPrimitive && it.content == "true"
    }
    if (config == null || !loggedIn) {
        Log.w(TAG, "confirmation requested before the page exposed a signed-in identity")
        onUnavailable()
        return
    }
    val pageId = config.string("pageId")
    onCaptured(
        CapturedSession(
            cookie = cookies,
            // A delegated identity is the most specific answer the live
            // page can give. Otherwise normalise its DATASYNC_ID.
            dataSyncId = pageId ?: normalizeDataSyncId(config.string("dataSyncId")),
            pageId = pageId,
            authUser = config.string("authUser"),
            visitorData = config.string("visitorData"),
            clientVersion = config.string("clientVersion"),
            loggedIn = true,
        ),
    )
}

/**
 * The probe's transport. The upstream probe returns an object for the WebView
 * to serialise; `executeJavaScript` has no result callback on this platform,
 * so the same probe body delivers its answer through the message router
 * instead — `JSON.stringify`ed first, which is the same value the WebView
 * would have serialised, just stringified on this side of the bridge.
 */
private const val YTCFG_PROBE_QUERY = """
(function () {
  var deliver = function (result) {
    try {
      window.cefQuery({
        request: JSON.stringify(result === undefined ? null : result),
        persistent: false,
        onSuccess: function () {},
        onFailure: function () {}
      });
    } catch (e) {}
  };
  try {
    if (!window.ytcfg || !window.ytcfg.get) { deliver(null); return; }
    var get = function (key) {
      var value = window.ytcfg.get(key);
      return (value === undefined || value === null || value === '') ? null : String(value);
    };
    deliver({
      loggedIn: String(!!window.ytcfg.get('LOGGED_IN')),
      pageId: get('DELEGATED_SESSION_ID'),
      dataSyncId: get('DATASYNC_ID'),
      authUser: get('SESSION_INDEX'),
      visitorData: get('VISITOR_DATA'),
      clientVersion: get('INNERTUBE_CLIENT_VERSION')
    });
  } catch (e) {
    deliver(null);
  }
})()
"""

private val json = Json { ignoreUnknownKeys = true }

/** The probe's result, or null for anything that isn't the object it promises. */
private fun String?.parseConfig(): JsonObject? =
    this?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/**
 * The pieces of one open dialog that live across recompositions but are not
 * composable state: the client and browser to close, and the channel the
 * probe's answer arrives on.
 *
 * The message-router handler and the display handler are the same object
 * because their lifetimes are: both die when the dialog's browser does, and
 * neither has anything else to do.
 */
internal class BrowserHolder : CefMessageRouterHandler, CefDisplayHandler {
    var client: CefClient? = null
    var browser: CefBrowser? = null
    private var router: CefMessageRouter? = null

    /** Where the address bar is — the only display event the dialog reads. */
    @Volatile var onAddress: ((String) -> Unit)? = null

    @Volatile private var probe: CompletableDeferred<String>? = null

    /** Arms a fresh answer slot; the previous one, if any, is abandoned. */
    fun armProbe(): CompletableDeferred<String> =
        CompletableDeferred<String>().also { probe = it }

    /** Wires this holder in as both the query handler and the URL watcher. */
    fun asHandler(router: CefMessageRouter) {
        this.router = router
        router.addHandler(this, false)
    }

    override fun onQuery(
        browser: CefBrowser,
        frame: CefFrame,
        queryId: Long,
        request: String,
        persisted: Boolean,
        callback: CefQueryCallback,
    ): Boolean {
        // Everything the page says through this router is a probe answer —
        // YouTube Music's own pages never touch `window.cefQuery`, and a
        // request arriving without an armed probe is one the dialog already
        // stopped waiting for.
        probe?.complete(request)
        callback.success("")
        return true
    }

    override fun onQueryCanceled(browser: CefBrowser, frame: CefFrame, queryId: Long) {
        probe?.cancel()
        probe = null
    }

    override fun onAddressChange(browser: CefBrowser, frame: CefFrame, url: String) {
        // Per-frame; only the top frame's address is the page's address.
        if (frame.isMain) {
            Log.d(TAG, "login browser at $url")
            onAddress?.invoke(url)
        }
    }

    override fun onTitleChange(browser: CefBrowser, title: String) {}
    override fun onFullscreenModeChange(browser: CefBrowser, fullscreen: Boolean) {}
    override fun onTooltip(browser: CefBrowser, text: String): Boolean = false
    override fun onStatusMessage(browser: CefBrowser, value: String) {}
    override fun onConsoleMessage(
        browser: CefBrowser,
        severity: CefSettings.LogSeverity,
        message: String,
        source: String,
        line: Int,
    ): Boolean = false

    override fun onCursorChange(browser: CefBrowser, cursorType: Int): Boolean = false

    // CefNative, which the query-handler interface extends: the native side
    // hangs a pointer off the handler it drives, stored and returned exactly
    // as CEF's own CefClient does. Nothing on the Java half ever reads it.
    private val nativeRefs = HashMap<String, Long>()

    override fun setNativeRef(identifier: String?, nativeRef: Long) {
        if (identifier != null && nativeRef != 0L) nativeRefs[identifier] = nativeRef
    }

    override fun getNativeRef(identifier: String?): Long = nativeRefs[identifier] ?: 0L

    /** Closes this dialog's browser; CefApp stays alive for the next open. */
    fun close() {
        onAddress = null
        runCatching { browser?.close(true) }
        runCatching { router?.dispose() }
        runCatching { client?.dispose() }
        browser = null
        router = null
        client = null
    }
}

/**
 * The process-wide CefApp, started the first time a login window opens and
 * kept for every open after that.
 *
 * Initialisation is blocking — on a cold install it downloads and unpacks
 * ~100MB of Chromium natives first — so it runs on IO and the dialog shows a
 * spinner until it answers. Everything is wrapped: a machine that cannot
 * download, unpack or start CEF gets a login window that says so and an app
 * that is otherwise untouched.
 *
 * Windowed rendering (`windowless_rendering_enabled = false`) is the whole
 * reason this substrate was chosen over the cheaper offscreen ones: a
 * windowed browser has real OS keyboard and IME handling, which is what
 * typing a Google password with a Chinese input method needs. The cookie jar
 * lives beside the binaries in the same `cef/` folder — its persistence is
 * what lets a second sign-in skip the password.
 */
internal object CefRuntime {
    private val mutex = Mutex()

    @Volatile private var app: CefApp? = null

    /**
     * The dialog sessions still open, in creation order. Shutdown closes them
     * through their own [BrowserHolder.close] — the browser's cookie store
     * flushes on that path — before the app itself is asked to shut down:
     * `CefApp`'s native shutdown walks the browser tree and faults inside
     * jcef.dll when a browser is still alive under it, which is exactly the
     * state quitting with the login window open used to produce.
     */
    private val holders = mutableListOf<BrowserHolder>()

    /** Why the last start-up failed, for the dialog's status line. */
    @Volatile var lastError: String? = null
        private set

    internal fun register(holder: BrowserHolder) {
        synchronized(holders) { holders += holder }
    }

    internal fun unregister(holder: BrowserHolder) {
        synchronized(holders) { holders -= holder }
    }

    suspend fun app(): CefApp? {
        app?.let { return it }
        return mutex.withLock {
            app ?: run {
                lastError = null
                val built = withContext(Dispatchers.IO) { start() }
                built.also { app = it }
            }
        }
    }

    private fun start(): CefApp? = runCatching {
        val builder = CefAppBuilder().apply {
            setInstallDir(AppFiles.dir("cef"))
            setProgressHandler(IProgressHandler { stage, value ->
                if (stage == EnumProgress.DOWNLOADING || stage == EnumProgress.EXTRACTING) {
                    Log.d(TAG, "CEF $stage ${"%.0f".format(value * 100)}%")
                } else {
                    Log.d(TAG, "CEF $stage")
                }
            })
            val settings = getCefSettings()
            settings.windowless_rendering_enabled = false
            settings.cache_path = AppFiles.dir("cef").absolutePath
            // Software rendering, on purpose. The login browser is forms and
            // text — it has no use for the GPU — and software is the rendering
            // path that behaves identically everywhere a login might be
            // attempted: RDP sessions, virtual machines, machines with a
            // broken or absent GPU stack, where hardware compositing is the
            // one thing that can turn the page black. The one thing it does
            // NOT buy is scriptability: Chromium's surface is DirectComposition
            // either way, so PrintWindow — the screenshot pipeline's only way
            // to see a window — reads a white rectangle where the page is
            // actually live, and the visual check of this dialog's browser is
            // a human's, like the SMTC overlay before it.
            addJcefArgs("--disable-gpu", "--disable-gpu-compositing")
        }
        builder.build()
    }.getOrElse {
        Log.w(TAG, "CEF start-up failed", it)
        lastError = it.message ?: it::class.simpleName
        null
    }

    /**
     * Best effort, at quit, on the EDT the CEF handlers live on. Every open
     * dialog's browser goes first — see [holders] for why the order is not
     * negotiable — then the app itself. An app that was never started has
     * nothing to shut down, and a failure here must not block the window's
     * own exit path: the JVM's halt reaps whatever CEF thread survived.
     */
    fun shutdown() {
        val open = synchronized(holders) { holders.toList() }
        open.forEach { runCatching { it.close() } }
        runCatching { app?.dispose() }
        app = null
    }
}
