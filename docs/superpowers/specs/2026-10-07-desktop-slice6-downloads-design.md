# BitChord 桌面版 · 切片 6 设计：下载落地（下载内核 + 标签与歌词 + 下载页 + 音质设置）

日期：2026-10-07
分支：`desktop-slice2-now-playing`
状态：已完成（实施记录见 §7）

## 1. 背景

下载是上游的核心承诺之一：断网听、带标签与歌词的本地文件、整张合集打包。桌面端目前只有一个 14 行的 `Downloads.kt` 占位（`saved` 恒空），唯一的消费者 `DownloadedBadge` 从未画出来过；`Song` 模型的 `localUri/localPath/downloadFormat` 字段、`StreamResolver.resolveForDownload`、`TrackMatcher` 都已就位却无人使用。切片 5 把歌词仓库搬齐了，`LrcWriter`/`EmbeddedLyrics`（下载时写入、播放时读出）是当时明确留给本切片的两件。

上游规模：`download/` 12 文件约 4200 行（DownloadService/Downloads/Downloader/DownloadSession/DownloadStore/OfflineDash/OfflineHls/MediaTagger + Flac/Mp4/Webm Tagger + LyricsTag），本地音乐页 1726 行，NerdStats 全量 347 行，QualityUpgrade 竞速 801 行。上游测试已有 `DownloadSessionTest`/`DownloadStoreTest`/`MediaTaggerTest` 三份可移植。

桌面的关键运气：**上游四个 Tagger 全是手写字节级解析**（安卓没有 jaudiotagger 才手写的），在 JVM 上原样可编译；`Downloader`/`DownloadSession`/`LyricsTag` 零 Android import。

## 2. 已确认的决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **逐字移植 7 个文件**：`Downloader`/`DownloadSession`/`LyricsTag`/`FlacTagger`/`Mp4Tagger`/`WebmTagger` + `data/lyrics/LrcWriter`（切片 5 留下的那件） | 零或近零 Android 面（见 §1）；它们的测试一并移植 |
| 2 | **`EmbeddedLyrics` 回归，桌面形**：`forUri(context, uri)` → `forFile(file: File)`（`fromBytes`/`sidecar` 的字节核心逐字保留），`LyricsCoordinator` 补回 `localUri` 参数与"先文件后网络"分支 | 切片 5 头注记下的债；下载的歌离线也要出词 |
| 3 | **`DownloadStore` 桌面重写**：`AppFiles.dir("downloads")` 单一目的地；`Pending` = `.part` 临时文件 + 原子改名；`fileNameFor` 命名/净化与 `storable` 编解码表逐字保留 | MediaStore/SAF/遗留权限三整块不存在；桌面没有"用户可见的 Music/BitChord"约定，导出目录选择推迟（偏离 4） |
| 4 | **不做 `exportDownloads`（导出到用户目录）** | 需要目录选择器与散落文件管理，独立工作量；`AppFiles` 私有目录对断网播放已完整。设置里不出现该行 |
| 5 | **`Downloads.kt` 带 File 接缝移植**：`Uri`→`String`（文件绝对路径），SharedPreferences→`FileStore` 字符串编码（三张 map 序列化为 JSON 字符串存三个键——kotlinx.serialization 已是依赖）；`startForegroundService`→对象内 4 worker 排空协程（DownloadService 的 `takeNext/onRunning/onIdle` 排空核心，~30 行） | 上游队列/记录/合集核心本就是协程 + 流；Service 壳（通知/前台）桌面无对应物，任务栏进度提示推迟 |
| 6 | **拒绝打包路由（OfflineDash/OfflineHls 不搬）**：`routeFor` 只走 `StreamResolver.resolveForDownload` + `Downloader.fetch`；`.mpd`/`.m3u8` 一律拒绝 | 打包路由的播放端（VLC 读本地 fMP4 播放列表）未验证——按"宁少给"先拒绝，等引擎接缝实测后随播放侧一起回 |
| 7 | **`MediaTagger` 移植 + ImageIO 替换**：封面解码/缩放（`android.graphics.Bitmap` 1000px JPEG q92）→ `javax.imageio`；`persistArtwork` 侧车照搬（`AppFiles.dir("download-artwork")`） | Tagger 家族唯一真实的 Android 面；JVM 自带 ImageIO |
| 8 | **播放集成**：`PlayerController` 的 `resolveUrl` 接缝前置检查——`Downloads.verifiedSavedUri(videoId)` 非空直接给引擎文件路径（上游 `toMediaItem` 的短路灯），解析失败走 `forgetMissing` 换流 | `Song.localUri` 字段、VLC 引擎的 MRL 都吃文件路径；上游 scheme 判定语义等价 |
| 9 | **入口 = 右键菜单两行 + 侧边栏"已下载"页**：`SongContextMenu` 增〔下载/删除下载〕（含进行中状态的进度与取消——上游 SongActionsSheet 的下载区语义）；`Destination.Downloads` + 简单歌曲列表页（日期排序、右键可删），侧边栏资料库组加一行 | 上游的入口是 SongActionsSheet 下载区 + LibraryScreen"On Device"货架 → LocalMusicScreen(isDownloads)；桌面右键已是行的主语义（切片 4），LocalMusicScreen 全家（艺人/专辑页签、搜索、排序、多选、WebDAV）属本地音乐切片 |
| 10 | **不做 DownloadManagerSheet 与工具栏下载钮**（`DownloadSession` 全量移植，其 `visible` 流照常驱动将来那条） | 每行取消/进度在右键菜单已可达；管理表是批量大队列的人机工程，等实测反馈再定 |
| 11 | **音质设置族的最小闭环**：`audioQuality` 落盘 + 设置对话框"播放音质"单选（ seam 已在：`StreamResolver` 天花板读 `effectiveAudioQuality`）；`DownloadQuality` 枚举 + 落盘 + "下载音质"单选（`Downloads.prepare` 每曲读一次，上游同款）；**不做** metered 双档（桌面无计量网络概念，`effectiveAudioQuality` 保持单值）、wifiOnlyDownloads、NerdStats 全量/竞速/质量升级 UI（随切片 7 的 AUTOPLAY/媒体会话走） | 设置页欠的三件里两件能闭环；竞速族需要引擎级 audition 接缝，独立成片 |
| 12 | **持久化记录的键**：`downloaded_tracks`/`downloaded_tracks_metadata`/`downloaded_collections` 与上游同名（JSON 值） | 升级兼容的语义档案；上游 SharedPreferences 键位不迁移（平台不同无交集） |

## 3. 交付物

| 文件 | 内容 |
|---|---|
| `download/Downloader.kt`/`DownloadSession.kt`/`LyricsTag.kt`/`FlacTagger.kt`/`Mp4Tagger.kt`/`WebmTagger.kt`（新，逐字）+ `data/lyrics/LrcWriter.kt`（回归） | 取流管道、队列状态机、歌词嵌入、三个字节 Tagger |
| `download/DownloadStore.kt`（新，桌面重写） | `AppFiles.dir("downloads")`、`.part`+改名、`fileNameFor`/`storable` 逐字 |
| `download/MediaTagger.kt`（新，ImageIO 替换） | 标签分发、封面解码缩放、侧车封面 |
| `download/Downloads.kt`（重写占位） | 队列/记录/合集核心 + 4 worker 排空 + FileStore JSON 持久化 |
| `data/lyrics/EmbeddedLyrics.kt`（回归，桌面形） | `forFile(file)`；`LyricsCoordinator` 补 `localUri` 分支 |
| `desktop/playback/PlayerController.kt` | 下载短路灯 + `forgetMissing` |
| `ui/components/SongContextMenu.kt` + `songActions` | 下载/删除下载/进行中进度三态 |
| `ui/shell/NavState.kt` + `ui/screens/DownloadsPage.kt`（新）+ `Sidebar.kt` | `Destination.Downloads` + 列表页 + 侧边栏行 |
| `data/settings/AppSettings.kt` | `audioQuality` 落盘、`DownloadQuality` + 落盘 |
| `ui/screens/SettingsDialog.kt` | 播放音质 / 下载音质两组单选 |
| 测试 | 上游 `DownloadSessionTest`/`DownloadStoreTest`/`MediaTaggerTest` 移植 + `fileNameFor`/持久化往返 + `EmbeddedLyrics.fromBytes` 往返 |

## 4. 关键技术点与风险

| 风险 | 对策 | 验证手段 |
|---|---|---|
| VLC 引擎播本地文件路径的 MRL 行为未实测 | 引擎 seam 已确认吃字符串 MRL；先用单测/探针播一个下载产物再进 UI | 实机 + 探针日志 |
|googlevideo 分块限速（上游为此做了 2MiB ranged GET） | `Downloader` 逐字保留该策略 | 下载一条真实曲目的耗时日志 |
| 403/404/410 重解析竞态 | `Downloader` 的 REFUSAL_CODES 路径逐字（`onPlaybackRefused`/`mediaHeadersFor` 桌面已有） | 单测 + 实机 |
| Tagger 的堆压力（40MB FLAC ≈ 120MB 在途） | 上游 `taggingLock` + 16MiB 头帽逐字 | MediaTaggerTest 移植 |
| 下载中的曲目被播放（半成品） | `Pending` 契约：commit 前记录不存在；`verifiedSavedUri` 只认已提交 | DownloadStoreTest |
| JSON 持久化损坏（进程被杀在写入中） | FileStore 原子写 + 读失败回退默认（`runCatching`） | 往返测试 |
| 右键菜单状态竞态（进行中→完成） | 菜单构建是纯函数吃状态快照，行打开时收集 `Downloads.active`/`saved`——上游 SongActionsSheet 同款 | 实机 |

## 5. 验证计划

1. **单元测试**：三份上游测试移植；`fileNameFor` 净化/长度表；三键持久化往返；`EmbeddedLyrics.fromBytes`（MP4/FLAC 容器字节）往返；`storable` 表。
2. **行为断言**：探针实机——右键下载一条 → 进度 → 完成徽标 → 断网（或跳过解析）播放走本地文件 → 歌词面板出词（EmbeddedLyrics 路径）。
3. **视觉判读**：下载页、右键菜单三态、设置音质组 × 深/浅 × 中/英 → `.shots/slice6/`。
4. **常量核对**：`DownloadQuality` 三态、worker 数 4、`CHUNK_BYTES` 2MiB 进测试。

## 6. 本切片不做

本地音乐扫描与 `LocalMusicScreen` 全家（目录扫描/艺人专辑页签/排序/多选/WebDAV——独立切片）；OfflineDash/OfflineHls 打包路由（决策 6）；导出目录选择（决策 4）；DownloadManagerSheet 与工具栏下载钮（决策 10）；NerdStats 全量/无损竞速/质量升级 cue（切片 7，随 AUTOPLAY 与媒体会话）；音质切换中播放的 audition 接缝；wifiOnlyDownloads/metered 双档。


## 7. 实施记录（2026-10-07 回填）

**交付**：决策 1–12 落地。逐字七件（`Downloader`/`DownloadSession`/`LyricsTag`/三 Tagger/`LrcWriter`）+ `DownloadStore` 桌面重写（`AppFiles.dir("downloads")`、`.part`+改名、命名/编解码表逐字）+ `Downloads.kt` 移植（4 worker 排空取代 DownloadService，记录持久化到自有 `downloads.properties`）+ `MediaTagger`（ImageIO 替换 Bitmap，1000px/JPEG q92 不变）+ `EmbeddedLyrics` 回归（`forFile`）+ `LyricsCoordinator` 补回 `localUri` 分支 + 播放短路灯（`resolveUrl` 先查下载记录，引擎播文件路径不带 header）+ 右键菜单下载三态（进行中=取消/已存=删除/否则=下载，失败重试同形）+ `Destination.Downloads` + 下载页（按记录验证、按加入时间倒序）+ 侧边栏行 + 设置对话框音频/下载质量两组单选（`audioQuality` 首次落盘）。

**与设计的偏离**：

1. **`requested` 流未移植**：它服务于合集页"区分我点过的"——合集下载入口（BrowseActionsSheet）不在本片，记录随合集 API 一起回。
2. **菜单的下载行并入 `songActions` 主表**（设计说"另加两行"）：三态是同一状态的三张脸，放进同一个能力过滤函数测试表才完整；`SongContextMenuTest` 的既有断言随新行更新。
3. **`Downloads.prepare` 的 lossless 快问保留了，`requireM4a` 恒 false**（无导出，YouTube webm/opus 照存）。
4. **下载排空的 `drain` 退出时调 `onStopped()`**（服务 onDestroy 的语义，落在协程自然结束处）。
5. **测试适配**：`DownloadStoreTest` 裁掉源层/wifi/双档用例（层不存在），`DownloadSessionTest` 裁掉 collections 区段（API 未移植）、`@Before/@After` → `@BeforeTest/@AfterTest`、`assertArrayEquals`/`assertTrue` 的 JUnit 参数序换成 kotlin.test 形状（数组断言以文件内私有助手保持用例正文逐字）。
6. **探针扩展**：`bitchord.probeDownload=true`（首曲入队一次）与 `probeDestination=downloads`。

**验证**：31 套件 / 290 测试全绿（新增：移植 `DownloadSessionTest`（去合集区段）/`DownloadStoreTest`（判定表+命名表）/`MediaTaggerTest`（三容器逐字）+ 菜单三态用例 + 质量键往返）。**端到端实机**：`probeDownload` 全链路——`周杰倫 - 晴天.webm`（4.7MB）落盘 `%LOCALAPPDATA%/BitChord/downloads/`，`downloads.properties` 记录 videoId→路径，封面侧车进 `download-artwork/`，日志证 resolve→fetch→tag→embed→commit→remember 全通。截图三张（`.shots/slice6/`）：下载页空态（侧边栏行高亮）、设置对话框两组质量阶梯（Lossless/High 选中态）、英文浅色下载页。**实机待验**：右键三态的人工触发、下载行点击后离线播放（resolve 短路灯已在代码路径上，探针无法断网验证）、歌词面板读嵌入词（EmbeddedLyrics.forFile 已接进 Coordinator 的 localUri 分支）。
