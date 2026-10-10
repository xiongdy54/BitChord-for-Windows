# BitChord 桌面版 · 切片 14 设计:交叉淡入淡出

日期:2026-10-11
分支:`desktop-slice2-now-playing`
状态:设计定稿(实施记录见 §7,实施后回填)

## 1. 背景

切片 9 的"本切片不做"给它留了独立成片的判词:"交叉淡入淡出(`CrossfadeController.kt` 1815 行,双引擎接缝独立成片)"。

上游设计的全部洞察在 `CrossfadeController.kt` 头部 KDoc(35–98 行):**两个对等引擎,角色互换而非 seek**。上一版单播放器音量斜坡在接缝处有静音坑;把离去轨道放第二播放器会双渲染同一音频(实测错位 9–41ms);现行设计把 **incoming 轨道**装上 standby 引擎(volume=0、cue 点起播、暂停待命),两引擎永不持有相同音频。状态机 `IDLE → ARMING → FADING`(旁支 SLEEP_FADE/BAILING):tick 里 `remaining ≤ fade + ARM_LEAD_MS(4000)` 即 arm;arm = 队列复制到 standby + cue 点起播;到点 `startFade()` = incoming 出声 + **handoff 在第一声时触发**(会话/通知/位置时钟瞬间换人)+ outgoing 队列截断使其自然终了;fade 进度由 incoming 自身位置驱动(暂停即停摆);等功率曲线 `sin(p·π/2)/cos(p·π/2)`(sin²+cos²=1,无中点凹陷);**手动 skip 即 bail**(BAIL_MS=120 斜坡,"crossfade 是轨道播完的属性,不是被换掉的属性");repeat-one/无下一曲/队列替换/ListenTogether 全不 arm;SleepTimer.afterTrack 走同长淡出分支。设置:`crossfadeSeconds`(0–12s,11 档),字符串 `crossfade`/`crossfade_subtitle` **桌面合并拷贝已自带**。

桌面现状:`AudioEngine.play(url, headers, startAtMs)` 的 `:start-time=` 输入选项**正是 cue 点原语**(打开时生效,竞态安全);第二并发 libvlc 实例已有两处先例(质量换源的 auditionEngine 整建整毁、切片 13 的 CanvasArtworkPlayer 常驻);`PlayerController` 的 advance 流程、推送式位置(`engine.onTime`)、`watchSleepTimer` 的常驻协程模式都是接缝的现成挂点;`FakeAudioEngine` 可推时间、记录音量——测试驱动方式现成。

## 2. 已确认的决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **重写为主、语义逐条对照**(非逐字):上游 1815 行里约 45% 是 KDoc、约 500–600 行是 Automix 滤波系(`playback/smart/` 全家 13 文件 5201 行)——**Automix/smart fade 全家裁出本片**(独立后续件);可移植的状态机核心(Phase 枚举、arm/consider/begin/startFade/driveFade/bail/finish、等功率曲线、sleep-fade 分支、全部守护与常量)以桌面方言重写,约 450–650 行,每条上游注释的语义在 KDoc 里对号 | 状态机是智力资产要保真;Automix 是独立家族(切片 9 判词已分家);Media3 绑定三处(ExoPlayer 引用/STATE_*/setMediaItems+cue)在桌面有直接对应物 |
| 2 | **第二引擎常驻会话,`PlayerController` 双持有**:`engine`(active)+ `standbyEngine`(惰性建,首个 arm 时建一次,整会话复用——auditionEngine 的 KDoc 已警告 factory 创建成本);上游 `active={}/standby={}/onHandoff` 三参注入同形;**handoff = 换 `engine` 字段引用**(与上游 adoptPlayer 最同构):把 init 里的 8 处 `engine.onXxx` 挂接抽成私有 `bindEngine(e)`,handoff 时 `bindEngine(incoming)` + SMTC/状态流的读取本就经 `engine` 字段自动指向新引擎 | 上游 onHandoff 的全部内容(焦点/监听器/角色交换/会话指针)在桌面的对应物就是字段换绑;常驻是 auditionEngine 注释的成本结论 |
| 3 | **`AudioEngine` 接口加两个成员**(带默认实现,`FakeAudioEngine`/未来引擎不为不支持的设备付假实现——切片 9 决策 4 判例):`prepare(url, headers, startAtMs)`(打开即静音暂停,cue 点待命——上游 `setMediaItems+cue+playWhenReady=false` 的对应物)与 `resume()`(解除暂停)。`VlcAudioPlayer` 落地:`play()` 后立即 `controls().setPause(true)`(或 media options `:start-paused`,以 javap 核实者为准) | 接口是"声音卡面的接口"(切片 9 决策 4 原话);cue 待命是 crossfade 的原子需求;默认实现保住所有既有调用面 |
| 4 | **状态机常驻协程**:仿 `watchSleepTimer` 的 `collectLatest` 形,`while(isActive)` tick 读 `engine.timeMs`(媒体钟,非 UI 钟)对比 `state.durationMs`;tick 步长照上游(IDLE 250/ARM 40/FADE 30/BAIL 15ms);触发条件逐条对照:isPlaying、非质量换源进行中(桌面 `qualitySwapJob` 在场即守)、`queue.hasNext()`、duration 已知、非 repeat-one、`crossfadeSeconds > 0` | 推进信号的唯一入口(engine.onFinished→advance)不动——crossfade 的 handoff 恰恰要**抑制**那个入口一次(outgoing 截断后其 onFinished 落在已换绑的旧引擎对象上,自然终了走 onFinished 但 engine 字段已指向 incoming;以 handoff 标志判别丢弃该次 advance) |
| 5 | **skip 即 bail**:`next()/previous()/jumpTo/seekTo` 四入口插 `crossfade.onSkipRequested()`(handoff 前无事发生,handoff 后 120ms 斜坡压掉 outgoing);队列**替换**类操作(新 playOneOff/playRadio)同理 bail | 上游"被换掉的不配 crossfade"语义;四入口是桌面全部换曲面 |
| 6 | **设置与 UI**:AppSettings 加 `crossfadeSeconds: Int`(0–12,默认 0,KEY `crossfade_seconds` 照上游);设置对话框"播放"组加滑杆行(0=关);字符串零新增(`crossfade`/`crossfade_subtitle` 已随合并拷贝) | 上游范围 0–12s steps=11 原样;smartFade 行不做(Automix 裁出) |
| 7 | **不做**:Automix/smart fade 全家(`playback/smart/` 13 文件 + CrossfadeController 的滤波器常量段约 20 个 + analysisFor/requestAnalysis 等六注入参——桌面构造器全部省略);`smartFadeEnabled`/`smartMixInProgress`/`smartTransitionWindow` 三键;ListenTogether 互斥(一起听本就不在桌面);版本换源的 550ms 交叉淡出(桌面质量换源走 auditionEngine 试听缝,接缝不同,互斥守卫保留但换源斜坡不搬) | 宁少给;Automix 是"歌曲交接点的滤波器编排",与 crossfade 的接缝只共享引擎对 |
| 8 | **验证先行**:测试驱动方式 = `FakeAudioEngine` 扩双实例记录(playedUrls/volumes 序列)+ `tick(ms)` 推时间;PlayerControllerTest 的 Unconfined 内联模式;状态机表:arm 窗口/触发点数学/repeat-one 不 arm/无下一曲不 arm/bail 无爆音/handoff 后 advance 抑制/等功率曲线端点 | 全部纯 JVM 可测;真实音频的接缝听感人手验证 |

## 3. 交付物

| 文件 | 内容 |
|---|---|
| `desktop/playback/CrossfadeController.kt`(新) | 状态机桌面形(450–650 行):Phase/tick/consider/begin/startFade/driveFade/bail/sleep-fade/finish/等功率曲线/常量全族 |
| `desktop/playback/AudioEngine.kt` | `prepare`/`resume` 两成员(默认实现) |
| `desktop/playback/VlcAudioPlayer.kt` | prepare(cue 点静音暂停)/resume 落地 |
| `desktop/playback/PlayerController.kt` | 双引擎持有 + `bindEngine` 抽取 + handoff 换绑 + tick 协程 + 四入口 bail + advance 抑制 + sleep-fade 接线 |
| `data/settings/AppSettings.kt` | `crossfadeSeconds`(0–12) |
| `ui/screens/SettingsDialog.kt` | 播放组滑杆行(字符串零新增) |
| 测试 | `CrossfadeControllerTest`(状态机表)+ `PlayerController` 既有测试扩 handoff/bail 行 + FakeAudioEngine 双实例扩展 |

## 4. 关键技术点与风险

| 风险 | 对策 | 验证手段 |
|---|---|---|
| vlcj"打开即暂停"的原子性(play 后 setPause 有竞态窗口) | javap 核实 `:start-paused` 输入选项;不可用则 prepare 内 play(volume=0)→setPause——静音窗口内无声音外泄,竞态无听众 | javap + 探针日志 |
| 双流并发解码的 CPU(两路 FLAC 同时解) | 常驻引擎复用已省 factory 成本;FADE 段仅数秒;若实机卡顿,记入瑕疵 | 实机听感 |
| 音量 0–100 整数量化的曲线 stair(1% 步长) | 30ms tick 下 1% 步进 ≈ 33 步/秒,可听性可接受(上游 0–1f 连续,桌面如实记差异) | 实机听感 |
| handoff 换绑漏一处监听(8 处 onXxx) | `bindEngine` 单点抽取;测试断言换绑后 onTime/onFinished 落在 incoming | 单测 |
| advance 抑制的标志泄漏(handoff 丢弃一次 onFinished 后忘清) | 抑制标志 handoff 内同步消费;状态机 finish 必清 | 单测 |
| outgoing 的 SMTC 位置在 fade 段读旧引擎 | engine 字段换绑发生在第一声时——位置/歌名/时长瞬时切到 incoming(上游 MediaSession 换人的同构时刻) | 单测 |
| 与睡眠定时的 both-armed 冲突 | afterTrack armed → tick 走 sleep-fade 分支(不 arm 双引擎),照上游 575/715 行分支 | 单测 |

## 5. 验证计划

1. **单元测试**:§3 测试行,既有 52 类全绿。
2. **行为断言(实机探针)**:`bitchord.probeCrossfade=<秒>`(设秒数 + `probePlayVideoId` 播两曲队列,日志出 arm/fade/handoff 行);`probeDestination=player` 截图滑杆行。
3. **视觉判读**:`.shots/slice14/`——设置滑杆行 zh-dark;播放页回归(接缝改造后布局无恙)。
4. **实机(人手)**:开 6s crossfade 播放长队列 → 接缝听感(无静音坑/无爆音/无双声)、暂停时 fade 停摆、手动 skip 干脆、睡眠定时"播完此曲"仍正确、质量换源与 crossfade 不相撞。

## 6. 本切片不做

Automix/smart fade 全家(`playback/smart/` 13 文件 5201 行 + 滤波器常量段,独立后续件);smartFade 三键;版本换源斜坡;ListenTogether 互斥;Equalizer(下一片);登录/本地/下载面板/运动封面(10–13 已收讫)。

## 7. 实施记录(实施后回填)

交付:`desktop/playback/CrossfadeController.kt`(新,状态机全族:IDLE/ARMING/FADING/SLEEP_FADE/BAILING、tick 步长 250/40/30/30/15、ARM_LEAD_MS=4000、ARM_TIMEOUT_MS=12000、BAIL_MS=120、等功率 sin/cos 曲线、fadeFor=min(秒×1000,时长/3)、sleep-fade 分支、bail 无爆音斜坡——常量逐字照搬);`AudioEngine.prepare`(默认实现=play+pause,带竞态注释)/`VlcAudioPlayer.prepare`/`resume` 落地;`PlayerController` 双引擎(`engine` 改 var + `standbyEngineInstance` 惰性建、整会话复用、handoff 角色互换)+ `bindEngine` 抽取 + `onCrossfadeHandoff`(≈adoptPlayer:监听器迁移、字段先行、queue.next() 移针、NerdStats.clear、时长换座、prefetch、autoplay top-up)+ 六处 bail 入口(next/previous/jumpTo/seekTo/seekToFraction/resolveAndPlay)+ advance 前置 `onSessionEnded` 消费 + 质量换源互斥;`AppSettings.crossfadeSeconds`(0–12,KEY `crossfade_seconds`,照上游);设置对话框播放组滑杆行(`crossfade`/`crossfade_subtitle`/`off` 均为合并拷贝既有字符串,零新增);`FakeAudioEngine` 双实例扩展(`preparedUrls`/`preparedStartMs`);`CrossfadeControllerTest` 16 行 + `PlayerControllerTest` 4 行 handoff/bail/quality。

偏离(均有因):
1. **`:start-paused` 采纳**:javap/二进制探针核实所装 libvlccore 3.0.23 含 `start-paused` 选项串——prepare 走输入选项(与 `:start-time` 同为开时生效),无 play-then-pause 竞态可文档化;`setVolume(0)` 降为纵深防御。
2. **advance 抑制的形态**:决策 4 的"一次性标志由 advance() 消费"改为 handoff 时把 outgoing 引擎的 `onFinished` 重绑到状态机的尾端标记(`onOutgoingTailEnded`)——即上游队列截断的桌面等价物。原因:共享标志若在 finish() 之后才消费(尾部自然终了与 fade 完成同刻竞速),会吞掉 incoming 轨道自己的合法 advance;重绑是同一抑制且无标志生命周期。outgoing 的其余五个回调同时摘除(否则与 incoming 争显示时钟)。
3. **reconcileQueue 不需要**:桌面引擎各持一个 URL,队列从不复制,AutoPlay 追加在结构上安全;`onArmIncoming`(响度归一化)一并省略——桌面无响度处理器。
4. **tick 循环随设置启停**:仿 watchSleepTimer 但用 `collect`+幂等启动(collectLatest 会在滑杆拖动时取消心跳、把两个引擎冻在半程增益);`crossfadeSeconds=0` 不建循环(该状态下机器无事可做);`manualTicks` 测试缝把墙钟 tick 挡在单测外。
5. **质量换源互斥照上游 3876 行的"等"**:落点前有界轮询(250ms/20s,上游 UPGRADE_CROSSFADE_* 常量)待 `isTransitioning()` 清除,再与既有 re-check 一起把关;解析与试听(自有静音引擎)照常先行。
6. 状态机加 `now: () -> Long` 时钟缝(ARM_TIMEOUT/BAIL 的墙钟等待在单测里需虚拟钟);arm 解析用 `withContext(resolveDispatcher)` 跳线程(否则阻塞 Main)。
7. `SleepTimerTest`"过期限不倒负"一行放宽为 `null || 0`:测试类顺序因新文件重排后,同一 JVM 里任何早建的 PlayerController 的 watchSleepTimer 会对过期限就地 `cancel()`——属于既有潜在测试隔离瑕疵的显形,已在行注说明。

验证:纯 JVM 全绿——53 个测试类 / 502 用例 ×3 连跑零失败;`bitchord.probeCrossfade` 实机探针、`.shots/slice14/` 截图与接缝听感属机外人工验证(§5.2–5.4 未行)。

已知瑕疵:fade 段双流并发解码的 CPU(§4 既记,待实机听感);ARMING 期间 standby 的陈旧 error 事件会误杀一次 arm(漏一次 crossfade,非破碎一次);fade 中拖动音量滑杆只写 session 引擎(outgoing 保持曲线,与上游 session-volume 语义同形)。
