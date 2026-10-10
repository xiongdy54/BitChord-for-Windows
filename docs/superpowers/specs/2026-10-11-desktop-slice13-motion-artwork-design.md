# BitChord 桌面版 · 切片 13 设计:运动封面(CanvasArtworkPlayer 族)

日期:2026-10-11
分支:`desktop-slice2-now-playing`
状态:已完成(实施记录见 §7)

## 1. 背景

切片 2 的 §3.3 判词:"运动封面不做:`CanvasArtworkPlayer`(TextureView 抽帧)留到以后"。桌面 `NowPlayingScreen.kt` 头部注释(392–417 行)留了完整的欠条清单:`CanvasArtworkPlayer`、hero clip、帧取色、canvas 四状态、Spotify presentation state——并逐条点名要恢复的上游常量行号;该文件是 **append-only 契约**(370–377 行 marker 协议),新增动封面代码必须按协议追加。

盘点澄清了一个容易望文生义的点:**运动封面不是动图,是循环播放的短视频**(MP4 与 HLS,Spotify Canvas/Apple motion artwork/Tidal 一族)。检测不来自 Innertube(上游完全不读 YouTube 动封面字段),而是按 标题/歌手/专辑 在 4 个外部目录做文本搜索 + 严格匹配复核:`data/canvas/` 六件——`CanvasArtwork.kt`(130,模型 + NFD 归一化严格匹配 + canvasGet)、`CanvasRepository.kt`(228,provider 链 + LRU 含负缓存 + Mutex 串行化 + `canvasFor`/`canvasForAlbum` 双入口)、`CommunityCanvas.kt`(133,manifest 搜索)、`TidalCanvas.kt`(161,→ `1280x1280.mp4`)、`AppleMusicCanvas.kt`(444,→ HLS m3u8)、`SpotifyCanvas.kt`(381,GraphQL+REST 双路 + **手写 protobuf** 读 canvaz-cache,桌面已有 protobuf-javalite)——**约 1,477 行纯 JVM 可逐字移植**。渲染层 `ui/player/CanvasArtworkPlayer.kt`(703 行)= 第二个刻意低调的播放器(volume=0、REPEAT_MODE_ONE、禁音频轨)→ TextureView 抽帧 → 底部渐隐/变换/取色;`PlayerState` canvas 段(45 行)与设置(`animated_canvas` 等 4 键)。

桌面管道现状:vlcj 4.12.1 在依赖里(原生解 MP4/HLS,无"桌面无解码器"风险);帧进内存(direct surface)后上游 `getBitmap` 的 GPU 回读在桌面反而是免费;`Http.client`、protobuf、kotlinx-serialization 全在 classpath;`NowPlayingScreen` 的 hero 是静态 `AsyncImage` + DstIn 渐隐,取色/网格渐变管道(`ArtworkPalette`/`ArtworkMeshBackdrop`)健在。

## 2. 已确认的决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **数据层六件逐字移植**(~1,477 行):`CanvasArtwork/CanvasRepository/CommunityCanvas/TidalCanvas/AppleMusicCanvas/SpotifyCanvas`;`Http.client` 同名同形,`SpotifyCanvas` 无 token 时本就是 no-op | 纯 JVM 的逐字移植是本项目纪律;四 provider 里三个不需要任何账号,Spotify 无凭据自动缺席——降级链原样成立 |
| 2 | **`SpotifyToken` 不做**(Android WebView 离屏 harvest);桌面 JCEF harvest 留作独立后续件 | harvest 是交互式 cookie 流,值一个 provider;三 provider 无账号可用,Spotify 缺席不破族;`prioritize_spotify_canvas` 开关仍移植(为将来生效预留) |
| 3 | **渲染 = 桌面新写 `ui/player/CanvasArtworkPlayer.kt`**:第二个 `MediaPlayer`(独立 `MediaPlayerFactory`,与音频引擎平行——上游"第二个刻意的 ExoPlayer"的结构对等)+ vlcj `CallbackVideoSurface`/`RenderCallback` RV32 帧进内存 → Skia `Bitmap.installPixels` → `ImageBitmap` → Compose `Canvas`;上游状态机照抄:`REPAINT_TIMEOUT_MS=700` 表面重建超时回静帧、`onPlayerError` 换 `fallbackUrl` 一次、320ms 淡入、回调契约(`onRenderedChanged/onFrameCaptured/onCoverChanged/refreshFrameEveryMs`);CROP/FIT_PORTRAIT 变换在 Compose 层用 `drawImage` 的 src/dst rect 重做(`Matrix`/`RenderEffect`/`PorterDuff` 全不可用);底部渐隐用既有 `DstIn` 渐变刷(HERO 渐隐同族);**帧取色免费**(帧本在内存,降采样 128px 喂 `ArtworkPalette` 的既有管道,照上游 `FRAME_CAPTURE_PX=128`)。**vlcj 视频回调 API 以 javap 核实后落笔**,不猜 | vlcj 的帧回调是标准通道;与音频引擎隔离是上游结构决策(音量 0、循环单轨、禁音频轨),桌面照抄避免与 `VlcAudioPlayer` 抢实例状态 |
| 4 | **缓存 = 文件缓存新写 `data/canvas/CanvasCache.kt`**(桌面形):MP4 按 URL hash 落 `%LOCALAPPDATA%\BitChord\canvas\<hash>`,命中即本地路径给 vlcj,未命中边下边播(直接 URL);LRU 容量照上游 150MB;**HLS 不缓存**(m3u8 是分段流,桌面 v1 直接流播,KDoc 写明每圈重取段成本的现状) | 上游 `CanvasCache` 的"没有它每圈重下载"是 40MB/首事故的教训;MP4 占三 provider,HLS(仅 Apple)流播是可接受的 v1 诚实态;Media3 SimpleCache 无桌面对应物,文件缓存是其最小诚实等价 |
| 5 | **接线 = NowPlayingScreen hero**(append-only 协议):canvas 四状态(`canvasRendered/canvasFrame/canvasCover/canvasAspect`)、`LaunchedEffect(videoId, albumName)` 先 `cached()` 后 `canvasFor()`、`canvasAllowedNow`(桌面无 metered 概念 → `= animatedCanvas`)、淡入/回退静帧链;`ALBUM_SETTLE_MS=700` 等点名常量恢复 | 欠条清单的逐条回收;append-only 是该文件自己的契约 |
| 6 | **设置**:AppSettings 加 `animatedCanvas`(默认 true)+ `prioritizeSpotifyCanvas`(默认 false)两键;设置对话框"播放"组加〔动画封面〕开关(+〔优先 Spotify Canvas〕副开关,条件渲染于前者之下,照上游层级);`canvas_over_cellular` 不做(桌面无 metered 域)、`spotify_canvas_auto_hide` 不做(其 UI 是玻璃 deck) | 键名照上游以保"升级不丢"语义;开关族在设置对话框已成范式 |
| 7 | **不做**:Spotify auto-hide 玻璃 deck(上游自认成本最高的 live behaviour,独立后续件);`SpotifyToken` JCEF harvest;DetailScreen 专辑页头动封面、`LandscapePlayer` 接线(hero 是唯一在场表面,其余面随各自后续片);HLS 缓存;`canvas_over_cellular` | 控制范围在"族的核心"——数据层 + hero 渲染 + 设置;其余面有各自切片 |
| 8 | **探针**:`bitchord.probeCanvas=<videoId>` 无头解析(打印 source/url/匹配判定),与 `probeDestination=localmusic` 同族;截图经 `probeOpenPlayer` | 目录搜索是网络行为,无头探针先行验证匹配与 URL 形;视觉上动封面是视频,截图只能证"帧在画" |

## 3. 交付物

| 文件 | 内容 |
|---|---|
| `data/canvas/` 六件(新) | 逐字移植(~1,477 行) |
| `data/canvas/CanvasCache.kt`(新) | MP4 文件缓存(LRU 150MB、hash 键、命中本地路径) |
| `ui/player/CanvasArtworkPlayer.kt`(新) | vlcj 帧回调 → Skia 渲染 + 上游状态机/回调契约/渐隐/变换(桌面形,450–600 行) |
| `ui/player/NowPlayingScreen.kt` | append-only 追加:canvas 四状态 + hero 接线 + 常量恢复 |
| `data/settings/AppSettings.kt` | `animatedCanvas`/`prioritizeSpotifyCanvas` |
| `ui/screens/SettingsDialog.kt` | 播放组两开关 |
| `desktop/Main.kt` + `build.gradle.kts` | `probeCanvas` 探针 + 键登记 |
| 测试 | `CanvasArtworkTest`(严格匹配表:NFD 归一化/feat. 拆分/多歌手分隔/噪声剔除)、`CanvasRepositoryTest`(缓存含负项/Mutex 串行)、CanvasCache(LRU 淘汰/命中) |

## 4. 关键技术点与风险

| 风险 | 对策 | 验证手段 |
|---|---|---|
| vlcj 视频帧回调 API 形状(RV32 格式、线程域、surface 生命周期) | javap 核实后才写渲染层;回调线程域按 vlcj 文档固定,帧拷贝到 Skia 位图在回调内最小化 | 探针 + 实机播放器目视 |
| 帧率/性能(720×1280@30fps BGRA → Skia) | installPixels 是 memcpy 级;若实测掉帧,降采样到显示尺寸再上传(CROP dst 已知) | 实机播放动封面曲目观察 |
| 目录搜索的文本匹配误命中 | 上游 `matches()` 严格复核 + 负缓存原样;匹配测试表钉死 | 单测 |
| HLS 每圈重取段的流量成本 | v1 如实(KDoc + 记录);Apple 源排位靠后且 MP4 源占多数 | 常量核对(默认 provider 序) |
| canvas 是装饰,永远不许弄坏播放器(上游 `CanvasRepository.kt:27` 原则) | 全链 `runCatching`;解析/解码/渲染任何失败回静帧,`REPAINT_TIMEOUT_MS` 兜底 | 单测 + 实机(断网开播放器) |
| NowPlayingScreen append-only 契约被破坏 | 只按 marker 协议追加;`git diff` 审查确认无既有段改写 | 代码审读 |

## 5. 验证计划

1. **单元测试**:§3 测试行,既有 49 类全绿。
2. **行为断言(实机探针)**:`probeCanvas=<videoId>` 打印解析结果(命中的 provider、URL 形、匹配判定);播放器打开动封面曲目 → 日志出 canvas 解析行 + 帧回调行。
3. **视觉判读**:`.shots/slice13/`——hero 显示动封面帧(区别于静帧构图)、回退静帧态、设置两开关;zh-dark 取代表。
4. **实机(人手)**:播放带 Canvas 的曲目(如上游支持清单内流行曲)→ 动封面循环、随曲切换无残影、暂停随播放器、静音确认;断网 → 回静帧不崩。

## 6. 本切片不做

Spotify auto-hide 玻璃 deck;`SpotifyToken` JCEF harvest(Spotify 源在本片缺席但架构就位);DetailScreen/LandscapePlayer 的动封面面;HLS 缓存;`canvas_over_cellular`;交叉淡入淡出、Equalizer(Goal 后件);登录/本地音乐/下载面板(已在 10–12 收讫)。

## 7. 实施记录(2026-10-11 回填)

**交付**:决策 1–8 落地。`data/canvas/` 六件逐字移植(CanvasArtwork/CanvasRepository/Community/Tidal/AppleMusic/Spotify 含手写 protobuf);`SpotifyToken.kt` 桌面占位(上游函数名,答 null——JCEF harvest 后续片只填这两个函数,SpotifyCanvas 本体不动);`CanvasCache.kt`(MP4 按 URL hash 落 `AppFiles.dir("canvas")`、后台预取下次用、首播直连流、LRU 150MB、HLS 永不缓存);`CanvasArtworkPlayer.kt`(640 行:`CallbackMediaPlayerComponent` + `RenderCallbackAdapter` RV32 帧 → Skia `installPixels` → Compose Canvas;CROP/FIT_PORTRAIT 用 `drawImage` src/dst rect;底部渐隐 DstIn 同族;上游状态机照抄——320ms 淡入、fallbackUrl 一次、700ms 兜底、帧 128px 喂取色);NowPlayingScreen 按 append-only 协议追加(四状态 + hero 接线 + `ALBUM_SETTLE_MS=700L`/`MESH_REFRESH_MS=3_000L` 恢复 + `rememberCanvasArtwork`,`canvasAllowedNow = animatedCanvas`);ArtworkMeshBackdrop 收到 `canvasFrame`(含上游黑帧拒绝);设置两开关(`animated_canvas`/`prioritize_spotify_canvas` 上游键名);`probeCanvas` 探针双登记。

**javap 关键发现**:vlcj 的直接缓冲是 `ByteOrder.nativeOrder()`,libvlc RV32 写 B,G,R,A → int 恰为 Java `0xAARRGGBB`——**无需 R/B 交换**(与 vlcj 自家渲染器写 `TYPE_INT_ARGB` 同一事实),KDoc 记录;`CallbackMediaPlayerComponent` 的 Swing surface 参数可空(native add 判空,bytecode 核实);adapter 复用同一 int[],故回调内拷贝(`buffer.copyOf`,libvlc 视频线程域)。

**与设计的偏离**:

1. **`REPAINT_TIMEOUT_MS` 看门狗换钥**:上游盯"表面重建"(TextureView 的 surface 会丢),桌面内存回调面无 surface 丢失——同一常量、同一判词,改为"媒体 700ms 未产帧"。
2. **挂载恒为 banner 界**(`fillMaxWidth().height(heroHeight)`),非上游 portrait 的 `fillMaxSize`——无 Spotify 全屏 presentation 则全屏 portrait 情形不存在;FIT_PORTRAIT 链保留待 harvest。
3. **`portraitRevealBounds`/`bottomFadeEndPx` 不入参数表**:它们是 TextureView 首帧对布局的栅栏与 Android mask 的指向器;桌面渲染器自己映射视频矩形并在映射矩形上渐隐。
4. **CanvasRepository 的测试缝**:`internal fun songChain(spotifyFirst)` 造 ChainLink 列表传入 internal 重载(object 无构造器);生产语义不变,双序皆有测试钉名。
5. **测试驱出真 bug**:缓存键最初哈希原始 URL 而非去查询参数的路径——单测抓出修正。

**验证**:52 类 482 测试全绿(新增 38:匹配表 16/仓储 13/缓存 9)。实机探针三连(真实网络):`SJKoWAd5ySo`(晴天)→ **负路径全链正确**——Apple web token 自动获取("good until Dec 11 2026")、社区 manifest 187 条命中 'Dracula' by 'Tame Impala' 后被严格复核**正确拒绝**(冒名顶替者)、Spotify 无 token 跳过、3.9s 归 none;`dQw4w9WgXcQ` → none;`G7KNmW9a75Y`(Flowers — Miley Cyrus)→ **正路径命中**:Apple Music 报 "motion artwork on album 'Endless Summer Vacation'",产出 HLS `mvod.itunes.apple.com/…​.m3u8`,8.5s。设置开关截图 `.shots/slice13/settings-canvas-zh-dark.png`。**人手验证待做**:播放器 hero 的动封面实际渲染与循环(当日 YouTube 流解析遭 bot-check,播放不可脚本化)、断网回静帧、性能(帧转换上界 `DISPLAY_MAX_EDGE=1280` 分配,掉帧则降到 hero 像素高——记录在案)。

**已知瑕疵**:Spotify 源本片缺席(token harvest 是后续件),`heroMode` 的 OR 支路注释写明在等它;HLS 每圈重取段(设计决策 4 的 v1 诚实态);渲染性能未经 GPU 窗口实测。
