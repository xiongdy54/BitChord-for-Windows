# BitChord 桌面版 · 切片 9 设计：睡眠定时 + 音频输出设备

日期：2026-10-07
分支：`desktop-slice2-now-playing`
状态：已完成（实施记录见 §7）

## 1. 背景

切片 9 收的是项目里**最老的两笔欠账**。切片 2 的"本切片不做"把它们划给了当时的"切片 6"：**睡眠定时**与**音频输出设备切换**——后来切片 6 挪给了下载族，这两件一直没人认领；切片 4 的上下文菜单 KDoc 也给睡眠定时留了话："the sleep timer … stay absent rather than arriving dead"（`SongContextMenu.kt:36`）。上游规模（逐项核实过）：

- **睡眠定时**：`SleepTimer.kt` 全文 60 行——一个纯状态机（deadline/minutes/afterTrack 三条流 + 数学），`PlaybackService.kt:5096` 的监视协程（`collectLatest` 到点暂停）、`:2838` 的转场钩子（`ended && afterTrack` → 暂停 + 取消，repeat-one 也算"这一曲结束"）、`:946` 的队列见底清理；UI 是播放器 ⋮ 面板里的 `SleepTimerPicker`（`SongActionsSheet.kt:575`，"播完此曲"领衔 + 四档预设 + 关闭行 + 每秒倒计时）。
- **音频输出**：上游 `AudioOutputStatus.kt`（598 行）+ `AudioRouting.kt` + `AudioOutputPolicy.kt` + `audio/`（蓝牙/有线路由追踪）共一千余行，几乎全部是 Android 的 `AudioManager`/BT 域——桌面没有对应 substrate。**真正可搬的语义只有一层**：用户指定输出设备、当前播放跟着过去、选择被记住。桌面引擎是 libvlc：vlcj 4.12.1 的 `AudioApi` 自带 `outputDevices()`（枚举当前 aout 模块的设备，Windows 上即 mmdevice/WASAPI 端点）与 `setOutputDevice(module, deviceId)`——不需要写一行 COM。

两件都是"播放器实用件"，上游也把它们放在同一张播放器面板里；合为一 slice。

## 2. 已确认的决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **`SleepTimer.kt` 逐字移植**到 `desktop/playback/`（唯一改动：`android.os.SystemClock` → 项目既有的同名 `data/SystemClock`，该文件本就是为这类逐字移植造的） | 60 行纯 JVM，deadline 数学与"播完此曲"的事件语义原样成立；不重造 |
| 2 | **监视协程进 `PlayerController` 的 init**，`collectLatest { deadline → delay(remaining); engine.pause(); SleepTimer.cancel() }`，与上游 `watchSleepTimer` 同形（上游 pause 也不看播放态——对停着的播放器 pause 是无害操作） | 上游把监视放在 service 里，桌面的 service 就是 PlayerController；测试可把 deadline 直接拨到过去，立即触发，不需要假时钟 |
| 3 | **"播完此曲"落在 `advance()` 的队头**：进队先查 `SleepTimer.afterTrack`——armed 则取消定时器并**就地停住**（playhead 留在刚放完的行上，不推进、不解析下一曲），随后正常走 `queue.onFinished()`。队列见底的 null 分支同样 `SleepTimer.cancel()`（上游 `STATE_ENDED` 的清理） | 桌面的推进只有一个入口（`engine.onFinished → advance`），在入口拒绝推进就是"这首歌之后什么都不响"的完整实现。**刻意偏离上游**：Media3 的转场回调在播放器已滑到下一曲后暂停，屏幕上"当前曲"是下一曲；桌面停在放完的那一行——"这是最后放过的东西"更诚实，且免去为不响的下一曲付一次 resolve。repeat-one 下同样停住（否则定时器永远不触发，上游注释原话的理由） |
| 4 | **输出设备进 `AudioEngine` 接口**：`audioOutputDevices(): List<AudioOutputDevice>` + `setAudioOutputDevice(deviceId: String?)`，接口给默认实现（空表 / no-op），`AudioOutputDevice(id, name)` 是纯数据 | 接口的既定身份是"声音卡面的接口"；设备是声卡的一部分。默认实现让 `FakeAudioEngine` 与未来的引擎不为不支持的设备付假数据 |
| 5 | **VLC 机械**：枚举 = `player.audio().outputDevices()`（vlcj 已把 `libvlc_audio_output_device_enum` 的异步列表器包成同步表），`runCatching` 兜空表；切换 = `player.audio().setOutputDevice(null, id)`（module 传 null = 当前 aout，Windows 即 mmdevice）；"系统默认" = `setOutputDevice(null, null)`，若实机验证发现 libvlc 不把 NULL 当重置，则回退为**记录设置、下次引擎实例生效**并在 KDoc 写明（决策 8 的探针先行） | vlcj 4.12.1 javap 已核对：`outputDevices()`/`setOutputDevice(String,String)`/`outputDevice()` 三件都在；不写 COM |
| 6 | **设置与状态在 `AppSettings`**：`outputDeviceId: StateFlow<String?>`（null = 跟随系统默认）+ `outputDeviceName: StateFlow<String?>`（设备拔掉后对话框还能显示它叫什么），`setAudioOutputDevice(id, name)` 一次写两样 + 落盘；`PlayerController.setOutputDevice(id, name)` 是唯一写入口（写设置 + 调引擎），init 时把存住的 id 应用到引擎 | 与 `setAudioQuality`/`setAutoplay` 同形：显式入口、单点落地，避开收集器时序坑（切片 8 偏离 1 的教训） |
| 7 | **入口分两个面，各按其性质落位**：**睡眠定时 = 播放页右键菜单**——把 `PlayerPage` 整体包进 `ContextMenuArea`，条目 = 当前曲目的整套行菜单动作（上游从播放器打开的就是同一张 `SongActionsSheet`，`MainActivity.kt:2116`，`showSleepTimer = fromPlayer`）+ 顶部的〔睡眠定时〕行（armed 时标签带倒计时/播完此曲状态）；选择器是窗口级 `Dialog`（`SleepTimerDialog`），形状照上游 picker：标题 + 状态行、"播完此曲"领衔、四档预设（15/30/45/60，60 显示 `one_hour`）、关闭行、每秒倒计时。**输出设备 = 设置对话框**"播放"组新增〔音频输出〕行 → `OutputDeviceDialog` 单选列表（〔系统默认〕领衔 + 枚举设备） | 播放页根部的吞指针 pointerInput（`Shell.kt:435`）只吞 down 的主传递，行菜单的先例（可点行 + 祖先 `ContextMenuArea` 共存）证明右键仍会到达祖先的菜单检测器——实机验证兜底。输出设备是"设置级"的决定（一次选好长期不变），睡眠定时是"瞬态"控制（倒计时、当晚就过期），两者的落位跟着性质走 |
| 8 | **实机探针先行**：`bitchord.probeAudioDevices`（枚举并打印设备表）、`bitchord.probeSleepTimer <秒>`（把 deadline 拨到 N 秒后，看日志的暂停行）。切换的"即插即响"由探针在真机上确认（播放中换设备、听/日志确认） | libvlc 设备切换的生效时机（当前流 vs 下一流）是文档没说死的事，探针说了算 |
| 9 | **字符串**：`sleep_timer`/`after_this_song`/`pause_countdown`/`pause_after_song`/`pause_after_a_while`/`one_hour`/`minute_count`(plurals)/`turn_off_timer`/`audio_output` 上游 strings 全都有（en+zh 合并拷贝已带），**零新增复用**；新增桌面字符串仅 `system_default`（〔系统默认〕）与（若需要）设备为空的占位行 | 桌面字符串族的规矩就是能复用上游绝不另写 |
| 10 | **不做**：设备热插拔的自动跟随（上游 `AudioRouting` 的 BT/有线事件域——桌面无 substrate；VLC 的 mmdevice aout 自己会跟随系统默认设备失效回落，探针确认后写进记录）；设备面板的刷新按钮（对话框每次打开重新枚举，面板存续期内不刷新——瞬态面板不背长驻职责）；`audio_output_playing`（上游"正在此播放"徽标——上游的输出面板列的是**别家的**投放端点，桌面只选本机设备，没有"别处"可标） | 宁少给，不给死功能 |

## 3. 交付物

| 文件 | 内容 |
|---|---|
| `desktop/playback/SleepTimer.kt`（新） | 逐字移植的 60 行状态机 |
| `desktop/playback/AudioEngine.kt` | `AudioOutputDevice` 数据类 + 接口两成员（默认实现） |
| `desktop/playback/VlcAudioPlayer.kt` | 枚举/切换/重置的 vlcj 落地（`runCatching` 兜底） |
| `desktop/playback/PlayerController.kt` | init：睡眠定时监视协程 + 存住的输出设备应用；`advance()`：afterTrack 分支 + 见底清理；`setAudioOutputDevice(id, name)` |
| `data/settings/AppSettings.kt` | `outputDeviceId`/`outputDeviceName` + 键 + setter |
| `ui/player/PlayerContextMenu.kt`（新） | `PlayerContextMenuArea`：当前曲目的 `songActions` 复用 + 〔睡眠定时〕行（倒计时状态每秒刷新，读 fresh） |
| `ui/player/SleepTimerDialog.kt`（新） | 窗口级选择器，上游 picker 的桌面形（Dialog + Radio 行 + 倒计时 produceState） |
| `ui/screens/OutputDeviceDialog.kt`（新） | 设备单选对话框（打开时 IO 枚举；〔系统默认〕+ 设备表） |
| `ui/shell/Shell.kt` | 播放页包进菜单区、`showSleepTimer` 状态与对话框宿主、设置对话框的新接线 |
| `ui/screens/SettingsDialog.kt` | "播放"组〔音频输出〕行（值文本 = 现选设备名/系统默认） |
| `desktopStrings/{values,values-zh}/strings.xml` | `system_default` |
| `desktop/Probe.kt` | `probeAudioDevices` / `probeSleepTimer` |
| 测试 | `SleepTimerTest`（状态机表）+ `SleepTimerPlaybackTest`（到点暂停/播完此曲停住/见底清理/输出设备落地与应用） |

## 4. 关键技术点与风险

| 风险 | 对策 | 验证手段 |
|---|---|---|
| 播放页根部的吞指针 pointerInput 把右键也吞掉，菜单打不开 | `ContextMenuArea` 在祖先节点检测（行菜单已证明后代吞 down 不阻碍祖先菜单）；实机右键确认，打不通则把菜单区挪进 `PlayerPage` 根部的内侧 | 实机右键出菜单截图 |
| libvlc 设备切换对**当前流**不生效（只管下一曲） | 决策 8 探针先行；若属实，切换生效语义如实写进 KDoc 与实施记录（"当前曲目走到头才换耳"是 libvlc 的事实，不粉饰），必要时评估"停-设-续播"的接缝是否值得一次可闻切口 | 实机播放中切换 + 日志 |
| `setOutputDevice(null, null)` 不被 libvlc 当作"回到系统默认" | 探针验证；不成立则〔系统默认〕的选择记录为 null、引擎在下次实例化时应用（KDoc 注明"本轮会话仍停在旧设备"），不写 JNA COM 去猜默认端点 | 探针日志 |
| 设备枚举阻塞（原生调用几十至几百毫秒） | 枚举只在对话框打开时跑一次，且在 `Dispatchers.IO`；面板用小 spinner 等待 | 对话框打开流畅 |
| 睡眠定时监视的 `delay` 在测试里真实等待 | 测试把 deadline 拨到过去（`remaining ≤ 0` 立即走暂停分支），不需要假时钟；"播完此曲"分支是纯同步判定 | 套件全绿 |
| 设置探针污染 `settings.properties` | 探针用的键写完即恢复原值（切片 8 已知瑕疵，本片顺手处理自己的部分） | 实机跑探针后 diff settings 文件 |

## 5. 验证计划

1. **单元测试**：`SleepTimerTest`（start/重设/startAfterTrack 切换/cancel/isRunning/remainingMs 数学/边界）+ `SleepTimerPlaybackTest`（过期 deadline → 引擎暂停且定时器清空；afterTrack armed → advance 停住不解析下一曲、repeat-one 下同样停住；无 afterTrack → 正常推进；队列见底 → 定时器清理；输出设备 setter 落设置并调引擎、init 应用存住的设备）+ 既有 40 类全绿。
2. **行为断言（实机探针）**：`probeAudioDevices` 打出设备表；播放中 `probeSleepTimer 10` → 10 秒后日志出 `playing=false` 行且媒体覆盖层转入暂停；右键播放页 → 菜单含当前曲目动作 + 〔睡眠定时〕。
3. **切换断言（实机）**：播放中在设置里换输出设备 → 声音跟着过去（或按决策 5/8 的探针结论如实记录生效语义）；选〔系统默认〕→ 回到系统默认设备。
4. **视觉判读**：`.shots/slice9/`——睡眠定时对话框（空态/倒计时/播完此曲选中三态 × 深/浅）、输出设备对话框、播放页右键菜单；判读用既有截图管线。
5. **常量核对**：预设 15/30/45/60、倒计时格式 `m:ss`（`%d:%02d`，Locale.ROOT）、双语字符串进断言或 KDoc。

## 6. 本切片不做

一起听（`data/listentogether/` 全家 + 后端联调——独立大切片）；登录 UI 与账号切换（需要桌面 WebView  substrate，独立决策）；本地音乐扫描与 `LocalMusicScreen` 全家（含 WebDAV，独立切片）；下载管理面板与工具栏下载钮（切片 6 决策 10 的"等实测反馈"仍然有效）；运动封面 `CanvasArtworkPlayer` 族；交叉淡入淡出（`CrossfadeController.kt` 1815 行，双引擎接缝独立成片）；Equalizer（VLC 有现成均衡器 API，随"音频设置"族另排）；语言热切换；Discord RPC、Scrobble（切片 8 既有判词）；模块源体系。

## 7. 实施记录（2026-10-10 回填）

**交付**：决策 1–6、8–9 落地。`desktop/playback/SleepTimer.kt`（逐字移植，仅 SystemClock 换 import）；`AudioEngine` 增 `AudioOutputDevice` 与 `audioOutputDevices()`/`setAudioOutputDevice(String?)`（默认实现，fake 与未来引擎不为不支持的设备付假数据）；`VlcAudioPlayer` 三件落地（vlcj 的 `outputDevices()`/`setOutputDevice(null, id)`，`runCatching` 兜空表；mmdevice 列出的 "Default" 空 id 条目被过滤——它不是可再指名的设备，与〔系统默认〕语义重复）；`PlayerController`：`watchSleepTimer()`（`collectLatest` 到点暂停 + 取消，`PlaybackService.kt:5096` 同形）、`advance()` 队头 afterTrack 分支（armed 则停在场行不推进）与队列见底清理、`setAudioOutputDevice(id, name)` + `audioOutputDevices()` + init 应用存住的设备；`AppSettings.outputDeviceId/Name` + `FileStore.remove()`；`SongContextMenu` 抽出 `songMenuItems`（播放器菜单与行菜单共用一份构建器）；`PlayerContextMenuArea`（睡眠定时行领衔 + 当前曲整套行菜单，倒计时每秒刷）；`SleepTimerDialog`（上游 picker 的桌面形：播完此曲领衔 + 四档 + 关闭行 + `sleepTimerRemaining()` 每秒倒计时）；`OutputDeviceDialog` + `OutputDevicePanel`；设置对话框"播放"组〔音频输出〕行；Shell 接线（`showSleepTimer`、对话框宿主、设置新参）；`system_default` 双语；探针 `probeAudioDevices`/`probeSwitchDevice`/`probeVideoId`/`probeSleepTimerMs`/`probeSleepAfterTrack`/`probeArmTimerMs`/`probeSleepPreview`/`probeOutputPreview`。

**与设计的偏离**：

1. **决策 7 的菜单落位改为"吞层在外、菜单区在内"**：设计原案是 `ContextMenuArea` 包在播放页外面（行菜单的拓扑）。实机结构排查发现 Compose 主传递是"最深节点先走"，播放页根部那个吞指针 `pointerInput`（先于菜单区）把右键 down 消费掉后，祖先检测器的 `changedToDown`（对已消费 down 返回 false）永远等不到——行菜单能活是因为 `clickable` 只消费主键。终局形状：Shell 里一个外层 Box 持吞指针（恢复吞掉一切，保住"死区不漏到 chrome"的原职责），`PlayerContextMenuArea` 在它**内侧**——主传递深于吞层，右键必然先到菜单检测器，结构性保证而非经验依赖。
2. **右键的实机探针两起两落，物理确认留待人手**（切片 4 的先例）：先 Robot 真点——会点到用户正在使用的界面上（用户当场指出），立即移除；后改进程内合成 AWT 事件直发 Skia 画布——完全不动桌面，但校准失败：在已知可用的曲库行菜单上同样触发不了弹窗，skiko 对非真实输入有过滤，此路不通。探针整体撤除；结构论证见偏离 1，请打开播放页右键做人手确认。
3. **睡眠定时对话框的勾选图标**：`SleepOptionRow` 最初无条件下划图标，截图判读发现每行都挂一个灰勾（"半选中"的谎），修正为选中才画勾、关闭行画叉。
4. **决策 8 的探针武器化**：`probeArmTimerMs` 走 `SleepTimer.start(分钟)` 而非直接拨 deadline——绕过 minutes 流会让选择器的勾选档不亮，截图说谎。
5. **`OutputDeviceDialog` 拆出 `OutputDevicePanel`**：设置对话框内的嵌套 Dialog 渲染在独立原生窗口，PrintWindow 截不到（菜单预览探针同坑）——面板本体抽出，预览探针按菜单的先例内联渲染同一组合函数。
6. **`sleepTimerRemaining`/`formatSleepCountdown`/`sleepTimerPresetLabel` 放在 `ui/player/`** 而非对话框私有：播放器菜单行的倒计时标签与对话框读同一条流，一份格式化。

**验证**：41 类 360 测试全绿（新增 `SleepTimerTest` 6 条：武装/换挡/清空/remaining 数学/预设表；`SleepTimerPlaybackTest` 8 条：虚拟时间下到点暂停且只暂停一次、未到期不暂停、播完此曲在场行停住不解析下一曲、repeat-one 下同样停、无定时器照常推进、队列见底花掉 deadline、设备开关写设置并调引擎且同值幂等、init 应用存住的设备）。实机探针：设备枚举列出本机 mmdevice 端点（Speakers/Headphones）；**换流即插即响**——播放中切耳机端点媒体钟连续推进无错误（5.1s→14s），`setOutputDevice(null, null)` 回系统默认同样通过；**睡眠定时到点**——`probeSleepTimerMs=8000` 后日志 `sleep timer elapsed; pausing` + `playing=false`；**播完此曲**——曲终日志 `sleep timer: after this song; holding at the finished row` 且其后无任何下一曲 resolve。截图 `.shots/slice9/`：睡眠定时对话框空态（zh-dark）、倒计时态（en-light，15 minutes ✓ + Turn off timer ✗ + "14:55 until playback pauses"）、输出设备面板（系统默认选中 + 两端点）、播放器菜单预览（"睡眠定时器 · 14:54"领衔 + 歌曲行）、设置页（加高窗口）、播放页回归（重构后布局无恙）。

**已知瑕疵**：设备测试向真实 `settings.properties` 写入后经 `captureDevice()` 恢复原值（比切片 8 探针的"不自恢复"进一步，但 AppSettings 单例仍被测试触碰）；`SleepTimer` 是进程级单例，测试间靠 `@AfterTest` 清理；SMTC 覆盖层与物理右键是系统 UI/人手交互，脚本不可及——前者由 attach 日志与媒体键人手覆盖（切片 8 先例），后者待人手右键确认。

