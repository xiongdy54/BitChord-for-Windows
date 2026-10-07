# BitChord 桌面版 · 切片 8 设计：SMTC 系统媒体控件 + 音质换流接缝

日期：2026-10-07
分支：`desktop-slice2-now-playing`
状态：设计中

## 1. 背景

切片 7 的设计（§6）把两块工程划给了切片 8：SMTC/媒体会话（媒体键、系统覆盖层、位置上报——Windows 侧要走 WinRT interop），以及"音质升级引擎与无损竞速"（上游 `QualityUpgrade.kt` 802 行 + 隐藏播放器试听 + 无缝换流）。 slice 7 还在质量徽章留了话："竞速两态随引擎缺席而缺席，徽章的接入面（测量快照 + `currentVideoId` 绑定）已就位"。

上游规模（逐项核实过）：

- **媒体会话**：`PlaybackService` 是 Media3 `MediaLibraryService`（`PlaybackService.kt:275`），transport 命令全部走 `SessionPlayer : ForwardingPlayer` 的重写（play/pause/seek/next/previous，`:6367-6592`）；12 个自定义 `SessionCommand`（favorite/autoplay/upgradeQuality/revert…，`:674-685`）；位置由 Media3 从 player 自动导出，服务不手写上报；媒体通知用 `DefaultMediaNotificationProvider`（`:1169-1175`）。桌面的对应物不是 Media3——是 Windows 的 **SMTC（System Media Transport Controls）**：媒体键、Win10/11 媒体覆盖层（Global Media Transport Controls）、任务栏缩略工具条都从它来。Win32 应用经 `SystemMediaTransportControlsInterop.GetForWindow(hwnd)` 领取。
- **音质升级引擎**：上游 `QualityUpgrade.kt` 的全部状态机（pending/followUps/forced/shelved/refused/asked/handAsked）都为"从模块目录里找一条更好的录音"服务——`SourceResolver.upgradeFor` 按标题/时长匹配第二来源的候选。**桌面没有模块源**（`data/sources` 只有 `TrackMatcher`，切片 7 实施记录第 1 条已写明"唯一来源是 YouTube 解析器"），上游引擎的 substrate 不存在；`NerdStats.racingLossless`（"无损竞速"）也随之没有真相可报——YouTube 阶梯里没有无损，挂一个"Loading lossless"徽章是永远的谎言。
- **音质切换对当前曲目**：上游核实结果是**没有**——`effectiveAudioQuality` 只在"下一次流被解析"时读（模块 resolver 与 YouTube 阶梯封顶处），改设置对正在播的曲目毫无作用。桌面上这还有一层放大：`prefetchNext` 会把下一曲的 URL 预解析进 `StreamResolver.recent`（20 分钟 TTL），换音质后下一曲仍从缓存拿到旧 ceiling 的 URL——上游的 open-time 解析没有这个问题，桌面有。

本切片的范围因此收缩为桌面诚实的那一半：

1. **SMTC 全量**（本片大头）——媒体键、系统覆盖层、元数据与封面、位置/拖动 seek。
2. **音质换流接缝**——"音质设置变化 → 当前曲目按新 ceiling 重选阶梯 → 静音试听证明 → 原位换流"，外加 `recent` 缓存清理让下一曲立即生效。这是对上游行为的**刻意桌面偏离**（理由见决策 7），而试听/换流的机制本身是上游 audition（`auditionUpgrade`，`PlaybackService.kt:4094-4190`）的桌面形：第二引擎实例、静音、证明、失败即无声放弃。

## 2. 已确认的决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **SMTC = WinRT interop，JNA 直写 vtable**（`com.sun.jna` 已在 Compose Desktop 类路径上；项目已在用 `Native.getComponentPointer` 取 HWND）。`GetForWindow(hwnd)` 绑定主窗口；命令面对应上游 SessionPlayer 重写的五件：play/pause/stop/next/previous + `PlaybackPositionChangeRequested`（拖动 seek，`ISystemMediaTransportControls2`） | 没有现成 JVM 库桥 Windows.Media；JNA COM 模式是唯一依赖零增量的路。repeat/shuffle 的自定义 SessionCommand 族无 SMTC 对应物（覆盖层按钮位固定），不进本片 |
| 2 | **所有 WinRT 调用走专职单线程执行器**（进程内一个 `Executors.newSingleThreadExecutor`，`RoInitialize(MTA)` 在其首任务里做）。回调查回到回调线程外的 Kotlin 门面 | AWT 已在 EDT 上 `CoInitialize` 过 STA；在 AWT 线程上 `RoInitialize(MTA)` 会 `RPC_E_CHANGED_MODE`。专职线程把 COM apartment 问题整个隔离 |
| 3 | **ABI 事实取自本机 Windows SDK 10.0.26100 头文件**（`winrt/impl/*.h` 的 ABI 块与 `um/SystemMediaTransportControlsInterop.h`），GUID/vtable 槽位逐项抄录，写死为常量并注释来源 | vtable 顺序错一位就是崩溃；SDK 头是权威，不凭记忆 |
| 4 | **门面拆两层**：`WinRtSmtcBackend`（COM 调用，`SmtcBackend` 接口的实现）+ `SmtcSession`（diff/节流/状态语义，持 `SmtcBackend`）。`SmtcSession` 纯 JVM，单测打假 backend | COM 层不可单测，把可测的语义层（何时推什么）与不可测的机械层（怎么推）分开 |
| 5 | **元数据 = Title + Artist + 封面**（`IMusicDisplayProperties.put_Title/put_Artist`；封面走 `Uri` → `RandomAccessStreamReferenceStatics.CreateFromUri` → `put_Thumbnail` → `Update()`）。AlbumTitle/TrackNumber 不进（需再 QI `IMusicDisplayProperties2`，覆盖层不展示，价值不抵面） | Song 上有的三样直接给；系统自己拉 https 封面 |
| 6 | **位置上报**：换曲/播放暂停切换/seek 时立即 `UpdateTimelineProperties`（Position/StartTime=0/EndTime/MinSeekTime=0/MaxSeekTime=duration），播放中每 2s 校正一次。SMTC 是 self-paced——系统从上次 Position 自行外推 | Windows 文档语义；高频推送是纯浪费，低频校正防漂移 |
| 7 | **音质接缝 = 桌面形引擎，明确偏离上游**：`AppSettings.audioQuality` 变化 → 清 `StreamResolver.recent` → 当前曲目在播且剩余够长时，`StreamResolver.repickFor(videoId, 新 ceiling)`（显式 ceiling、不读写 `recent`）重选阶梯 → 与当前 URL 不同则静音试听证明 → 主引擎 `play(url, startAtMs=当前位置)` 原位换流。上游只对下一曲生效且无换流；桌面单一设置 + prefetch 缓存污染使"下一曲生效"都不成立，故接缝把切换做成即时 | 上游的引擎没有桌面 substrate（决策见背景），但"换了音质设置却要等缓存过期才生效"在桌面是可见的坏行为；audition 机制（第二引擎、静音、证明、失败放弃）是上游 `auditionUpgrade` 的忠实桌面形 |
| 8 | **试听证明 = 第二 `VlcAudioPlayer`**（独立 `MediaPlayerFactory`，audition 后整体 release），volume 0，等 `onAudioStats` + `onLength` 双到位且 `|auditionLength − 当前时长| ≤ 2s`（时长未知时退化为"stats 到位"）；8s 超时。上游的 `MIN_PROVEN_LOAD_BYTES`/`warmRange` 无对应（vlcj 自己的 HTTP 栈，桌面没有共享缓存可暖），打开+解码+时长匹配就是桌面的证明 | 上游 `buildAuditionPlayer` 的"不开第二个 AudioTrack"桌面形 = volume 0；时长门是上游 `auditionVerdict` 的对应物 |
| 9 | **换流 = `engine.play(url, headers, startAtMs)`**：`AudioEngine.play` 增 `startAtMs` 参数（VLC 用 `:start-time=<秒>` 媒体选项），位置取 `engine.timeMs`（媒体钟，与 `previous()` 的既有决策一致）。换流后音量恢复、`playingUrl` 更新；徽章的测量随新流的 `onAudioStats` 自然刷新 | Media3 的 `replaceMediaItem+seekTo(position)` 桌面没有对应物——桌面引擎只有 play/seek，`:start-time` 是唯一能保证"从原位出声"的手段，先 play 再 seek 有竞态窗口 |
| 10 | **守卫全套照上游精神**：只在实际播放中换（暂停时换会把暂停的曲目放出来）；剩余 < 10s 不换（不值得一次音频切口）；一次一个 swap（新一轮取消旧一轮）；repick 结果与当前 URL 相同 → no-op（MEDIUM/HIGH/LOSSLESS 同 ceiling 的切换自然落空）；试听中换曲/取消 → `finally` 释放引擎、清竞速态；换流后立即报错走既有 `onError` 路径，不做上游 `watchUpgrade` 的回滚（时长门在试听时已把这类风险挡掉） | 上游 "every guard errs towards not swapping" 的原话；回滚的第二引擎接缝（再换回去）是纯增量风险 |
| 11 | **NerdStats 竞速态桌面形**：`qualityRacing: MutableStateFlow<Set<String>>` + `onQualityRaceStart/onQualityRaceEnd`（上游 `racingLossless` 的名字不搬——桌面竞速的不是"无损"而是"音质换流"，名字撒谎的徽章比没有徽章差）。徽章 racing 分支优先于 measured，文本用新字符串 `switching_quality`（"切换音质中"/"Switching quality"）——上游的 "Upgrading Quality" 对降级方向是谎言 | slice 7 的接入面原样可用；方向中立的措辞对升降两个方向都诚实 |
| 12 | **`PlayerState.isQualityUpgraded` 不启用**：它是 Media3 `EXTRA_QUALITY_UPGRADED` 的快照对应物（上游只喂给菜单的 revert 行），桌面换流不重排队列条目、无菜单消费面，写了就是死状态 | 与切片 7 的 "空集是死代码" 同一判据 |

## 3. 交付物

| 文件 | 内容 |
|---|---|
| `desktop/playback/smtc/SmtcBackend.kt`（新） | 接口：setEnabled/setPlaybackStatus/setMetadata/setTimeline/release + 按钮回调类型 |
| `desktop/playback/smtc/WinRtSmtcBackend.kt`（新） | 全部 WinRT/JNA 机械：combase（HSTRING/RoInitialize/RoGetActivationFactory）、Interop.GetForWindow、控件 vtable 槽位、DisplayUpdater/MusicProperties、Uri+CreateFromUri 封面、TimelineProperties 实例、按钮与 seek 事件的 COM 回调对象 |
| `desktop/playback/smtc/SmtcSession.kt`（新） | 语义层：diff（同状态不重推）、状态映射（song==null → Closed）、命令分派回调；纯 JVM 可测 |
| `desktop/playback/PlayerController.kt` | `playingUrl` 记录、`audioQuality` 收集器（清缓存 + 评估换流）、audition 状态机（`repickUrl`/`auditionEngine` 两个测试缝）、`NerdStats` 竞速接线 |
| `desktop/playback/AudioEngine.kt` + `VlcAudioPlayer.kt` | `play(url, headers, startAtMs = 0L)`（VLC `:start-time=` 选项）；`FakeAudioEngine` 同步 |
| `data/innertube/StreamResolver.kt` | `repickFor(videoId, maxKbps)`（InnerTubeX→extraction，显式 ceiling，不读写 `recent`）+ `onQualityChanged()`（清 `recent`） |
| `data/NerdStats.kt` | `qualityRacing` 流 + start/end |
| `ui/player/PlayerControls.kt` | `LosslessOrStats` racing 分支 |
| `desktopStrings/{values,values-zh}/strings.xml` | `switching_quality` |
| `Main.kt` | `SmtcSession` 创建/接 HWND/状态收集器/2s 位置校正/命令回接/quit 释放 |
| 测试 | `SmtcSessionTest`（状态映射与 diff 表）+ `QualitySwapTest`（换流状态机：换/不换/试听失败/剩余不足/暂停不换/中途切歌放弃/竞速翻转） |

## 4. 关键技术点与风险

| 风险 | 对策 | 验证手段 |
|---|---|---|
| WinRT vtable 槽位或 GUID 抄错 → 崩溃或静默失灵 | 全部取自本机 SDK 头文件并注释来源；backend 每个调用 `runCatching` + HRESULT 检查 + TrackLog，任何一步失败整个 backend 转入 dead 态不再调用 | 实机探针：attach 后读 `get_IsEnabled` 回显、推一次状态后由覆盖层人工确认 |
| COM apartment 冲突（AWT STA vs WinRT MTA） | 决策 2 的专职线程；回调线程只做 Kotlin 转发 | 实机长会话（播放数分钟）无崩溃 |
| 换流的音频切口比预期长（VLC 重新 open） | 上游同款取舍：missed upgrade 是安静的失败，interrupted song 不是——守卫全套向"不换"倾斜；切口写进 KDoc 不粉饰 | 实机听感 + DebugLog 的换流行 |
| audition 在 vlcj 事件线程回调 → 与队列线程竞态 | `CompletableDeferred` 收 stats/length；换流落地前的 videoId 复查在 queueDispatcher 上 | `QualitySwapTest` 状态机表 |
| `recent` 清理过猛（清掉的 URL 各自重解析，多付网络） | 这是延迟缓存不是存储——清理只发生在用户显式换音质时，代价一次性的重解析 | 行为断言：换音质后下一曲的 resolve 日志走 InnerTubeX 而非 recent 命中 |
| SMTC 元数据残影（换曲后覆盖层仍显旧标题） | diff 以 song 为键：videoId 变化即重推元数据；song==null → ClearAll + Closed | `SmtcSessionTest` diff 表 |
| 测试污染全局 `AppSettings.audioQuality` | Rig 的清理恢复原值；换流测试注入假 audition 引擎工厂，不触真 vlcj | 套件全绿 |

## 5. 验证计划

1. **单元测试**：`SmtcSessionTest`（初始推送/diff/换曲/暂停/空态/seek 请求）+ `QualitySwapTest`（决策 8–10 的判定表）+ 既有 37 类全绿。
2. **行为断言（实机探针）**：播放真实曲目 → DebugLog 出现 SMTC attach 成功行；按媒体键（play/pause/next/previous）→ 日志记录命令且播放随动；拖覆盖层进度条 → seek 落到媒体钟。
3. **换流断言（实机）**：播放在途时把音质从"无损"切到"低"，听/日志确认当前曲目换到 ≤64kbps 档（`TIMING`+`resolved … @ …kbps` + 换流行），徽章闪"切换音质中"后落到测量档；切回无损同路。
4. **视觉判读**：`.shots/slice8-badge-racing.png`（换流中的徽章）→ 本片覆盖层是系统 UI，不在窗口截图范围内，以行为日志与人工确认为准（写进实施记录）。
5. **常量核对**：`switching_quality` 双语、AUDITION 超时/时长容差/剩余下限、位置校正周期 2s 进测试或 KDoc。

## 6. 本切片不做

模块源体系（`SourceRegistry`/`ModuleSource`/JioSaavn/addon——`QualityUpgrade` 的第二眼与 `NerdStats` 的"无损竞速"在桌面永远没有 substrate，除非那天模块源移植，届时 audition 接缝直接挂上）；手动"升级音质"菜单行（上游 `upgradeQualityNow`，无第二源即无升级目标）；OriginalVersion/音视频版本换流；repeat/shuffle 的覆盖层自定义命令面（SMTC 按钮位固定，无对应物）；Discord RPC、Scrobble、桌面小组件；媒体通知（桌面的"通知"就是媒体覆盖层本身）。
