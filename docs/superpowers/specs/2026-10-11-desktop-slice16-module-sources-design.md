# BitChord 桌面版 · 切片 16 设计：模块源体系（可插拔音源）

日期：2026-10-11
分支：`desktop-slice2-now-playing`（fork：xiongdy54/BitChord-for-Windows，上游 kushagrasinghx/BitChord）
状态：待用户复核

## 1. 背景

切片 8 的"本切片不做"给模块源体系留了判词："`SourceRegistry`/`ModuleSource`/JioSaavn/addon——`QualityUpgrade` 的第二眼与 `NerdStats` 的'无损竞速'在桌面永远没有 substrate，除非那天模块源移植，届时 audition 接缝直接挂上。" 此后每一片的"不做"清单都挂着"模块源体系"，切片 15 收讫 Goal 序列后，它是剩余功能里杠杆最大的一块：**Hi-Res 无损音源、YouTube 之外的目录、逐源健康检查**都压在它身上。

上游规模（逐文件核实）：`data/sources/` 19 文件约 6200 行（`SourceResolver.kt` 1158、`TrackMatcher.kt` 672——桌面已有、`SourceRegistry.kt` 457、`ModuleSource.kt` 603、`AddonSource.kt`+`addon/` 1560、`module/` 1460、`JioSaavnSource.kt` 108、`YouTubeSource.kt` 74、`DeviceCodecs.kt` 148），加 `data/jiosaavn/JioSaavnService.kt` 246 行。上游测试 `SourcesTest.kt` 1497 行 + `AddonSourceTest.kt` ~45KB + `AddonOrderTest`/`SourceFormatsTest`/`SharedCallsTest`，是现成的移植底料。

桌面现状：`data/sources/` 只有 `TrackMatcher.kt`（切片 6 移植，API 齐全）；`Song` 模型已有 `sourceQuality` 字段；`AudioEngine.play(url, headers)` 支持随流头（WebDAV 先例）；`PlayerController.resolveUrl` 是四段阶梯（WebDAV → 本地文件 → 下载记录 → `StreamResolver`），正是接缝。

## 2. 已确认的决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **复制-适配到 `desktop/`**，包名与目录结构同上游，`app/` 零改动 | 项目既定模式；便于日后与上游对照 |
| 2 | **JS 引擎：`io.github.dokar3:quickjs-kt-jvm:1.0.14`** | 与上游 `quickjs-kt-android:1.0.14` 同族同版，绑定 API 一致（`QuickJs.create`/`evaluate`/`define`）；已实测构件在 Maven Central 与本机镜像可达，jar 内含 `jni/windows_x64/libquickjs.dll`——Windows 原生库自带，模块脚本的 JS 语义与安卓完全一致 |
| 3 | **QuickJsExecutor 绑定面照抄**：`console.*`→TrackLog、`__spine.fetch`（OkHttp 专用 client、20s 超时、Chrome 130 UA 兜底）、JS 侧 `fetch` shim 与 polyfills（AbortController/Object.assign/Promise.any/allSettled/atob/URL）、IIFE 假 CommonJS 协议、`callOn` 双段 evaluate（答案暂存全局再取） | 绑定面小且完全可引用；同版引擎下逐字搬运即逐字等价 |
| 4 | **持久化：`AppFiles.file("sources.json")`**，kotlinx-serialization JSON 列表、逐条容错解码 | 源 URL 非机密，不上 DPAPI；沿用桌面 `FileStore` 体系；上游"一条坏枚举不毁全表"的语义保留 |
| 5 | **`AudioQuality.permits(kind)` 按上游语义补齐**：LOSSLESS→全通；HIGH→仅 `!canServeLossless` 的 kind；MEDIUM/LOW→仅 YouTube | 上游 `AppSettings.kt:72-83` 原文语义；桌面 `effectiveAudioQuality` 无 metered 拆分的现状不变 |
| 6 | **接入 `PlayerController.resolveUrl` 阶梯**：`src:{configId}::{trackId}` 键 → `SourceResolver.resolve`（pinned 路径，headers 随流）；普通 YouTube id 在 `canSubstituteForYouTube()` 时**竞速**（源查找 vs `StreamResolver.resolve`，20s 预算，先到先得） | 上游 `PlaybackService.resolveWithModulePriority`（:4468）的桌面等价物；resolveUrl 是"id 的函数"，与 prefetch 兼容 |
| 7 | **下载接入**：下载 worker 在 LOSSLESS/HIGH 档先走 `SourceResolver.forDownload`（LOSSLESS 走位精确源阶梯 + 其余竞速，HIGH 全体竞速、拒绝位精确、`beatsYouTubeAac` 底线 kbps>256）；Standard 档保持 YouTube-only | 上游 `Downloads.fromSources`（:1177）语义 |
| 8 | **搜索接入**：`SearchViewModel` 对 rank 高于 YouTube 的启用源并行搜索，命中行（`Song` 携 trackKey）插进歌曲区头部 | 上游 `MainViewModel:1991` 按 rank 分裂搜索的桌面落法 |
| 9 | **UI：设置对话框新增 Sources 组**（桌面形态，不另开整页）：源列表行（序号、kind 图标、显示名、状态行、开关、user-added 拖拽重排）、Add source 行、编辑器对话框（URL 字段 + Test/Save/Remove/Dismiss + 状态行，照上游 `AddonEditorAlert`）、进入时对 `needsServer` 源健康探测、addon manifest 名回写 label、JioSaavn 启用确认、`upgradeLengthSlackSeconds` 滑杆；WebDAV 组已存在不重复 | 上游 `SourcesScreen.kt` 语义折叠进桌面设置对话框；字符串靠上游合并拷贝现成键 |
| 10 | **`DeviceCodecs` 桌面化为常真**：libvlc 自带全套解码器（E-AC-3 JOC 可解），保留对象形状与 test seam | 上游探测的是 Android MediaCodec；桌面解码责任在 VLC |
| 11 | **`StreamContainer` 缺席的替代**：`AddonSource.openable` 的传输声明调用点以注释保留语义（`hls`/`dash` 随 URL 交给 libvlc 自嗅探），不移植 StreamContainer | 桌面无 Media3 ChunkedDataSource；libvlc 原生支持 HLS/DASH 容器嗅探 |
| 12 | **NerdStats 与播放器接缝**：`parseTrackKey` 接入（键行显示源显示名）；队列行对 source 行为照旧（右键菜单/收藏照 YouTube 行语义） | 上游 `NerdStats.kt:272/303` 对应物 |

## 3. 交付物

**数据层（`data/sources/`，自上游复制-适配）**
- `MusicSource.kt`（接口 + `StreamFormat`/`SourceStream`/`StreamRequest`/`SourceHealth`）、`SourceKind.kt`——纯数据，近零改动
- `SourceRegistry.kt`——EncryptedPrefs→`sources.json`；`init()` 去 Context 化；`active()`/`activeForPlayback()`/`identify`/`duplicateOf`/`probeCandidate`/`reorderAddons`/`trackKey` 全保留
- `SourceResolver.kt`——路由引擎：`requestForNow`（由 `effectiveAudioQuality` 派生）、pinned `resolve`、`substituteForYouTube` 竞速、`bestAcross`、`matchAndStream`/`streamBest`、`worthSwapping`/`isBetter`/`beatsYouTubeAac`、`forDownload`；`youtubeFallback` 保留语义（源流播放失败回 YouTube）
- `module/`：`QuickJsExecutor.kt`（JVM 化）、`ModuleManager.kt`、`ModuleIndex.kt`、`SpineModule.kt`、`ModuleResults.kt`、`SharedCalls.kt`
- `addon/`：`AddonClient.kt`、`AddonModels.kt`、`SourceFormats.kt`（OkHttp 桌面可用，原样）
- `ModuleSource.kt`、`AddonSource.kt`、`JioSaavnSource.kt`、`YouTubeSource.kt`（后者适配桌面 `StreamResolver`）、`DeviceCodecs.kt`
- `data/jiosaavn/JioSaavnService.kt`——246 行 HTTP API 客户端原样

**播放接入（`desktop/playback/`）**
- `PlayerController.resolveUrl` 阶梯插入源路径；竞速预算与让路语义注释齐全
- 头部/取消语义：`SourceStream.headers` 进 `engine.play`；协程取消不吞

**下载接入（`download/`）**
- LOSSLESS/HIGH 档的 `forDownload` 走源阶梯；失败回退 YouTube 路径不破坏现有管道

**UI（`ui/screens/`）**
- `SettingsDialog` 新增 Sources 组 + `SourceEditorDialog`（照 LyricsSettingsDialogs 的对话框形态，Panel 形式可被探针截获）
- `desktopStrings` 补桌面缺键（en/zh 各一份）

**构建（`desktop/build.gradle.kts`）**
- `io.github.dokar3:quickjs-kt-jvm:1.0.14`；显式 okhttp（对齐 ktor-client-okhttp 带入的版本，避免双版本冲突）

## 4. 关键技术点与风险

| 风险 | 对策 | 验证手段 |
|---|---|---|
| quickjs-kt JVM 的 `define`/异步任务泵语义与安卓版有差 | 绑定用上游同款 DSL；`callOn` 双段 evaluate 协议照抄（答案落全局再取，规避 evaluate 对 Promise 的 toString 行为）；引擎池参数（MAX_MODULES=12、每模块 3 引擎、512KB 栈）不变 | 内联模块脚本单测：searchTracks/getTrackStreamUrl 往返 + fetch 绑定回放 canned JSON |
| okhttp 版本冲突（ktor 3.5.2 带 okhttp 5 alpha） | 先 `dependencyInsight` 查实际解析版本，显式声明同一版本 | 编译期 + 依赖树检查 |
| 源竞速拖慢起播（addon 慢于 YouTube） | 上游语义原样：`select` 先到先得、YouTube 腿同场竞速、源腿让路给 `settledForLess`（桌面本切片暂以日志代替 QualityUpgrade 二看，注释留缝）；20s 预算、`SUBSTITUTE_TIMEOUT_MS` 常量不动 | 单测：假源快答/慢答/抛异常三态 |
| `TrackMatcher` 桌面版 API 缺口 | 已核实 `queries/ranked/withinSeconds/sharesArtist/hasConflictingAlbums/uniquelyMostCreditedCloseMatch` 全在 | SourceResolver 编译期即验证 |
| 健康探测/搜索打到真网 | 单测全用 JDK `com.sun.net.httpserver` 起 localhost fake server 或假 `MusicSource`；探针不新增真网依赖 | CI 无外网可跑 |
| 拖拽重排与设置对话框空间 | 沿 LyricsSettingsDialogs 的 440dp 对话框形态；行内上下移按钮替代触屏拖拽（桌面鼠标友好，语义同 `reorderAddons`） | UI 探针截图 |

## 5. 验证计划

1. **行为断言**（自动化，移植/新写 ~60 用例）：trackKey 往返与裸 videoId 拒认、`StreamFormat` 判无损只看 codec、`qualityTier` 自由文本、`malformed` 双 origin 拒认、`permits` 三档、`reorderAddons` 语义、`duplicateOf` 规范化、`SourceFormats` 四类识别、`SharedCalls` 在飞共享/TTL/失败不缓存、`bestAcross` 先到先得、`worthSwapping`/`isBetter`/`beatsYouTubeAac`、fake addon server 的 manifest/search/stream 协议（429 退避、404=miss、加密拒绝、行内 streamURL 兜底）、QuickJS 模块全链路（无网络）
2. **数据层探针**：`-PprobeSources=true`——注册一个本地 fake addon、健康探测、pinned 解析、下载阶梯走源（日志为证）
3. **UI 判读**（截图）：设置对话框 Sources 组（空态/两源态/编辑器态）中英各一套
4. **反向验证**：无源时 resolveUrl 阶梯与切片 15 行为逐字节一致（竞速短路为 StreamResolver 直通）

## 6. 本切片不做

QualityUpgrade 全家（`settledForLess`/`force`/`askByHand` 手工升级菜单行—— audition 接缝在桌面已备，等本切片 substrate 落地后独立成片）；`prefetchSubstitute` 读-ahead（桌面 prefetch 接缝 `PlayerController.onPrefetch` 另片）；SMB；一起听；Discord RPC/Scrobbling；per-connection 双音质档（桌面无 metered 概念，现状保留）；`upgrade_length_slack_seconds` 之外的隐藏设置。

## 7. 实施记录（2026-10-11 回填）

### 与设计的四处偏离

1. **持久化容器**：设计写的是 `sources.json`，实施用了 `sources.properties`（`FileStore`）——与 settings/downloads 同一容器，JSON 列表原样存在键里，逐条容错解码的语义不变；FileStore 自带写穿，省掉一份自造的原子写。上游"列表先落、迁移标记后落"的次序被显式保留并有注释（FileStore 没有两键原子编辑，次序代替原子性）。
2. **决定 8（搜索接入）撤销**：实施时核实上游 `MainViewModel.sourceResults`（:1986）**是从未被调用的死代码**——上游搜索实际也不 fan out 到源。桌面保持 YouTube-only 即为对齐，不搬死代码。
3. **`requestForDownload` 的 HIGH 档映射**：上游 `DownloadQuality.HIGH.maxKbps == MAX_VALUE`，靠数值自然映射到 `StreamRequest.Best`；桌面切片 6 把 HIGH 钉在 256（YouTube 阶梯上限，`DownloadStoreTest` 锁定）。为保住两边的语义，桌面映射按档位名而非数值：HIGH 对源仍是"best you have"（与上游结果一致），YouTube 路径继续用自己的 256。
4. **编辑器拆成 Panel + Dialog**：设置对话框之上的子对话框渲染在独立原生窗口里，截图管线看不到（`OutputDevicePanel` 的 KDoc 早已记过这课）。`SourceEditorDialog` 拆出 `SourceEditorPanel`，探针走内联渲染。

### 验证结果

- **单元测试全绿**（全套含存量）：新增 `SourcesTest`（24 用例：trackKey 往返、StreamFormat 三态、permits 四档、init 播种/迁移、reorder/duplicate 规范化、request 派生、worthSwapping/isBetter/beatsYouTubeAac、bestAcross 竞速与弃腿、qualityTier 自由文本、malformed 双 origin、ModuleIndex 类目扫描）+ `AddonAndModuleTest`（12 用例：JDK HttpServer 上的 addon manifest/search/stream 协议、健康三态、行内 streamURL 兜底、normalizeBase、**QuickJS 模块全链路**——Windows 原生 DLL 加载、fetch 绑定走真实 localhost HTTP、searchTracks/getTrackStreamUrl 往返、Spine 模板字面量预处理、错误以 JSON 落地不炸调用方）。
- **数据层探针**：`-PprobeDestination=sources` / `=sourceeditor`（新增两处探针目的地）截图两组，见 `.shots/slice16/`——来源组（JioSaavn 警告行、YouTube"始终开启"行、添加插件行、容差滑杆 3s）与编辑器（URL 字段 + 测试/保存/关闭，禁用态正确）。
- **反向验证**：无源时（seeded YouTube 唯一启用）`canSubstituteForYouTube()` 为 false，resolveUrl 阶梯短路为原四段路径——存量播放测试未动一行全部通过，即此路径的字证据。

