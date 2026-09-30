# BitChord 桌面版 · 切片 2 实施计划（Now Playing 全屏播放页 + 队列）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付原版的全屏播放页与三段式队列（CONTEXT / USER_QUEUE / AUTOPLAY），让 MiniPlayer 的点击成为通路，并把 710 行队列算法照搬进桌面端，使"播完自动下一首"在解析要 6–19 秒的现实下真的可用。

**Architecture:** 延续"复制-适配"：队列算法文件按原包名原文件名搬进 `desktop/src/main/kotlin/com/music/bitchord/playback/`，唯一改动是把"动播放机"的那几行收进一个 `QueueHost` 接口（由桌面自有的 `QueueTimeline` 实现）；`PlayerController` 从单曲播放器升级为队列播放器，播放心跳挂在 `VlcAudioPlayer.onFinished` 上。界面文件（`ui/player/`）同样照搬，删除歌词 / 一起听 / 运动封面三族，桌面端新写的只有：队列状态机、覆盖层宿主、行菜单、预取、Esc。`app/` 一个字节都不改。

**Tech Stack:** Kotlin 2.4.10 / Compose Multiplatform 1.12.1（桌面）/ Haze 1.3.1 / Coil 3.6.3 / VLCJ 4.12.1 / `kotlin("test")` + JUnit Platform。

**Spec:** `docs/superpowers/specs/2026-09-30-desktop-slice2-now-playing-queue-design.md`

## Global Constraints

- 分支 `desktop-slice2-now-playing`（从 `windows-desktop` 切出）。spec 第 5 行与第 1、9 条引用的 `windows-desktop` / `Shell.kt:124` 的 `// TODO(slice-2)` / "`showPlayer` 状态已经接上" / Home-Search 行的空回调 `onShowActions` —— **经逐行核对均不成立**。实测锚点：`Shell.kt:160-164` 是 `onNext = {}` / `onPrevious = {}` / `onExpand = {}` 三个空 lambda（注释在 `:162-163`），`SearchPage.kt:123-124` 是 `onSongLongPress = {}` / `onSongSwipe = {}`，HomePage 根本没传 `onItemLongPress` 所以 `HomeScreen.kt:394` 不画 ⋮；`Shell.kt` 里没有 `showPlayer`。按实测锚点干活，并在 Task 13 把 spec 这几处更正回去。
- **`app/` 零改动**（本机无 Android SDK，无法验证安卓构建）。`app/` 只作为只读对照物。
- `desktop/` 是独立 Gradle 构建；所有命令先 `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"`，构建用 `./gradlew -p desktop <task>`。
- 从 `app/` 复制的文件**保持原包名与文件名**，文件头加一行来源注释；改动集中在少量替换处。
- **算法保真是本切片第一约束**（决策 3）：`QueueBuilder` / `QueueHistory` / `QueueCoordinator` / `QueueShuffle` 正文逐字搬，只允许三类改动——删 Android import、把 `Player` 换成 `QueueHost`、删跨进程残留（`sendCustomCommand` / `Bundle` / `reorderFromCommand` / `buildPartyPlaybackQueue`）。护栏语句、早退分支、KDoc 里解释"为什么这么绕"的注释一律保留。
- 不引入任何 `android.*` / `androidx.*`（`androidx.compose.*` 由 CMP 提供，`dev.chrisbanes.haze`、`coil3` 已在依赖里）。`androidx.media3.*` 一个符号都不许出现。
- **单元测试优先复用安卓版已有的测试**：`app/src/test/java/com/music/bitchord/Queue{Builder,Coordinator,History,Shuffle,ShuffleTier,Sections,EdgeScroll}Test.kt` 共约 1160 行，已是纯 JVM（只依赖 `org.junit.Assert` + 一个 `java.lang.reflect.Proxy` 假 Player）。搬时只换包名与断言 import；**期望值不许改**——改了就不再是对照物。
- 桌面测试约定：`src/test/kotlin`，`kotlin.test` + JUnit Platform（`build.gradle.kts:70,78-80`），反引号句式用例名，命令 `./gradlew -p desktop test --console=plain --tests "*XxxTest*"`。
- 验证三件套每次都要做：**单元测试** + **行为断言**（`-Pbitchord.*` 钩子驱动，读 stdout 日志）+ **视觉判读**（亲自看截图，中英双语，至少 1180×780 与 1550×974 两档）。对齐 / 溢出 / 裁切这类判断必须另有定点几何量，不拿缩放过的截图当量具。
- 不做死按钮（切片 1 收口规矩）：歌词全族、AUTOPLAY 开关、音频输出、一起听、运动封面、行菜单其余动作项——**不出现**，不是置灰。
- 每个 Task 结束都提交；提交信息用英文 conventional commits，scope 恒为 `desktop`，正文说明来源文件与改动点。

**本切片引用的原版坐标（照抄，勿自造）**

| 项 | 值 | 来源 |
|---|---|---|
| 横屏判定 | `windowWidth > windowHeight && windowWidth >= 560.dp` | `app/.../ui/player/NowPlayingScreen.kt:408-409`，常量 `:316` |
| 历史窗口 | `MAX_QUEUE_HISTORY = 25` | `app/.../playback/QueueHistory.kt:4` |
| 上一首重播阈值 | `BACK_RESTARTS_AFTER_MS = 10_000L` | `app/.../playback/PlaybackService.kt:157` |
| 横屏最大宽 / 紧凑高 | `1100.dp` / `440.dp` | `app/.../ui/player/LandscapePlayer.kt:76,84` |
| 播放页栏宽 / gutter | `PLAYER_MAX_WIDTH = 560.dp` / `PLAYER_GUTTER = 30.dp` | `NowPlayingScreen.kt:291,284` |
| 历史轮转规则 | REPEAT_ALL 轮转到队尾，否则删 | `PlaybackService.kt:2712-2724` |
| 封面像素级 | `PLAYER_ART_PX = 1200` | `desktop/.../data/model/Models.kt`（已存在，目前无调用者） |

**桌面现状速查（供只读本计划的工程师）**

- `PlayerController`（`desktop/.../desktop/playback/PlayerController.kt`，125 行）只有 `play(Song)` / `playCollection(browseId,label)` / `togglePlayPause` / `seekToFraction` / `setVolume` / `release`，六个 `StateFlow`；`init` 里 `vlc.onFinished = { _positionMs.value = _durationMs.value }`（`:55`）——只钳位置，不推进。
- `VlcAudioPlayer`（100 行）已暴露 `onFinished`（`:24`，由 `:41-44` 的 `finished()` 触发）。
- `StreamResolver.resolve(videoId)`（`:258`）已按 videoId 缓存 20 分钟（`:1082-1084`）并合并并发解析（`coalescedResolve`，`:386-416`）——预取不需要新缓存，只需要有人去调。
- 桌面 `AppSettings`（65 行）纯内存、无持久化；`FileStore`（`DesktopStore.kt:30`）只有 `getString/putString/getLong/putLong`。
- 桌面 `ui/player/` 目前只有 `MeshGradient.kt` + `FrameHeuristics.kt`。
- `TrackLog`（`data/TrackLog.kt`）已带 512KB 环形缓冲与 `suspend fun forTrack(song): String`（`:136`）——spec 决策 5 说的"DebugLog 需要留一份最近 N 行"这件事**已经做完了**，"复制日志"直接用 `TrackLog.forTrack`。
- `YtMusicRepository.rate(videoId, LikeStatus)`（`:813`）与 `LikeState`（`data/LikeState.kt`，`overrides: StateFlow<Map<String, LikeStatus>>`）已就位。
- `YtMusicRepository.radio(videoId)`（`:550`，RDAMVM 取曲目）已搬过来但无调用者——属切片 6，本切片**不要**接它。

---

### Task 1: 队列内核落地——`PlayerState` / `QueueBuilder` / `QueueHistory`

**Files:**
- Create: `desktop/src/main/kotlin/com/music/bitchord/playback/PlayerState.kt`（内容源自 `app/.../playback/PlayerConnection.kt:42-94, 485-489, 780-781`）
- Copy: `app/.../playback/QueueBuilder.kt` → `desktop/src/main/kotlin/com/music/bitchord/playback/QueueBuilder.kt`
- Copy: `app/.../playback/QueueHistory.kt` → `desktop/src/main/kotlin/com/music/bitchord/playback/QueueHistory.kt`
- Test: `desktop/src/test/kotlin/com/music/bitchord/playback/QueueBuilderTest.kt`（源自 `app/src/test/java/com/music/bitchord/QueueBuilderTest.kt`，162 行）
- Test: `desktop/src/test/kotlin/com/music/bitchord/playback/QueueHistoryTest.kt`（源自 53 行）
- Test: `desktop/src/test/kotlin/com/music/bitchord/playback/QueueSectionsTest.kt`（源自 49 行）

**Interfaces:**
- Consumes: 桌面已有的 `com.music.bitchord.data.model.Song` / `QueueTier` / `PlaybackSourceType`（`queueTier:42` / `queueEntryId:48` / `radioName:54` / `playbackSource*:93-97` 都在）、`com.music.bitchord.data.SystemClock.elapsedRealtime()`。
- Produces:
  - `@Stable class PlaybackPosition { var positionMs: Long }`（`internal set`）
  - `data class PlayerState(song: Song? = null, isPlaying: Boolean = false, position: PlaybackPosition = PlaybackPosition(), durationMs: Long = 0L, error: String? = null, isLoading: Boolean = false, repeatMode: Int = RepeatMode.OFF, queue: List<Song> = emptyList(), queueIndex: Int = 0, hasPrevious: Boolean = false, hasNext: Boolean = false, isQualityUpgraded: Boolean = false)`
  - `object RepeatMode { const val OFF = 0; const val ONE = 1; const val ALL = 2 }`
  - `object QueueBuilder { fun extend(existing: List<Song>, candidates: List<Song>, limit: Int): List<Song>; fun isSameRecording(a: Song, b: Song): Boolean; internal fun normalisedTitle(raw: String): String; internal fun artistSet(raw: String): Set<String> }`
  - `internal const val MAX_QUEUE_HISTORY = 25`；`internal fun queueHistoryTrimCount(currentIndex: Int): Int`；`internal fun skippedByQueueJump(currentIndex: Int, targetIndex: Int): IntRange?`；`internal fun <T> queueStartingAt(items: List<T>, startIndex: Int): List<T>`
  - `fun autoplaySectionStart(fromAutoplay: List<Boolean>, currentIndex: Int): Int`
  - `internal fun queueStartIndex(requestedIndex: Int, itemCount: Int, shuffled: Boolean): Int`

- [ ] **Step 1: 复制两个纯 JVM 文件**

```bash
cd "/c/Users/xiongdy/Documents/vibecoding/BitChord for Windows"
mkdir -p desktop/src/main/kotlin/com/music/bitchord/playback
cp app/src/main/java/com/music/bitchord/playback/QueueBuilder.kt desktop/src/main/kotlin/com/music/bitchord/playback/
cp app/src/main/java/com/music/bitchord/playback/QueueHistory.kt desktop/src/main/kotlin/com/music/bitchord/playback/
```

两文件的 `package com.music.bitchord.playback` 原样保留，**一行正文都不改**（`QueueBuilder` 的 import 只有 `Song` 和 `java.util.Locale`；`QueueHistory` 没有 import）。各在文件顶部加一行来源注释：

```kotlin
// Ported verbatim from app/src/main/java/com/music/bitchord/playback/QueueBuilder.kt — no changes.
```

`queueStartingAt`（`QueueHistory.kt:27-31`）在安卓版没有生产调用者、只有测试用；照搬保留，别删——删了就是多一处与上游的差异。

- [ ] **Step 2: 写 `PlayerState.kt`**

这是本切片唯一"拼装"出来的内核文件：`PlayerConnection.kt` 其余 ~700 行是 Media3/Bundle 桥，整体不搬，只剪出四段纯的。文件头注释要说明这一点，让日后 diff 的人知道为什么这里少 700 行。

```kotlin
// PlayerState, PlaybackPosition and the two pure queue-index helpers, cut verbatim out of
// app/src/main/java/com/music/bitchord/playback/PlayerConnection.kt (:42-94, :485-489, :780-781).
// The Media3 bridge around them does not come over: the desktop player holds Songs in this
// process, so the MediaItem/Bundle serialisation — 16 EXTRA_* keys, toSong/toMediaItem, the
// two Bundle round-trips — has nothing left to be for.
package com.music.bitchord.playback

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.music.bitchord.data.model.Song

/** Desktop stand-in for `androidx.media3.common.Player.REPEAT_MODE_*`: three states, same order. */
object RepeatMode {
    const val OFF = 0
    const val ONE = 1
    const val ALL = 2
}
```

接着原样搬 `PlaybackPosition`（`PlayerConnection.kt:42-62`，**连同整段 KDoc**——它解释的就是"播放头为什么不能进 `PlayerState`"，桌面同样成立，一个字都别缩写）与 `PlayerState`（`:64-94`），只改两处：`val repeatMode: Int = Player.REPEAT_MODE_OFF` → `val repeatMode: Int = RepeatMode.OFF`；KDoc 里指向 `[rememberPlayerState]` / `[MediaController]` / "driven by the MediaController" 的措辞改成桌面实际（由 `PlayerController` 驱动），因为那些符号不搬，链接会指向不存在的东西。

再搬两个纯函数，函数体一字不动（连同各自 KDoc）：

```kotlin
fun autoplaySectionStart(fromAutoplay: List<Boolean>, currentIndex: Int): Int {
    val after = (currentIndex + 1).coerceIn(0, fromAutoplay.size)
    return (after until fromAutoplay.size).firstOrNull { fromAutoplay[it] }
        ?: fromAutoplay.size
}

internal fun queueStartIndex(requestedIndex: Int, itemCount: Int, shuffled: Boolean): Int =
    if (shuffled) 0 else requestedIndex.coerceIn(0, itemCount - 1)
```

`PlayerConnection.kt:491-494` 那个 `MediaController.autoplaySectionStart()` 重载不搬（跨进程产物）。

- [ ] **Step 3: 搬三个测试**

```bash
mkdir -p desktop/src/test/kotlin/com/music/bitchord/playback
for t in QueueBuilderTest QueueHistoryTest QueueSectionsTest; do
  cp "app/src/test/java/com/music/bitchord/$t.kt" "desktop/src/test/kotlin/com/music/bitchord/playback/$t.kt"
done
```

每个文件只做三处改动，**期望值一个都不改**：

1. `package com.music.bitchord` → `package com.music.bitchord.playback`（放同包才够得着 `internal`，与安卓版"同模块"的前提一致）。
2. `import org.junit.Assert.{assertEquals,assertTrue,assertFalse,assertNull}` → `import kotlin.test.{...}`；`import org.junit.Test` → `import kotlin.test.Test`。
3. 补 `import com.music.bitchord.data.model.Song` / `QueueTier`（安卓测试通过 `MediaItem` 造数据，`QueueSectionsTest`/`QueueHistoryTest` 只用纯 List，多半不需要）。

- [ ] **Step 4: 跑测试**

Run: `./gradlew -p desktop test --console=plain --tests "*QueueBuilderTest*" --tests "*QueueHistoryTest*" --tests "*QueueSectionsTest*"`
Expected: BUILD SUCCESSFUL，三类全绿。若某条失败，先怀疑自己搬运时动了正文，而不是改期望值——`QueueBuilder.extend` 的同录音去重（同名不同 videoId）与同艺人上限（`PER_ARTIST_LIMIT = 2` / `SEED_ARTIST_LIMIT = 4`）就是靠这些用例证明"逐字搬"这件事。

- [ ] **Step 5: 全量测试 + 提交**

Run: `./gradlew -p desktop test --console=plain`
Expected: BUILD SUCCESSFUL（切片 1 的 16 条 + 本任务新增全绿）。

```bash
git add desktop/src/main/kotlin/com/music/bitchord/playback desktop/src/test/kotlin/com/music/bitchord/playback
git commit -m "feat(desktop): carry the queue kernel's pure half over"
```

---

### Task 2: `QueueHost` 接缝 + `QueueCoordinator`（纯构建器逐字，改播放机的三个换接口）

**Files:**
- Create: `desktop/src/main/kotlin/com/music/bitchord/playback/QueueHost.kt`
- Copy+Modify: `app/.../playback/QueueCoordinator.kt` → `desktop/src/main/kotlin/com/music/bitchord/playback/QueueCoordinator.kt`
- Test: `desktop/src/test/kotlin/com/music/bitchord/playback/FakeQueueHost.kt`
- Test: `desktop/src/test/kotlin/com/music/bitchord/playback/QueueCoordinatorTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `MAX_QUEUE_HISTORY` / `queueHistoryTrimCount` / `autoplaySectionStart`；桌面 `Song` / `QueueTier` / `PlaybackSourceType`。
- Produces:
  - `interface QueueHost { val itemCount: Int; val currentIndex: Int; fun songAt(index: Int): Song?; fun removeAt(index: Int); fun replaceRange(from: Int, to: Int, songs: List<Song>); fun setTimeline(songs: List<Song>, startIndex: Int); fun jumpTo(index: Int, positionMs: Long = 0L); fun playCurrent() }`
  - `object QueueCoordinator`：`fun Song.asQueueEntry(tier: QueueTier): Song`、`data class QueueSource(title: String, type: PlaybackSourceType, id: String? = null)`、`data class ContextQueueResult(timeline: List<Song>, startIndex: Int)`、`buildContextQueue(currentTimeline, currentIndex, newContextSongs, selectedIndex, contextSource): ContextQueueResult`、`buildOneOffQueue(currentTimeline, currentIndex, tappedSong, source): List<Song>`、`findUserQueueInsertionIndex(timeline, currentIndex, isNext): Int`、`clearUserQueue(host: QueueHost, tierAt: (Int) -> QueueTier)`、`consumePlayedUserQueue(host: QueueHost, tierAt: (Int) -> QueueTier)`、`buildJumpQueue(currentTimeline, currentIndex, targetIndex): List<Song>?`、`jumpToQueueItem(host: QueueHost, targetIndex: Int, cachedTimeline: List<Song>? = null)`
  - 删除：`buildPartyPlaybackQueue`、文件级 `private fun Player.queueTierAt`

- [ ] **Step 1: 写假宿主**

`FakeQueueHost` 是安卓版那个 `java.lang.reflect.Proxy` 假 `Player`（`QueueCoordinatorTest.kt:36-65`）的直白替身——五列接口不需要动态代理。

```kotlin
package com.music.bitchord.playback

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song

/**
 * A queue that is only a list. Stands in for the player the way the Proxy fake
 * stood in for Media3's Player in app/src/test/java/com/music/bitchord/QueueCoordinatorTest.kt.
 */
class FakeQueueHost(
    songs: List<Song>,
    override var currentIndex: Int = 0,
) : QueueHost {
    val items: MutableList<Song> = songs.toMutableList()
    val played = mutableListOf<String>()

    override val itemCount: Int get() = items.size
    override fun songAt(index: Int): Song? = items.getOrNull(index)
    override fun removeAt(index: Int) {
        items.removeAt(index)
        // ExoPlayer shifts currentMediaItemIndex when an item behind the playhead
        // is removed; the fake has to, or the mutating functions look correct while
        // the real host would land somewhere else.
        if (index < currentIndex) currentIndex -= 1
    }
    override fun replaceRange(from: Int, to: Int, songs: List<Song>) {
        repeat(to - from) { items.removeAt(from) }
        items.addAll(from, songs)
    }
    override fun setTimeline(songs: List<Song>, startIndex: Int) {
        items.clear(); items.addAll(songs); currentIndex = startIndex
    }
    override fun jumpTo(index: Int, positionMs: Long) { currentIndex = index; played += items[index].videoId }
    override fun playCurrent() { items.getOrNull(currentIndex)?.let { played += it.videoId } }

    /** The tier the algorithms ask for at an index. */
    fun tierAt(index: Int): QueueTier = items[index].queueTier
}
```

- [ ] **Step 2: 写失败测试（纯构建器 + 跳转的构建侧）**

`desktop/src/test/kotlin/com/music/bitchord/playback/QueueCoordinatorTest.kt`。下面这些用例就是 spec §5.1 前三条的证明，名字与断言都按原版语义写：

```kotlin
package com.music.bitchord.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueCoordinatorTest {

    private fun song(id: String, tier: QueueTier = QueueTier.CONTEXT) = Song(
        videoId = id, title = "T$id", artist = "A$id", thumbnailUrl = null, queueTier = tier,
    )
    private val source = QueueSource("Search", PlaybackSourceType.SEARCH, "sid")

    @Test
    fun `a context queue keeps the live user queue where it was`() {
        val result = QueueCoordinator.buildContextQueue(
            currentTimeline = listOf(song("now"), song("old", QueueTier.USER_QUEUE), song("stale")),
            currentIndex = 0,
            newContextSongs = (1..3).map { song("c$it") },
            selectedIndex = 0,
            contextSource = source,
        )
        assertEquals(listOf("c1", "old", "c2", "c3"), result.timeline.map { it.videoId })
        assertEquals(0, result.startIndex)
    }

    @Test
    fun `a context queue starts at the tapped row`() {
        val result = QueueCoordinator.buildContextQueue(
            currentTimeline = emptyList(), currentIndex = 0,
            newContextSongs = (1..3).map { song("c$it") }, selectedIndex = 2, contextSource = source,
        )
        assertEquals(2, result.startIndex)
        assertEquals("c3", result.timeline[result.startIndex].videoId)
    }

    @Test
    fun `a one-off queue is the tapped track plus what the user queued`() {
        val timeline = QueueCoordinator.buildOneOffQueue(
            currentTimeline = listOf(song("old"), song("keep", QueueTier.USER_QUEUE)),
            currentIndex = 0,
            tappedSong = song("tap"),
            source = source,
        )
        assertEquals(listOf("tap", "keep"), timeline.map { it.videoId })
    }

    @Test
    fun `play next lands right after the current track, add to queue at the user tier's tail`() {
        val timeline = listOf(
            song("now"), song("u1", QueueTier.USER_QUEUE), song("c1"), song("u2", QueueTier.USER_QUEUE),
        )
        assertEquals(1, QueueCoordinator.findUserQueueInsertionIndex(timeline, currentIndex = 0, isNext = true))
        assertEquals(2, QueueCoordinator.findUserQueueInsertionIndex(timeline, currentIndex = 0, isNext = false))
    }

    @Test
    fun `a queue entry gets its id once and keeps it`() {
        val tagged = song("v1").asQueueEntry(QueueTier.USER_QUEUE)
        assertEquals(tagged.queueEntryId, tagged.asQueueEntry(QueueTier.CONTEXT).queueEntryId)
        assertEquals(QueueTier.USER_QUEUE, tagged.queueTier)
    }

    @Test
    fun `a forward jump keeps the user queue and the rows after the target`() {
        val jumped = QueueCoordinator.buildJumpQueue(
            listOf(
                song("now"), song("c1"), song("skip", QueueTier.USER_QUEUE),
                song("c2"), song("target"), song("c3"),
            ),
            currentIndex = 0, targetIndex = 4,
        )!!
        assertEquals(listOf("target", "skip", "c3"), jumped.map { it.videoId })
    }

    @Test
    fun `a backward jump changes nothing`() {
        assertNull(QueueCoordinator.buildJumpQueue(listOf(song("a"), song("b")), currentIndex = 1, targetIndex = 0))
    }

    @Test
    fun `jumping to an autoplay row promotes it into the context tier`() {
        val jumped = QueueCoordinator.buildJumpQueue(
            listOf(song("now"), song("r1", QueueTier.AUTOPLAY), song("r2", QueueTier.AUTOPLAY)),
            currentIndex = 0, targetIndex = 1,
        )!!
        assertEquals(QueueTier.CONTEXT, jumped.first().queueTier)
        assertEquals(PlaybackSourceType.QUEUE, jumped.first().playbackSourceType)
        // The AUTOPLAY branch returns `listOf(promotedTarget) + allFutureUserQueue +
        // remainingAutoplay` (app QueueCoordinator.kt:265-271), so the whole result is
        // [r1, r2]: the promoted target leads and r2 survives as remaining autoplay.
        assertEquals(listOf("r1", "r2"), jumped.map { it.videoId })
    }

    @Test
    fun `the context queue labels every row with the source it came from`() {
        val result = QueueCoordinator.buildContextQueue(
            currentTimeline = emptyList(), currentIndex = 0,
            newContextSongs = listOf(song("c1")), selectedIndex = 0, contextSource = source,
        )
        assertEquals("Search", result.timeline.single().playbackSource)
        assertEquals(PlaybackSourceType.SEARCH, result.timeline.single().playbackSourceType)
        assertEquals("sid", result.timeline.single().playbackSourceId)
    }

    @Test
    fun `an empty context is refused before anything is built`() {
        val result = QueueCoordinator.buildContextQueue(
            currentTimeline = listOf(song("a")), currentIndex = 0,
            newContextSongs = emptyList(), selectedIndex = 0, contextSource = source,
        )
        assertTrue(result.timeline.isEmpty()); assertEquals(0, result.startIndex)
    }
}
```

Run: `./gradlew -p desktop test --console=plain --tests "*QueueCoordinatorTest*"`
Expected: 编译失败——`QueueCoordinator` / `QueueHost` / `QueueSource` 未解析。

**已核对的三件事**（别在实施时重新推导，也别照着"感觉对"改期望值）：

1. `buildContextQueue` 只保留 **`currentIndex` 之后** 的 USER_QUEUE（`:70-75` 的 `subList(currentIndex + 1, …)`）。当前那一行本身不会被带进新队列——所以要证明"USER_QUEUE 不丢"，测试里被保护的行必须排在 currentIndex **之后**。
2. `buildJumpQueue` 的 CONTEXT 分支顺序是 `listOf(targetSong) + allFutureUserQueue + remainingContext + remainingAutoplay`（`:276`）。`allFutureUserQueue` 从 `currentIndex+1` 一路收集到队尾（`:257`），所以**被跳过的那条 USER_QUEUE 也会活下来**；被丢掉的只有被跳过的 CONTEXT/AUTOPLAY。这就是上面 `target, skip, c3` 这个顺序的来历。
3. `MAX_QUEUE_HISTORY = 25` 意味着 `currentIndex` 必须 **>25** 才有可裁的历史（`queueHistoryTrimCount = (currentIndex - 25).coerceAtLeast(0)`）。造历史用例时至少需要 **27** 行、且播放头在第 26 位，否则裁数为 0、轮转与删除都不会发生。

- [ ] **Step 3: 写 `QueueHost.kt`**

```kotlin
// The seam the ported queue algorithms use instead of Media3's Player.
//
// app/.../playback/QueueCoordinator.kt touches nine Player members: currentMediaItemIndex,
// mediaItemCount, getMediaItemAt (twice), currentMediaItem, removeMediaItem, replaceMediaItems,
// setMediaItems, seekTo+play, and playbackState+prepare. On Android nine because the items have
// to cross a process boundary — hence MediaItem, Bundle and the 16 EXTRA_* keys. Desktop holds
// Songs in this process, so all of that half of the surface disappears: seven members and no
// serialisation.
package com.music.bitchord.playback

import com.music.bitchord.data.model.Song

interface QueueHost {
    val itemCount: Int
    val currentIndex: Int
    fun songAt(index: Int): Song?
    fun removeAt(index: Int)
    fun replaceRange(from: Int, to: Int, songs: List<Song>)
    fun setTimeline(songs: List<Song>, startIndex: Int)
    fun jumpTo(index: Int, positionMs: Long = 0L)
    fun playCurrent()
}
```

- [ ] **Step 4: 搬 `QueueCoordinator.kt`，逐处替换**

```bash
cp app/src/main/java/com/music/bitchord/playback/QueueCoordinator.kt desktop/src/main/kotlin/com/music/bitchord/playback/
```

按序改动，每处都是删/换几行，**不重写函数**：

1. 删 `import androidx.media3.common.Player`（`:3`）与 `import com.music.bitchord.data.listentogether.PartyTrack`（`:4`）。文件顶加来源注释。
2. 删文件级 `private fun Player.queueTierAt(index: Int): QueueTier = getMediaItemAt(index).queueTier`（`:15`）。它上面的 KDoc（`:11-14`）说的是"JVM 测试可以自己传 tier，因为 MediaItem 的 metadata bundle 是个什么都不存的桩"——桌面连 MediaItem 都没有，KDoc 保留并改成说明 `tierAt` 必须由调用方显式给出。
3. **逐字不动**：`asQueueEntry`（`:49-52`）、`QueueSource`（`:20-24`）、`ContextQueueResult`（`:30-33`）、`buildContextQueue`（`:61-97`）、`buildOneOffQueue`（`:104-124`）、`findUserQueueInsertionIndex`（`:158-175`）、`buildJumpQueue`（`:244-288`，含那个对三值枚举穷尽、无 `else` 的 `when`）。
4. 删 `buildPartyPlaybackQueue`（`:133-149`，一起听＝切片 6）。
5. 两个改播放机的函数换签名，`tierAt` 不再有默认值：

```kotlin
fun clearUserQueue(host: QueueHost, tierAt: (Int) -> QueueTier) { … }
fun consumePlayedUserQueue(host: QueueHost, tierAt: (Int) -> QueueTier) { … }
```

正文逐字照 `:183-197` / `:208-223`，只做这些替换：`player.currentMediaItemIndex` → `host.currentIndex`；`player.mediaItemCount` → `host.itemCount`；`tierAt(i)` 保持（原本就是参数调用）；`player.removeMediaItem(i)` → `host.removeAt(i)`；`if (player.currentMediaItem == null) return`（`:211`）→ `if (host.songAt(currentIndex) == null) return`（同一件事：没有当前条目就别动）。反向删除循环连同它上面那句 *"Remove in reverse order to preserve preceding indices during removal"* 一起留下。

6. `jumpToQueueItem`（`:294-325`）换 host：
   - `:299-300` 同上。
   - `:305-306` 的 `player.seekTo(targetIndex, 0L)` + `player.play()` → `host.jumpTo(targetIndex, 0L)`（接口那一个调用就包含了 seek 与出声；`QueueHost` 的注释要写明这合并了什么）。
   - `:310-311` `(0 until count).map { player.getMediaItemAt(it).toSong() }` → `(0 until count).mapNotNull { host.songAt(it) }`。
   - `:315` `(0..currentIndex).map { player.getMediaItemAt(it) }` → `(0..currentIndex).mapNotNull { host.songAt(it) }`。
   - `:316` `newUpcoming.map { it.toMediaItem() }` **整段删**（不再包一层），直接用 `newUpcoming`。
   - `:320` `player.setMediaItems(newPlaylist, newTargetIndex, 0L)` → `host.setTimeline(newPlaylist, newTargetIndex)`。
   - `:321-322` `if (runCatching { player.playbackState }.getOrNull() == Player.STATE_IDLE) player.prepare()` **删**，连同其上方解释 idle 语义的注释换成一句：Media3 需要显式 prepare 因为播放器可能处于 idle；桌面的播放器"给个 URL 就响"，prepare 与 play 是同一件事，由 `playCurrent()` 承担。
   - `:324` `player.play()` → `host.playCurrent()`。
7. `:227` 的 `cachedTimeline?.takeIf { it.size == count }` 护栏保留——它是"快照与真实列表不一致就不要用快照"的防御，桌面同样成立。

- [ ] **Step 5: 跑测试**

Run: `./gradlew -p desktop test --console=plain --tests "*QueueCoordinatorTest*"`
Expected: PASS（10 条）。

- [ ] **Step 6: 提交**

```bash
git add desktop/src/main/kotlin/com/music/bitchord/playback desktop/src/test/kotlin/com/music/bitchord/playback
git commit -m "feat(desktop): the queue coordinator, with a seven-member seam where Media3 sat"
```

---

### Task 3: 改播放机的队列动作 + REPEAT_ALL 历史轮转（行为证明）

**Files:**
- Modify: `desktop/src/test/kotlin/com/music/bitchord/playback/FakeQueueHost.kt`（加 `repeatMode` 与 `trimHistory()`）
- Modify: `desktop/src/test/kotlin/com/music/bitchord/playback/QueueCoordinatorTest.kt`（追加用例）

**Interfaces:**
- Consumes: Task 2 的 `QueueHost` + `QueueCoordinator` 三个改播放机函数；Task 1 的 `queueHistoryTrimCount` / `skippedByQueueJump` / `MAX_QUEUE_HISTORY`。
- Produces: `clearUserQueue` / `consumePlayedUserQueue` / `jumpToQueueItem` / 历史轮转的行为证明。Task 5 的 `QueueTimeline` 按这些已证语义实现，Task 7 的 `PlayerGeometryTest` 引用这里的常量。

- [ ] **Step 1: 给假宿主加历史窗口**

在 `FakeQueueHost` 里加（这就是 `PlaybackService.kt:2712-2724` 的桌面直译，Task 5 会写一份一模一样的在真宿主上）：

```kotlin
    var repeatMode: Int = RepeatMode.OFF

    /** PlaybackService.kt:2712-2724: under repeat-all, expired history rotates to the tail. */
    fun trimHistory() {
        val expired = queueHistoryTrimCount(currentIndex)
        if (expired <= 0) return
        if (repeatMode == RepeatMode.ALL) repeat(expired) { items.add(items.removeAt(0)) }
        else repeat(expired) { items.removeAt(0) }
        currentIndex -= expired
    }
```

同一文件里补两处 Task 2 评审留下的不一致（都是"假宿主必须像真宿主"这一件事）：

```kotlin
    override fun replaceRange(from: Int, to: Int, songs: List<Song>) {
        val replaced = to - from
        repeat(replaced) { items.removeAt(from) }
        items.addAll(from, songs)
        // Same shifting rule as removeAt, and the same guard as the real host's
        // replaceRange in QueueTimeline: only an edit behind the needle moves it.
        // shuffle's reorder is always ahead and always equal-size, so this is a
        // no-op there — but the fake must not be the only place that knows what
        // a real host does.
        if (from < currentIndex) {
            currentIndex = (currentIndex + songs.size - replaced)
                .coerceIn(0, (items.size - 1).coerceAtLeast(0))
        }
    }
```

并把这条契约写进接缝本身而不是只写在假宿主里——`QueueHost.removeAt` 与 `replaceRange` 的 KDoc 各加一句：**"removing or shrinking rows behind the playhead must move the playhead down; ExoPlayer does this implicitly"**。Task 5/6 的真宿主是实现这个接口而不是实现这个假类，契约不在接口上就没人读得到。

- [ ] **Step 2: 追加失败测试**

在 `QueueCoordinatorTest.kt` 末尾追加：

```kotlin
    @Test
    fun `clearing the queue leaves the playing row and the context rows`() {
        val host = FakeQueueHost(
            listOf(song("now"), song("u1", QueueTier.USER_QUEUE), song("c1"), song("u2", QueueTier.USER_QUEUE)),
        )
        QueueCoordinator.clearUserQueue(host, host::tierAt)
        assertEquals(listOf("now", "c1"), host.items.map { it.videoId })
    }

    @Test
    fun `entering context drops the user rows that already played`() {
        val host = FakeQueueHost(
            listOf(song("u0", QueueTier.USER_QUEUE), song("now"), song("c1")), currentIndex = 1,
        )
        QueueCoordinator.consumePlayedUserQueue(host, host::tierAt)
        assertEquals(listOf("now", "c1"), host.items.map { it.videoId })
        assertEquals(0, host.currentIndex)
    }

    @Test
    fun `consume leaves the user queue ahead of the playhead alone`() {
        val host = FakeQueueHost(
            listOf(song("now"), song("ahead", QueueTier.USER_QUEUE)), currentIndex = 0,
        )
        QueueCoordinator.consumePlayedUserQueue(host, host::tierAt)
        assertEquals(listOf("now", "ahead"), host.items.map { it.videoId })
    }

    @Test
    fun `a forward jump through the host re-seats the timeline and plays`() {
        val host = FakeQueueHost(
            listOf(song("now"), song("skip", QueueTier.USER_QUEUE), song("target")),
        )
        QueueCoordinator.jumpToQueueItem(host, targetIndex = 2, cachedTimeline = host.items.toList())
        // history = (0..currentIndex) = [now]; newUpcoming = [target, skip] (the USER_QUEUE
        // row survives, buildJumpQueue:257-276); newTargetIndex = history.size = 1.
        assertEquals(listOf("now", "target", "skip"), host.items.map { it.videoId })
        assertEquals(1, host.currentIndex)
        assertEquals(listOf("target"), host.played)
    }

    @Test
    fun `a backward jump seeks in place and never rebuilds the list`() {
        val host = FakeQueueHost(listOf(song("a"), song("b"), song("c")), currentIndex = 2)
        QueueCoordinator.jumpToQueueItem(host, targetIndex = 0, cachedTimeline = host.items.toList())
        assertEquals(listOf("a", "b", "c"), host.items.map { it.videoId })
        assertEquals(0, host.currentIndex)
        assertEquals(listOf("a"), host.played)
    }

    @Test
    fun `history beyond the window rotates to the tail under repeat-all`() {
        val host = FakeQueueHost((1..27).map { song("t$it") }, currentIndex = 26)
        host.repeatMode = RepeatMode.ALL
        host.trimHistory()
        assertEquals(25, host.currentIndex)
        assertEquals(27, host.items.size)
        assertEquals("t2", host.items.first().videoId)
        assertEquals("t1", host.items.last().videoId)
    }

    @Test
    fun `history beyond the window is deleted when repeat is off`() {
        val host = FakeQueueHost((1..27).map { song("t$it") }, currentIndex = 26)
        host.trimHistory()
        assertEquals(26, host.items.size)
        assertEquals(25, host.currentIndex)
        assertEquals("t2", host.items.first().videoId)
    }

    @Test
    fun `nothing is trimmed until the playhead passes the window`() {
        val host = FakeQueueHost((1..26).map { song("t$it") }, currentIndex = 25)
        host.trimHistory()
        assertEquals(26, host.items.size)
        assertEquals(25, host.currentIndex)
    }

    @Test
    fun `the history window is the app's own number`() {
        assertEquals(25, MAX_QUEUE_HISTORY)
        assertEquals(0, queueHistoryTrimCount(currentIndex = 25))
        assertEquals(1, queueHistoryTrimCount(currentIndex = 26))
    }

    @Test
    fun `a jump that only steps over the next row has nothing to delete`() {
        assertNull(skippedByQueueJump(currentIndex = 3, targetIndex = 4))
        // `(currentIndex + 1) until targetIndex` — QueueHistory.kt:18, carried verbatim —
        // is 4..5 for 3 → 6, matching the original's own shape of 3..6 for 2 → 7
        // (app/src/test/java/com/music/bitchord/QueueHistoryTest.kt:23).
        assertEquals(4..5, skippedByQueueJump(currentIndex = 3, targetIndex = 6))
    }
```

Run: `./gradlew -p desktop test --console=plain --tests "*QueueCoordinatorTest*"`
Expected: 前四条若 Task 2 做对了就直接通过；`trimHistory` 三条与 `a forward jump…` 可能失败。**逐条读断言消息**：凡是"删多了 / 删少了 / 下标没修正"，回去读 `PlaybackService.kt:2712-2724` 的 `moveMediaItem(0, mediaItemCount - 1)` 循环语义，别调期望值凑过。`jumpToQueueItem` 的 forward 分支要注意：`newPlaylist = history + newUpcoming`、`newTargetIndex = history.size`，而 `history = (0..currentIndex)`（`:315`）——本例 `currentIndex` 是 0，所以 history 只有 `[now]` 一条、`newTargetIndex == 1`，不是 2。

- [ ] **Step 3: 记录一处刻意的合并（写进交付物，不隐藏）**

原版有**两套**跳转剪枝：`QueueCoordinator.jumpToQueueItem`（点队列行），以及 `PlaybackService.kt:6527-6571` session 侧的 `seekTo(mediaItemIndex, positionMs)`（通知栏 / Android Auto 的跳转，用 `skippedByQueueJump` + 按目标 tier 决定删哪些 + `moveMediaItem` 把目标挪到紧邻下一位）。桌面本切片只有"点队列行"一个入口，**只搬前一套**；后一套属"外部遥控"，随切片 6 的媒体会话一起来。在 `QueueCoordinator.kt` 的 `jumpToQueueItem` KDoc 末尾补一句说明，Task 13 写进 spec 的偏离记录。`skippedByQueueJump` 因此暂时没有生产调用者——照搬保留（它只有 2 行，是 `MAX_QUEUE_HISTORY` 同族的原版语义档案）。

- [ ] **Step 4: 全量测试 + 提交**

Run: `./gradlew -p desktop test --console=plain`
Expected: BUILD SUCCESSFUL。

```bash
git add desktop/src/test/kotlin/com/music/bitchord/playback desktop/src/main/kotlin/com/music/bitchord/playback/QueueCoordinator.kt
git commit -m "test(desktop): prove the queue's mutating half, including repeat-all rotation"
```

---

### Task 4: `QueueShuffle` 就地置换 + `AppSettings` 落盘

**Files:**
- Copy+Modify: `app/.../playback/QueueShuffle.kt` → `desktop/src/main/kotlin/com/music/bitchord/playback/QueueShuffle.kt`
- Modify: `desktop/src/main/kotlin/com/music/bitchord/data/settings/AppSettings.kt`（现 65 行，纯内存）
- Test: `desktop/src/test/kotlin/com/music/bitchord/data/settings/AppSettingsTest.kt`
- Test: `desktop/src/test/kotlin/com/music/bitchord/playback/QueueShuffleTest.kt`（源自 120 行）
- Test: `desktop/src/test/kotlin/com/music/bitchord/playback/QueueShuffleTierTest.kt`（源自 135 行）
- Modify: `desktop/src/main/kotlin/com/music/bitchord/desktop/Main.kt`（启动时把落盘的开关交回 `QueueShuffle`）

**Interfaces:**
- Consumes: Task 2 的 `QueueHost`。
- Produces:
  - `object QueueShuffle { val enabled: StateFlow<Boolean>; fun setEnabled(enabled: Boolean); fun toggle(host: QueueHost); fun enableForNextQueue(); fun startingOrder(songs: List<Song>, startIndex: Int): List<Song>; internal fun restoreOrder(upcoming: List<String>, original: List<String>): List<Int>; internal fun avoidIdentityShuffle(original: List<Int>, shuffled: List<Int>): List<Int> }`
  - `AppSettings.shuffleEnabled: MutableStateFlow<Boolean>` / `setShuffleEnabled(Boolean)` / `AppSettings.repeatMode: MutableStateFlow<Int>` / `setRepeatMode(Int)`（两者**写盘**，key 与原版 `AppSettings.kt:1957-1958` 逐字一致）

- [ ] **Step 1: 先给 `AppSettings` 加落盘**（`QueueShuffle` 依赖它才能编译）

`FileStore` 只有 String/Long 两对访问器，布尔与 Int 走 `putString` 转换即可，**不要为此扩 FileStore**（YAGNI，且扩了就得重测那个被 InnerTubeX 依赖的文件）。`AppSettings.kt` 加：

```kotlin
    /** The original keeps these in SharedPreferences (`bitchord_settings`); here it is one file. */
    private val prefs: FileStore by lazy { FileStore(AppFiles.file("settings.properties")) }

    val shuffleEnabled = MutableStateFlow(prefs.getString(KEY_SHUFFLE_ENABLED, "false") == "true")
    val repeatMode = MutableStateFlow(prefs.getString(KEY_REPEAT_MODE, "0").toIntOrNull() ?: RepeatMode.OFF)

    fun setShuffleEnabled(value: Boolean) {
        shuffleEnabled.value = value
        prefs.putString(KEY_SHUFFLE_ENABLED, value.toString())
    }

    fun setRepeatMode(value: Int) {
        repeatMode.value = value
        prefs.putString(KEY_REPEAT_MODE, value.toString())
    }

    private const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"
    private const val KEY_REPEAT_MODE = "repeat_mode"
```

需要 import `com.music.bitchord.data.AppFiles` / `com.music.bitchord.data.FileStore` / `com.music.bitchord.playback.RepeatMode`，两个 key 字符串与安卓版 `AppSettings.kt:1957-1958` 逐字一致。

- [ ] **Step 2: 写失败测试**

`desktop/src/test/kotlin/com/music/bitchord/data/settings/AppSettingsTest.kt`：

```kotlin
package com.music.bitchord.data.settings

import com.music.bitchord.data.AppFiles
import com.music.bitchord.data.FileStore
import com.music.bitchord.playback.RepeatMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class AppSettingsTest {

    /**
     * A throwaway file, reopened as a second process would. Not `AppFiles.root` —
     * a unit test must not leave a real `settings.properties` behind in the user's
     * app directory, and `AppSettings`' own `prefs` must stay untouched so the
     * Task 5 and Task 6 cases read the defaults they assume.
     */
    private fun tempFile(): File =
        File.createTempFile("bitchord-settings-test", ".properties").apply { deleteOnExit() }

    @Test
    fun `a boolean round-trips through the file it lives in`() {
        val file = tempFile()
        FileStore(file).putString("shuffle_enabled", "true")
        assertTrue(FileStore(file).getString("shuffle_enabled", "false") == "true")
        FileStore(file).putString("shuffle_enabled", "false")
        assertFalse(FileStore(file).getString("shuffle_enabled", "true") == "true")
    }

    @Test
    fun `repeat mode reads back as the number it was written as`() {
        val file = tempFile()
        FileStore(file).putString("repeat_mode", RepeatMode.ALL.toString())
        assertEquals(
            RepeatMode.ALL,
            FileStore(file).getString("repeat_mode", "0").toIntOrNull(),
        )
    }

    @Test
    fun `the keys are the ones the Android app persists under`() {
        val file = tempFile()
        val store = FileStore(file)
        store.putString("shuffle_enabled", "true")
        store.putString("repeat_mode", "1")
        val reread = FileStore(file)
        assertEquals("true", reread.getString("shuffle_enabled", "false"))
        assertEquals("1", reread.getString("repeat_mode", "0"))
    }
}
```

需 import `java.io.File`（`FileStore` / `RepeatMode` 已在上面）。**不要**在这三个用例里读写 `AppSettings` 单例本身——它的 `prefs` 指向用户目录，测试动了就会污染 Task 5/6 的默认假设。三个用例证明的是：键名与安卓版一致、布尔与 Int 的字符串编码能过"`FileStore` 只有 String/Long"这一层转换、以及重开文件读得到。

（`FileStore` 每次构造都重读文件，所以"写完用新实例读"就是持久化的直接证明。）

再搬两份 shuffle 测试：

```bash
for t in QueueShuffleTest QueueShuffleTierTest; do
  cp "app/src/test/java/com/music/bitchord/$t.kt" "desktop/src/test/kotlin/com/music/bitchord/playback/$t.kt"
done
```

- `QueueShuffleTest.kt` 只改包名（→ `com.music.bitchord.playback`，`queueStartIndex` 同包够得着）与断言 import。它测的全是纯函数（`restoreOrder` / `avoidIdentityShuffle` / `queueStartIndex`），**期望值不改**。
- `QueueShuffleTierTest.kt` 需要一处实质替换：它用 `toMediaItem()` + `MediaItem.queueEntryId` 构造队列，桌面**没有**这两个符号 → 改成直接 `Song(queueEntryId = "entry-$id", queueTier = tier)` 列表喂 `QueueShuffle.startingOrder(...)`，断言"USER_QUEUE 钉在最前、CONTEXT 与 AUTOPLAY 各自打乱、当前曲在头"这些**顺序期望一个都不改**。

- [ ] **Step 3: 运行确认失败**

Run: `./gradlew -p desktop test --console=plain --tests "*QueueShuffle*"`
Expected: 编译失败——`QueueShuffle` 未解析。

- [ ] **Step 4: 搬 `QueueShuffle.kt`，删跨进程那一整条**

```bash
cp app/src/main/java/com/music/bitchord/playback/QueueShuffle.kt desktop/src/main/kotlin/com/music/bitchord/playback/
```

改动清单（对应 spec §4 风险表 B 组，逐条落实）：

| 原 | 桌面 |
|---|---|
| 删 `:3-8` 六个 import：`android.os.Bundle`、`androidx.core.os.bundleOf`、`androidx.media3.common.MediaItem`、`.Player`、`.session.MediaController`、`.session.SessionCommand` | 保留 `QueueTier` / `Song` / `AppSettings` / 三个 `kotlinx.coroutines.flow.*` |
| `fun toggle(player: Player)`（`:50-54`） | `fun toggle(host: QueueHost)`，转调 `shuffle(host)` / `restore(host)`，`AppSettings.setShuffleEnabled(_enabled.value)` 原样 |
| `fun enableForNextQueue()`（`:61-65`） | **不动**（零 player 调用） |
| `fun startingOrder(songs, startIndex)`（`:72-79`） | **一字不动**——本来就是纯 `Song` 版本 |
| `private fun shuffle(player: Player)`（`:88-108`） | 参数换 `host`。`:89` `player.queueItems()` → `host.snapshot()`（下方新加的扩展）；`:90` `it.queueEntryId ?: it.mediaId` → `it.queueEntryId ?: it.videoId`（与 `:73` 的 Song 版统一成同一个表达式）；`:91` `player.currentMediaItemIndex + 1` → `host.currentIndex + 1`；`:98-100` `upcoming[it].queueTier` 不变（现在直接读 `Song.queueTier`）；`:93-96` "upcoming 为空就只置位返回"的早退连同注释一起留；其余照抄 |
| `private fun restore(player: Player)`（`:125-138`） | 同上换 host，控制流不动 |
| `internal fun restoreOrder(...)`（`:156-170`） | **不动**（纯函数，含那段解释 multimap 为什么存在的 KDoc） |
| `private fun sections(order, upcoming: List<MediaItem>)`（`:173-176`） | 第二参数类型改 `List<Song>`，**三行函数体一字不动** |
| `private fun applyOrder(player, from, order)`（`:200-213`） | **整块替换**：删 `if (player is MediaController) sendCustomCommand(SessionCommand(ACTION_REORDER_QUEUE, Bundle.EMPTY), bundleOf(...))` 分支，只留 else 的语义（`reorder(...)`）。`:191-198` 那段"Media3 会把 MediaItem 的 localConfiguration 抹掉，所以只能传下标"的 KDoc **保留**，末尾补一句：桌面就地应用，那趟往返不存在 |
| `fun reorderFromCommand(player: Player, args: Bundle)`（`:216-220`） | **删**（连同本文件对 `ACTION_REORDER_QUEUE` / `EXTRA_REORDER_*` 的引用；这些常量定义在 `PlaybackService.kt:214-218`，不搬） |
| `private fun reorder(player, from, order: IntArray)`（`:230-234`） | 换 host：`from + order.size > host.itemCount` 的**整体拒绝**护栏保留（`:222-229` KDoc 也保留——它解释"为什么不部分应用"，桌面同样成立）；`:232` → `List(order.size) { host.songAt(from + order[it])!! }`；`:233` → `host.replaceRange(from, from + order.size, target)` |
| `private fun Player.queueItems(): List<MediaItem>`（`:236-237`） | 改成 `private fun QueueHost.snapshot(): List<Song> = List(itemCount) { songAt(it)!! }` |

- [ ] **Step 5: 启动时把开关交回内核**

`Main.kt` 在 `val player = PlayerController(scope)`（`:48`）**之前**加一行，对应原版 `PlaybackService.kt:5285` 的 `QueueShuffle.setEnabled(AppSettings.shuffleEnabled.value)`：

```kotlin
    // Same as the Android service does on create: the object may already hold a value, so
    // assign both true and false rather than only turning it on.
    QueueShuffle.setEnabled(AppSettings.shuffleEnabled.value)
```

- [ ] **Step 6: 跑测试 + Android 符号核查**

Run: `./gradlew -p desktop test --console=plain --tests "*QueueShuffle*" --tests "*AppSettingsTest*"`
Expected: PASS（两份搬过来的用例 + 2 条设置用例）。
Run: `./gradlew -p desktop test --console=plain && grep -rn "androidx\.media3\|^import android\." desktop/src/main/kotlin/com/music/bitchord/playback/ ; echo "grep-exit:$?"`
Expected: BUILD SUCCESSFUL 且 grep 无输出（`grep-exit:1`）——这是 spec §4 第一行"编译期无 `androidx.media3` / `android.*` 符号"的机械证据。

- [ ] **Step 7: 提交**

```bash
git add desktop/src/main/kotlin/com/music/bitchord/playback/QueueShuffle.kt desktop/src/main/kotlin/com/music/bitchord/data/settings/AppSettings.kt desktop/src/main/kotlin/com/music/bitchord/desktop/Main.kt desktop/src/test/kotlin
git commit -m "feat(desktop): shuffle in place, and the two settings that now outlive the session"
```

---

### Task 5: `QueueTimeline` —— 队列推进的纯状态机（新写）

**Files:**
- Create: `desktop/src/main/kotlin/com/music/bitchord/playback/QueueTimeline.kt`
- Test: `desktop/src/test/kotlin/com/music/bitchord/playback/QueueTimelineTest.kt`

**Interfaces:**
- Consumes: Task 1-4 全部（`QueueHost`、`QueueCoordinator.*`、`QueueShuffle`、`queueHistoryTrimCount`、`RepeatMode`、`MAX_QUEUE_HISTORY`）。
- Produces: `class QueueTimeline : QueueHost`：
  - `override val itemCount: Int` / `override var currentIndex: Int`（`private set`）/ `songAt` / `removeAt` / `replaceRange` / `setTimeline` / `jumpTo` / `playCurrent`
  - `fun snapshot(): List<Song>`
  - `var repeatMode: Int` / `var onSeek: (Song, Long) -> Unit` / `var onPlay: (Song) -> Unit` / `var onChanged: () -> Unit`
  - `fun start(timeline: List<Song>, startIndex: Int): String?`
  - `fun playFrom(newContextSongs: List<Song>, selectedIndex: Int, source: QueueSource): String?`
  - `fun startOneOff(tappedSong: Song, source: QueueSource): String?`
  - `fun enqueueNext(song: Song)` / `fun enqueueLast(song: Song)`
  - `fun onFinished(): String?` / `fun next(): String?` / `fun previous(positionMs: Long): String?`
  - `fun toggleShuffle()` / `fun clearUserQueue()` / `fun jumpToRow(targetIndex: Int)` / `fun removeRow(index: Int)` / `fun moveRow(from: Int, to: Int)`
  - `companion object { const val BACK_RESTARTS_AFTER_MS = 10_000L }`

- [ ] **Step 1: 写失败测试**

这是本切片唯一需要新写的队列状态。spec §3.2 的四条语义各至少一条用例，外加"推进只走一个入口"与"历史窗口"。**不需要假时钟**：推进是纯下标计算，直接喂 `onFinished()` 断言返回的 videoId 序列（spec §4 第二行的验证手段就是这个）。

```kotlin
package com.music.bitchord.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueTimelineTest {

    private fun song(id: String, tier: QueueTier = QueueTier.CONTEXT) = Song(
        videoId = id, title = "T$id", artist = "A$id", thumbnailUrl = null, queueTier = tier,
    )
    private fun rows(vararg ids: String, tier: QueueTier = QueueTier.CONTEXT) =
        ids.map { song(it, tier).asQueueEntry(tier) }
    private fun started(
        vararg ids: String,
        tier: QueueTier = QueueTier.CONTEXT,
        startIndex: Int = 0,
    ): QueueTimeline = QueueTimeline().apply { start(rows(*ids, tier = tier), startIndex) }
    private val search = QueueSource("S", PlaybackSourceType.SEARCH)

    /** playFrom consults the shuffle flag, so every case starts from a known order. */
    @BeforeTest
    fun shuffleOff() {
        QueueShuffle.setEnabled(false)
    }
```

`asQueueEntry` 是 `object QueueCoordinator` 的成员扩展，测试文件必须显式 `import com.music.bitchord.playback.QueueCoordinator.asQueueEntry`（同包也不会自动进作用域）——这是搬运安卓测试时最容易撞上的一个编译错。

接着是各条用例：

```kotlin
    @Test
    fun `finishing walks forward and stops at the tail`() {
        val t = started("a", "b", "c")
        assertEquals("b", t.onFinished())
        assertEquals("c", t.onFinished())
        assertNull(t.onFinished())
        assertEquals("c", t.songAt(t.currentIndex)?.videoId)
    }

    @Test
    fun `repeat-one replays the same row`() {
        val t = started("a", "b")
        t.repeatMode = RepeatMode.ONE
        assertEquals("a", t.onFinished())
        assertEquals(0, t.currentIndex)
    }

    @Test
    fun `repeat-all wraps at the tail`() {
        val t = started("a", "b")
        t.repeatMode = RepeatMode.ALL
        assertEquals("b", t.onFinished())
        assertEquals("a", t.onFinished())
    }

    @Test
    fun `back restarts past ten seconds and steps before it`() {
        val t = started("a", "b", "c", startIndex = 1)
        assertEquals("b", t.previous(positionMs = QueueTimeline.BACK_RESTARTS_AFTER_MS + 1))
        assertEquals(1, t.currentIndex)
        assertEquals("a", t.previous(positionMs = 2_000L))
        assertEquals(0, t.currentIndex)
    }

    @Test
    fun `back at the head with nothing behind it does nothing`() {
        val t = started("a", "b")
        assertNull(t.previous(positionMs = 0L))
        assertEquals(0, t.currentIndex)
    }

    @Test
    fun `advancing has exactly one entry point`() {
        val t = started("a", "b", "c")
        val moves = mutableListOf<String?>()
        t.onChanged = { moves += t.songAt(t.currentIndex)?.videoId }
        t.onFinished()
        // assertEquals cannot infer one T across List<String> and MutableList<String?>.
        assertEquals(listOf<String?>("b"), moves)
    }

    @Test
    fun `a context queue keeps rows the user queued ahead of the playhead`() {
        val t = started("a", "b")
        t.enqueueLast(song("u1"))
        val startedId = t.playFrom(listOf(song("c1"), song("c2")), selectedIndex = 1, source = search)
        assertEquals("c2", startedId)
        assertEquals(listOf("c1", "c2", "u1"), t.snapshot().map { it.videoId })
        assertEquals(1, t.currentIndex)
    }

    @Test
    fun `a one-off tap replaces the context and keeps the user queue`() {
        val t = started("a", "b")
        t.enqueueLast(song("u"))
        t.startOneOff(song("tap"), source = search)
        assertEquals(listOf("tap", "u"), t.snapshot().map { it.videoId })
        assertEquals(0, t.currentIndex)
    }

    @Test
    fun `play next lands after the current row, add to queue joins the user block`() {
        val t = started("a", "b", "c")
        t.enqueueNext(song("n"))
        assertEquals(listOf("a", "n", "b", "c"), t.snapshot().map { it.videoId })
        t.enqueueLast(song("q"))
        // findUserQueueInsertionIndex returns the first non-USER row after the
        // playhead (QueueCoordinator:145-148), so user rows form one block
        // directly behind the current track rather than going to the list tail.
        assertEquals(listOf("a", "n", "q", "b", "c"), t.snapshot().map { it.videoId })
        assertEquals(QueueTier.USER_QUEUE, t.snapshot()[2].queueTier)
    }

    @Test
    fun `history beyond the window rotates rather than vanishing under repeat-all`() {
        val t = QueueTimeline()
        t.repeatMode = RepeatMode.ALL
        t.start((1..27).map { song("t$it").asQueueEntry(QueueTier.CONTEXT) }, startIndex = 0)
        repeat(26) { t.onFinished() }
        assertEquals(27, t.itemCount)
        assertEquals(25, t.currentIndex)
        assertEquals("t2", t.snapshot().first().videoId)
        assertEquals("t1", t.snapshot().last().videoId)
    }

    @Test
    fun `history beyond the window is dropped when repeat is off`() {
        val t = QueueTimeline()
        t.start((1..27).map { song("t$it").asQueueEntry(QueueTier.CONTEXT) }, startIndex = 0)
        repeat(26) { t.onFinished() }
        assertEquals(26, t.itemCount)
        assertEquals(25, t.currentIndex)
        assertEquals("t2", t.snapshot().first().videoId)
    }

    @Test
    fun `removing a row behind the playhead does not move the playhead`() {
        val t = started("a", "b", "c", startIndex = 2)
        t.removeRow(0)
        assertEquals(1, t.currentIndex)
        assertEquals("c", t.songAt(t.currentIndex)?.videoId)
    }

    @Test
    fun `removing the current row is refused`() {
        val t = started("a", "b", "c", startIndex = 1)
        t.removeRow(1)
        assertEquals(listOf("a", "b", "c"), t.snapshot().map { it.videoId })
    }

    @Test
    fun `shuffle leaves the current row and pins the user queue at the head`() {
        val t = QueueTimeline()
        t.start(
            listOf(
                song("now"), song("c1"), song("c2"), song("c3"),
                song("u1", QueueTier.USER_QUEUE),
            ).map { it.asQueueEntry(it.queueTier) },
            startIndex = 0,
        )
        t.toggleShuffle()
        // QueueShuffle.shuffle builds order as userQueueIndices + shuffledSection
        // (context) + shuffledSection(autoplay) — the user row therefore lands in
        // slot from = currentIndex + 1 no matter how the context rows permute.
        assertEquals("now", t.songAt(0)?.videoId)
        assertEquals("u1", t.songAt(1)?.videoId)
        assertEquals(5, t.itemCount)
        assertEquals(0, t.currentIndex)
        // toggle() persists through AppSettings.setShuffleEnabled(true), which writes
        // the real settings.properties. Put the default back so a test run does not
        // leave the user's next session starting shuffled.
        AppSettings.setShuffleEnabled(false)
        QueueShuffle.setEnabled(false)
    }

    @Test
    fun `starting a queue while shuffle is on leads with the picked row`() {
        QueueShuffle.setEnabled(true)
        val t = QueueTimeline()
        val startedId = t.playFrom(
            listOf(song("c1"), song("c2"), song("c3")), selectedIndex = 2, source = search,
        )
        // startingOrder puts songs[startIndex] first, and queueStartIndex then says
        // the playhead is 0 rather than 2 — the two halves of PlayerConnection:762-771
        // that must always move together.
        assertEquals("c3", startedId)
        assertEquals(0, t.currentIndex)
        assertEquals("c3", t.songAt(0)?.videoId)
        assertEquals(3, t.itemCount)
        QueueShuffle.setEnabled(false)
    }
}
```

`toggleShuffle()` 会经 `QueueShuffle.toggle` 走到 `AppSettings.setShuffleEnabled(true)`，也就是真的写一次 `settings.properties` —— 上面用例末尾那两行把它还原回默认，`@BeforeTest` 再把内存标志钉成关。**不要**为了测试给 `QueueTimeline` 加公开写方法，也**不要**给 `AppSettings` 加"重置"API。

Run: `./gradlew -p desktop test --console=plain --tests "*QueueTimelineTest*"`
Expected: 编译失败——`QueueTimeline` 未解析。

- [ ] **Step 2: 写 `QueueTimeline.kt`**

设计要点（写进文件头注释，因为它回答"为什么桌面多了一个原版没有的文件"）：原版这段状态住在 ExoPlayer 里，所以它需要 MediaController、session 和每个 tier 一个 Bundle；桌面把列表握在自己进程，于是 `QueueCoordinator` / `QueueShuffle` 的改播放机函数直接跑在这个对象上，而 ExoPlayer 原本隐式做的"播完往下走"必须写出来。

```kotlin
package com.music.bitchord.playback

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry

class QueueTimeline : QueueHost {

    companion object {
        /** Past this point in a track, back restarts it instead of skipping. `PlaybackService.kt:157`. */
        const val BACK_RESTARTS_AFTER_MS = 10_000L
    }

    private val items = mutableListOf<Song>()
    override var currentIndex: Int = 0
        private set
    override val itemCount: Int get() = items.size

    var repeatMode: Int = RepeatMode.OFF
    var onSeek: (Song, Long) -> Unit = { _, _ -> }
    var onPlay: (Song) -> Unit = {}
    // Fires per list edit, not per act: consumePlayedUserQueue and clearUserQueue loop over
    // host.removeAt, and each removeAt publishes. So one advance over a queue with k consumed USER
    // rows publishes k+1 times, and clearing the queue publishes once per row cleared. The consumer's
    // publish() is idempotent (a whole PlayerState written into a MutableStateFlow), so the extra
    // calls are absorbed; do not batch them to match the act. The invariant is the list and the
    // needle, not the publication count.
    var onChanged: () -> Unit = {}

    override fun songAt(index: Int): Song? = items.getOrNull(index)
    /**
     * A member here rather than of [QueueHost]: this object *is* the list, so the copy is one call,
     * while every caller that reaches the queue through the seam has to build it from [songAt] —
     * which is what `QueueShuffle`'s private extension of the same name does.
     */
    fun snapshot(): List<Song> = items.toList()

    override fun removeAt(index: Int) {
        items.removeAt(index)
        if (index < currentIndex) currentIndex -= 1
        onChanged()
    }

    override fun replaceRange(from: Int, to: Int, songs: List<Song>) {
        val replaced = to - from
        repeat(replaced) { items.removeAt(from) }
        items.addAll(from, songs)
        // Only an edit behind the needle moves it. ExoPlayer leaves
        // currentMediaItemIndex alone when items ahead of it change size, and
        // shuffle's reorder is always ahead and always equal-size — so guarding
        // this is what keeps a future unequal-size edit from teleporting the
        // playhead.
        if (from < currentIndex) {
            currentIndex = (currentIndex + songs.size - replaced)
                .coerceIn(0, (items.size - 1).coerceAtLeast(0))
        }
        onChanged()
    }

    override fun setTimeline(songs: List<Song>, startIndex: Int) {
        items.clear(); items.addAll(songs)
        currentIndex = startIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
        onChanged()
    }

    /** Jump and make it sound — the seekTo + play (+ prepare) pair from `jumpToQueueItem` folded into one. */
    override fun jumpTo(index: Int, positionMs: Long) {
        val song = items.getOrNull(index) ?: return
        currentIndex = index
        onChanged()
        onSeek(song, positionMs)
        onPlay(song)
    }

    override fun playCurrent() { items.getOrNull(currentIndex)?.let(onPlay) }

    // ---- entry points -------------------------------------------------------------

    fun start(timeline: List<Song>, startIndex: Int): String? {
        if (timeline.isEmpty()) return null
        setTimeline(timeline, startIndex)
        return items[currentIndex].videoId
    }

    /**
     * The desktop form of `MediaController.playSongs` (`PlayerConnection.kt:757-774`),
     * minus the MediaItems. Kept as its own function because the shuffle decision
     * belongs here and nowhere else: a queue started while shuffle is on goes in
     * shuffled, with the picked row moved to the head, rather than being played out
     * of order. `queueStartIndex` is the other half of that and must stay paired
     * with `startingOrder` — one moves the row, the other moves the playhead.
     */
    private fun playSongs(songs: List<Song>, startIndex: Int): String? {
        if (songs.isEmpty()) return null
        val shuffled = QueueShuffle.enabled.value
        val queue = if (shuffled) {
            QueueShuffle.startingOrder(songs, startIndex.coerceIn(songs.indices))
        } else {
            songs
        }
        return start(queue, queueStartIndex(startIndex, queue.size, shuffled))
    }

    fun playFrom(newContextSongs: List<Song>, selectedIndex: Int, source: QueueSource): String? {
        if (newContextSongs.isEmpty()) return null
        val result = QueueCoordinator.buildContextQueue(
            currentTimeline = snapshot(), currentIndex = currentIndex,
            newContextSongs = newContextSongs, selectedIndex = selectedIndex, contextSource = source,
        )
        return playSongs(result.timeline, result.startIndex)
    }

    fun startOneOff(tappedSong: Song, source: QueueSource): String? =
        playSongs(
            QueueCoordinator.buildOneOffQueue(snapshot(), currentIndex, tappedSong, source),
            startIndex = 0,
        )

    fun enqueueNext(song: Song) = insert(song, isNext = true)
    fun enqueueLast(song: Song) = insert(song, isNext = false)

    private fun insert(song: Song, isNext: Boolean) {
        val at = QueueCoordinator.findUserQueueInsertionIndex(snapshot(), currentIndex, isNext)
        items.add(at, song.asQueueEntry(QueueTier.USER_QUEUE))
        onChanged()
    }

    fun toggleShuffle() = QueueShuffle.toggle(this)
    fun clearUserQueue() = QueueCoordinator.clearUserQueue(this, ::tierAt)
    private fun tierAt(index: Int): QueueTier = items.getOrNull(index)?.queueTier ?: QueueTier.CONTEXT

    fun jumpToRow(targetIndex: Int) =
        QueueCoordinator.jumpToQueueItem(this, targetIndex, cachedTimeline = snapshot())

    // Two refusals, and both are this function's business: the row under the needle, and an index the
    // list no longer has. removeAt forwards straight to items.removeAt, which throws, while
    // trimHistory shrinks the head of the list from inside the pump — so a row the queue panel drew
    // at index 26 can be past the end by the time its delete button is clicked. moveRow, jumpTo and
    // jumpToQueueItem each already guard their index; this was the one that did not.
    fun removeRow(index: Int) {
        if (index in items.indices && index != currentIndex) removeAt(index)
    }

    fun moveRow(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from))
        // `to` is where the moved row ends up. The needle moves in two steps — the removal shifts
        // it down, the insertion shifts it up — and the two cancel out when both are behind it.
        currentIndex = if (from == currentIndex) {
            to
        } else {
            val afterRemoval = if (from < currentIndex) currentIndex - 1 else currentIndex
            if (to <= afterRemoval) afterRemoval + 1 else afterRemoval
        }
        onChanged()
    }

    // ---- the pump -----------------------------------------------------------------

    /**
     * End of media, and the only place that decides what plays next (spec §4). Repeat-one
     * replays in place; otherwise walk forward; wrap under repeat-all; stop at the tail.
     */
    fun onFinished(): String? {
        if (items.isEmpty()) return null
        if (repeatMode == RepeatMode.ONE) return items[currentIndex].videoId
        if (currentIndex + 1 < items.size) { currentIndex += 1; return afterMoved() }
        if (repeatMode == RepeatMode.ALL) { currentIndex = 0; return afterMoved() }
        return null
    }

    // The next button. Under repeat-all the tail wraps to the head, exactly as onFinished() does:
    // the original gets this for free from ExoPlayer — the session's transport hands the press to
    // the player itself (PlaybackService.kt:6573-6591 -> seekToNextMediaItem()) and the enabled state
    // comes from player.hasNextMediaItem() (PlayerConnection.kt:305-306), an answer from the player
    // rather than an index comparison, which is what PlayerState's carried KDoc means by "the
    // wrap-around of repeat-all is already accounted for". Here the answer has to be computed, and an
    // enabled glyph that returns nothing is the dead button spec §2 rules out. The rule copied is
    // Media3's own Player.REPEAT_MODE_* contract: repeat-all gives Next/Previous "looping at the ends
    // so that Next when playing the last MediaItem will move to the first", and repeat-one's "behave
    // as they do in REPEAT_MODE_OFF … doing nothing when there is no previous or next". So repeat-one
    // gets no wrap — there the loop belongs to the current row, which onFinished answers in place —
    // and with repeat off the tail is a stop.
    fun next(): String? {
        if (items.isEmpty()) return null
        if (currentIndex + 1 < items.size) { currentIndex += 1; return afterMoved() }
        if (repeatMode == RepeatMode.ALL) { currentIndex = 0; return afterMoved() }
        return null
    }

    // The back button: the restart rule keeps precedence, then step back, then — under repeat-all
    // only — wrap from the head to the tail. Both the step and the wrap leave through afterMoved(),
    // so the history trim and the USER pruning are applied whichever way the needle moved; a
    // single-row queue wraps onto itself rather than throwing or spinning.
    fun previous(positionMs: Long): String? {
        if (items.isEmpty()) return null
        if (positionMs > BACK_RESTARTS_AFTER_MS) return items[currentIndex].videoId
        if (currentIndex > 0) { currentIndex -= 1; return afterMoved() }
        if (repeatMode == RepeatMode.ALL) { currentIndex = items.size - 1; return afterMoved() }
        return null
    }

    private fun afterMoved(): String {
        trimHistory()
        QueueCoordinator.consumePlayedUserQueue(this, ::tierAt)
        onChanged()
        return items[currentIndex].videoId
    }

    /** `PlaybackService.kt:2712-2724`, verbatim in shape. */
    private fun trimHistory() {
        val expired = queueHistoryTrimCount(currentIndex)
        if (expired <= 0) return
        if (repeatMode == RepeatMode.ALL) repeat(expired) { items.add(items.removeAt(0)) }
        else repeat(expired) { items.removeAt(0) }
        currentIndex -= expired
    }
}
```

`QueueShuffle.toggle(this)` 的签名没有 `tierAt`（Task 4 里 `shuffle`/`restore` 直接从 `Song.queueTier` 读），与 `QueueCoordinator` 那两个显式传 `tierAt` 的函数不同——这是有意的：`QueueCoordinator` 的 `tierAt` 默认值原本是 `player::queueTierAt`，删掉后必须由调用方给；`QueueShuffle` 本来就在读 item 的 tier，换成 `Song` 之后不需要注入。

- [ ] **Step 3: 跑测试**

Run: `./gradlew -p desktop test --console=plain --tests "*QueueTimelineTest*"`
Expected: PASS（本计划 15 条；实落 25 条 —— 另加 `next()`、`moveRow()`、跳转回调三条覆盖本任务声明却未被这 15 条调用到的成员，再加评审的七条：`removeRow` 越界拒绝、一次推进按编辑发布（USER 行被逐行消耗的那条路径）、REPEAT_ALL 下 `next()`/`previous()` 的回绕各一端、REPEAT_ONE 仍是普通步进、单行队列不抛不转、回绕同样走 `afterMoved()` 的剪枝）。两条 `history beyond the window …` 若失败，先量 `currentIndex` 修正与 `queueHistoryTrimCount` 的关系（`afterMoved()` 里 `trimHistory()` 与 `consumePlayedUserQueue` 谁先动下标），**别改期望值**。

- [ ] **Step 4: 全量测试 + 提交**

Run: `./gradlew -p desktop test --console=plain`
Expected: BUILD SUCCESSFUL。

```bash
git add desktop/src/main/kotlin/com/music/bitchord/playback/QueueTimeline.kt desktop/src/test/kotlin/com/music/bitchord/playback/QueueTimelineTest.kt
git commit -m "feat(desktop): the queue pump, written out where ExoPlayer used to do it silently"
```

---

### Task 6: `PlayerController` 升级成队列播放器（含引擎接缝与预取）

**Files:**
- Modify: `desktop/src/main/kotlin/com/music/bitchord/desktop/playback/PlayerController.kt`（现 125 行，重写主体）
- Create: `desktop/src/main/kotlin/com/music/bitchord/desktop/playback/AudioEngine.kt`
- Modify: `desktop/src/main/kotlin/com/music/bitchord/desktop/playback/VlcAudioPlayer.kt`（加 `override`，实现不动）
- Test: `desktop/src/test/kotlin/com/music/bitchord/desktop/playback/FakeAudioEngine.kt`
- Test: `desktop/src/test/kotlin/com/music/bitchord/desktop/playback/PlayerControllerTest.kt`

**Interfaces:**
- Consumes: Task 5 的 `QueueTimeline`；Task 1 的 `PlayerState` / `PlaybackPosition` / `RepeatMode`；已有 `StreamResolver.resolve(videoId): String`（`:258`）+ `mediaHeadersFor(url)`（`:512`）；`SystemClock.elapsedRealtime()`；`AppSettings.shuffleEnabled/repeatMode`。
- Produces（字段名与原版 `PlayerState` 对齐，spec §3.1 的要求，Task 10 的 36 个参数靠这个名字表一一对上）：
  - `interface AudioEngine { var onTime/onLength/onPlayingChanged/onFinished/onError; val isPlaying: Boolean; val timeMs: Long; fun play(url: String, headers: Map<String,String> = emptyMap()); fun pause(); fun resume(); fun stop(); fun seekTo(fraction: Float); fun setVolume(percent: Int); fun release() }`
  - `class PlayerController(scope: CoroutineScope, engine: AudioEngine = VlcAudioPlayer())`
  - `val state: StateFlow<PlayerState>` / `val position: PlaybackPosition` / `val volume: StateFlow<Int>` / `val status: StateFlow<String?>` / `val shuffleEnabled: StateFlow<Boolean>`
  - `fun playFrom(songs: List<Song>, index: Int, source: PlaybackSourceType, sourceTitle: String, sourceId: String?)` / `fun playOneOff(song: Song, source: PlaybackSourceType, sourceTitle: String, sourceId: String?)` / `fun play(song: Song)`（保留，等价于 `playOneOff`）/ `fun playCollection(browseId: String, label: String)`
  - `fun togglePlayPause()` / `fun next()` / `fun previous()` / `fun seekTo(ms: Long)` / `fun seekToFraction(f: Float)` / `fun cycleRepeat()` / `fun setRepeat(mode: Int)` / `fun toggleShuffle()` / `fun enqueueNext(song)` / `fun enqueueLast(song)` / `fun jumpTo(index: Int)` / `fun removeFromQueue(index: Int)` / `fun moveInQueue(from: Int, to: Int)` / `fun clearQueue()` / `fun setVolume(percent: Int)` / `fun release()`
  - `var resolveUrl: suspend (String) -> String`（测试缝隙）/ `var onPrefetch: (suspend (String) -> Unit)?`

- [ ] **Step 1: 抽 `AudioEngine`**

`VlcAudioPlayer` 现有五个回调 + 七个方法就是桌面播放需要的全部；提成接口，`PlayerController` 依赖接口，JVM 测试才能不装 VLC 跑。

```kotlin
// The slice-1 VLC wrapper, seen as an interface, so the queue can be tested without VLC.
interface AudioEngine {
    var onTime: ((Long) -> Unit)?
    var onLength: ((Long) -> Unit)?
    var onPlayingChanged: ((Boolean) -> Unit)?
    var onFinished: (() -> Unit)?
    var onError: ((String) -> Unit)?
    val isPlaying: Boolean
    val timeMs: Long
    fun play(url: String, headers: Map<String, String> = emptyMap())
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(fraction: Float)
    fun setVolume(percent: Int)
    fun release()
}
```

`VlcAudioPlayer` 只改类声明为 `class VlcAudioPlayer : AudioEngine` 并给五个 `var`、`isPlaying`、`timeMs` 与七个函数加 `override`；**实现体一行不动**（`spec §3.2` 的推进就挂在这个 `onFinished` 上）。

- [ ] **Step 2: 写失败测试**

`FakeAudioEngine.kt`：

```kotlin
package com.music.bitchord.desktop.playback

class FakeAudioEngine : AudioEngine {
    override var onTime: ((Long) -> Unit)? = null
    override var onLength: ((Long) -> Unit)? = null
    override var onPlayingChanged: ((Boolean) -> Unit)? = null
    override var onFinished: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var isPlaying: Boolean = false
    override var timeMs: Long = 0L
    val playedUrls = mutableListOf<String>()

    /** What libvlc's `finished` event does: clear playing, then tell us. */
    fun finish() {
        isPlaying = false; onPlayingChanged?.invoke(false); onFinished?.invoke()
    }
    fun tick(ms: Long) { timeMs = ms; onTime?.invoke(ms) }

    override fun play(url: String, headers: Map<String, String>) {
        playedUrls += url; isPlaying = true; onPlayingChanged?.invoke(true)
    }
    override fun pause() { isPlaying = false; onPlayingChanged?.invoke(false) }
    override fun resume() { isPlaying = true; onPlayingChanged?.invoke(true) }
    override fun stop() { isPlaying = false; onPlayingChanged?.invoke(false) }
    override fun seekTo(fraction: Float) {}
    override fun setVolume(percent: Int) {}
    override fun release() {}
}
```

`PlayerControllerTest.kt`（scope 与 `resolveDispatcher` 都用 `Dispatchers.Unconfined`，让解析协程当场跑完，于是**不需要** `kotlinx-coroutines-test`；光有 Unconfined 的 scope 不够——`withContext(Dispatchers.IO)` 仍会真跳线程，断言就会读到空引擎）：

```kotlin
package com.music.bitchord.desktop.playback

import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.playback.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerControllerTest {

    private fun song(id: String) = Song(id, "T$id", "A$id", null)

    /** A controller whose stream resolution is instant: this test is about the queue, not the network. */
    private fun controller(): Pair<PlayerController, FakeAudioEngine> {
        val engine = FakeAudioEngine()
        // QueueShuffle.enabled is process-wide and Task 5's cases flip it; playSongs
        // consults it, so a case that expects list order must pin it off first.
        QueueShuffle.setEnabled(false)
        val player = PlayerController(CoroutineScope(Dispatchers.Unconfined), engine)
        player.resolveUrl = { videoId -> "https://test/$videoId" }
        player.resolveDispatcher = Dispatchers.Unconfined
        return player to engine
    }

    @Test
    fun `finishing advances to the next row and plays it`() {
        val (player, engine) = controller()
        player.playFrom(listOf(song("a"), song("b"), song("c")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        assertEquals("https://test/a", engine.playedUrls.last())
        engine.finish(); assertEquals("https://test/b", engine.playedUrls.last())
        engine.finish(); assertEquals("https://test/c", engine.playedUrls.last())
        assertEquals(2, player.state.value.queueIndex)
        engine.finish()
        assertEquals(listOf("https://test/a", "https://test/b", "https://test/c"), engine.playedUrls)
        assertTrue(!player.state.value.isPlaying)
    }

    @Test
    fun `the state carries the names the original's PlayerState uses`() {
        val (player, _) = controller()
        player.playFrom(listOf(song("a"), song("b")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        val s = player.state.value
        assertEquals("a", s.song?.videoId)
        assertEquals(2, s.queue.size)
        assertEquals(0, s.queueIndex)
        assertEquals(false, s.hasPrevious)
        assertEquals(true, s.hasNext)
        assertEquals(RepeatMode.OFF, s.repeatMode)
        assertEquals(false, s.isQualityUpgraded)
    }

    @Test
    fun `repeat-all wraps and hasNext stays honest`() {
        val (player, engine) = controller()
        player.playFrom(listOf(song("a"), song("b")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        player.cycleRepeat(); assertEquals(RepeatMode.ALL, player.state.value.repeatMode)
        assertEquals(true, player.state.value.hasNext)
        engine.finish(); engine.finish(); engine.finish()
        assertEquals(1, player.state.value.queueIndex)
    }

    @Test
    fun `back past ten seconds replays the current row`() {
        val (player, engine) = controller()
        player.playFrom(listOf(song("a"), song("b")), index = 1,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        engine.tick(11_000L); player.previous()
        assertEquals(1, player.state.value.queueIndex)
        assertEquals(2, player.state.value.queue.size)
        engine.tick(2_000L); player.previous()
        assertEquals(0, player.state.value.queueIndex)
    }

    @Test
    fun `a queued row does not interrupt what is playing`() {
        val (player, engine) = controller()
        player.playOneOff(song("now"), PlaybackSourceType.SEARCH, "S", null)
        player.enqueueLast(song("later"))
        player.enqueueNext(song("nextone"))
        assertEquals(listOf("https://test/now"), engine.playedUrls)
        assertEquals(listOf("now", "nextone", "later"), player.state.value.queue.map { it.videoId })
        assertEquals(QueueTier.USER_QUEUE, player.state.value.queue[1].queueTier)
    }

    @Test
    fun `the row after the one that started is resolved ahead of time`() {
        val (player, _) = controller()
        val prefetched = mutableListOf<String>()
        player.onPrefetch = { videoId -> prefetched += videoId }
        player.playFrom(listOf(song("a"), song("b")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        assertEquals(listOf("b"), prefetched)
    }

    @Test
    fun `the last row prefetches nothing`() {
        val (player, _) = controller()
        val prefetched = mutableListOf<String>()
        player.onPrefetch = { videoId -> prefetched += videoId }
        player.playOneOff(song("solo"), PlaybackSourceType.SEARCH, "S", null)
        assertEquals(emptyList(), prefetched)
    }

    @Test
    fun `a jump from the queue panel plays the target row`() {
        val (player, engine) = controller()
        player.playFrom(listOf(song("a"), song("b"), song("c")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        player.jumpTo(2)
        // jumpToQueueItem keeps history up to the old playhead and re-seats the rest,
        // so the target lands at history.size == 1, not at 2 — the list becomes [a, c, …].
        assertEquals(1, player.state.value.queueIndex)
        assertEquals("https://test/c", engine.playedUrls.last())
    }

    @Test
    fun `volume and position reach the engine`() {
        val (player, engine) = controller()
        player.playOneOff(song("a"), PlaybackSourceType.SEARCH, "S", null)
        engine.tick(45_000L)
        assertEquals(45_000L, player.state.value.position.positionMs)
        assertEquals(45_000L, player.position.positionMs)
    }
}
```

Run: `./gradlew -p desktop test --console=plain --tests "*PlayerControllerTest*"`
Expected: 编译失败——`resolveUrl` / `onPrefetch` / `playFrom` / `state` 未解析。

- [ ] **Step 3: 重写 `PlayerController`**

保留切片 1 已有的对外语义（`togglePlayPause` / `seekToFraction` / `setVolume` / `release` / `status`），其余新增。骨架与必须写清的六点：

```kotlin
class PlayerController(
    private val scope: CoroutineScope,
    private val engine: AudioEngine = VlcAudioPlayer(),
) {
    private val queue = QueueTimeline()
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    val position = PlaybackPosition()
    private val _volume = MutableStateFlow(80)
    val volume: StateFlow<Int> = _volume.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()
    val shuffleEnabled: StateFlow<Boolean> = QueueShuffle.enabled

    /** Test seam. Production resolves through StreamResolver, whose cache already exists. */
    var resolveUrl: suspend (String) -> String = { StreamResolver.resolve(it) }
    /**
     * The blocking resolve hops to IO in production. Tests set this to
     * `Dispatchers.Unconfined` — `withContext(Dispatchers.IO)` does not run inline
     * even under an Unconfined scope, so without this seam every assertion right
     * after `play…()` would race the network coroutine and read an empty engine.
     */
    var resolveDispatcher: CoroutineDispatcher = Dispatchers.IO
    /** Handed the videoId of the row after the one that just started. */
    var onPrefetch: (suspend (String) -> Unit)? = null

    init {
        queue.onPlay = ::playCurrentRow
        queue.onSeek = { _, ms -> engine.seekTo(if (_state.value.durationMs > 0) ms.toFloat() / _state.value.durationMs.toFloat() else 0f) }
        queue.onChanged = ::publish
        engine.onTime = { ms -> position.positionMs = ms; publish() }
        engine.onLength = { _state.value = _state.value.copy(durationMs = it) }
        engine.onPlayingChanged = { _state.value = _state.value.copy(isPlaying = it) }
        engine.onError = { _status.value = it; _state.value = _state.value.copy(isPlaying = false) }
        engine.onFinished = { advance() }
        engine.setVolume(_volume.value)
    }
}
```

1. **推进只认一个入口**（spec §4 第 2 行）：`advance()` 是 `onFinished` 唯一下游。它先 `val id = queue.onFinished()`——这一步**只改列表**，同步、纯；拿到 id 才 `resolveAndPlay(id)`。拿到 null 就停在末尾（`isPlaying=false`、`isLoading=false`、`_status` 保持 null，因为"走到队尾"是正常结局不是错误，原版 `PlaybackService` 在这里也只是 idle）。
2. **"切下一首"与"解析下一首"分开**：`resolveAndPlay(videoId)` 先 `engine.stop()` + `position.positionMs = 0` + `isLoading = true`，再 `scope.launch { withContext(resolveDispatcher) { runCatching { resolveUrl(videoId) } } }`，成功 → `engine.play(url, StreamResolver.mediaHeadersFor(url))`，失败 → `_status.value = "resolve failed: …"`（文案沿用切片 1 的 `PlayerController.kt:76`）。原 `play(song)` 的主体搬进这里，改成接收 videoId。
3. **入队入口**：`playFrom(songs, index, source, sourceTitle, sourceId)` → `queue.playFrom(songs, index, QueueSource(sourceTitle, source, sourceId))` → `resolveAndPlay(returnedId)`；`playOneOff` 同理走 `queue.startOneOff`；`play(song)` 保留为 `playOneOff(song, PLAYBACK-from-current)` 的薄封装，别删（`HomePage.kt:61-68` / `SearchPage.kt:60` 现在就在调它）。
   `playCollection(browseId, label)` 改成：`browseSongs(browseId)` 取回曲目后 **`playFrom(songs, 0, PlaybackSourceType.BROWSE, label, browseId)`** —— 现在它只播第一首并丢弃其余（`PlayerController.kt:89-104`），那就是 CONTEXT 档的缺口，本任务补上（对应 spec §3.2"入队入口"）。
4. **上一首**：`previous()` → `queue.previous(engine.timeMs)`。**用 `engine.timeMs` 而不是 `position.positionMs`**：后者是插值过用于显示的，判定重播阈值要用 VLC 的实际位置。拿到 id 后 `resolveAndPlay(id)`；拿到 null（队首、未过阈值、且不是 REPEAT_ALL —— Task 5 之后队首在 REPEAT_ALL 下会回绕到队尾）就别动。
5. **repeat 循环**：`cycleRepeat()` 按原版 `MainActivity.kt:2083-2087` 的 OFF→ALL→OFF（安卓版也不循环 REPEAT_ONE，保持一致），写 `queue.repeatMode` 并 `AppSettings.setRepeatMode(...)`，再 `publish()`。
6. **hasNext / hasPrevious 从队列取**，不从 `queueIndex` 猜，且**只认 REPEAT_ALL**：`hasNext = queue.repeatMode == RepeatMode.ALL || index + 1 < size`、`hasPrevious = queue.repeatMode == RepeatMode.ALL || index > 0`。
   **这里刻意不用 `!= RepeatMode.OFF`。** 原版 `Player.hasPreviousMediaItem()` / `hasNextMediaItem()` 在 `REPEAT_ONE` 下就是"没有下一首"（Media3 的 `REPEAT_MODE_ONE` 文档原话：ends "behave as they do in REPEAT_MODE_OFF"），而 Task 5 的 `next()` 在该模式下同样返回 null。写成 `!= OFF` 会在最后一行亮起一个按下去什么都不做的按钮——正是本切片禁止的死按钮。与 `QueueTimeline` 的 wrap 条件必须逐字保持一致：**能回绕的模式才 enabled**。
   这条与原版 `PlayerConnection.kt:87-91` 的 KDoc（"taken from the player so the wrap-around of repeat-all is already accounted for"）同意。
7. **预取**（决策 7）：`resolveAndPlay` **成功起播之后**，取 `queue.songAt(currentIndex + 1)`，有就 `scope.launch { runCatching { onPrefetch?.invoke(id) ?: resolveUrl(id) } }`。`StreamResolver` 已按 videoId 缓存 20 分钟（`:1082-1084`）、已合并并发解析（`coalescedResolve`，`:386-416`），**所以预取不需要新缓存，只需要有人去调 `resolve`**。失败必须吞掉（`runCatching`），一次预取失败不该污染任何状态。`onPrefetch` 只是给测试留的断言点，生产路径默认 null。
8. **进度插值**（spec §4 最后一行）：`PlaybackPosition` 是 `@Stable` 可变对象，`onTime` 只写它的字段；`publish()` 用 `_state.value.copy(position = position)`，`position` 标识恒定不变，所以 `PlayerState` 不会因播放头整体失效——这正是原版把播放头单拎出来的理由（KDoc 已搬进 `PlayerState.kt`）。`publish()` 同时刷新 `song / queue / queueIndex / hasPrevious / hasNext / isLoading / repeatMode`。
9. **持久状态的还原放在 `Main.kt`，不放构造函数**。`PlayerController.init` **不读** `AppSettings.shuffleEnabled` / `repeatMode`——读盘的活由 `Main.kt` 在创建 player 之后做，对应原版 `PlaybackService.kt:5285-5286` 的 service-onCreate 还原。理由有两条：一是原版就还原在服务里而非播放器构造里；二是构造函数读盘会让单元测试互相污染——Task 5 的 `toggleShuffle()` 会真的把 `shuffle_enabled=true` 写进 `%LOCALAPPDATA%\BitChord\settings.properties`，若 init 读盘，后面每条用例都会从"洗牌开着"起步。
   在 `Main.kt` 的 `val player = PlayerController(scope)`（`:48`）**之后**加：

```kotlin
    // PlaybackService.kt:5286 restores repeat the same way; the shuffle half of that
    // pair is already applied above, before the player exists.
    player.setRepeat(AppSettings.repeatMode.value)
```

`setRepeat(mode: Int)` 只做 `queue.repeatMode = mode` + `publish()`，**不写盘**（写了会把默认值固化，抹掉"用户从没动过"这个状态）。

- [ ] **Step 4: 跑测试**

Run: `./gradlew -p desktop test --console=plain --tests "*PlayerControllerTest*"`
Expected: PASS（9 条）。第 1 条三次 `finish()` 后 `playedUrls` 恰好 3 个——**多出第 4 个就是双跳**（`onFinished` 被挂了不止一处，或 `previous()`/`next()` 也走了 advance 路径）；回到 `init` 检查。第 6 条若拿到空列表说明预取没接上或接在了 resolve 之前。

- [ ] **Step 5: 全量测试 + 编译 + 提交**

Run: `./gradlew -p desktop test --console=plain && ./gradlew -p desktop compileKotlin --console=plain`
Expected: 两条 BUILD SUCCESSFUL。

```bash
git add desktop/src/main/kotlin/com/music/bitchord/desktop/playback desktop/src/test/kotlin/com/music/bitchord/desktop
git commit -m "feat(desktop): a queue-aware player, and the read-ahead that makes it usable"
```

---

### Task 7: 播放器控件 `PlayerControls.kt` + 四个矢量图标

**Files:**
- Copy+Modify: `app/.../ui/player/PlayerControls.kt` → `desktop/src/main/kotlin/com/music/bitchord/ui/player/PlayerControls.kt`
- Copy: `app/.../ui/player/ThinSlider.kt` → `desktop/src/main/kotlin/com/music/bitchord/ui/player/ThinSlider.kt`
- Modify: `desktop/build.gradle.kts`（`syncAppStrings` 的 include 清单，现 `:92-101`）
- Modify: `desktop/src/main/kotlin/com/music/bitchord/data/SystemClock.kt`（加 `uptimeMillis()`）
- Test: `desktop/src/test/kotlin/com/music/bitchord/ui/player/PlayerGeometryTest.kt`（第一组常量）

**Interfaces:**
- Consumes: 桌面已有的 `Res.string.*` 管线（`syncAppStrings`，切片 1 已跑通）、`ui/haptics/Haptics`（`Haptic` 全词汇表已就位）、`AppSettings.effectiveAudioQuality`（`:39`）、`data/NerdStats.kt`（**只有 `pickedKbps`/`pickedSource`**，见下表 NerdStats 行）、`Res.drawable.*`。
- Produces（Task 10/11 的调用面，名字与原版一致）：`internal fun TransportRow(isPlaying, isLoading, previousEnabled, nextEnabled, onPrevious, onPlayPause, onNext, compact: Boolean = false)`、`internal fun PlayerScrubber(shown: () -> Float, durationMs: Long, loading: Boolean, transitionWindow: ClosedFloatingPointRange<Float>?, onScrub: (Float) -> Unit, onScrubFinished: () -> Unit, centerLabel: @Composable BoxScope.() -> Unit = {})`、`internal fun VolumeRow(value: () -> Float, onValueChange: (Float) -> Unit, onValueChangeFinished: () -> Unit)`、`internal fun PlaybackQualityLabel(song, isLoading, modifier)`、`internal fun MarqueeText(text, style, color, modifier, enabled, startDelayMillis, leading, onOverflowChange)`、`internal fun CircleGlyph(icon, contentDescription, onClick, active, haptic)`、`internal fun ExplicitBadge(color)`、`internal fun Modifier.bleedHorizontally(gutter: Dp): Modifier`、`internal fun Modifier.opensPage(browseId: String?, onOpen: (String) -> Unit): Modifier`、`@Composable internal fun playbackOriginText(song: Song): String`（`playedBy` 参数随一起听删）、`internal fun PlaybackOriginCaption(text, onClick, textAlign, contentPadding, modifier)`、`internal fun PlayerActionRow(queueOpen: Boolean, shuffleEnabled: Boolean, repeatMode: Int, onToggleQueue: () -> Unit, onToggleShuffle: () -> Unit, onCycleRepeat: () -> Unit)`（Step 2b：歌词/AUTOPLAY/输出/一起听六个参数随各自的族删，`onOpenMenu` 不属于这一行——菜单走 `CircleGlyph`，见 `NowPlayingScreen`），`internal val VOLUME_ROW_HEIGHT = 32.dp`。

- [ ] **Step 1: 把四个图标接进资源管线**

原版是 XML 矢量（已确认四个文件都在 `app/src/main/res/drawable/`），走 `ic_logo.xml` 那条已验证的管线。`desktop/build.gradle.kts` 的 `syncAppStrings`（`:92-101`）里那个 `from(...)` 块加四行：

```kotlin
        // The wordmark the top bar draws.
        include("drawable/ic_logo.xml")
        // The transport glyphs: vector drawables, same pipeline as the wordmark.
        include("drawable/ic_player_play.xml")
        include("drawable/ic_player_pause.xml")
        include("drawable/ic_player_next.xml")
        include("drawable/ic_player_previous.xml")
```

Run: `./gradlew -p desktop generateComposeResClass --console=plain && grep -rln "ic_player_play" desktop/build/generated/ | head -3`
Expected: 生成物里出现 `ic_player_play` 等四个访问器。消费方式与 `FrostedTopBar.kt:319` 的 `painterResource(Res.drawable.ic_logo)` 完全一致。

- [ ] **Step 2: 复制两文件，按报错逐类清 Android 符号**

```bash
cp app/src/main/java/com/music/bitchord/ui/player/PlayerControls.kt desktop/src/main/kotlin/com/music/bitchord/ui/player/
cp app/src/main/java/com/music/bitchord/ui/player/ThinSlider.kt desktop/src/main/kotlin/com/music/bitchord/ui/player/
```

编译循环：`./gradlew -p desktop compileKotlin --console=plain 2>&1 | grep "^e: " | sed 's|.*/desktop/src/main/kotlin/com/music/bitchord/||' | sort -u | head -30`。已知类别与处置：

| 报错 | 处置 |
|---|---|
| `androidx.media3.common.Player`（`:100`，来源是 `PlayerActionRow` 里 `repeatMode: Int` 与 `:549-566` 的常量比较） | 删 import；`Player.REPEAT_MODE_ALL` / `_OFF` / `_ONE` → `RepeatMode.ALL` / `OFF` / `ONE`（`com.music.bitchord.playback.RepeatMode`）。比较逻辑一字不改 |
| `androidx.annotation.DrawableRes` + `@DrawableRes icon: Int`（`:11, :658` `TransportGlyph`） | 参数改 `icon: DrawableResource`（`org.jetbrains.compose.resources.DrawableResource`）；四处 `R.drawable.ic_player_*`（`:391, 412, 421, 688`）→ `Res.drawable.ic_player_*`；`painterResource` 的 import 换 `org.jetbrains.compose.resources.painterResource` |
| `android.os.SystemClock`（`:10`，调用点 `:870, :1094` 用的是 `uptimeMillis()`） | import 换 `com.music.bitchord.data.SystemClock`，并在桌面 `SystemClock.kt` 加 `fun uptimeMillis(): Long = elapsedRealtime()`——保持调用点零改动，与该文件"保留安卓名字，让搬运文件与上游只差一行 import"的既有意图一致 |
| `android.media.AudioFormat`（`:9`，`:957-960` 的 `ENCODING_PCM_*` 实际编码判定） | 那是 Android 音频栈的输出格式，桌面没有对应物。**整段删**，不要改成"猜一个桌面等价物" |
| `NerdStats.Snapshot` / `NerdStats.current` / `NerdStats.racingLossless`（`:249-263`、`:279` `SleeveNerdStats`、`:1035`、`:1313-1408` `LosslessOrStats`、`:1535-1550` `describe(context)`） | **桌面 `data/NerdStats.kt` 只有 `pickedKbps(videoId)` 与 `pickedSource(videoId)` 两个查询——没有 `Snapshot`、没有 `current`、没有 `racingLossless`**（已核对，全 34 行）。`Snapshot`/`current`/`racingLossless` 是安卓侧跨进程与 lossless 竞速的产物，属切片 6。**处置：不新建 `Snapshot` 体系**（那等于自造一层原版数据结构，违反"改动集中在少量替换处"）。`SleeveNerdStats` 与 `LosslessOrStats` 保留外观与调用位置，但只渲染桌面确实知道的三件事：`AppSettings.effectiveAudioQuality` 的档位标签、`NerdStats.pickedKbps(song.videoId)` 解出来的码率、`pickedSource` 的来源名；`racing`/`stillRacing`/`nerdStats` 参数与 `describe(context)` 整条删。**措辞不得暗示验证过硬件输出或实际编码格式**（CONTRIBUTING.md "Audio Changes and Telemetry"）。删不掉的分支宁可少给，别给假数据。 |
| `LocalContext` / `context.getString`（`:87, 282, 1538-1550`） | 删；改 `stringResource(Res.string.*)` |
| 一起听族：`ListenTogether.State.badge()`（`:1014-1023`）、`rememberControlsLocked()`（`:1034-1042`）、`rememberPartyBadge()`（`:1051-1059`）、`OutputPartyPill`（`:794-832`）、`PillSegment` 的 party 分支、`PlayerActionRow` 的 `onListenTogether` / `onOpenListenTogetherMembers` / `onOpenOutput` 参数（`:497-499`）、`playbackOriginText(song, playedBy)` 的 `playedBy`（`:397-399`） | **整族删**（决策 6）。`PlayerActionRow` 保留 `queueOpen` / `shuffleEnabled` / `repeatMode` 与三个 callback，其余成员按原版顺序原样留 |
| `stringResource(R.string.*)`、`stringArrayResource(R.array.*)`、`R.plurals.*` | → `Res.string.*`，并按切片 1 已踩过的坑同时换 `stringResource` 的 import 为 `org.jetbrains.compose.resources.stringResource`，加 `import com.music.bitchord.desktop.resources.*`（通配符，否则每个键都要单独 import） |
| `AppSettings.audioQualityWifi` / `audioQualityCellular` / `meteredConnection`（`:249-263`） | 桌面 `AppSettings` 没这些字段；`PlaybackQualityLabel` 与 `LosslessOrStats` 只留 `effectiveAudioQuality` 一条路径，把 wifi/cellular 分支与 KDoc 里解释"为什么分档"的那段一起删 |

- [ ] **Step 2b: `PlayerActionRow` 的收口（本切片必须做的一个形状决定）**

删掉歌词、AUTOPLAY、音频输出、一起听四族之后，原版这一行的三个成员（左 `BottomGlyph` 歌词、中间胶囊、右 `BottomGlyph` 队列）只剩两个，而中间那个 `AnimatedContent(targetState = queueOpen)`（`:523`）存在的唯一理由是**在"队列模式三格胶囊"和"输出/派对两格胶囊"之间换**——后者整族删掉之后，这个切换只剩一个状态。已核对 `:484-604` 的正文，决定如下：

1. **`AnimatedContent` 与 `queueOpen` 分支整段删**，永远直接渲染队列模式胶囊。它上面的注释（"Unclipped… the capsule's own rounded ends are what the eye follows"）随它一起删——那是切换动画的注释，不是胶囊的。
2. **保留 `PillSegment` 的 `PILL_SEGMENT_WIDTH_TRIPLE = 52.dp`**，不要退回 `PILL_SEGMENT_WIDTH = 64.dp`。该常量的 KDoc（`:720-726`）自己说过 64 的间距"即使只有两个图标也显得空"；52 是作者为"眼睛看到的密度"调过的数，剩下 shuffle+repeat 两格时用它才维持原版的视觉密度。**`PILL_SEGMENT_WIDTH`（64）、`PILL_HEADPHONES_SIZE`（23）、`PILL_PARTY_SIZE`（22）随 `OutputPartyPill` 一起删**，`pillWidth(segments)` 留着（`widestRow` 用得到）。
3. **`widestRow` 重算**为 `BOTTOM_ACTION_SIZE + pillWidth(2)`（原来是 `BOTTOM_ACTION_SIZE * 2 + pillWidth(3)`），`edgeInset` 那行公式不动。结果：中间胶囊不再居中，两枚控件贴在内缩后的两侧。**这是一处可见的形状偏离，不是缺陷**——它是"歌词/AUTOPLAY/输出/一起听都不出现"的直接代价。留到 Task 13 的截图判读里给使用者看，并在 spec 的偏离记录里写明；**实施时不要为了让它看起来平衡而自造控件**（切片 1 的死按钮规矩）。
4. `BottomGlyph` 的 `label`/`tapWindowMs` 参数、`SHUFFLE_TAP_WINDOW_MS`、`PILL_*` 常量形状保持原样。
5. `playbackOriginText(song, playedBy)` 去掉 `playedBy` 参数（`:397-399`），文案里"谁点了这首歌"的分支删。

- [ ] **Step 2c: 其余逐类清 Android 符号**

处理 Step 2 的表以外，还需注意：`rememberControlsLocked()`/`ListenTogether.State.badge()`/`rememberPartyBadge()`/`OutputPartyPill`/`OutputCaption` 整族删（`:938-1059`），`AudioPipelineDialog`/`AudioOutputSheet`/`ListenTogetherMembersSheet` 的调用点一并删；这些都不出现，不是置灰。

**尺寸常量一个都不许改**（Task 13 几何量核对的对象）：`VOLUME_ROW_HEIGHT = 32.dp`（`:434`）、`PLAYER_SKIP_ICON_SIZE = 53.dp`（`:699`）、`PLAYER_SKIP_TOUCH_SIZE = 53.dp`（`:700`）、`PLAYER_SKIP_HEIGHT_SCALE = 0.85f`（`:706`）、`BOTTOM_ACTION_SIZE = 44.dp`（`:708`）、`PILL_SEGMENT_WIDTH_TRIPLE = 52.dp`（`:724`）、`PILL_ICON_SIZE = 24.dp`（`:737`）、`MARQUEE_DP_PER_SEC = 26f`（`:1132`）、`MARQUEE_GAP = 48.dp`（`:1135`）、`MARQUEE_REST_MS = 5_000L`（`:1138`）、`SHUFFLE_TAP_WINDOW_MS = 400L`（`:125`），以及 `TransportRow` 内联的 `playSize 58/74.dp`（`:380`）、`playTouch 76/92.dp`（`:381`）、spinner `strokeWidth 3.dp` / `size 30/38.dp`（`:406-407`）、`CircleGlyph` 的 34.dp 圆盘 / 19.dp 图标（`:624, 642-645`）。`PILL_SEGMENT_WIDTH`/`PILL_HEADPHONES_SIZE`/`PILL_PARTY_SIZE` 是 Step 2b 明确随输出胶囊删掉的三个例外。

- [ ] **Step 3: 补 `ThinSlider`**

`ThinSlider.kt`（284 行）里 `MixSheen`（`:246`）等是纯 Compose；预期只有 `android.os.SystemClock` 一类零星报错，按 Step 2 同一张表处置。`VolumeRow` 调它的 `idleHeight = 6.dp, activeHeight = 10.dp`（`PlayerControls.kt:462-463`）保持。

- [ ] **Step 4: 写第一组几何量测试**

`desktop/src/test/kotlin/com/music/bitchord/ui/player/PlayerGeometryTest.kt`（spec §5.4：移植后的 transport/scrubber 尺寸常量与原版逐字一致。**用断言钉，不用缩放过的截图当量具**）。本任务只断现在存在的符号；`PLAYER_MAX_WIDTH` / `PLAYER_GUTTER` / 横屏判定在 Task 8 追加，`LANDSCAPE_*` 在 Task 11 追加。

```kotlin
package com.music.bitchord.ui.player

import androidx.compose.ui.unit.dp
import com.music.bitchord.playback.MAX_QUEUE_HISTORY
import com.music.bitchord.playback.QueueTimeline
import com.music.bitchord.playback.RepeatMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Numbers the port is not allowed to invent. Each is the Android app's own; a drift
 * here means a ported file was rewritten rather than carried over — exactly the
 * failure mode decision 3 exists to prevent.
 */
class PlayerGeometryTest {

    @Test
    fun `transport sizes match the app's`() {
        assertEquals(32.dp, VOLUME_ROW_HEIGHT)
        assertEquals(44.dp, BOTTOM_ACTION_SIZE)
        assertEquals(53.dp, PLAYER_SKIP_ICON_SIZE)
        assertEquals(53.dp, PLAYER_SKIP_TOUCH_SIZE)
        assertEquals(0.85f, PLAYER_SKIP_HEIGHT_SCALE)
        assertEquals(52.dp, PILL_SEGMENT_WIDTH_TRIPLE)
        assertEquals(24.dp, PILL_ICON_SIZE)
    }

    @Test
    fun `marquee motion matches the app's`() {
        assertEquals(26f, MARQUEE_DP_PER_SEC)
        assertEquals(48.dp, MARQUEE_GAP)
        assertEquals(5_000L, MARQUEE_REST_MS)
    }

    @Test
    fun `the queue constants are the app's own numbers`() {
        assertEquals(25, MAX_QUEUE_HISTORY)
        assertEquals(10_000L, QueueTimeline.BACK_RESTARTS_AFTER_MS)
        assertEquals(0, RepeatMode.OFF)
        assertEquals(2, RepeatMode.ALL)
    }
}
```

Run: `./gradlew -p desktop test --console=plain --tests "*PlayerGeometryTest*"`
Expected: PASS（3 条）。若某条常量因 Step 2/2b 删族而被顺手删掉，先回到那张表确认它是**明确要删的三个**（`PILL_SEGMENT_WIDTH`/`PILL_HEADPHONES_SIZE`/`PILL_PARTY_SIZE`）之一；不在那三个里的就**把常量恢复**（`pillWidth(segments)` 还在用 `PILL_SEGMENT_WIDTH_TRIPLE`），不要为了让测试过去而删断言。

- [ ] **Step 5: 全量测试 + 提交**

Run: `./gradlew -p desktop test --console=plain && ./gradlew -p desktop compileKotlin --console=plain`
Expected: 两条 BUILD SUCCESSFUL。

```bash
git add desktop/build.gradle.kts desktop/src/main/kotlin/com/music/bitchord/ui/player desktop/src/main/kotlin/com/music/bitchord/data/SystemClock.kt desktop/src/test/kotlin/com/music/bitchord/ui/player
git commit -m "feat(desktop): port the player controls, on the app's own transport glyphs"
```

---

### Task 8: 队列面板 `PlayerQueue.kt`（三段分节 + 行内拖拽）+ 播放页常量先行

**Files:**
- Copy+Modify: `app/.../ui/player/PlayerQueue.kt` → `desktop/src/main/kotlin/com/music/bitchord/ui/player/PlayerQueue.kt`
- Create: `desktop/src/main/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt`（**只搬常量与几何函数**，正文留给 Task 10）
- Test: `desktop/src/test/kotlin/com/music/bitchord/ui/player/QueueEdgeScrollTest.kt`（源自 `app/src/test/java/com/music/bitchord/QueueEdgeScrollTest.kt`，109 行）
- Modify: `desktop/src/test/kotlin/com/music/bitchord/ui/player/PlayerGeometryTest.kt`（追加横屏判定）

**Interfaces:**
- Consumes: Task 7 的 `Modifier.bleedHorizontally`；桌面 `Song` / `QueueTier`；`Res.string.queue / now_playing / next_in_queue / next_from / autoplay / clear / drag_to_reorder / remove_from_queue`（**实测全部已在同步过来的 `strings.xml` 里**：行 160 / 125 / 126 / 127 / 379 / 97 / 428 / 621）；Task 1 的 `autoplaySectionStart`；`thumbnailBorder` / `ExplicitSongTitle` / `rememberRemoteArtworkUrl`（桌面都已有）。
- Produces: `@Composable internal fun InlineQueue(queue: List<Song>, currentIndex: Int, autoplayEnabled: Boolean, onJumpTo: (Int) -> Unit, onRemove: (Int) -> Unit, onMove: (Int, Int) -> Unit, onClear: () -> Unit, onScrollingChange: (Boolean) -> Unit = {}, onDragActiveChange: (Boolean) -> Unit = {}, collapsePlayerOnScroll: Boolean = false, onRevealPlayer: () -> Unit = {}, onHidePlayer: () -> Unit = {}, modifier: Modifier = Modifier)`、`internal fun edgeScrollSpeed(top, bottom, viewportStart, viewportEnd, zone, speed): Float`、`internal fun Modifier.fadingEdges(): Modifier`、`val LANDSCAPE_PLAYER_MIN_WIDTH = 560.dp`、`fun landscapePlayerAvailable(windowWidth: Dp, windowHeight: Dp): Boolean`、`val PLAYER_GUTTER = 30.dp`、`val PLAYER_MAX_WIDTH = 560.dp`。

- [ ] **Step 1: 先建 `NowPlayingScreen.kt` 的常量与几何（解 Task 8 与 Task 10 的循环依赖）**

`PLAYER_GUTTER` / `PLAYER_MAX_WIDTH` 定义在 `NowPlayingScreen.kt:284,291`，而 `PlayerQueue.kt` 要用它们——所以本任务先把常量层建立起来，正文留给 Task 10（Task 10 **追加**，不覆盖）。

新文件 `desktop/src/main/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt`，从 `app/.../ui/player/NowPlayingScreen.kt` 逐字搬这些常量（连同各自 KDoc）：

| 常量 | 原行 | 值 |
|---|---|---|
| `ART_PX = PLAYER_ART_PX` | `:173` | 1200 |
| `THUMB_SIZE` / `HEADER_HEIGHT` / `ART_TITLE_GAP` | `:214-216` | 54 / 60 / 20 dp |
| `QUEUE_TRAVEL_MS` / `QUEUE_CARRY_FRACTION` / `QUEUE_FLICK_VELOCITY` | `:225-242` | 420 / 0.3f / 450f |
| `DISMISS_STRIP_HEIGHT` / `ART_BOX_TOP_PAD` | `:250, 252` | 32 / 8 dp |
| `HERO_FADE_FRACTION` | `:267` | 0.42f |
| `PLAYER_GUTTER` / `PLAYER_MAX_WIDTH` | `:284, 291` | 30 / 560 dp |
| `TABLET_PLAYER_MIN_WIDTH` / `LANDSCAPE_PLAYER_MIN_WIDTH` | `:302, 316` | 700 / 560 dp |
| `ARTWORK_EXPANDED_SCALE` / `ARTWORK_PAUSE_SHRINK_SCALE` / `ARTWORK_DRAG_SHRINK_SCALE` / `ArtworkScaleEasing` / `ARTWORK_SCALE_DURATION_MS` | `:324-339` | 逐字 |
| `CONTROL_GAP_SPREAD_MAX` | `:349` | 24.dp |

以及四个几何函数（逐字，含 KDoc）：`landscapePlayerAvailable`（`:408-409`）、`playerFillsWindow`（`:380`）、`tabletSizedPlayer`（`:391`）、`fullBleedArtworkAvailable`（`:373`）。

**不搬**引用被删族的那些：`ART_RETRIES` / `ART_RETRY_DELAY_MS` / `ALBUM_SETTLE_MS` / `REVERT_CUE_MS` / `SEEK_SETTLE_*`（音频版本切换与音质，切片 6）、`VERSION_PILL_ART_INSET`（未被使用）、`SUBVIEW_STATUS_SCRIM_MIN_ALPHA` / `MESH_REFRESH_MS`（歌词/网格刷新）、`LYRICS_CONTROLS_IDLE_MS` / `SPOTIFY_CANVAS_*` / `SPOTIFY_DECK_TOP_FADE_FRACTION`（歌词与运动封面族）、`private var lastControlSpread`（文件级可变缓存，随控件正文来）。

- [ ] **Step 2: 复制 `PlayerQueue.kt` 并清符号**

```bash
cp app/src/main/java/com/music/bitchord/ui/player/PlayerQueue.kt desktop/src/main/kotlin/com/music/bitchord/ui/player/
```

改动清单（逐处，不重写）：

1. `import com.music.bitchord.playback.autoplaySectionStart`（`:80`）保留——它在桌面真的存在（Task 1），`:119` 的 KDoc 现在能解析了。
2. **`controlsLocked` 参数与 `locked` 分支整族删**（一起听）：`InlineQueue` 签名去掉 `controlsLocked`（`:215`）；`queueSection` 去掉 `locked`（`:373`）；`InlineQueueRow` 去掉 `locked`（`:698`）并把 `draggable = !locked`（`:390`）改成 `draggable = true`；`QueueClearButton` 去掉 `locked`（`:417`）；`:273-275` 与 `:317-319` 两处 Clear 的出现条件（`tracks.user.isNotEmpty()`）**保持**。
3. `keepScrollInList`（`:93-104`）保留函数本体，但 `InlineQueue` 里 `:290` 的 `nestedScroll(keepScroll)` 删（桌面没有 ModalBottomSheet 的抢占问题）；`controlsOnScroll`（`:291`）与 `collapsePlayerOnScroll` 分支**保留**——播放页"滚动时收起控件"是真的。
4. `stringResource(R.string.*)` → `Res.string.*`；`next_from`（`Next from: %1$s`）用 `stringResource(Res.string.next_from, title)`（桌面同用法先例：`HomeScreen.kt:510` `stringResource(Res.string.shelf_similar_to, rest)`）。
5. `AsyncImage` / `thumbnailBorder` / `ExplicitSongTitle` / `rememberRemoteArtworkUrl` / `BitChordIcons` / `Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = QUEUE_ROW_MOTION)`（`:203`）/ `QUEUE_ROW_MOTION = tween<IntOffset>(200, FastOutSlowInEasing)`（`:123`）/ `QUEUE_EDGE_SCROLL_ZONE = 40.dp`（`:471`）/ `QUEUE_EDGE_SCROLL_SPEED = 340.dp`（`:472`）/ 行几何（thumb 44.dp + 6.dp 圆角 `:746-748`、drag handle 20.dp + `offset(x = (-4).dp)` `:724-728`、`GraphicEq` 18.dp `:771`、remove 32.dp 圆 + 18.dp 图标 `:778,787`）——**全部逐字保留**。
6. `autoplayEnabled` 参数与 `AutoplayHeading`（`:430-486`）**保留**（spec §3.1 的"AUTOPLAY 槽位：类型、渲染、分档逻辑都在，只是本切片没有往里填东西的代码"）。它靠 `tracks.autoplay.isEmpty()` 走空态文案（`R.string.autoplay_empty_description`）；本切片队列里 autoplay 段恒空 → 该段标题按原版逻辑不出现。**这就是"AUTOPLAY 不出现"的实现方式：不写桩、不改渲染，只是没人往里填。**
7. `splitQueue`（`:173`）与 `QueueTracks`（`:156`）**保持 `private`**。安卓版 `QueueSectionsTest` / `QueueMigrationTest` 测的是内核函数 `autoplaySectionStart`（Task 1 已覆盖），**不是** `splitQueue`——不要为了让测试够到它而放宽可见性，那是与上游的无谓分叉。`splitQueue` 的分段正确性由 Task 13 的截图判读覆盖。

- [ ] **Step 3: 搬边缘滚动测试 + 补横屏判定断言**

```bash
cp app/src/test/java/com/music/bitchord/QueueEdgeScrollTest.kt desktop/src/test/kotlin/com/music/bitchord/ui/player/QueueEdgeScrollTest.kt
```

改包名 → `com.music.bitchord.ui.player`、断言 import → `kotlin.test.*`。`edgeScrollSpeed` 在原版是 `internal`（`:487`），同模块同包可见，**期望值不改**。

在 `PlayerGeometryTest.kt` 追加（spec §5.4 的横屏判定）：

```kotlin
    @Test
    fun `the landscape rule is the original's, not a desktop one`() {
        assertEquals(true, landscapePlayerAvailable(1180.dp, 780.dp))
        assertEquals(false, landscapePlayerAvailable(780.dp, 1180.dp))
        assertEquals(false, landscapePlayerAvailable(559.dp, 100.dp))
        assertEquals(true, landscapePlayerAvailable(560.dp, 100.dp))
    }

    @Test
    fun `the player column keeps the app's own width and gutter`() {
        assertEquals(560.dp, PLAYER_MAX_WIDTH)
        assertEquals(30.dp, PLAYER_GUTTER)
        assertEquals(700.dp, TABLET_PLAYER_MIN_WIDTH)
    }
```

Run: `./gradlew -p desktop test --console=plain --tests "*QueueEdgeScroll*" --tests "*PlayerGeometryTest*" && ./gradlew -p desktop compileKotlin --console=plain`
Expected: PASS（`PlayerGeometryTest` 5 条 + edge-scroll 那批）+ BUILD SUCCESSFUL。

- [ ] **Step 4: 记录鼠标拖拽的待实测项（不先改设计）**

spec §4 第 3 行的对策：原版行内拖拽是 `pointerInput { detectDragGestures(...) }` 挂在 20.dp 的 handle 上（`:728-738`），`QueueDrag`（`:553-688`）用 `listState.layoutInfo.visibleItemsInfo` 找交换目标并带边缘自动滚动。这套代码在桌面鼠标下**能编译、能触发**，要实测的是两点：拖动启动阈值（触屏有 touch slop，鼠标可能移动一像素就算拖）与 20.dp 命中区对手指偏小、对鼠标同样偏小。**处置原则**：实测不一致就调 `detectDragGestures` 的启动与 handle 的命中 padding，不改分段设计；改动写进 Task 13 的偏离记录。置换算法本身已由 Task 5 的 `moveRow` 证明，这里只测手势。

- [ ] **Step 5: 提交**

```bash
git add desktop/src/main/kotlin/com/music/bitchord/ui/player desktop/src/test/kotlin/com/music/bitchord/ui/player
git commit -m "feat(desktop): the inline queue, three sections and all"
```

---

### Task 9: 播放页底图层 `ArtworkMeshBackdrop.kt`

**Files:**
- Copy+Modify: `app/.../ui/player/ArtworkMeshBackdrop.kt`（749 行）→ `desktop/src/main/kotlin/com/music/bitchord/ui/player/ArtworkMeshBackdrop.kt`
- Modify: `desktop/src/main/kotlin/com/music/bitchord/ui/theme/SkiaPixels.kt`（加 `scaledArgb` helper）

**Interfaces:**
- Consumes: `Bitmap.argbPixels()`（`SkiaPixels.kt:15`，已有）、`ui/theme/ColorMath.kt` 的 `colorToHsl` / `hslToColor` / `relativeLuminance`、`coil3` + `PlatformContext.INSTANCE`（`MeshGradient.kt:225` 已跑通）、`AppSettings.reduceDynamicBlur`（`:50`）、`CARD_ART_PX` / `artworkAt`（`Models.kt`）。
- Produces: `@Composable fun rememberFullArtworkBlurImage(imageUrl: String?, artPx: Int = PLAYER_ART_PX, prepare: Boolean = true): ImageBitmap?`、`@Composable fun FullArtworkBlurBackdrop(image: ImageBitmap?, modifier: Modifier = Modifier)`、`@Composable fun rememberArtworkMesh(imageUrl: String?, canvasFrame: Any? = null, artPx: Int = CARD_ART_PX): ArtworkMesh?`、`@Composable fun ArtworkMeshBackdrop(mesh: ArtworkMesh?, seam: Dp = 0.dp, modifier: Modifier = Modifier)`、`class ArtworkMesh internal constructor(...)`。

**为什么这两个必须搬**：spec §3.3 要求的图层顺序是 `MeshGradientBackground → ArtworkMeshBackdrop → FullArtworkBlurBackdrop → hero 封面`，而 `ArtworkMeshBackdrop` / `FullArtworkBlurBackdrop` 在桌面**根本不存在**（切片 1 的 plan Task 5 列了它，实际没搬）。桌面目前只有 `MeshGradientBackground` 与 `rememberArtworkColors`（后者至今无调用者），四层里缺两层。

- [ ] **Step 1: 复制，逐个替换 Android 位图操作**

```bash
cp app/src/main/java/com/music/bitchord/ui/player/ArtworkMeshBackdrop.kt desktop/src/main/kotlin/com/music/bitchord/ui/player/
```

`android.*` / `androidx.*` 一共 6 个 import，逐个处置：

| 原 | 桌面替代 |
|---|---|
| `import android.graphics.Bitmap`（`:3`） | `org.jetbrains.skia.Bitmap`（与 `SkiaPixels.kt:3` / `MeshGradient.kt` 一致） |
| `import android.os.Build`（`:4`，`Build.VERSION.SDK_INT` 的模糊能力判断） | 删判断，走"无 RenderEffect"那条路径（本切片仍走非玻璃路径，与切片 1 一致） |
| `import androidx.core.graphics.ColorUtils`（`:36`） | `colorToHSL` / `blendARGB` / `HSVToColor` → `ui/theme/ColorMath.kt` 的 `colorToHsl` / `hslToColor`；`blendARGB` 用本文件已有的 private `lerpArgb(from, to, t)`（`:717`）替，**不新建公共 API** |
| `import androidx.lifecycle.compose.collectAsStateWithLifecycle`（`:37`） | `androidx.compose.runtime.collectAsState` |
| `import coil3.request.allowHardware`（`:41`） | 删该调用（桌面 Skia 解码没有 hardware bitmap 概念；`MeshGradient.kt:226-229` 的 `ImageRequest` 就不带它） |
| `LocalContext.current` / `SingletonImageLoader.get(context)`（`:31, 113`） | `coil3.PlatformContext.INSTANCE`，两处都换——`MeshGradient.kt:225` 与 `ArtworkPalette.kt:154` 已是这个写法 |

位图读写：

- `(result as? SuccessResult)?.image?.toBitmap()`（`:117`）在桌面返回 `org.jetbrains.skia.Bitmap`，直接喂 `argbPixels()`（已有 helper），**不需要新代码**。
- `bitmap.boxBlurred(FULL_BLUR_PASSES).asImageBitmap()`（`:118-119`）：**删掉手写像素盒式模糊**（`boxBlurred` `:445-459` + `boxBlurPass` `:460-506`，约 62 行），`rememberFullArtworkBlurImage` 直接返回 `toBitmap().asImageBitmap()`，糊度改由 Compose 承担——`FullArtworkBlurBackdrop` 的 `Image(...)`（`:157-162`）加 `Modifier.blur(48.dp)`（该文件已经 import `androidx.compose.ui.draw.blur`（`:21`）并在 mesh 分支上用它）。原版手写像素模糊的理由是低版本 Android 没有 `Modifier.blur`；桌面 CMP 1.12.1 有真实 Skia 实现。**这一步必须实测确认糊度存在**（Task 13 Step 3 的截图判读项之一）。若画出来毫无变化，回退方案是把 `boxBlurPass` 按 `IntArray` 重写（`argbPixels()` 进、`IntArray` 出），那时才需要 IntArray→`ImageBitmap` 的 helper——**先按 `Modifier.blur` 走，别提前写回退**。
- `Bitmap.createScaledBitmap(...)` 与 `getPixels` / `setPixels`：在 `SkiaPixels.kt` 加最近邻取样 helper，纯 IntArray，不碰 Skia 写像素（Skia `Bitmap` 只有 `installPixels(byte[])` / `allocN32Pixels`，没有 `writePixels(IntArray)`；刻意避开）：

```kotlin
/**
 * Nearest-neighbour resample of an ARGB int array — the form the mesh and the
 * blur source read. Kept in IntArray space on purpose: Skia's Bitmap has no
 * writePixels(IntArray), and the Android original never needed one either.
 */
internal fun scaledArgb(src: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray =
    IntArray(dstW * dstH) { i ->
        val x = (i % dstW) * srcW / dstW
        val y = (i / dstW) * srcH / dstH
        src[(y * srcW + x).coerceIn(src.indices)]
    }
```

- `meshOf(source: Bitmap, seed: Int)`（`:594`）改成 `meshOf(pixels: IntArray, width: Int, height: Int, seed: Int)`，内部三步（`resampled` `:691` / `rotatedBelowSeam` `:671` / 36 格网格构造）是纯 IntArray 算法，**逐字保留**；`IntArray.resampled(cols, rows, size)`（`:691`）里若用了 `createScaledBitmap`，换成上面的 `scaledArgb`。
- `smoothstep`（`:712`）、`argb(red, green, blue)`（`:728`）、`Int.lifted()`（`:741`）、`FallbackBackdrop = Color(0xFF121212)`（`:433`）、两个 LRU `LinkedHashMap`（`fullBlurCache` `:440`、`meshCache` `:507`）——**全部原样**。
- `rememberArtworkMesh(canvasFrame: Bitmap?)` 的参数改成 `Any? = null`，且**只保留 null 路径**（运动封面不做，spec §3.3 末条）；参数留着是为了 Task 10 的调用点 `rememberArtworkMesh(remoteArt, canvasFrame, ART_PX)` 能逐字照搬。`:186-200` 那段解释"canvasFrame 会接管取色"的 KDoc 保留，并注明桌面暂无帧来源（`FrameHeuristics.kt:17` 的 `isLikelyBlackFrame` 已在桌面等着，但没有生产者）。

- [ ] **Step 2: 编译 + 起窗口确认没崩**

Run: `./gradlew -p desktop compileKotlin --console=plain 2>&1 | grep "^e: " | head -20`
Expected: 无输出。
Run: `./gradlew -p desktop run -Pbitchord.autoExitMs=12000 --console=plain`
Expected: 窗口正常打开、主页照常渲染（本任务还没有调用者，只要不引入编译/初始化崩溃即可）。真正的视觉验证在 Task 13。

- [ ] **Step 3: 提交**

```bash
git add desktop/src/main/kotlin/com/music/bitchord/ui/player/ArtworkMeshBackdrop.kt desktop/src/main/kotlin/com/music/bitchord/ui/theme/SkiaPixels.kt
git commit -m "feat(desktop): the player's two backdrop layers, on Skia not android.graphics"
```

---

### Task 10: `NowPlayingScreen.kt` 正文（竖屏分支 + 参数收口）

**Files:**
- Copy+Modify: `desktop/src/main/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt`（追加正文；常量层已在 Task 8 Step 1 建立）
- 对照源：`app/.../ui/player/NowPlayingScreen.kt`（3096 行）

**Interfaces:**
- Consumes: Task 7 全部控件、Task 8 的 `InlineQueue`、Task 9 两层底图、Task 1 `PlayerState` / `PlaybackPosition` / `RepeatMode`、桌面 `MeshGradientBackground` + `rememberArtworkColors`（`MeshGradient.kt:75,220`）、`rememberArtworkTopBandLuminance` + `topBandScrimAlpha`（`ArtworkPalette.kt:143,195`，**目前无调用者，本页是第一个消费者**）、`PLAYER_ART_PX`（同样首次使用）、`rememberRemoteArtworkUrl`（`RemoteArtwork.kt:27`）、`BitChordIcons`、`OptimizedHaze.optimizedHazeEffect`（`:25`）、`Haptics`。
- Produces: `fun NowPlayingScreen(song, isPlaying, isLoading, position, durationMs, queue, queueIndex, hasPrevious, hasNext, repeatMode, shuffleEnabled, likeStatus, onToggleLike, onPlayPause, onNext, onPrevious, onSeekFraction, onToggleShuffle, onCycleRepeat, onJumpTo, onRemoveFromQueue, onMoveInQueue, onQueueDragActiveChange, onClearQueue, onOpenMenu, onOpenAlbum, onOpenArtist, windowWidth, windowHeight, volume, onVolumeChange, modifier)`。

- [ ] **Step 1: 取正文**

```bash
sed -n '507,2968p' app/src/main/java/com/music/bitchord/ui/player/NowPlayingScreen.kt > /tmp/nps-body.txt
wc -l /tmp/nps-body.txt
```

把 `/tmp/nps-body.txt`（`fun NowPlayingScreen` 的 `@Composable` 注解起，到函数闭合）粘进桌面文件的常量层之后，`@Composable` / `@OptIn(ExperimentalHazeApi::class, ExperimentalHazeMaterialsApi::class)` 两个注解保留。**这一步是追加，不是覆盖**——Task 8 建立的常量与几何函数必须留在文件里。

- [ ] **Step 2: 参数表按 spec §3.3 删 21 个**

原版 48 个参数（`:510-606`）。删除清单与理由（**其余参数名字与类型一字不改**，Task 11 的 36 参数接线靠这张表）：

| 删 | 原行 | 理由 |
|---|---|---|
| `playedBy`、`onListenTogether` | `:512, :589` | 一起听＝切片 6 |
| `audioVersionSwitching`、`qualityUpgraded` | `:525, :527` | 音频↔视频切换＝切片 6 |
| `signedIn`、`accountName` | `:535, :536` | 登录态与输出设备＝切片 4/6 |
| `autoplayEnabled`、`onToggleAutoplay` | `:534, :564` | 决策 6：AUTOPLAY 开关不出现 |
| `onOpenPlaybackSource` | `:581` | 详情页＝切片 4 |
| `lyrics`、`lyricsSource`、`lyricsProviderStates`、`onSelectLyricsProvider`、`lyricsUnavailable`、`lyricsOffsetOpen`、`onDismissLyricsOffset` | `:590-596` | 歌词＝切片 3 |
| `onBlockedControl` | `:548` | controlsLocked 属一起听锁，随该族删 |
| `onSeek` | `:549` | （勘误，随歌词族一起走）它唯一的使用点是 `:652` 歌词行点击 `seekToLyric`，那一族本来就整删；scrubber 与 `onScrubFinished` 都走 fraction（`:1298` 与桌面同一处都是 `scrub.release(onSeekFraction)`），所以"ms 还有人用"不成立。留一个没人调的参数＝本切片禁止的死按钮，Task 11 不得传它 |

**保留**：`song`、`isPlaying`、`isLoading`、`position: PlaybackPosition`（`:521`，spec §4 最后一行的插值就靠它）、`durationMs`、`queue`、`queueIndex`、`hasPrevious`、`hasNext`、`repeatMode: Int`、`shuffleEnabled`、`likeStatus`、`onToggleLike`、`onPlayPause`、`onNext`、`onPrevious`、`onSeekFraction`（留，且是本页唯一的 seek 入口——scrubber 与 `onScrubFinished` 都走 fraction；见上表 `onSeek` 一行的勘误）、`onToggleShuffle`、`onCycleRepeat`、`onJumpTo`、`onRemoveFromQueue`、`onMoveInQueue`、`onQueueDragActiveChange`、`onClearQueue`、`onOpenMenu`、`onOpenAlbum`、`onOpenArtist`、`windowWidth`、`windowHeight`、`modifier`。

**桌面新增的两个参数**（唯一对这张表的加法，理由写在了代码的参数注释里）：`volume: Float`（0f..1f）与 `onVolumeChange: (Float) -> Unit`，插在 `windowHeight` 之后、`modifier` 之前。原版音量不走参数——`rememberPlayerVolume()`（app `:952`）在内部读 `AudioManager` 并挂一个 `Settings.System` 的 `ContentObserver`（app `PlayerState.kt:284-354`），这两样桌面都没有对应物；桌面的音量在 `PlayerController`（`volume: StateFlow<Int>` 百分比 + `setVolume(percent: Int)`），只能走参数。spec §3.3 列了 `VolumeRow`，两个布局都渲染它，`AppSettings.hideVolumeBar` 不移植（本切片无设置面板），因此原来的隐藏分支与它顶位的 `VOLUME_ROW_HEIGHT` 间隔条一并没有了——两者同高 32dp，下方控件不移位。

- [ ] **Step 3: 按三族删正文**

删除的行族（行号来自探查，**以编译器报错为准逐条清，不要成批 sed**）：

- **歌词族**（spec §6 整族不做）：`:633, 646-653, 732-734, 755-762, 772-783, 788-807, 858, 870, 876, 953-969, 982-1002, 1121-1135, 1306-1321, 1348-1356, 1374-1379, 1403, 1433-1438, 1503-1518, 1565-1621, 1840, 2678-2764, 2775, 2826-2828, 2848-2896` ≈ 330 行。
- **运动封面 / hero / Spotify canvas 族**（spec §3.3"运动封面不做"）：`:659-694, 704-712, 1051-1142, 1173-1195, 1696-1771, 1775-1833, 1868-1882, 1884-1972, 1979-1985, 2156-2159, 2226-2230, 2276-2292, 2470-2490, 2825-2840` ≈ 560 行。**例外：静态 hero `AsyncImage`（`:1725-1768`）逐字保留**——它的 modifier 链（`.align(TopStart).fillMaxWidth().height(heroHeight).hazeSource(playerHaze).graphicsLayer{...}.drawWithContent{ drawContent(); drawRect(Brush.verticalGradient(... HERO_FADE_FRACTION ...), blendMode = DstIn) }`）正是 spec §3.3 要求的第四层。`heroMode` / `heroVisible()` / `heroHeight` 由 canvas 驱动，canvas 删完后退化：把 `heroHeight` 写成显式的方形封面高度，并留一句注释说明原版它由 canvas 呈现模式决定。
- **Android-only 五处**（spec §3.3 明列）：`PlayerBackHandler`（`:3055-3065`）+ `OverlayBack`（`:3079-3096`）**整删**，七处调用点 `:858, 861, 868, 870, 872, 874, 876` 一并删（队列的关闭改由 Task 11 的 Esc 与页内关闭控件承担）；`StatusBarIcons(dark = false)`（`:625`）删；`LocalView.current` + `keepScreenOn`（`:845-849`）删；`LocalContext.current`（`:608`，本就是死局部变量）删；`android.graphics.Bitmap canvasFrame`（`:11, 675`）随 canvas 族删。
- **`Build.VERSION.SDK_INT < Build.VERSION_CODES.S`**（`:1904`）：删判断，走 `optimizedHazeEffect` 分支（桌面 `OptimizedHaze.kt:25` 签名一致：`Modifier.optimizedHazeEffect(state, style, block)`）。`:1919-1947` 那段 `HazeStyle(...)` + `HazeInputScale.Fixed(0.18f)` + `mask = Brush.verticalGradient(...)` + `canDrawArea = { true }` 照搬，并**实测这些参数在 Skia 下是否都生效**，不生效的记进 Task 13。
- **insets**：`:1196` 的 `WindowInsets.statusBars.asPaddingValues().calculateTopPadding()` 与 `:1977-1978` 的 `.statusBarsPadding().navigationBarsPadding()` 在桌面解析为 0 —— **保留调用不删**（切片 1 已确认 CMP 自带；它的实施记录第 2 条就是这个坑）。顶部留白实际由 Task 11 的整窗覆盖层承担。

- [ ] **Step 4: 图层顺序按 spec §3.3 核对**

正文 `Box`（`:1642`）里从下往上必须是：

1. `MeshGradientBackground(palette = rememberArtworkColors(remoteArt), trackKey = song.videoId, modifier = Modifier.graphicsLayer { alpha = 1f - fullArtworkBackdropAlpha })`（`:1666-1678` 的形状；桌面签名一致，`canvasFrame` 恒 null）
2. `ArtworkMeshBackdrop(mesh = artMesh, seam = ...)`（Task 9；`canvasFirstPortrait` / `renderedCanvasBottom` 随 canvas 删，`seam` 退化为 `if (heroMode) heroHeight else 0.dp`）
3. `FullArtworkBlurBackdrop(image = fullArtworkBlurImage, modifier = Modifier.graphicsLayer { alpha = fullArtworkBackdropAlpha })`（Task 9；原版的 `movableContentOf` hoisting（`:1409-1417`）可简化成直接调用，**但要留注释说明原版为什么 hoist**——横竖屏分支切换会重建组合，`movableContentOf` 是为了保住状态）
4. 静态 hero `AsyncImage`（`:1725-1768` 逐字）
5. 顶部可读性 scrim：`:1842-1866` 的 `topBandScrimAlpha(rememberArtworkTopBandLuminance(remoteArt, ART_PX))` + 8 段 `pow(1.5)` 渐变（`:1848-1858`）。桌面 `ArtworkPalette.kt` 里的 `rememberArtworkTopBandLuminance`（`:143`）、`topBandScrimAlpha`（`:195`）、`PLAYER_STATUS_SCRIM_MIN/MAX_ALPHA`（`:201-202`）**已经在等着，本页是第一个调用者**，逐字接上。

取色一律 `ART_PX = PLAYER_ART_PX`（1200）。

- [ ] **Step 5: 编译到干净**

Run: `./gradlew -p desktop compileKotlin --console=plain 2>&1 | grep "^e: " | sed 's|.*/desktop/src/main/kotlin/com/music/bitchord/||' | sort -u | head -40`
Expected: 逐类清到无输出。**规则**：一个报错背后是一整族（例如 `LyricsPanel` / `CurrentLyricStrip` / `LyricsTranslationMotion` 未解析）时，回 Step 3 把那族残留删净，**绝不为它写桩**。
Run: `grep -cE "Lyrics|lyrics|CanvasArtwork|canvasFrame|ListenTogether|party" desktop/src/main/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt`
Expected: 只应剩下对原版的说明性引用（数字应 <10）；出现新的可调用符号即为漏删。
Run: `./gradlew -p desktop test --console=plain`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: 提交**

```bash
git add desktop/src/main/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt
git commit -m "feat(desktop): the full-screen player body, minus three feature families"
```

---

### Task 11: 横屏分支 + `PlayerPage` 接线 + Shell 覆盖层 + Esc

**Files:**
- Copy+Modify: `app/.../ui/player/LandscapePlayer.kt`（510 行）→ `desktop/src/main/kotlin/com/music/bitchord/ui/player/LandscapePlayer.kt`
- Create: `desktop/src/main/kotlin/com/music/bitchord/ui/shell/PlayerPage.kt`（`PlayerState` → `NowPlayingScreen` 36 参数的适配层）
- Modify: `desktop/src/main/kotlin/com/music/bitchord/ui/shell/Shell.kt`（`:76` 的 tab 局部状态提升为 `ShellState`；`:160-164` 三个空 lambda；覆盖层绘制）
- Modify: `desktop/src/main/kotlin/com/music/bitchord/desktop/Main.kt`（`Window(onPreviewKeyEvent = …)`，现 `:62-66`）
- Modify: `desktop/build.gradle.kts`（`run` 的 `-P` 白名单加 `bitchord.debug`，现 `:64-71`）
- Modify: `desktop/src/test/kotlin/com/music/bitchord/ui/player/PlayerGeometryTest.kt`（追加横屏常量）

**Interfaces:**
- Consumes: Task 10 的 `NowPlayingScreen`、Task 8 的 `InlineQueue`、Task 6 的 `PlayerController.state / position / shuffleEnabled / …`、桌面 `LikeState.overrides`。
- Produces: `class ShellState { var selectedTab: Int; var showPlayer: Boolean; var menuSong: Song? }`；`@Composable fun PlayerPage(player: PlayerController, state: ShellState, modifier: Modifier = Modifier)`；`@Composable internal fun LandscapePlayerLayout(pane: PlayerPane, background: @Composable (Modifier) -> Unit, artwork: @Composable (Modifier) -> Unit, actions: @Composable () -> Unit, mainPane: @Composable (compact: Boolean) -> Unit, queuePane: @Composable () -> Unit, modifier: Modifier)`；`fun toggleSongLike(song: Song, liked: Boolean)`。

- [ ] **Step 1: 搬 `LandscapePlayer.kt`**

```bash
cp app/src/main/java/com/music/bitchord/ui/player/LandscapePlayer.kt desktop/src/main/kotlin/com/music/bitchord/ui/player/
```

改动只有四处：

1. `internal enum class PlayerPane { Main, Lyrics, Queue }`（`:102`）→ 删 `Lyrics` 成员，**同时**删 `NowPlayingScreen.kt` 里 `when (pane)` 的 Lyrics 分支（两处必须同删，否则 `when` 不再穷尽 → 编译失败）。
2. `LandscapeLyricsPane`（`:456-510`）整删，连同 `LandscapePlayerLayout` 的 `lyricsPane` 参数（`:138`）与 `NowPlayingScreen` 的传参点（`:1565-1621`，Task 10 已删歌词族，这里只剩签名层面要收）。
3. `LandscapeArtwork`（`:263-274`）的 `canvas` / `canvasRendered` / `onCanvasRenderedChange` 三个参数与 `CanvasArtworkPlayer`（`:305-312`）删；静态 `AsyncImage` + `BitChordIcons.MusicNote` 占位（`:288`）留。
4. `LandscapeMainPane`（`:327-341`）的 `lyricStrip: (@Composable () -> Unit)?` 参数删；`LandscapeCredits`（`:376-388`）的 `signedIn` / `showRevertCue` 随参数表删除一起收。

**常量逐字留**：`LANDSCAPE_PLAYER_MAX_WIDTH = 1100.dp`（`:76`）、`LANDSCAPE_COMPACT_HEIGHT = 440.dp`（`:84`）、`LANDSCAPE_GUTTER_COMPACT = 20.dp`（`:90`）、`LANDSCAPE_PANE_FADE_IN_MS = 220` / `_DELAY_MS = 90` / `_FADE_OUT_MS = 140`（`:97-99`）、`LANDSCAPE_HANDLE_STRIP = 24.dp`（`:105`）、sleeve shadow 14.dp / corner 10.dp（`:281-282`）、handle 38×5.dp（`:240-241`）。
紧凑分支的整条链（`:145-161` 的 `val compact = maxHeight < LANDSCAPE_COMPACT_HEIGHT` → `gutter` → `mainPane(compact)` → `TransportRow(compact = compact)`）**不动**——spec §3.3 明确要求 h<440dp 的紧凑分支带过来。`:148` 的 `.windowInsetsPadding(WindowInsets.safeDrawing)` 保留（CMP 自带）。

在 `PlayerGeometryTest.kt` 追加（spec §5.4）：

```kotlin
    @Test
    fun `the landscape branch keeps the app's own numbers`() {
        assertEquals(1100.dp, LANDSCAPE_PLAYER_MAX_WIDTH)
        assertEquals(440.dp, LANDSCAPE_COMPACT_HEIGHT)
        assertEquals(20.dp, LANDSCAPE_GUTTER_COMPACT)
        assertEquals(24.dp, LANDSCAPE_HANDLE_STRIP)
    }
```

Run: `./gradlew -p desktop test --console=plain --tests "*PlayerGeometryTest*" && ./gradlew -p desktop compileKotlin --console=plain`
Expected: PASS（6 条）+ BUILD SUCCESSFUL。

- [ ] **Step 2: `ShellState` 与 Esc**

`Shell.kt` 现在把 tab 状态放在 composable 里（`:76`，`initialQuery.isNotBlank()` 时初始化为 3）。`Window` 的 `onPreviewKeyEvent` 在 content **之外**，够不着 content 里的 `remember`，所以 `showPlayer` 必须住在 `Window` 之上。`Shell.kt` 里新增：

```kotlin
class ShellState {
    var selectedTab by mutableIntStateOf(0)
    var showPlayer by mutableStateOf(false)
    var menuSong: Song? = null
}
```

`Shell(...)` 参数表加 `state: ShellState`；`:76` 的局部 tab 状态改用 `state.selectedTab`（**保留** `initialQuery` 非空时初始化为搜索 tab 的既有逻辑，改成在 `Main.kt` 构造 `ShellState` 时设一次）；`:169-174` 的 `FloatingBottomBar(onTabSelected = { state.selectedTab = it })`。

`Main.kt`：`application { }` 里 `val shellState = remember { ShellState().apply { if (probeQuery.isNotBlank()) selectedTab = 3 } }`，`Window(...)` 加参数。**本 API 已实测存在**：`androidx.compose.ui.window.Window_desktopKt` 的签名里 `Function1<KeyEvent, Boolean>` 出现两次（即 `onPreviewKeyEvent` 与 `onKeyEvent`），紧随五个 Boolean 之后：

```kotlin
    Window(
        onCloseRequest = quit,
        state = rememberWindowState(width = windowWidth.dp, height = windowHeight.dp),
        title = "BitChord for Windows",
        onPreviewKeyEvent = { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyUp && shellState.showPlayer) {
                shellState.showPlayer = false
                true
            } else {
                false
            }
        },
    ) { … }
```

`import androidx.compose.ui.input.key.Key` / `KeyEventType`。`BitChordTheme { Shell(...) }`（`Main.kt:79-87`）把 `shellState` 传进去。

**为什么放窗口层而不是页面内**：`onPreviewKeyEvent` 先于焦点组件运行，返回 `true` 即消费；`showPlayer` 为假时返回 `false`，Esc 原样落到搜索框已有的清空逻辑（`SearchScreen.kt:119-120`），两者互不干扰。spec 决策 4 说"桌面没有返回键语义，Esc 是它的等价物"，这就是那个等价物的落点。
**如果该签名的 `onPreviewKeyEvent` 不可用**（例如重载不匹配），回退手段已验证存在：`androidx.compose.ui.awt.LocalAwtWindow`（`LocalAwtWindowKt.getLocalAwtWindow()`，实测在 `ui-desktop-1.12.1.jar` 里）提供 `java.awt.Window`，用 `DisposableEffect` 挂 `keyPressed` 监听。两条路都试不通才算失败，并在 Task 13 如实记录。

- [ ] **Step 3: `PlayerPage.kt`（36 参数适配层）**

原版这三十多个参数由 `MainActivity.kt:2008-…` 组装。桌面同样集中一处，好让 `NowPlayingScreen.kt` 保持"与上游只差被删的族"。

```kotlin
// The desktop form of MainActivity's ~40-argument call to NowPlayingScreen.
// One PlayerState snapshot feeds the whole page, which is why the screen file
// could be carried over without renaming a single parameter.
@Composable
fun PlayerPage(player: PlayerController, state: ShellState, modifier: Modifier = Modifier) {
    val snapshot by player.state.collectAsState()
    val shuffle by player.shuffleEnabled.collectAsState()
    val volumePercent by player.volume.collectAsState()
    val overrides by LikeState.overrides.collectAsState()
    val song = snapshot.song ?: return

    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val windowWidth = with(density) { size.width.toDp() }
    val windowHeight = with(density) { size.height.toDp() }

    NowPlayingScreen(
        song = song,
        isPlaying = snapshot.isPlaying,
        isLoading = snapshot.isLoading,
        position = player.position,
        durationMs = snapshot.durationMs,
        queue = snapshot.queue,
        queueIndex = snapshot.queueIndex,
        hasPrevious = snapshot.hasPrevious,
        hasNext = snapshot.hasNext,
        repeatMode = snapshot.repeatMode,
        shuffleEnabled = shuffle,
        likeStatus = overrides[song.videoId] ?: LikeStatus.INDIFFERENT,
        onToggleLike = { toggleSongLike(song, overrides[song.videoId] == LikeStatus.LIKE) },
        onPlayPause = player::togglePlayPause,
        onNext = player::next,
        onPrevious = player::previous,
        onSeekFraction = player::seekToFraction,
        onToggleShuffle = player::toggleShuffle,
        onCycleRepeat = player::cycleRepeat,
        onJumpTo = player::jumpTo,
        onRemoveFromQueue = player::removeFromQueue,
        onMoveInQueue = player::moveInQueue,
        onQueueDragActiveChange = { },
        onClearQueue = player::clearQueue,
        onOpenMenu = { state.menuSong = song },
        onOpenAlbum = { },
        onOpenArtist = { },
        windowWidth = windowWidth,
        windowHeight = windowHeight,
        // `PlayerController.volume` is 0..100; the bar is 0f..1f. `setVolume`
        // already coerces to 0..100, so nothing else is needed at the seam.
        volume = volumePercent / 100f,
        onVolumeChange = { player.setVolume((it * 100).roundToInt()) },
        modifier = modifier,
    )
}
```

- `volume` / `onVolumeChange`：桌面的音量在 `PlayerController`（`val volume: StateFlow<Int>` 百分比、`fun setVolume(percent: Int)`），不在任何 hook 里，所以只能由本页把它们传下去（`NowPlayingScreen` 的参数注释记了这条加法的原因）。连续回写是对的：原版 `PlayerVolume.drag` 每个 `onValueChange` 都直接写 `AudioManager`（app `PlayerState.kt:299-308`），而它作为 `onValueChangeFinished` 的 `volume::release`（app `:1301`）什么都不提交——只清一个 `dragging` 标志，用来按住观察器把硬件音量键的跳变补上 tween 时不去跟手指抢。桌面没有第二个写者也没有系统面板，所以两处调用点的 `onValueChangeFinished` 都是空的。**副作用**：`onSeek` 删掉后 `player.seekTo(ms)` 失去唯一的生产调用者（`PlayerController.kt:351`，测试也不调它），留还是删归 Task 13。

- `windowWidth/windowHeight` 用 `LocalWindowInfo.current.containerSize`（切片 1 已验证的 `LocalConfiguration.current` 替代），这样把窗口拉高到 `h > w` 时**原版的竖屏分支自动生效**——spec 决策 1 要的正是"不自己另造判定条件，拉高即生效"。
- `onQueueDragActiveChange = { }`：原版它用于拖拽时压掉播放页滑动手势（`:575`）。桌面没有那个竞争手势，空实现**不是死按钮**（它不渲染任何东西）。
- `onOpenAlbum` / `onOpenArtist` 写空 lambda 前**必须先确认页面上没有可见入口**：`grep -n "onOpenAlbum\|onOpenArtist" desktop/src/main/kotlin/com/music/bitchord/ui/player/`。若 `Modifier.opensPage(...)`（`PlayerControls.kt:1124`）仍在封面无标题处被调用，按切片 1 的规矩**把那个入口一并删掉**，而不是留空回调——宁可少给，不给死按钮。
- `toggleSongLike` 的实现随 Task 12 Step 3 落地（与行菜单共用）；本步先声明为 `fun toggleSongLike(song: Song, liked: Boolean)` 的空体，Task 12 填。

- [ ] **Step 4: Shell 覆盖层**

`Shell.kt` 剩余改动（对照实测锚点，不是 spec 说的 `:124`）：

1. `:153-168` 的 `MiniPlayer(...)`：`onNext = player::next`（原 `{}`，`:160`）、`onPrevious = player::previous`（原 `{}`，`:161`）、`onExpand = { state.showPlayer = true }`（原 `{}`，`:164`，连同 `:162-163` 那句"全屏播放页是下一个切片"的注释一起改掉——它就是本切片要消灭的东西）。`MiniPlayer` 自带的 `Modifier.miniPlayerTrackSwipe`（`MiniPlayer.kt:123-159`，72dp 提交阈值）因此**免费获得滑动切歌**。
2. 在 `Box(:91)` 的**所有子节点之后**追加：

```kotlin
        // The player paints last, over the fades and the bottom surfaces. It is
        // deliberately not registered as a haze source: it brings its own gradient
        // backdrop, and a second frost over the content column is how you end up
        // seeing a ghost of the tab bar through the sleeve.
        if (state.showPlayer) {
            PlayerPage(
                player = player,
                state = state,
                modifier = Modifier.fillMaxSize(),
            )
        }
```

`:98-101` 那个唯一的 `hazeSource(hazeState)` **保持不变**（spec §4 倒数第 2 行：播放器不注册新 haze 源）。打开播放页时 MiniPlayer 与浮动 tab 条被整屏 Column 盖住，与原版一致；验证 = 截图里不该出现底部 tab 条的残影。

3. 给 `run` 的 `-P` 白名单加一项 `bitchord.debug`（`desktop/build.gradle.kts:64-71` 的 `listOf(...)`），否则 `-Dbitchord.debug` 传不进应用、读不到日志。

- [ ] **Step 5: 起窗口 + 视觉判读**

```bash
./gradlew -p desktop run -Pbitchord.probeQuery="周杰伦" -Pbitchord.probeAutoplay=true \
  -Pbitchord.autoExitMs=60000 -Pbitchord.windowWidth=1180 -Pbitchord.windowHeight=780 \
  -Pbitchord.debug=true --console=plain
```

Expected（**必须亲自看截图**）：
- 1180×780 → `landscapePlayerAvailable` 为真 → **横屏两栏分支**：左 sleeve 方形封面、右栏控件与队列；
- `-Pbitchord.windowHeight=400` → `compact` 生效：gutter 由 30dp 变 20dp、`TransportRow(compact = true)`；
- `-Pbitchord.windowWidth=700 -Pbitchord.windowHeight=1400` → 走**竖屏分支**（spec 决策 1 的"拉高即生效"，这条要实测）；
- 点 MiniPlayer → 播放页出现；按 Esc → 播放页消失、MiniPlayer 回来、**音频继续**（日志里 `playing` 不因关闭翻转）。

- [ ] **Step 6: 全量测试 + 提交**

Run: `./gradlew -p desktop test --console=plain && ./gradlew -p desktop compileKotlin --console=plain`
Expected: 两条 BUILD SUCCESSFUL。

```bash
git add desktop/src/main/kotlin/com/music/bitchord/ui/player/LandscapePlayer.kt desktop/src/main/kotlin/com/music/bitchord/ui/shell desktop/src/main/kotlin/com/music/bitchord/desktop/Main.kt desktop/build.gradle.kts desktop/src/test/kotlin/com/music/bitchord/ui/player/PlayerGeometryTest.kt
git commit -m "feat(desktop): the player opens full-screen, in landscape, and Esc closes it"
```

---

### Task 12: 行菜单（四个动作）+ 点赞 + 复制日志

**Files:**
- Create: `desktop/src/main/kotlin/com/music/bitchord/ui/components/DesktopSongActions.kt`
- Modify: `desktop/src/main/kotlin/com/music/bitchord/ui/shell/SearchPage.kt:123-124`
- Modify: `desktop/src/main/kotlin/com/music/bitchord/ui/shell/HomePage.kt`（`HomeScreen(...)` 调用在 `:88-106`，里面没有 `onItemLongPress`）
- Modify: `desktop/src/main/kotlin/com/music/bitchord/ui/shell/Shell.kt`（渲染 `state.menuSong`）
- Modify: `desktop/src/main/kotlin/com/music/bitchord/ui/shell/PlayerPage.kt`（`toggleSongLike` 填实）
- Test: `desktop/src/test/kotlin/com/music/bitchord/ui/components/SongActionsTest.kt`

**Interfaces:**
- Consumes: Task 6 的 `enqueueNext` / `enqueueLast`；`YtMusicRepository.rate(videoId, LikeStatus)`（`:813`）与 `LikeState.set / overrides`（`data/LikeState.kt:16-19`）；`TrackLog.forTrack(song): String`（`data/TrackLog.kt:136`，**已带设备/构建表头与 512KB 环形缓冲**——spec 决策 5 说的"桌面 `DebugLog` 现在只往 stdout 打，需要留一份最近 N 行"这件事 `TrackLog` 已经做完了，**不要新写缓冲**）；`LocalClipboard`；Task 11 的 `ShellState`。
- Produces: `@Composable fun DesktopSongActions(expanded, onExpandedChange, liked, onPlayNext, onAddToQueue, onToggleLike, onCopyLog, modifier)`；`fun toggleSongLike(song: Song, liked: Boolean)`。

- [ ] **Step 1: 写失败测试（测动作留下的状态，不测 UI）**

```kotlin
package com.music.bitchord.ui.components

import com.music.bitchord.data.LikeState
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.desktop.playback.FakeAudioEngine
import com.music.bitchord.desktop.playback.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The menu's four rows, as the state each one leaves behind. The menu's rendering
 * is a screenshot item; what could silently break is which tier a row lands in
 * and what like state a toggle leaves.
 */
class SongActionsTest {

    private fun song(id: String) = Song(id, "T$id", "A$id", null)

    private fun player(): PlayerController =
        PlayerController(CoroutineScope(Dispatchers.Unconfined), FakeAudioEngine()).apply {
            resolveUrl = { "https://test/$it" }
            resolveDispatcher = Dispatchers.Unconfined
            QueueShuffle.setEnabled(false)
        }

    @Test
    fun `the menu's play next inserts a USER_QUEUE row directly after the current one`() {
        val p = player()
        p.playFrom(listOf(song("a"), song("b")), 0, PlaybackSourceType.SEARCH, "S", null)
        p.enqueueNext(song("menu"))
        assertEquals(QueueTier.USER_QUEUE, p.state.value.queue[1].queueTier)
        assertEquals(listOf("a", "menu", "b"), p.state.value.queue.map { it.videoId })
    }

    @Test
    fun `the menu's add to queue does not change what is playing`() {
        val p = player()
        p.playOneOff(song("now"), PlaybackSourceType.SEARCH, "S", null)
        p.enqueueLast(song("menu"))
        assertEquals("now", p.state.value.song?.videoId)
        assertEquals(2, p.state.value.queue.size)
    }

    @Test
    fun `liking twice returns the row to indifferent`() {
        LikeState.set("v9", LikeStatus.LIKE)
        val next = LikeState.overrides.value["v9"]?.let {
            if (it == LikeStatus.LIKE) LikeStatus.INDIFFERENT else LikeStatus.LIKE
        }
        assertEquals(LikeStatus.INDIFFERENT, next)
    }

    @Test
    fun `an untouched row likes rather than unlikes`() {
        assertEquals(null, LikeState.overrides.value["v8-never-touched"])
    }
}
```

Run: `./gradlew -p desktop test --console=plain --tests "*SongActionsTest*"`
Expected: 四条 PASS（Task 6 已提供 `enqueueNext/enqueueLast`；桌面 `Models.kt:496` 有 `enum class LikeStatus { LIKE, DISLIKE, INDIFFERENT }`）。若前两条失败，是 Task 6 的入队语义有问题，回 Task 5/6 的测试定位，**不要在本任务改内核**。

- [ ] **Step 2: 写 `DesktopSongActions.kt`**

原版是 `SongActionsSheet.kt`（851 行、14 个动作）的 tinted `ModalBottomSheet`。桌面**不做 bottom sheet**：CMP 桌面没有 `ModalBottomSheet` 的合理对应物，而决策 5 只留 4 项，一个锚定 `DropdownMenu` 就是它该有的形态。四条 label / icon 与原版逐字对应（`:302-307`、`:308-313`、`:258-265`、`:359-364`）。

```kotlin
// The desktop shape of app/.../ui/components/SongActionsSheet.kt: four rows, not fourteen.
// The original opens a tinted ModalBottomSheet because a thumb needs a big target and a
// place to put eight more actions. A desktop pointer already knows where it clicked, so
// this is an anchored menu on the row. Labels, icons and string keys are the original's
// own — SongActionsSheet.kt:302-313, :258-265, :359-364.
@Composable
fun DesktopSongActions(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    liked: Boolean,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onToggleLike: () -> Unit,
    onCopyLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }, modifier = modifier) {
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.play_next)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = null) },
            onClick = { onExpandedChange(false); onPlayNext() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.add_to_queue)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null) },
            onClick = { onExpandedChange(false); onAddToQueue() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(if (liked) Res.string.remove_from_liked else Res.string.like)) },
            leadingIcon = {
                Icon(
                    if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = null,
                    tint = if (liked) accent else LocalContentColor.current,
                )
            },
            onClick = { onExpandedChange(false); onToggleLike() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.copy_log)) },
            leadingIcon = { Icon(Icons.Rounded.BugReport, contentDescription = null) },
            onClick = { onExpandedChange(false); onCopyLog() },
        )
    }
}
```

五个 `Res.string` 键（`play_next` `:159`、`add_to_queue` `:374`、`like` `:583`、`remove_from_liked` `:619`、`copy_log` `:403`）**实测都已在同步过来的 `strings.xml` 里**，无需新增字符串。四个图标都在 `materialIconsExtended`（`build.gradle.kts:34` 已有）。若 `DropdownMenu` 在这个 CMP 版本要求 `Popup` 包裹（material3 桌面实现自带 popup），以编译器与实机为准，不改四项内容。

- [ ] **Step 3: 填 `toggleSongLike` + 挂到三处**

`toggleSongLike`（`PlayerPage.kt`，Task 11 留的空体）：

```kotlin
/** Optimistic like, exactly like the original's notification action: paint first, then write. */
fun toggleSongLike(song: Song, liked: Boolean) {
    val next = if (liked) LikeStatus.INDIFFERENT else LikeStatus.LIKE
    val previous = LikeState.overrides.value[song.videoId]
    LikeState.set(song.videoId, next)
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        YtMusicRepository.rate(song.videoId, next).onFailure {
            LikeState.set(song.videoId, previous ?: LikeStatus.INDIFFERENT)
            TrackLog.w("like", "rate(${song.videoId}) failed: ${it.message}")
        }
    }
}
```

未登录时 `Innertube.rate` 的 `requireSession()` 会抛（`Innertube.kt:851-855` 说明它返回 200 带 error 对象也算失败）——**只回滚 + 打日志，不弹死按钮**。

1. **`Shell.kt` 渲染菜单 + 复制日志**：

```kotlin
        state.menuSong?.let { target ->
            val liked = LikeState.overrides.value[target.videoId] == LikeStatus.LIKE
            val menuScope = rememberCoroutineScope()
            val clipboard = LocalClipboard.current
            DesktopSongActions(
                expanded = true,
                onExpandedChange = { if (!it) state.menuSong = null },
                liked = liked,
                onPlayNext = { player.enqueueNext(target) },
                onAddToQueue = { player.enqueueLast(target) },
                onToggleLike = { toggleSongLike(target, liked) },
                onCopyLog = {
                    menuScope.launch {
                        val text = TrackLog.forTrack(target)
                        clipboard.setText(AnnotatedString(text))
                        TrackLog.d("menu", "copied ${text.length} chars of log")
                    }
                },
            )
        }
```

**不许改 `TrackLog.forTrack` 的文本格式**——那是移植期给用户抓现场的既有产物。

2. **`SearchPage.kt:123-124`**：`onSongLongPress = { state.menuSong = it }`、`onSongSwipe = { player.enqueueLast(it) }`（原版 swipe-to-queue 语义；`SongRow` 的 `onSwipeToQueue` 已存在，`Common.kt:294`）。`SearchPage` 需要拿到 `ShellState` → 参数表加 `state: ShellState`，`Shell.kt:106-111` 调用点跟着传。
   `:122` 的 `onSongClick` 现在是 `{ songs, index -> songs.getOrNull(index)?.let(::playSong) }`——**把丢掉的那个列表用起来**：改成 `player.playFrom(songs, index, PlaybackSourceType.SEARCH, vm.query, null)`。这正是 spec §3.2"入队入口"要求的形状（`SearchScreen.onSongClick: (List<Song>, Int)` 本来就是 `playFrom` 的签名，切片 1 里被丢掉了）。
3. **`HomePage.kt:88-106`** 补 `onItemLongPress`：`HomeScreen.kt:109` 的默认是 `null`，`RecentTrackRow` 的 ⋮ 因 `if (onLongPress != null)`（`:394`）而不画。传一个把 `ShelfItem` 转 `Song`（`HomePage.kt:61-68` 已有该转换）再 `state.menuSong = it` 的 lambda；**只有带 `videoId` 的条目给菜单**，合集条目（只有 `browseId`）不给——不给死菜单。`HomePage` 同样加 `state: ShellState` 参数。

- [ ] **Step 4: 起窗口实测**

```bash
./gradlew -p desktop run -Pbitchord.probeQuery="晴天" -Pbitchord.probeAutoplay=true \
  -Pbitchord.autoExitMs=45000 -Pbitchord.debug=true --console=plain > /tmp/slice2-menu.log 2>&1
grep -nE "queue|menu|copied|rate\(" /tmp/slice2-menu.log
```

Expected（日志 + 亲自看截图）：
- 搜索结果行点 ⋮ → 四项出现，图标与 label 与原版一致（中文环境显示"下一首播放 / 加入队列 / 喜欢 / 复制日志"）；
- "下一首播放"后打开播放页 → 目标在 `queueIndex + 1`；
- "加入队列"后它在 USER_QUEUE 段尾，且**当前曲没被打断**（日志中当前 `videoId` 不变、`playing` 不翻转）；
- 搜索结果第 3 行点击 → 整个结果列表入队且 `queueIndex == 2`（这条同时是 spec §5.2 第 1 条的证据）；
- "复制日志"→ `copied N chars of log`，并且剪贴板里真能粘出内容——**用 PowerShell `Get-Clipboard` 或 `python -c "import tkinter;print(tkinter.Tk().clipboard_get())"` 复核，别只看日志**；
- 主页合集卡不出现菜单（无 `videoId`）。

- [ ] **Step 5: 全量测试 + 提交**

Run: `./gradlew -p desktop test --console=plain`
Expected: BUILD SUCCESSFUL。

```bash
git add desktop/src/main/kotlin/com/music/bitchord/ui/components/DesktopSongActions.kt desktop/src/main/kotlin/com/music/bitchord/ui/shell desktop/src/test/kotlin/com/music/bitchord/ui/components
git commit -m "feat(desktop): the row menu — play next, add to queue, like, copy log"
```

---

### Task 13: 验收 —— 行为断言、双语截图、几何量、把实测写回 spec

**Files:**
- Modify: `docs/superpowers/specs/2026-09-30-desktop-slice2-now-playing-queue-design.md`（追加 `## 8. 实施记录`）

**Interfaces:**
- Consumes: 前 12 个任务的全部产物。
- Produces: 写回 spec 的实测记录，格式沿切片 1 的先例（其 `## 7. 实施记录` → `### 与设计的三处偏离` + `### 验证结果`）。

- [ ] **Step 1: 全量单元测试，逐条对 spec §5.1 点名**

Run: `./gradlew -p desktop test --console=plain`
Expected: BUILD SUCCESSFUL。然后打开测试报告（`desktop/build/reports/tests/test/index.html`）或 `grep` 用例名，逐条核对 spec §5.1 的六类断言都有对应用例：三段顺序 / `playFrom` 后 USER_QUEUE 不丢 + 两种插入点 / 跳转只丢被跳过的 USER_QUEUE 且向后跳不改列表 / 移除-清空-拖拽置换（`restoreOrder` / `avoidIdentityShuffle` / `sections` 段序不变）/ shuffle 不动当前曲与 USER_QUEUE + `MAX_QUEUE_HISTORY` 与轮转条目数 / `QueueBuilder.extend` 同录音去重与同艺人上限。
**缺哪类补哪类测试，不是补代码。** 若 `sections` / `startingOrder` 因 `private` 测不到，用 `startingOrder`（公开）与 `restoreOrder`（internal）覆盖同一语义，**不要放宽可见性**。

- [ ] **Step 2: 行为断言（一次跑够多条，日志为证）**

```bash
./gradlew -p desktop run -Pbitchord.probeQuery="周杰伦 晴天" -Pbitchord.probeAutoplay=true \
  -Pbitchord.autoExitMs=180000 -Pbitchord.windowWidth=1180 -Pbitchord.windowHeight=780 \
  -Pbitchord.debug=true --console=plain > /tmp/slice2-accept.log 2>&1
grep -nE "queueIndex|total resolve|playing|advance|prefetch|browse:|rate\(" /tmp/slice2-accept.log
```

逐条给证据（spec §5.2 六条）：

1. **搜索结果第 3 行 → 整列表入队且 `queueIndex == 2`**：在 `PlayerController.playFrom` 里加一条 `TrackLog.d("queue", "playFrom n=${songs.size} index=$index")`（移植期抓现场的正当日志），断言这条日志。
2. **播完自动下一首**：解析要 6–19s，实机等待不可行——**临时把 VLC 起始位置设到曲尾**（`VlcAudioPlayer.play` 之后 `player.controls().setPosition(0.97f)`）跑一次，看 `advance()` 的下标序列。**这条必须同时给出下一首的 `total resolve` 耗时**：预取命中时应接近 0（spec 决策 7 的验收点，`total resolve` 那行日志在 `StreamResolver` 的输出里）。跑完**还原这行临时改动**，并在 spec 记录里说明用了这个手段。
3. **"加入队列"不打断当前曲**：日志中当前 `videoId` 不变、`playing` 不翻转（Task 12 已跑一次，这里取成文证据）。
4. **REPEAT_ALL 末→首回绕**：`cycleRepeat()` 后走到队尾再 finish，`queueIndex` 回 0 且条目数不减（算法已由 Task 5 证明，这里要一次实机）。
5. **位置 >10s 时上一首是重播本曲**（Task 6 已证；实机再看一眼 `status` 与 `queueIndex`）。
6. **Esc 关闭播放页且音频继续**：点 MiniPlayer → 播放页 → Esc → MiniPlayer 回来，日志里 `playing` 全程未因关闭翻转。

- [ ] **Step 3: 视觉判读（必须亲自看截图，中英双语）**

| 项 | 尺寸 | 语言 | 判读点 |
|---|---|---|---|
| 横屏全屏播放器 | 1180×780 | en / zh | 左右两栏；四层底图按序且**有真实糊度**（Task 9 的 `Modifier.blur` 实测项）；标题 `MarqueeText` 不溢出、不换行；transport 四图标是原版的矢量形状 |
| 紧凑横屏 | 1180×400 | en | gutter 30→20dp、`TransportRow(compact=true)`、无裁切 |
| 竖屏分支 | 700×1400 | en | `landscapePlayerAvailable` 为假 → 竖屏布局出现（spec 决策 1 的"拉高即生效"） |
| 展开的队列面板 | 1180×780 | en / zh | 三段标题正确（Now playing / Next in queue / Next from: <来源>）；**AUTOPLAY 段不出现**；Clear 只在有 USER_QUEUE 时出现 |
| 行菜单 | 1180×780 | zh | 四项、图标与原版一致、无死项；主页合集卡无菜单 |
| 播放页 z-order | 1180×780 | en | 播放页里**不该出现底部 tab 条的残影**（spec §4 倒数第 2 行的验证） |

截图手段用切片 1 已跑通的那条。已知陷阱：`browser-use` 的隐藏视口会冻结过渡、坐标是文档坐标、reload 超时给的是旧文档、截图会中途失效——**优先本机 Chrome 无头（绝对路径）**。判读"糊没糊 / 溢出没溢出"时，视觉只能证明用户看得见什么，**对齐、溢出、裁切的结论必须另有定点几何量**（应用内日志打出 dp→px，或 `evaluate_script` 类只读探针），并带非零对照。

- [ ] **Step 4: 把实测写回 spec**

在 `docs/superpowers/specs/2026-09-30-desktop-slice2-now-playing-queue-design.md` 末尾追加 `## 8. 实施记录`，结构沿切片 1 的先例（`### 与设计偏离的地方` + `### 验证结果`）。**必须写进去的九条**（本计划过程中已确认的事实修正）：

1. `Shell.kt:124` 的 `// TODO(slice-2)` / "`showPlayer` 已接上" / "`onShowActions` 空回调"三处不成立；实际锚点是 `Shell.kt:160-164` 的三个空 lambda、`SearchPage.kt:123-124`，以及 HomePage 未传 `onItemLongPress`。
2. `ArtworkMeshBackdrop` / `FullArtworkBlurBackdrop` 桌面原本**不存在**（切片 1 的 plan 列了但没搬），spec §3.3 的四层缺两层，本切片补上（Task 9）。
3. 跳转剪枝只搬了 `QueueCoordinator.jumpToQueueItem`；`PlaybackService.kt:6527-6571` 的 session 侧第二套随切片 6 的媒体会话（Task 3 Step 3）。
4. 行内拖拽的鼠标实测结果，以及是否调了 `detectDragGestures` 的启动阈值 / handle 命中区（Task 8 Step 4）。
5. `Modifier.blur` 在桌面 Skia 下是否真的糊（Task 9）；若回退到 IntArray 盒式模糊，记为偏离。
6. `DebugLog` 不需要新增环形缓冲——`TrackLog` 已有（Task 12）。
7. 分支名是 `desktop-slice2-now-playing`，不是 spec 第 5 行写的 `windows-desktop`。
8. 预取的实测收益：第二首的 `total resolve` 从 6.4–19s 降到多少（给实测数，不写"明显改善"）。
9. 验证结果：单元测试总数与分类、行为断言的日志证据、双语与多尺寸截图结论、以及**已知不做的**（歌词 / AUTOPLAY 填充 / 运动封面 / 详情页 / 真玻璃 / 音频输出 / 一起听）。

- [ ] **Step 5: 差多少说多少**

上面任何一条没做到（例如 `probeAutoplay` 因网络失败、竖屏分支没截到图、预取收益没测出来），**在 spec 记录里如实写"未验证 + 补齐命令"**，绝不写成已通过。

- [ ] **Step 6: 提交并推送**

```bash
git status --short
git add docs/superpowers/specs/2026-09-30-desktop-slice2-now-playing-queue-design.md
git commit -m "test(desktop): verify slice 2, and write the results back into the design"
git push -u origin desktop-slice2-now-playing
```

推送前逐条看 `git status --short`：确认没有把 `/tmp` 副本、临时 VLC `setPosition(0.97f)` hack、`desktop/src/main/composeResources/`（已 gitignore 的构建产物）或任何凭据类文件带进去。

---

## Self-Review

- **Spec coverage**：§3.1 七个内核文件 → Task 1（`PlayerState` / `QueueBuilder` / `QueueHistory`）、Task 2（`QueueHost` / `QueueCoordinator`）、Task 4（`QueueShuffle` + `AppSettings.shuffleEnabled`）、Task 5（`QueueTimeline`——spec 未点名，但 §3.2"播放心跳与语义（手写部分）"必须有宿主状态才能落地，且 spec §3.1 已授权"由 `PlayerController` 实现 `QueueHost`"，本计划把那实现集中到一个可 JVM 测试的对象）。§3.2 四条 → Task 5（推进 / 回绕 / 上一首 / 队尾停住）+ Task 6（入队入口，含 `playCollection` 的 CONTEXT 缺口）。§3.3 → Task 7（`PlayerControls` + 图标管线）、Task 8（`PlayerQueue` / `InlineQueue`）、Task 9（两层底图）、Task 10（`NowPlayingScreen`）、Task 11（`LandscapePlayer` + Shell TODO 落地 + Esc）、Task 12（`DesktopSongActions`）。§4 七条风险各有对策与验证手段，分别落在 Task 2 / 5 / 6 / 8 / 9 / 11 / 13。§5 四类验证 → Task 13（§5.1 的算法测试分散在 Task 1-5 与 8，§5.4 的几何量在 Task 7 / 8 / 11 的 `PlayerGeometryTest`）。§6"本切片不做"逐项有"不出现"的具体实现方式（Task 10 删族、Task 8 的 AUTOPLAY 段恒空、Task 4/11 无 `startRadio` 调用）。
- **Placeholder scan**：无 "TBD / 待补 / 类似 Task N" 的步骤（表格里两处"同上"是指同一张替换表内相邻行的同一处置，不是空位）。四处刻意留白都给了处置而不是空缺：Task 9 的 `Modifier.blur`（先实测；回退方案的 helper 形状已给 `scaledArgb`）；Task 11 的 `onOpenAlbum` / `onOpenArtist`（附"先 grep 确认无可见入口，有则删入口"的硬规则）；Task 11 的 `toggleSongLike` 空体（Task 12 Step 3 给出完整实现）；Task 12 的协程作用域（写明用 `rememberCoroutineScope()`）。Task 2 Step 2 之后有一段 **已核对的三件事**，把 `buildContextQueue` 只保 currentIndex 之后的 USER_QUEUE、`buildJumpQueue` 的四段拼接顺序、以及 `MAX_QUEUE_HISTORY=25` 需要 ≥27 行才能触发裁剪这三条**结论**直接写死——执行者不必再推导，也不会因推导错误去改期望值。
- **测试之间的耦合已在计划里处理**：`QueueShuffle.enabled` 与 `AppSettings` 的落盘是进程级状态。Task 5 用 `@BeforeTest` 把洗牌钉成关；Task 6 的 `controller()` 再钉一次；`PlayerController.init` **不读盘**（Task 6 Step 3 第 9 条，还原由 `Main.kt` 做，与原版 `PlaybackService.kt:5285-5286` 的位置一致）。`AppSettingsTest` 写的是 `settings_test.properties` 这个独立文件，不碰 `settings.properties`。若实施时仍出现"单跑通过、全量跑失败"，先查这三处，不要改断言。
- **Type consistency**：`QueueHost` 七个成员在 Task 2 定义，Task 4（`snapshot` / `replaceRange` / `itemCount` / `songAt`）与 Task 5（实现全部七个 + `jumpTo` / `playCurrent`）使用一致；`RepeatMode.OFF/ONE/ALL` 由 Task 1 定义，Task 4（AppSettings 默认值）、Task 5（`repeatMode`）、Task 7（断言）用同一符号；`PlayerState` 字段名（`song / isPlaying / position / durationMs / isLoading / repeatMode / queue / queueIndex / hasPrevious / hasNext`）与 Task 6 的 `publish()`、Task 11 的 `PlayerPage` 接线逐字对齐（spec §3.1 的硬要求）；`BACK_RESTARTS_AFTER_MS` 只在 `QueueTimeline` 定义一处，Task 7 的断言引用它而不是重写数字；`QueueSource` / `ContextQueueResult` 由 Task 2 产出、Task 5 消费；播放入口在 Task 6 命名为 `playFrom(songs, index, source, sourceTitle, sourceId)`，Task 12 Step 3 与 Task 13 Step 2 都用这一形状；`AudioEngine` 由 Task 6 定义，`FakeAudioEngine` 在 Task 6 建、Task 12 的测试复用。
- **两处执行顺序耦合，必须照做**：① Task 8 Step 1 会先建立 `NowPlayingScreen.kt` 的常量与几何（为了让 `PlayerQueue.kt` 能编译），Task 10 **追加**正文而不是覆盖同一文件；② Task 6 改 `PlayerController` 的公开 API 会立刻打断 `HomePage.kt:61-68` / `SearchPage.kt:60` / `Shell.kt:160-164` 的调用——Task 6 刻意保留 `play(Song)` 薄封装正是为此，真正的 `playFrom` 切换放在 Task 12 Step 3，**不要在 Task 6 提前删 `play`**。
