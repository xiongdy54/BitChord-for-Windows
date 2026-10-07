# BitChord 桌面版 · 切片 5 设计：歌词整族（面板 + 单行条 + 翻译/罗马音 + 来源与偏移）

日期：2026-10-07
分支：`desktop-slice2-now-playing`
状态：已完成（实施记录见 §7）

## 1. 背景

歌词是全部切片里欠得最久的一整块：切片 1 的路线表把它排在"切片 3"，切片 2 搬播放页时整族删掉（约 330 行，删除行号留档在 slice 2 plan 的 Task 清单里），随后切片 3 让位给了 Apple Music 外壳，切片 4 让位给了右键菜单与设置。`NowPlayingScreen.kt` 里四处成文注释在等它回来：

- `:384-389` 主注（"Four things left"的第一件）：面板、scrubber 上方的单行条、翻译/罗马音圆钮、provider/offset 表面、`lyricsOpen`/`lyricsControlsOpen` 与五秒 stand-down、带偏移的取位 lambda、横屏第三栏；
- `:542-549` `onSeek(ms)` 参数之死——它唯一的调用点就是歌词行点击的 `seekToLyric`；
- `:1027-1032` `PlayerActionRow` 里上游本有歌词钮（图标 `BitChordIcons.LyricsQuote` 已在桌面，`BitChordIcons.kt:46`）；
- `LandscapePlayer.kt` 头注：`PlayerPane.Lyrics` 第三栏与 `lyricStrip` 槽整族被删。

上游规模：数据层 `app/.../data/lyrics/` 27 文件约 4300 行，UI 层约 3300 行（`PlayerLyrics.kt` 一个文件 2639 行），测试 6 份共 703 行**全部纯 JVM**（无 Robolectric/MockWebServer，已核对）。

桌面已就位的条件（逐项核实过）：`data/Http.kt:100` 有与上游同包同名的 OkHttp `Http.client`，`LyricsHttp.kt` 的 import 原样解析；46 处歌词字符串已随 `syncAppStrings` 合并进 `desktop/src/main/composeResources/values*/strings.xml`；`PlayerController.seekTo(ms)`（`:384`）与 `PlaybackPosition`（`playback/PlayerState.kt:65`）在位；`AppSettings` 的 `FileStore` 属性落盘模式在位。

## 2. 已确认的决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **数据层逐字移植 25 个文件**（`data/lyrics/` 除 `EmbeddedLyrics.kt`、`LrcWriter.kt`） | `LyricsHttp` 走共享 `Http.client`，12 个网络源 + 解析器（TTML/KaraokeLrc/EnhancedLrc/LrcLib/纯文本）+ `LyricsRepository` 竞速管线全是纯 JVM。Embedded/LrcWriter 的唯一调用者是下载族（`download/LyricsTag.kt`），桌面无下载/本地音乐，搬了即死代码——随下载切片走；`LyricsRepository` 的 `isrc`/`localUri` 参数原样保留（上游语义档案，Embedded 回归时不动签名） |
| 2 | **Android 替换点集中在 `LyricsTranslation` 一处**：`Context`→`AppFiles.dir` 下的同名缓存目录 `lyrics_translation_v3`（GZIP JSON、2 MiB 上限、LRU 裁剪语义照搬）；`android.util.LruCache`（12 条）→`LinkedHashMap` 淘汰式实现 | 该文件仅这两处 Android 依赖；Google web 端点（`translate_a/single` + `inputtools`）、3500 字符分批、私有区标记、≤2 并发、`timingSource` 时轴保留逻辑全是 JVM |
| 3 | **状态持有者 = 新建 `desktop/playback/LyricsCoordinator`**，与 `PlayerController(scope)` 同批装配（`Main.kt:303`），经 Shell→PlayerPage→NowPlayingScreen 传位；复刻上游 `MainViewModel` 的四个流（`lyrics`/`lyricsSource`/`lyricsChecked`/`lyricsProviderStates`）与 `loadLyrics(...)`/`selectLyricsProvider(source)`（含 per-source 手动作业与结果缓存） | 桌面无 MainViewModel 也无 ViewModel 基建；`PlayerController` 是 class（`:53`），先例在此。fetch 挂钩抽成构造参数可注入，生产路径默认真实现，测试注假源 |
| 4 | **UI 家族逐字移植 + 四处桌面替换**：`PlayerLyrics.kt`、`LyricClock.kt`、`LyricFocus.kt`、`LyricsControlsGesture.kt`、`PlaybackPositionScope`（app `PlayerState.kt:361`，随歌词族一起搬）。替换：`SystemClock.elapsedRealtime`→`System.nanoTime()/1_000_000`；`Toast`×6→不移植（行内 `status` 已呈现状态、失败态有 `LyricsUnavailableLine` 兜底，DebugLog 记录）；`AppCompatDelegate.getApplicationLocales`→`Locale.getDefault()`（`-Dbitchord.locale`/设置语言启动时已 set）；`Build.VERSION`≥S 发光门→桌面恒启用，挂 `reduceDynamicBlur` 通道 | 这四样是 UI 层仅有的 Android 面；其余（AnimatedContent/LazyColumn/drawWithCache/NestedScrollConnection/awaitEachGesture）桌面播放页已在用同族 API |
| 5 | **播放页接线**：`NowPlayingScreen` 恢复歌词七参数（`lyrics`/`lyricsSource`/`lyricsProviderStates`/`lyricsUnavailable`/`onSelectLyricsProvider`/`lyricsOffsetOpen`/`onDismissLyricsOffset`，与上游签名对齐）+ `lyricsOpen` 状态机 + 五秒 controls stand-down（`LYRICS_CONTROLS_IDLE_MS` 回归）+ 竖屏 `LyricsPanel` 与翻译/罗马音圆钮 + 单行条（`!lyricsOpen && syncedLyrics`）；恢复绝对 seek 入口 `onSeek(ms)`（歌词行点击 `seekToLyric`），PlayerPage 适配层接 `controller.seekTo(ms)`——scrubber 的 fraction 语义不动 | 上游 `:652` 的语义原样回归；`onSeek` 之死的注释（`:542-549`）随回归改写 |
| 6 | **横屏恢复第三栏**：`LandscapePlayer` 恢复 `PlayerPane.Lyrics`、`lyricsPane` 与 `lyricStrip` 槽，`PlayerPane` 回到上游三态 {Main, Lyrics, Queue} | `LandscapePlayer.kt:3-8` 头注记录的删除就是为本切片留的 |
| 7 | **Esc 语义：`lyricsOpen` 提升进 `ShellState`**（第三字段，与 `showPlayer` 同理由——窗口级键处理要读它）；`escapeClosesPlayer` 扩展为"歌词开着先关歌词，再关播放页"，EscapeRuleTest 补判定表；provider/offset 对话框自身是 `Dialog`，Esc 走对话框默认关闭，不进这条规则 | 切片 3 决策 8"ShellState 收窄成两件事"的边界条件改写：歌词开合必须被窗口级 Esc 规则看到 |
| 8 | **Provider/Offset 表面 = `Dialog`**（slice 4 `SettingsDialog` 先例），不搬 `PlayerDrawer`/`optimizedHazeEffect`（桌面无此基建） | provider 列表（来源按保存顺序 + 每源状态标签）与 ±100ms 步进器的**语义**照搬，表面桌面化 |
| 9 | **设置集成**：`SettingsDialog` 加"歌词"组——同步歌词开关、歌词来源入口（移植 `LyricsSourcesDialog`：16 源勾选 + 拖拽排序 + 音节优先开关 + PaxSenix key）、翻译语言入口（移植 `TranslationLanguageDialog`：131 语言搜索列表）；偏移留在播放页（上游同款）。`AppSettings` 新键按 FileStore 字符串编码：`synced_lyrics`、`lyrics_offset_ms`、`translation_language`、`lyrics_sources`、`lyrics_sources_seen`、`lyrics_source_order`、`prioritize_syllable_sync`、`paxsenix_api_key`（`lyrics_blur` 待面板模糊在 Skia 实测后定去留） | 上游这套设置本就是设置页 + 播放页两层；`readLyricsSources()` 的"未见新源自动并入"升级语义一并搬 |
| 10 | **翻译目标语言 = `AppSettings.translationLanguage`（blank=跟随应用语言）**，跟随语义读 `Locale.getDefault()` | `docs/LYRICS_TRANSLATION.md` 的既定语义；桌面把"应用语言"的来源从 AppCompatDelegate 换成 JVM 默认 locale，其余不变 |
| 11 | **通知栏歌词 ticker 不移植**（PlaybackService 的 500ms ticker→`playlistMetadata` 副标题） | Android 通知专用；桌面的等价物是 SMTC/媒体会话，属后续切片 |
| 12 | **字符串零新增**：46 个上游歌词键已在合并产物里 | 真缺的进 `desktopStrings`，随实施确认 |

## 3. 交付物

| 文件 | 内容 |
|---|---|
| `data/lyrics/*.kt`（新 25 文件） | `LyricsRepository`/`LyricsSource`/`LyricLine` 族/`LyricsHttp`/12 网络源/解析器族/`LyricsTranslation`（桌面化）/`TranslationLanguages`/`ProviderLyrics` 等，逐字移植 |
| `desktop/playback/LyricsCoordinator.kt`（新） | 四流 + `loadLyrics`/`selectLyricsProvider`，fetcher 可注入 |
| `data/settings/AppSettings.kt` | 决策 9 的八个键 + `readLyricsSources()` 升级语义 |
| `ui/player/PlayerLyrics.kt`（新） + `LyricClock.kt`/`LyricFocus.kt`/`LyricsControlsGesture.kt`（新） | 面板、单行条、圆钮、粒子换装、翻译状态机、时钟对账 |
| `desktop/playback/PlayerState.kt` | 补 `PlaybackPositionScope`（随歌词族从 app 来） |
| `ui/player/NowPlayingScreen.kt` | 歌词七参数、`lyricsOpen`/stand-down、竖屏面板+圆钮、单行条、`onSeek(ms)` |
| `ui/player/LandscapePlayer.kt` | `PlayerPane.Lyrics` 第三栏 + `lyricStrip` |
| `ui/player/PlayerControls.kt` | `PlayerActionRow` 补歌词钮（`LyricsQuote` 图标已在） |
| `ui/player/LyricsProviderSheet.kt` + `LyricsOffsetSheet.kt`（新，Dialog 形） | 来源选择 + 偏移调节，语义照搬 |
| `ui/screens/SettingsDialog.kt` + `LyricsSourcesDialog.kt`/`TranslationLanguageDialog.kt`（新） | 歌词设置组 + 来源管理 + 翻译语言 |
| `ui/shell/Shell.kt` + `desktop/Main.kt` | `ShellState.lyricsOpen`、Esc 规则扩展、`LyricsCoordinator` 装配 |
| 测试 | 上游 5 份移植（`LyricClockTest`/`LyricFocusTest`/`LyricsOffsetTest`/`NewLyricsSourceTest`/`ProviderLyricsTest`）+ `LyricsCoordinator` 状态机（假源）+ Esc 判定表扩展 |

## 4. 关键技术点与风险

| 风险 | 对策 | 验证手段 |
|---|---|---|
| `LyricsPanel` 为手机屏宽而写，桌面宽窗口下行无限拉长 | 面板列宽约束沿用桌面播放页现有列宽先例，实测宽窗（≥1600）截图判读 | `.shots/slice5/` |
| 翻译缓存替换（Context→文件目录、LruCache→LinkedHashMap）语义偏差 | 单测：缓存文件写读往返、12 条/2 MiB 淘汰上限、SHA-256 键稳定 | 单元测试 |
| `withFrameMillis` 在窗口失焦/最小化时停摆 | `LyricClockReconciler` 本就按"最近一次上报+流逝时间"对账（上游同款），回归单元测试覆盖 | `LyricClockTest` 移植 |
| blur/glow 在 Skia 的表现未知（上游有 SDK≥S 门） | 桌面恒启用但挂 `reduceDynamicBlur`；实测不合格则降级为无模糊 | 截图判读 |
| `revealLyricsControlsOnTap` 是触摸语义，桌面滚轮滚列表会不会误触控件显隐 | tap 判定走 `awaitEachGesture`（点击才触发，滚轮不产生 tap）；NestedScrollConnection 的滚动显隐逻辑照搬 | 实机 |
| 全部源不可达（网络受限） | `lyricsChecked`+空列表→`lyricsUnavailable`→`LyricsUnavailableLine` 兜底；provider states 全 NOT_FOUND 可见 | 单测 + 实机 |
| 来源管理对话框的拖拽排序在鼠标下表现 | 上游 `ReorderableSourceList` 用 pointerInput 拖拽，桌面鼠标同 API；实测不行则加键盘/按钮微调 | 实机 |

## 5. 验证计划

1. **单元测试**：上游 5 份移植（全 JVM）；`LyricsCoordinator` 状态机（假源注入：加载翻转 `lyricsChecked`、selectLyricsProvider 结果缓存、provider states 迁移 NOT_FETCHED→FETCHING→FOUND/NOT_FOUND）；`AppSettings` 新键落盘往返；Esc 判定表扩展。
2. **行为断言**：实机手测清单——歌词开合与 Esc、行点击 seek 到位、翻译/罗马音圆钮切换、偏移 ±100ms 生效、来源切换立即生效、单曲换歌后歌词随动。
3. **视觉判读**：面板/单行条/圆钮/provider/offset/设置组 × 深/浅 × 中/英 → `.shots/slice5/`。
4. **常量核对**：`LyricsSource` 16 枚举成员数、`PlayerPane` 三态、stand-down 5000ms 进测试。

## 6. 本切片不做

`EmbeddedLyrics`/`LrcWriter`（随下载/本地音乐切片）；通知栏歌词与 SMTC（媒体会话切片）；AUTOPLAY 按钮（切片 7）；运动封面 Canvas 族；Equalizer/Replay 附属页；行内多选与 ⋮ 入口（切片 4 既定）。


## 7. 实施记录（2026-10-07 回填）

**交付**：决策 1–12 全部落地。数据层 25 文件逐字移植（`data/lyrics/`），`LyricsTranslation` 的两处 Android 耦合换成 `File` 缓存根（`AppFiles.cacheDir`，`BitChord/cache/lyrics_translation_v3/`）与 12 条 LinkedHashMap LRU；`AppSettings` 八键 + `readLyricsSources`/`readLyricsSourceOrder` 的升级语义（升级语义抽成可测纯函数 `lyricsSourcesFrom`/`lyricsSourceOrderFrom`）；`LyricsCoordinator`（四流 + loadLyrics 门 + provider 选择器，fetch 可注入）随 `PlayerController` 装配；`PlayerLyrics.kt` 全家（面板/单行条/圆钮/粒子换装/翻译状态机/时钟对账）+ `LyricClock`/`LyricFocus`/`LyricsControlsGesture`（canvas 半边不搬，随运动封面切片）+ `PlaybackPositionScope`；播放页接线（面板/单行条三分支/圆钮/五秒 stand-down/上游三级 prewarm 复原）+ 横屏第三栏（`PlayerPane.Lyrics` + `lyricStrip` 槽 + `LandscapeLyricsPane` 逐字）+ `onSeek(ms)` 复活；Esc 先歌词后播放页；设置对话框歌词组 + `LyricsSourcesDialog`/`TranslationLanguageDialog`/`LyricsProviderDialog`/`LyricsOffsetDialog`。

**与设计的偏离**：

1. **Esc 规则的本体没变，变的是处理器**：`escapeClosesPlayer` 的判定不需要 `lyricsOpen` 这个输入（播放器开着 Esc 就该被消费），给它加参数就是死参数。两段关闭（先歌词后播放页）落在 Main.kt 的键处理里，EscapeRuleTest 原表不动。
2. **provider/offset 对话框是播放页内部状态**，不是窗口级参数——设计表里的 `lyricsOffsetOpen/onDismissLyricsOffset` 两参数没有落地。上游把 offset 状态放在 MainActivity 是因为它的 ⋯ 菜单在那；桌面的 ⋯ 菜单属于 Shell，于是两处状态都归播放页，入口是状态行 → provider 对话框 → 底部"歌词偏移"行——一个话题一张表面。
3. **来源对话框的两处桌面化**：拖拽排序换成每行上/下箭头（`ReorderableSourceList` 是按手指与固定行距设计的，鼠标下箭头是更诚实的目标）；PaxSenix key 是内联文本框而非带 save/cancel 的子弹窗。顺序与键的落盘语义不变。
4. **翻译目标的跟随语义**：`Locale.getDefault().toLanguageTag()` 取不到时落 `"en"`（上游落到系统首语言，同义）。
5. **`lyricsBlur` 有读无键**：面板模糊在 Skia 下实测合格（截图可见焦外衰减），字段带着上游默认值（开）进了 `AppSettings`，但落盘键等它的设置行出现再写——没人写的键不是节省。
6. **`rememberIsForeground` → 常量 `true`**：桌面窗口没有"进程退到后台"，时钟无后台可停；两处门照搬结构、值恒真。
7. **竖屏单行条少一个分支**：上游的三分支里有一支是 `hideSongStatus` 设置（桌面无此设置），桌面条数仍是三（条/nbsp 占位/状态行），但不会有关掉它的设置项出现。
8. **探针扩展**：`bitchord.probeOpenLyrics=true`——与 `probeOpenPlayer` 同族，歌词面板没有鼠标进不去，截图链路因此多一环（build.gradle.kts 转发表同步）。
9. **`PlayerQueue` 交还了代管品**：切片 2 寄存在 `rememberPlayerControlsOnScroll` + `CONTROLS_SCROLL_SLOP` 的交接注记（"谁搬 PlayerLyrics 进来谁删"）兑现，PlayerLyrics 自己的声明成为唯一一份。
10. **测试框架换算**：上游 4 份歌词测试是 JUnit4 断言，桌面是 kotlin.test——import 行换算，正文逐字。

**验证**：28 套件 / 250 测试全绿（新增：移植 `LyricClockTest`/`LyricFocusTest`/`NewLyricsSourceTest`/`ProviderLyricsTest` 4 套 + 新写 `LyricsCoordinatorTest` 8 例（假源注入：去重门/时长门/代际退役/选择器三态/未找到惰性）+ `LyricsSettingsTest` 6 例（升级判定表/空集保真/未知名剔除/FileStore 可空读取））。截图四张（`.shots/slice5/`，gitignore 内）：横屏歌词面板（中文深色，BiniLyrics 词、活动行亮/焦外衰减/注音符、双圆钮、署名状态行）、英文浅色（"Lyrics by BiniLyrics · Change" 本地化）、设置对话框歌词组（同步歌词开关）、竖屏单行条（scrubber 正上方当前句 + › 入口）。诊断插曲：首拍显示"正在排列歌词"是截图时机（搜索+解析约 10s）而非链路故障——临时 JVM 诊断证实仓库竞速 8s 出词（BINI_LYRICS 44 行），诊断已删。行点击 seek、圆钮切换、偏移步进、来源切换为鼠标交互，探针无法代替人手——实机待验。
