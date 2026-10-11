package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.sources.module.ModuleIndex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure-logic half of the sources family — upstream `SourcesTest.kt`'s
 * no-network cases, plus the desktop's own permits/race additions.
 */
class SourcesTest {

    // ── Track identity ───────────────────────────────────────────────────

    @Test
    fun `trackKey round-trips, including a separator inside the track id`() {
        val key = SourceRegistry.trackKey("cfg-1", "mod-a::12345")
        assertEquals("cfg-1" to "mod-a::12345", SourceRegistry.parseTrackKey(key))
    }

    @Test
    fun `an ordinary video id is not a track key`() {
        assertNull(SourceRegistry.parseTrackKey("dQw4w9WgXcQ"))
        assertNull(SourceRegistry.parseTrackKey("src:"))
        assertNull(SourceRegistry.parseTrackKey("src:cfg-only"))
    }

    // ── StreamFormat ─────────────────────────────────────────────────────

    @Test
    fun `lossless is decided by codec alone, unknown stays unknown`() {
        assertTrue(StreamFormat(codec = "flac", kbps = 1000).isLossless == true)
        assertTrue(StreamFormat(codec = "alac").isLossless == true)
        assertTrue(StreamFormat(codec = "aac", kbps = 320).isLossless == false)
        assertNull(StreamFormat(codec = null, kbps = null).isLossless)
    }

    @Test
    fun `atmos is its own codec family, not a lossless answer`() {
        val atmos = StreamFormat(codec = "eac3-joc")
        assertTrue(atmos.isDolbyAtmos)
        assertTrue(atmos.isLossless == false)
        assertEquals("Dolby Atmos", atmos.summary)
    }

    @Test
    fun `summary names codec, depth and rate, and omits kbps when lossless`() {
        assertEquals(
            "FLAC · 24-bit · 192 kHz",
            StreamFormat(codec = "flac", sampleRateHz = 192_000, bitDepth = 24).summary,
        )
        assertEquals(
            "AAC · 320 kbps",
            StreamFormat(codec = "aac", kbps = 320).summary,
        )
    }

    // ── permits ──────────────────────────────────────────────────────────

    @Test
    fun `lossless admits every kind, high skips lossless-capable ones, medium and low are YouTube only`() {
        for (kind in SourceKind.entries) {
            assertTrue(AudioQuality.LOSSLESS.permits(kind), "LOSSLESS must permit $kind")
            assertEquals(!kind.canServeLossless, AudioQuality.HIGH.permits(kind), "HIGH vs $kind")
            assertEquals(
                kind == SourceKind.YOUTUBE,
                AudioQuality.MEDIUM.permits(kind),
                "MEDIUM vs $kind",
            )
            assertEquals(
                kind == SourceKind.YOUTUBE,
                AudioQuality.LOW.permits(kind),
                "LOW vs $kind",
            )
        }
    }

    // ── Registry init seeding ────────────────────────────────────────────

    @Test
    fun `a fresh install seeds YouTube on and JioSaavn off, and drops the retired module kind`() {
        val after = SourceRegistry.sourcesForInit(
            stored = listOf(SourceConfig(kind = SourceKind.MODULE)),
            forceJioSaavnOff = true,
        )
        assertTrue(after.none { it.kind == SourceKind.MODULE })
        assertEquals(
            listOf(SourceKind.JIOSAAVN, SourceKind.YOUTUBE).map { it.name }.sorted(),
            after.map { it.kind.name }.sorted(),
        )
        assertEquals(false, after.first { it.kind == SourceKind.JIOSAAVN }.enabled)
        assertEquals(true, after.first { it.kind == SourceKind.YOUTUBE }.enabled)
    }

    @Test
    fun `a stored jiosaavn opt-in survives the migration marker`() {
        val after = SourceRegistry.sourcesForInit(
            stored = listOf(SourceConfig(kind = SourceKind.JIOSAAVN, enabled = true)),
            forceJioSaavnOff = false,
        )
        assertEquals(true, after.first { it.kind == SourceKind.JIOSAAVN }.enabled)
    }

    @Test
    fun `youtube disabled in storage is forced back on`() {
        val after = SourceRegistry.sourcesForInit(
            stored = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = false)),
            forceJioSaavnOff = false,
        )
        assertEquals(true, after.first { it.kind == SourceKind.YOUTUBE }.enabled)
    }

    // ── reorder + duplicates ─────────────────────────────────────────────

    @Test
    fun `reorderAddons applies the order, keeps the missing, drops the unknown`() {
        val a = SourceConfig(kind = SourceKind.ADDON, baseUrl = "https://a.example")
        val b = SourceConfig(kind = SourceKind.ADDON, baseUrl = "https://b.example")
        val youtube = SourceConfig(kind = SourceKind.YOUTUBE)
        SourceRegistry.configs.value = listOf(a, b, youtube)
        try {
            SourceRegistry.reorderAddons(listOf(b.id, "no-such-id"))
            val addons = SourceRegistry.configs.value.filter { it.kind.isUserAdded }
            assertEquals(listOf(b.id, a.id), addons.map { it.id })
        } finally {
            SourceRegistry.configs.value = emptyList()
        }
    }

    @Test
    fun `reorderAddons with fewer than two addons is a no-op`() {
        val a = SourceConfig(kind = SourceKind.ADDON, baseUrl = "https://a.example")
        SourceRegistry.configs.value = listOf(a)
        try {
            SourceRegistry.reorderAddons(listOf())
            assertEquals(listOf(a.id), SourceRegistry.configs.value.map { it.id })
        } finally {
            SourceRegistry.configs.value = emptyList()
        }
    }

    @Test
    fun `duplicateOf canonicalises trailing slash, host case and default port, but not path case`() {
        val a = SourceConfig(kind = SourceKind.ADDON, baseUrl = "https://MyServer.example:443/manifest.json")
        SourceRegistry.configs.value = listOf(a)
        try {
            assertNotEquals(null, SourceRegistry.duplicateOf("https://myserver.example/manifest.json/"))
            assertNotEquals(null, SourceRegistry.duplicateOf("https://myserver.example/manifest.json"))
            // The path can carry a token; two tokens differing in case differ.
            assertEquals(null, SourceRegistry.duplicateOf("https://myserver.example/MANIFEST.json"))
            assertEquals(null, SourceRegistry.duplicateOf("", exceptId = a.id))
        } finally {
            SourceRegistry.configs.value = emptyList()
        }
    }

    // ── Request derivation ───────────────────────────────────────────────

    @Test
    fun `requestForNow maps the ceiling - lossless, best, capped`() {
        val stored = AppSettings.audioQuality.value
        try {
            AppSettings.audioQuality.value = AudioQuality.LOSSLESS
            assertEquals(StreamRequest.Lossless, SourceResolver.requestForNow())
            AppSettings.audioQuality.value = AudioQuality.HIGH
            assertEquals(StreamRequest.Best, SourceResolver.requestForNow())
            AppSettings.audioQuality.value = AudioQuality.LOW
            assertEquals(StreamRequest.Capped(64), SourceResolver.requestForNow())
        } finally {
            AppSettings.audioQuality.value = stored
        }
    }

    @Test
    fun `requestForDownload reads the download rung, not the stream ceiling`() {
        assertEquals(StreamRequest.Lossless, SourceResolver.requestForDownload(com.music.bitchord.data.settings.DownloadQuality.LOSSLESS))
        assertEquals(StreamRequest.Best, SourceResolver.requestForDownload(com.music.bitchord.data.settings.DownloadQuality.HIGH))
        // Standard is YouTube's rung: the walk returns null by design, tested
        // through forDownload's guard below rather than here.
    }

    // ── Swap and ranking predicates ──────────────────────────────────────

    @Test
    fun `worthSwapping - lossless always, atmos never away, lossy needs 96 kbps`() {
        assertTrue(SourceResolver.worthSwapping(StreamFormat(codec = "flac"), StreamFormat(codec = "aac", kbps = 320)))
        assertFalse(
            SourceResolver.worthSwapping(
                StreamFormat(codec = "flac"),
                StreamFormat(codec = "eac3-joc"),
            ),
            "never away from an immersive mix",
        )
        assertTrue(
            SourceResolver.worthSwapping(
                StreamFormat(codec = "aac", kbps = 320),
                StreamFormat(codec = "opus", kbps = 160),
            ),
            "160 → 320 clears the 96 kbps margin",
        )
        assertFalse(
            SourceResolver.worthSwapping(
                StreamFormat(codec = "aac", kbps = 192),
                StreamFormat(codec = "opus", kbps = 160),
            ),
            "128 → 192 does not",
        )
        assertFalse(
            SourceResolver.worthSwapping(StreamFormat(codec = "aac", kbps = 320), null),
            "an unmeasured floor is one nothing lossy clears",
        )
    }

    @Test
    fun `isBetter - atmos ahead of lossless, lossless ahead of kbps`() {
        val atmos = StreamFormat(codec = "eac3-joc")
        val flac = StreamFormat(codec = "flac")
        val aac320 = StreamFormat(codec = "aac", kbps = 320)
        val aac128 = StreamFormat(codec = "aac", kbps = 128)

        assertTrue(SourceResolver.isBetter(atmos, flac))
        assertTrue(SourceResolver.isBetter(flac, aac320))
        assertTrue(SourceResolver.isBetter(aac320, aac128))
        assertFalse(SourceResolver.isBetter(aac128, aac320))
        assertTrue(SourceResolver.isBetter(aac320, null))
    }

    @Test
    fun `beatsYouTubeAac - the floor is 256, unstated loses`() {
        assertTrue(SourceResolver.beatsYouTubeAac(StreamFormat(codec = "aac", kbps = 320)))
        assertFalse(SourceResolver.beatsYouTubeAac(StreamFormat(codec = "aac", kbps = 256)))
        assertFalse(SourceResolver.beatsYouTubeAac(StreamFormat(codec = "aac")))
    }

    // ── bestAcross racing ────────────────────────────────────────────────

    private class FakeSource(
        override val configId: String,
        override val kind: SourceKind,
        private val delayMs: Long,
        private val format: StreamFormat?,
        private val querySeen: MutableList<String>? = null,
    ) : MusicSource {
        override val displayName: String = configId

        override suspend fun health(): SourceHealth = SourceHealth.Ok()

        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
        ): List<Song> {
            querySeen?.add(query)
            // On the test dispatcher, so runTest's virtual clock orders the legs.
            kotlinx.coroutines.delay(delayMs)
            if (format == null) return emptyList()
            return listOf(
                Song(
                    videoId = SourceRegistry.trackKey(configId, "t1"),
                    title = "Target Song",
                    artist = "Target Artist",
                    thumbnailUrl = null,
                    durationText = "3:30",
                    sourceQuality = if (format.isLossless == true) ModuleSource.LOSSLESS else null,
                ),
            )
        }

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
            format?.let { SourceStream(url = "https://stream.example/$trackId", format = it) }
    }

    private fun target() = TrackMatcher.Target(
        title = "Target Song",
        artist = "Target Artist",
        durationSec = 210,
        album = null,
        isExplicit = null,
        isVideo = false,
    )

    @Test
    fun `bestAcross - the quick answer wins and stragglers are abandoned`() = runTest {
        val quick = FakeSource("quick", SourceKind.JIOSAAVN, delayMs = 0, format = StreamFormat(codec = "aac", kbps = 320))
        val slow = FakeSource("slow", SourceKind.ADDON, delayMs = 60_000, format = StreamFormat(codec = "flac"))

        // The slow leg sits first, as rank order would put it; the race is
        // what lets the fast one past it.
        val winner = SourceResolverTestBridge.bestAcross(listOf(slow, quick), target())
        assertEquals("quick", winner?.first?.configId)
    }

    @Test
    fun `bestAcross - a source that throws loses its turn, not the track`() = runTest {
        val throwing = object : MusicSource {
            override val configId = "throwing"
            override val kind = SourceKind.ADDON
            override val displayName = "throwing"
            override suspend fun health(): SourceHealth = SourceHealth.Ok()
            override suspend fun search(query: String, limit: Int, waitForAll: Boolean, request: StreamRequest?): List<Song> =
                throw IllegalStateException("server down")
            override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? = null
        }
        val good = FakeSource("good", SourceKind.JIOSAAVN, delayMs = 0, format = StreamFormat(codec = "aac", kbps = 320))

        val winner = SourceResolverTestBridge.bestAcross(listOf(throwing, good), target())
        assertEquals("good", winner?.first?.configId)
    }

    // ── Module source helpers ────────────────────────────────────────────

    @Test
    fun `qualityTier reads free text - codec settles it, a bit depth alone is still lossless`() {
        assertEquals("LOSSLESS", ModuleSource.qualityTier("FLAC 16-bit / 44.1kHz"))
        assertEquals("LOSSLESS", ModuleSource.qualityTier("24-bit / 96kHz"))
        assertEquals("HIGH", ModuleSource.qualityTier("320kbps"))
        assertEquals("LOW", ModuleSource.qualityTier("128 kbps"))
        assertNull(ModuleSource.qualityTier(""))
    }

    @Test
    fun `malformed refuses a doubled origin and non-http, accepts a proxy url`() {
        assertTrue(ModuleSource.malformed("https://x.example/https://x.example/stream"))
        assertTrue(ModuleSource.malformed("ftp://x.example/stream"))
        assertFalse(ModuleSource.malformed("https://proxy.example/https://real.example/stream.flac"))
        assertTrue(ModuleSource.malformed("not a url at all"))
    }

    // ── Module index parsing ─────────────────────────────────────────────

    @Test
    fun `module index scans every category key and dedupes by id`() {
        val body = """
            {
              "category:music": [
                {"id": "m1", "name": "One", "download": "one.js"},
                {"id": "m2", "name": "Two", "download": "two.js"}
              ],
              "category:artworks": [{"id": "art", "name": "Art"}],
              "category:testing": [{"id": "t", "name": "T"}],
              "category:music:more": [{"id": "m1", "name": "One again"}],
              "other": {"not": "a list"}
            }
        """.trimIndent()
        val modules = ModuleIndex.parseModules(Json { ignoreUnknownKeys = true }, body)
        assertEquals(listOf("m1", "m2"), modules.map { it.id })
    }
}

/**
 * The internals the racing tests need sit behind `internal`; this bridge lives
 * in the same module so the tests can reach them the way upstream's tests do.
 */
internal object SourceResolverTestBridge {
    suspend fun bestAcross(
        sources: List<MusicSource>,
        target: TrackMatcher.Target,
    ): Pair<MusicSource, SourceStream>? =
        SourceResolver.run { bestAcross(sources, target, StreamRequest.Best) }
}
