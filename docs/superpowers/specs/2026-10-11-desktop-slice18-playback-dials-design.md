# BitChord 桌面版 · 切片 18 设计:播放的新旋钮(速度、响度、静音)与设置收尾

日期:2026-10-11
分支:`desktop-slice2-now-playing`(fork:xiongdy54/BitChord-for-Windows,上游 kushagrasinghx/BitChord)
状态:待用户复核

## 1. 背景

切片 16 的盘点把缺口摆上了桌,其中一半是"桌面完全无代码"的播放旋钮:**播放速度(0.5–2.0×)**(桌面引擎连 setRate 都没人调,上游有 `AppSettings.playbackSpeed` 却连 UI 都没有——上游比桌面更缺 UI)、**跳过静音**(`skip_silence`,上游是 ExoPlayer 的 `setSkipSilenceEnabled`,桌面引擎无此概念)、**响度归一化**(`loudnessDb` 已在 `InnerTubeXResolver.kt:231` 解析、`StreamResolver.loudnessDbFor` 已就位但**零消费方**——上游在 `PlaybackService.setupLoudnessEnhancer` 用 `LoudnessEnhancer` 施加 `-loudnessDb` dB 的增益,钳在 [-15, +3] dB)。另一半是"有数据层无 UI"的收尾:`reduceAnimation`、`swipeToPlayNext`、`lyricsBlur` 三键**有消费方、无持久化、无设置行**(第四个 `reduceDynamicBlur` 切片 17 已收讫);`hideVolumeBar` 桌面连键都没有,而 `NowPlayingScreen.kt:2607` 的注释正等着它("That switch rides AppSettings.hideVolumeBar… no field on desktop's AppSettings")。

桌面现状核实:vlcj 4.12.1 的 `ControlsApi.setRate(float)` 在场;`VlcAudioPlayer` 的 equalizer 先例(setEqualizer + play/prepare 防御性 reapply)是速度的同形模板;`SongRow` 的横滑手势(`Common.kt:409`)、歌词模糊(`PlayerLyrics.kt:1495`)、动画削减(`MeshGradient`/`ArtworkPalette`/`PlayerLyrics`)三个消费方全部在场,只欠持久化和设置行。上游合并拷贝的字符串资源里 `skip_silence(+subtitle/float_subtitle)`、`reduce_animation(+subtitle)`、`hide_volume_bar(+subtitle)`、`loudness_normalization(+subtitle)`、`play_next_on_swipe(+swipe_plays_next/swipe_adds_to_queue)`、`blur_unfocused_lyrics(+subtitle)` 全部现成,零新增;唯播放速度需要桌面自拟文案。

## 2. 已确认的决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **速度走 libvlc 的 `setRate`**,照 equalizer 的先例全幅复制:`AudioEngine.setSpeed(rate)`(默认 no-op)+ `VlcAudioPlayer` 的 `currentRate` 字段 + `play`/`prepare` 里的防御性 reapply——四个媒体开启点(resolveAndPlay、质量换流落点、crossfade 交接、standby prepare)由引擎内 reapply 一处覆盖 | 引擎防御性 reapply 的 KDoc 就是为此写的形态;逐点在 PlayerController 重新调 setRate 反而要五个调用点追一个状态 |
| 2 | **响度增益走 libvlc 均衡器的 preamp dB 域**,不进音量百分域:`EqualizerMapper.withLoudnessGain(tuning, gainDb)` 纯函数——tuning 关闭且增益非零时**附着一条平带均衡器只带 preamp**(切片 15 已证平带 = 位模式不变),开启时并入 curve 的 preamp 后钳 ±20 dB;音量百分域留给 crossfade 的增益乘法,两个域正交 | `setVolume` 的每个调用点(crossfade 每 30ms 一 tick)都要乘增益的方案是在给热度添柴;preamp 是 libvlc 唯一的 dB 域增益柄,且与 EQ 的合流点是现成的 |
| 3 | **响度的应用点按"轨道"走**:resolve 成功后(地图已填充)对会话引擎 applyCurrentEqualizer;crossfade 的 resolve lambda 里对 standby 施加 **incoming 轨道**的调谐(记录 `armedIncomingVideoId`);交接处用它替代"队列当前行"——交接时队列针还没动 | 响度是逐轨的;standby 坐的是下一轨,交接前的"当前行"还是旧轨——三处各自知道自己的轨道,没有一处靠猜 |
| 4 | **跳过静音:键 + 行 + 引擎接缝,引擎侧如实 no-op**。`AudioEngine.setSkipSilence(enabled)` 默认 no-op,`VlcAudioPlayer` 实现记录一行 TrackLog 说明 libvlc 3.x 无此滤镜;设置行副标题用桌面自拟文案说真话 | 上游的副标题自己写着"32 位浮点输出时不可用"——原版这开关也只在部分输出下生效。真正的 PCM 跳静音要把渲染通道整个换成自绘(音频回调接管输出、设备选择与 crossfade 增益全部重写),那是整个引擎的另一次重造,不是本片能夹带的;宁如实降级,不给假 DSP。引擎接缝在场,未来引擎有能力时一行接上 |
| 5 | **速度 UI = 设置对话框 Playback 组的滑杆行**(0.5–2.0×、0.05 步、读数"1.25×"),照 crossfade 滑杆行的形;不进播放器右键菜单(ContextMenu 是平表,塞七个速度档会淹没 songMenu) | 上游无速度 UI 可抄,桌面自拟;设置对话框是本片六行设置的自然宿主,一行滑杆把 0.5–2.0 的连续区间说全 |
| 6 | **三键收尾 = 持久化 + 行**:`reduce_animation`/`swipe_to_play_next`/`lyrics_blur` 补 FileStore 键与 setter(键名与上游字节兼容),设置行分别落 Appearance / Playback / Lyrics 组;`hide_volume_bar` 新增流+键+行(Playback 组),门控竖版播放器 `VolumeRow` 与横版音量槽(上游 :1552/:2945 的同一范围,不含工具栏) | 消费方都在场,差的只是"能记住"和"能翻";键名兼容让两平台的 settings 文件同义 |
| 7 | **播放速度的默认值 1.0、钳 0.5–2.0**,FileStore 以字符串存(与上游 SharedPreferences 的 float 同义不同形——桌面 FileStore 本就全是字符串);SMTC 的时间线速率照旧 1.0,记录为已知瑕疵 | FileStore 无 float 通道, widening 它违背"no new surface on the store"的既定纪律;SMTC 的 rate 字段是 WinRT 时间线属性的一次 marshalling 扩张,等有消费者再接 |
| 8 | **不做**:PlayerContextMenu 的速度行(菜单平表放不下连续区间);NerdStats 的响度徽章(上游把响度发到 AudioOutputStatus 供系统面板,桌面的对应物是 SMTC,一并记入决策 7 的已知瑕疵);跳过静音的真实现(决策 4) | 宁少给,不给假象;每一项都留了接缝 |

## 3. 交付物

| 文件 | 内容 |
|---|---|
| `data/settings/AppSettings.kt` | 七个流:`playbackSpeed`(persisted,钳 0.5–2.0)、`loudnessNormalization`(persisted,默认 true,上游同)、`skipSilence`(persisted,默认 false)、`reduceAnimation`/`swipeToPlayNext`/`lyricsBlur` 补持久化、`hideVolumeBar`(新增);键名全部与上游对齐 |
| `desktop/playback/EqualizerMapper.kt` | `withLoudnessGain(tuning, gainDb)` 纯函数(平带附着语义 + ±20 钳制) |
| `desktop/playback/AudioEngine.kt` | `setSpeed(rate)`、`setSkipSilence(enabled)` 默认 no-op,接口 KDoc 讲清两件事的接缝语义 |
| `desktop/desktop/playback/VlcAudioPlayer.kt` | `setRate` + `currentRate` + play/prepare reapply;setSkipSilence 的如实 no-op(TrackLog 一行) |
| `desktop/desktop/playback/PlayerController.kt` | 速度 watcher(两台引擎)、响度三应用点(会话/standby-armed/交接)、`armedIncomingVideoId` 记录、日志行 |
| `ui/screens/SettingsDialog.kt` | 六行:reduceAnimation(Appearance)、playbackSpeed 滑杆 + skipSilence + loudnessNormalization + hideVolumeBar + swipeToPlayNext(Playback)、blur_unfocused_lyrics(Lyrics) |
| `ui/player/NowPlayingScreen.kt` | 竖版 VolumeRow 与横版音量槽的 `hideVolumeBar` 门控 |
| `desktopStrings` | `playback_speed`/`playback_speed_subtitle`/`skip_silence_subtitle_desktop`(en/zh) |
| 测试 | `withLoudnessGain` 计数表(平带附着、并入、钳制、零增益恒等);playbackSpeed 钳制与持久化往返;fake 引擎记录 setSpeed 的控制器施加速率(两台引擎) |

## 4. 关键技术点与风险

| 风险 | 对策 | 验证手段 |
|---|---|---|
| preamp 与 EQ preamp 求和后越过 libvlc ±20 dB(vlcj setPreamp 会抛) | `withLoudnessGain` 在求和后钳制;EQ 的 make-up attenuation ≤0 与响度 [-15,+3] 的极端和(-27)钳到 -20,记录为已知角 | 单测:求和钳制行 |
| watchEqualizer 的 combine 集合再添两个流会让"哪个流变了都重画"的面变大 | 速度与响度各自独立 watcher(speed 不触发 equalizer 重算);响度 watcher 收 `loudnessNormalization` 一条 | 代码审读 |
| `currentEqualizerTuning` 读队列从非队列线程(watcher、crossfade ticker、resolve lambda) | 只读 `queue.songAt(currentIndex)` 快照——crossfade 的 `peekNextVideoId` 已是 ticker 线程读队列的既有先例;响度地图是 ConcurrentHashMap | 代码审读 + 既有测试 |
| 速率 ≠ 1 时 crossfade 的 ARM_LEAD_MS(墙钟 4s)在 2× 下只余 2s 墙钟 | ARM_TIMEOUT_MS(12s 墙钟)兜底;prefetch 通常已缓存,记录为已知边界 | 行为断言(2× 播放相邻轨) |
| 下载/本地/源曲目无 loudnessDb | `loudnessDbFor` 返回 null → 增益 0 → `withLoudnessGain` 恒等,均衡器状态回归 EQ 设置本义 | 单测 |
| 速率 reapply 在引擎内部,测试看不到 | 单测驱动 fake 引擎记录 setSpeed;VlcAudioPlayer 侧靠 equalizer reapply 的既有探针纪律(同形代码) | 单测 + 日志行 |

## 5. 验证计划

1. **单元测试**:§3 测试行,全套既有测试保持全绿。
2. **行为断言(日志)**:probePlayVideoId 跑一轨 → 日志出现 `speed → 1.0×` 与 `loudness -x.x dB → preamp`(真实流解析时);速度拨到 1.5 → 引擎 setRate 日志。
3. **视觉判读(截图,`.shots/slice18/`)**:设置对话框 Playback 组(新四行)+ Lyrics 组(blur 行),zh-dark;播放器竖版 hideVolumeBar 开/关两态。
4. **常量核对**:速度 0.5–2.0/0.05 步;响度钳 [-15,+3](上游 MIN/MAX);preamp 总钳 ±20。

## 6. 本切片不做

播放器菜单的速度行与响度徽章(决策 8);SMTC 时间线速率(决策 7 的已知瑕疵);跳过静音的真实现(决策 4,引擎重造另片);OriginalVersion/音视频切换、SlidingPlayerDeck、Canvas 登录(切片 21);搜索历史持久化、History 页、备份与更新检查、语言热切换(切片 19);下载合集批量/暂停恢复/目录选择(切片 20)。

## 7. 实施记录(2026-10-11 回填)

**交付**:决策 1–6 全部落地。`AppSettings` 七流——`playbackSpeed`(持久化 `playback_speed`,钳制抽成纯函数 `clampPlaybackSpeed`:0.05 步取整 + 0.5–2.0 钳)、`loudnessNormalization`(默认 true,上游同)、`skipSilence`、`hideVolumeBar`、`reduceAnimation`/`swipeToPlayNext`/`lyricsBlur` 补持久化,键名与上游逐字对齐;`EqualizerMapper.withLoudnessGain` 纯函数(关+零增益恒等返回同一实例、关+非零平带附着、开+非零求和钳 ±20);`AudioEngine.setSpeed/setSkipSilence` 默认 no-op;`VlcAudioPlayer.setRate` + `currentRate` + play/prepare 双 reapply(与 reapplyEqualizer 并排);`PlayerController.watchPlaybackDials` 三个 watcher(速度双引擎、响度重算调谐、静音双引擎)+ 响度三应用点(resolve 成功后 applyCurrentEqualizer、crossfade resolve lambda 给 standby 施加 **incoming** 调谐并记录 `armedIncomingVideoId`、交接用它)+ `standbyEngine()` 创建时同步速率与静音开关;SettingsDialog 六行;NowPlayingScreen 竖版 VolumeRow 的等高垫片分支(VOLUME_ROW_HEIGHT 回归有主)+ 横版 volume 槽 null 分支;desktopStrings en/zh 各三键;探针 `bitchord.hideVolumeBar`(内存种子,liquidGlass 同纪律)。

**与设计的偏离**:

1. **standby 创建时补速率/静音同步**(设计决策 1 的洞):watcher 只听"变化",arm 时才建的 standby 会以正常速度开整个 blend——`standbyEngine()` 创建块里补 `setSpeed(AppSettings.playbackSpeed.value)` + `setSkipSilence(...)`,单测钉死("a standby built after the dial moved must not open its blend at normal speed")。
2. **`baseEqualizerTuning` 拆分**:响度折叠进调谐后,"设置们的曲线"(无响度)与"当前轨道的调谐"(折叠后)是两个名字,watchEqualizer 的六流 combine 语义未动。
3. **测试夹逼出预期错误**:响度测试首跑种了 -6 dB(过轻)期待 +6 preamp,被上游 +3 dB 窗口钳到 3.0——实现是对的,预期改种过响轨(+6 dB → -6 preamp)并补钳制用例(-10 dB → +3 天花板)。

**验证**:60 类 589 测试全绿(新增 13:`withLoudnessGain` 五行计数表、`clampPlaybackSpeed` 钳制表、键名兼容表、控制器四例(速度双引擎/静音接缝/响度落 preamp/响度关=恒等)、读数格式两例)。**行为断言(实机,真实流)**:`probePlayVideoId=dQw4w9WgXcQ` 真实解析成功(InnerTubeX WEB_REMIX@136kbps)后日志依次出现 `loudness correction 3.0 dB → preamp`(该轨 loudnessDb ≤ -3,校正钳在 +3 天花板——端到端证据)与 `speed → 1.0×`。**视觉判读**(`.shots/slice18/`):设置对话框 zh-dark 全组一图(播放组五新行 + 外观组减少动画 + 歌词组 blur 行全部在场,滑杆/开关渲染正常);播放器竖版音量条显示/隐藏两态对照——隐藏态音量行消失而**上下控件 y 位置与显示态逐像素一致**(等高垫片成立)。**人手验证待做**:速度拨到 1.5× 的听感、响度开/关的 A/B、横版窗口的音量槽缺席观感。

**已知瑕疵**:run 任务截图后以非零码退出(工作区未提交的 CEF 关停路径改动所致,与本片无关);SMTC 时间线速率恒 1.0;跳过静音为记录在案的 no-op(决策 4)。
