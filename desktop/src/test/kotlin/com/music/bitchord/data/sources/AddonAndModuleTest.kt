package com.music.bitchord.data.sources

import com.music.bitchord.data.sources.addon.DetectedFormat
import com.music.bitchord.data.sources.addon.SourceFormats
import com.music.bitchord.data.sources.module.ModuleSearchResponse
import com.music.bitchord.data.sources.module.QuickJsExecutor
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The addon protocol and the QuickJS module machinery, against a localhost
 * HTTP server — the sources family's wire behaviour, asked and answered on
 * this machine with no outside network.
 */
class AddonAndModuleTest {

    private lateinit var server: HttpServer
    private var baseUrl = ""

    /** Handlers the individual tests install. */
    private val routes = mutableMapOf<String, Pair<Int, String>>()

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val (code, body) = routes[path]
                ?: (404 to """{"error":"no such route"}""")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, if (code == 404 && body.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
        QuickJsExecutor.unloadAll()
        routes.clear()
    }

    // ── SourceFormats: what is at this URL? ─────────────────────────────

    @Test
    fun `a manifest json is an addon`() = runTest {
        routes["/manifest.json"] = 200 to """
            {"id": "ref", "name": "Reference Addon", "version": "1.0", "resources": ["search", "stream"]}
        """.trimIndent()
        val detected = SourceFormats.identify("$baseUrl/manifest.json").getOrThrow()
        assertIs<DetectedFormat.Addon>(detected)
        assertEquals("Reference Addon", detected.manifest.displayName)
    }

    @Test
    fun `a body with category keys is a module index`() = runTest {
        routes["/index.json"] = 200 to """
            {"category:music": [{"id": "m1", "name": "One", "download": "one.js"}]}
        """.trimIndent()
        val detected = SourceFormats.identify("$baseUrl/index.json").getOrThrow()
        assertIs<DetectedFormat.ModuleIndex>(detected)
        assertEquals("$baseUrl/index.json", detected.url)
    }

    @Test
    fun `anything else is refused with a reason`() = runTest {
        routes["/manifest.json"] = 200 to """{"surprise": 1}"""
        val detected = SourceFormats.identify("$baseUrl/manifest.json").getOrThrow()
        assertIs<DetectedFormat.Unsupported>(detected)
        assertTrue(detected.reason.contains("surprise"))
    }

    // ── AddonSource: health, search, stream ─────────────────────────────

    @Test
    fun `health is Ok when the manifest answers`() = runTest {
        routes["/manifest.json"] = 200 to """
            {"id": "ref", "name": "Reference Addon", "version": "1.0", "resources": ["search", "stream"]}
        """.trimIndent()
        val source = AddonSource(SourceConfig(kind = SourceKind.ADDON, baseUrl = baseUrl))
        val health = source.health()
        assertIs<SourceHealth.Ok>(health)
        assertEquals("Reference Addon v1.0", health.detail)
    }

    @Test
    fun `a manifest that fails but a search that answers is still Ok`() = runTest {
        // No manifest route installed — it 404s.
        routes["/search"] = 200 to """{"tracks": [{"id": "1", "title": "Song", "artist": "Artist"}]}"""
        val source = AddonSource(SourceConfig(kind = SourceKind.ADDON, baseUrl = baseUrl))
        assertIs<SourceHealth.Ok>(source.health())
    }

    @Test
    fun `search maps rows and wraps ids in the track key`() = runTest {
        routes["/manifest.json"] = 200 to """
            {"id": "ref", "name": "Reference", "version": "1.0", "resources": ["search", "stream"]}
        """.trimIndent()
        routes["/search"] = 200 to """
            {"tracks": [
                {"id": "t1", "title": "First", "artist": "A", "duration": 200.0, "format": "flac"},
                {"id": "t2", "title": "Second", "artist": "B"},
                {"id": "", "title": "Blank id is dropped", "artist": "C"}
            ]}
        """.trimIndent()
        val source = AddonSource(SourceConfig(kind = SourceKind.ADDON, baseUrl = baseUrl))
        val rows = source.search("First", 10)
        assertEquals(2, rows.size)
        assertEquals(SourceRegistry.trackKey(source.configId, "t1"), rows[0].videoId)
        assertEquals("First", rows[0].title)
        // A row that says flac advertises the lossless tier.
        assertEquals(ModuleSource.LOSSLESS, rows[0].sourceQuality)
    }

    @Test
    fun `stream answers with the endpoint url and falls back to the row's own streamURL on a miss`() = runTest {
        routes["/manifest.json"] = 200 to """
            {"id": "ref", "name": "Reference", "version": "1.0", "resources": ["search", "stream"]}
        """.trimIndent()
        routes["/search"] = 200 to """
            {"tracks": [{"id": "t1", "title": "First", "artist": "A", "duration": 200.0,
                         "streamURL": "$baseUrl/direct/t1.flac"}]}
        """.trimIndent()
        val source = AddonSource(SourceConfig(kind = SourceKind.ADDON, baseUrl = baseUrl))
        source.search("First", 10)

        // No /stream route → 404 → a miss, not a fault; the row's own URL answers.
        val stream = source.stream("t1", StreamRequest.Best)
        assertNotNull(stream)
        assertEquals("$baseUrl/direct/t1.flac", stream.url)
    }

    @Test
    fun `an addon whose stream endpoint answers carries the url and the row's duration`() = runTest {
        routes["/manifest.json"] = 200 to """
            {"id": "ref", "name": "Reference", "version": "1.0", "resources": ["search", "stream"]}
        """.trimIndent()
        routes["/search"] = 200 to """
            {"tracks": [{"id": "t1", "title": "First", "artist": "A", "duration": 200.0}]}
        """.trimIndent()
        routes["/stream/t1"] = 200 to """{"url": "$baseUrl/audio/t1.flac"}"""
        val source = AddonSource(SourceConfig(kind = SourceKind.ADDON, baseUrl = baseUrl))
        source.search("First", 10)
        val stream = source.stream("t1", StreamRequest.Lossless)
        assertNotNull(stream)
        assertEquals("$baseUrl/audio/t1.flac", stream.url)
        assertEquals(200, stream.durationSec)
        // flac by extension: lossless.
        assertTrue(stream.format.isLossless == true)
    }

    @Test
    fun `a request to a server that is not there is Unreachable, not a crash`() = runTest {
        val dead = AddonSource(SourceConfig(kind = SourceKind.ADDON, baseUrl = "http://127.0.0.1:1"))
        val health = dead.health()
        assertIs<SourceHealth.Unreachable>(health)
    }

    @Test
    fun `normalizeBase folds the two spellings of one server into one address`() {
        assertEquals(
            "https://a.example",
            com.music.bitchord.data.sources.addon.AddonClient.normalizeBase("https://a.example/manifest.json/"),
        )
        // Tokens in the path are case-sensitive credentials.
        assertEquals(
            "https://a.example/Token",
            com.music.bitchord.data.sources.addon.AddonClient.normalizeBase("https://a.example/Token"),
        )
    }

    // ── QuickJS: the module machinery end to end ────────────────────────

    private val moduleScript = """
        var __base = '%BASE%';
        async function searchTracks(query, limit, context) {
            var res = await fetch(__base + '/module/search?q=' + encodeURIComponent(query));
            if (!res.ok) throw new Error('search failed: ' + res.status);
            var body = await res.json();
            return body;
        }
        async function getTrackStreamUrl(trackId, quality, context) {
            var res = await fetch(__base + '/module/stream?id=' + encodeURIComponent(trackId));
            var body = await res.json();
            return body;
        }
        module.exports = { searchTracks: searchTracks, getTrackStreamUrl: getTrackStreamUrl };
    """.trimIndent().replace("%BASE%", "{BASE_URL}")

    @Test
    fun `a module's exports answer through the fetch binding`() = runTest {
        val script = moduleScript.replace("{BASE_URL}", baseUrl)
        routes["/module/search"] = 200 to """
            {"tracks": [{"id": "mt1", "title": "Module Track", "artist": "Module Artist",
                         "duration": 180, "audioQuality": "FLAC 16-bit / 44.1kHz"}]}
        """.trimIndent()
        routes["/module/stream"] = 200 to """
            {"streamUrl": "$baseUrl/module/mt1.flac",
             "track": {"id": "mt1", "mimeType": "audio/flac", "bitDepth": 16, "sampleRate": 44100}}
        """.trimIndent()

        val loaded = QuickJsExecutor.loadModule("test-module", script, baseUrl)
        assertTrue(loaded.isSuccess, "loadModule failed: ${loaded.exceptionOrNull()?.message}")
        assertTrue(QuickJsExecutor.isLoaded("test-module"))

        val searchRaw = QuickJsExecutor.callExport(
            "test-module",
            "searchTracks",
            listOf("\"hello\"", "10", "{}"),
        )
        assertTrue(searchRaw.isSuccess, "searchTracks failed: ${searchRaw.exceptionOrNull()?.message}")
        val searchJson = searchRaw.getOrThrow()
        val parsed = Json { ignoreUnknownKeys = true }
            .decodeFromString(ModuleSearchResponse.serializer(), searchJson)
        assertEquals("mt1", parsed.tracks.single().id)
        assertEquals("Module Track", parsed.tracks.single().title)

        val streamRaw = QuickJsExecutor.callExport(
            "test-module",
            "getTrackStreamUrl",
            listOf("\"mt1\"", "\"LOSSLESS\"", "{}"),
        )
        assertTrue(streamRaw.isSuccess, "getTrackStreamUrl failed: ${streamRaw.exceptionOrNull()?.message}")
        val streamJson = streamRaw.getOrThrow()
        assertTrue(streamJson.contains("mt1.flac"), "stream response should carry the url, got: $streamJson")
    }

    @Test
    fun `a module that throws reports the error as a json body, not a crash`() = runTest {
        val throwing = """
            async function searchTracks(query, limit, context) {
                throw new Error('backend exploded');
            }
            module.exports = { searchTracks: searchTracks };
        """.trimIndent()
        assertTrue(QuickJsExecutor.loadModule("throwing-module", throwing, "").isSuccess)
        val raw = QuickJsExecutor.callExport("throwing-module", "searchTracks", listOf("\"q\"", "5", "{}"))
        assertTrue(raw.isSuccess)
        assertTrue(raw.getOrThrow().contains("backend exploded"))
    }

    @Test
    fun `a script whose exports are missing loads, but answers every call with not-a-function`() = runTest {
        // The IIFE swallows script-level silence into an empty exports object —
        // a load-time failure is reserved for a script that cannot evaluate at
        // all. The miss surfaces when the call comes, which is where the
        // resolver's attempt() turns it into "this source didn't work".
        assertTrue(QuickJsExecutor.loadModule("empty-module", "var x = 1;", "").isSuccess)
        val raw = QuickJsExecutor.callExport("empty-module", "searchTracks", listOf("\"q\"", "5", "{}"))
        assertTrue(raw.isFailure)
    }

    @Test
    fun `the template-literal export form preprocesses into runnable code`() = runTest {
        // Built the honest way: the Spine form is `export const x = ` + the code inside backticks.
        val inner = """
            async function searchTracks(query, limit, context) {
                return { tracks: [{ id: 'spine-1', title: 'Spine Track', artist: 'A', duration: 100 }] };
            }
            module.exports = { searchTracks: searchTracks };
        """.trimIndent()
        val template = "export const source = `$inner`;"
        assertTrue(QuickJsExecutor.loadModule("spine-module", template, "").isSuccess)
        val raw = QuickJsExecutor.callExport("spine-module", "searchTracks", listOf("\"q\"", "5", "{}"))
        assertTrue(raw.isSuccess)
        assertTrue(raw.getOrThrow().contains("spine-1"))
    }
}
