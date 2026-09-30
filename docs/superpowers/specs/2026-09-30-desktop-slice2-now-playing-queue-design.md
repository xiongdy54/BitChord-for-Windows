# BitChord 桌面版 · 切片 2 设计：Now Playing 全屏播放页 + 队列

日期：2026-09-30
分支：`windows-desktop`（fork：xiongdy54/BitChord-for-Windows，上游 kushagrasinghx/BitChord）
状态：待用户复核

## 1. 背景

切片 1 已经交付：外壳（顶栏磨砂 + 浮动 tab + MiniPlayer）、主页、搜索页，以及"点一首 → 出声"的单曲链路。MiniPlayer 的点击现在是死路——`desktop/.../ui/shell/Shell.kt:124` 留着 `// TODO(slice-2): NowPlayingScreen(...)`，`showPlayer` 状态已经接上，缺的只是页面本身。

本切片交付原版的全屏播放页与队列。原版相关代码分两块：

- **界面**：`app/.../ui/player/`（`NowPlayingScreen.kt` 3096 行、`PlayerControls.kt` 1575 行、`PlayerQueue.kt` 792 行、`LandscapePlayer.kt` 510 行）
- **队列语义**：`app/.../playback/`（`QueueBuilder.kt` 116 行、`QueueCoordinator.kt` 326 行、`QueueHistory.kt` 30 行、`QueueShuffle.kt` 238 行 = 710 行算法，加上 6500 行不可移植的 `PlaybackService.kt`）

后续切片：3 歌词，1.5 真玻璃，4 库/设置/详情页，5 浮窗 + Dock，6 长尾（自动续播、一起听、睡眠定时）。

## 2. 已确认的决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **全屏页沿用原版自己的横屏判定**：`landscapePlayerAvailable(w, h) = w > h && w >= 560.dp`（`NowPlayingScreen.kt:408`，常量在 `:316`） | 桌面窗口本来就是横向的（当前 1180×780 已满足），横屏分支就是原版手机横屏的观感；竖屏分支同一文件带着走，把窗口拉高即生效，不自己另造判定条件 |
| 2 | **队列做满三段**：CONTEXT（从列表/合集播放时整列表入队）+ USER_QUEUE（行菜单"下一首播放/加入队列"）+ AUTOPLAY（占位，本切片不填充） | 用户选定。三段的分档语义就是原版 `QueueTier` 的设计意图，桌面 `Models.kt` 里枚举与 `queueTier/playbackSource/queueEntryId/radioName` 字段切片 1 已经跟着搬过来了 |
| 3 | **710 行队列算法照搬**，只把"改动播放机"的那几行换一个薄接口承接 | 这些算法是原版行为的唯一真相来源（含大段解释"为什么这么绕"的注释）。重写一遍等于把对照物扔掉，日后无法判断差异来自移植还是来自理解错误 |
| 4 | **宿主形态：Shell 内的全屏覆盖层 + Esc 关闭** | 原版是 Activity 上的 Compose 层 + `PlayerBackHandler`（`NowPlayingScreen.kt:3056`）。桌面没有返回键语义，Esc 是它的等价物；不引入第二个窗口，也不引入路由框架 |
| 5 | **行菜单只给 4 项**：下一首播放 / 加入队列 / 点赞（已赞则取消）/ 复制日志 | `SongActionsSheet.kt` 有 851 行、14 个动作，其余（转换音频↔视频、加入/移出歌单、打开专辑/艺人、睡眠定时、歌词偏移、分享）分属切片 3/4/6。宁可少给，不给死按钮——这是切片 1 收口时定下的规矩。"复制日志"= 把 `DebugLog` 的环形缓冲写进剪贴板（桌面 `DebugLog` 现在只往 stdout 打，需要留一份最近 N 行），它是移植期给用户抓现场用的，不是原版功能的复刻 |
| 6 | **歌词按钮与自动播放按钮本切片都不出现**（不是置灰） | 歌词整族属切片 3；AUTOPLAY 档位虽然队列内核里全线支持，但往里填内容要 `startRadio`（RDAMVM，切片 6），没有内容的开关是死按钮。原版操作行里的音频输出、一起听同理缺席。操作行的其余成员保持原样 |
| 7 | **下一首预取**：开始播放一首之后，异步把紧接着那条的流 URL 解析好并缓存 | 切片 1 实测解析一次要 6.4s–19s（NewPipe + 本机被 YouTube 标记）。不预取的话，"播完自动下一首"会变成十几秒静音，队列功能形同虚设。这是本切片可用的前提，不是锦上添花 |

## 3. 交付物

包名与目录结构与 `app/` 对齐；`app/` 只读。

### 3.1 队列内核 `desktop/.../playback/`

| 文件 | 处理方式 |
|---|---|
| `QueueBuilder.kt` | 逐字搬（只 import `java.util.Locale`，纯 JVM） |
| `QueueHistory.kt` | 逐字搬（30 行，纯函数） |
| `QueueCoordinator.kt` | 4 个纯构建器逐字搬：`buildContextQueue` / `buildOneOffQueue` / `findUserQueueInsertionIndex` / `buildJumpQueue`；3 个改动播放机的函数（`clearUserQueue` / `consumePlayedUserQueue` / `jumpToQueueItem`）保留控制流、把 `Player` 换成 `QueueHost`；`buildPartyPlaybackQueue` 删（一起听=切片 6） |
| `QueueShuffle.kt` | 纯逻辑逐字搬：`startingOrder` / `shuffledSection` / `avoidIdentityShuffle` / `restoreOrder` / `sections` / `shuffle()` 的下标计算；`applyOrder` 的 `MediaController.sendCustomCommand` 分支删掉——它存在的唯一理由是 Controller 与 Service 跨进程，桌面直接就地应用置换；开关状态仍写回 `AppSettings`，桌面 `AppSettings.kt` 需新增 `shuffleEnabled` 持久化（原版走 SharedPreferences，桌面已有 `DesktopStore`） |
| `QueueHost.kt`（新） | 见 §4 的接口清单，由 `PlayerController` 实现 |
| `PlayerState.kt`（新） | 从 `PlayerConnection.kt:64` 逐字搬 `PlayerState` 数据类与 `PlaybackPosition`（`:59`）；`Player.REPEAT_MODE_*` 换成桌面自有的 `RepeatMode` 常量 |

`PlayerController` 从"单曲播放器"升级成"队列播放器"：持有 `timeline: List<Song>` + `index`，对外暴露的字段名与原版 `PlayerState` 保持一致（`song / isPlaying / position / durationMs / isLoading / repeatMode / queue / queueIndex / hasPrevious / hasNext`），这样 `NowPlayingScreen` 那 ~40 个参数能一一对上，移植时不用改名。

### 3.2 播放心跳与语义（手写部分）

- **播完推进**：`VlcAudioPlayer.onFinished` → `REPEAT_ONE` 重播本曲 / 有下一首则前进 / 走到队尾时若 `REPEAT_ALL` 则回绕 / 否则停在末尾
- **REPEAT_ALL 的历史回绕**：照 `PlaybackService.kt:2712-2724`——已播条目超出 25 首窗口（`MAX_QUEUE_HISTORY`）时，不是删掉而是**轮转到队尾**，这样无限循环能继续；非 REPEAT_ALL 才真的删（`queueHistoryTrimCount`）
- **上一首**：播放位置 > 10s（`BACK_RESTARTS_AFTER_MS = 10_000L`，`PlaybackService.kt:157`）时先重播本曲，否则回上一条
- **入队入口**：`playFrom(list, index)`（搜索/列表/合集）→ 走 `buildContextQueue`；`playOneOff(song)`（点歌、迷你条）→ 走 `buildOneOffQueue`；两者都保留已有 USER_QUEUE
- **AUTOPLAY 槽位**：类型、渲染、分档逻辑都在，只是本切片没有往里填东西的代码（RDAMVM 属切片 6）

### 3.3 界面 `desktop/.../ui/player/` 与外壳

- `NowPlayingScreen.kt` 移植：参数表照抄（去掉歌词组、一起听、`onOpenPlaybackSource`、Android-only 的 `BackHandler`/insets/`StatusBarIcons`/keepScreenOn 五处）；图层顺序照原版：`MeshGradientBackground` → `ArtworkMeshBackdrop` → `FullArtworkBlurBackdrop` → hero 封面
- **运动封面不做**：`CanvasArtworkPlayer`（TextureView 抽帧）留到以后，本切片用静态封面 + 已有的取色/网格渐变
- `LandscapePlayer.kt` 移植（含 h<440dp 的紧凑分支）
- `PlayerControls.kt` 移植：`TransportRow` / `PlayerScrubber` / `VolumeRow` / `PlaybackQualityLabel` / `MarqueeText` / `CircleGlyph` / `ThinSlider`；播放暂停上下曲四个图标用原版 `ic_player_{play,pause,next,previous}.xml` 矢量图（已确认是 XML 矢量，不是位图），走切片 1 已经跑通的 `ic_logo.xml` 那条管线——只需把这 4 个文件加进 `syncAppStrings` 的 drawable 清单
- `PlayerQueue.kt` 的 `InlineQueue`：三段分节 + 段标题（Now Playing / 接下来播放 + 清除 / 来自<来源>）+ 跳转 + 移除 + 行内拖拽重排
- 行菜单：`ui/components/DesktopSongActions.kt`（新，`Popup` 实现）替换掉 Home/Search 行现在挂着的空回调 `onShowActions`
- `Shell.kt:124` 的 TODO 落地：MiniPlayer 点击 → `showPlayer = true`；Esc / 覆盖层上的关闭按钮 → false

## 4. 关键技术点与风险

| 风险 | 对策 | 验证手段 |
|---|---|---|
| **更正探查阶段的记录**：当时说"只有 3 处 Media3 调用需要抽象"，逐文件核对后是 **9 类调用点**——`getMediaItemAt` / `currentMediaItemIndex` / `mediaItemCount` / `currentMediaItem` / `removeMediaItem` / `replaceMediaItems` / `setMediaItems` / `seekTo`+`play` / `playbackState`+`prepare`；另外 `QueueShuffle.kt` 还 import 了 `android.os.Bundle`、`bundleOf`、`MediaController`、`SessionCommand` | 收进一个 `QueueHost` 接口，7 个成员：`itemCount` / `currentIndex` / `songAt(i)` / `removeAt(i)` / `replaceRange(from, to, songs)` / `setTimeline(songs, startIndex)` / `jumpTo(index, positionMs)`。`Bundle` 与自定义命令整条删（跨进程产物） | 编译期无 `androidx.media3` / `android.*` 符号；单元测试直接跑算法 |
| **播完推进是原版的"隐式行为"**（ExoPlayer 自己往下走 + 服务监听 transition），桌面必须自己写，最容易出双跳/漏跳/状态错拍 | 推进只认一个入口（`onFinished`），并把"切下一首"与"解析下一首"分开：先更新 timeline/index，再 resolve；resolve 期间 `isLoading` 为真、旧音不续 | 单元测试用假时钟喂 `onFinished` 断言 index 序列；实机播完一首短曲观察自动进下一首（探针可跑 15s，必要时临时把 VLC 起始位置设到曲尾） |
| **行内拖拽重排**在原版靠触屏 `pointerInput`，桌面的鼠标拖动语义不同 | 先在桌面实测 `DragGestureDetector` 阈值行为；不一致就调（拖动启动距离、命中区）而不是先改设计 | 拖动前后队列顺序断言（单测在算法层）+ 实机拖一次 |
| **切歌间隙**：解析 6.4s–19s（切片 1 实测） | 决策 7 的预取：解析结果按 videoId 缓存，播放时命中即用；缓存过期即弃 | 日志比对第二首的 `total resolve` 耗时（预取命中应接近 0） |
| **全屏覆盖层的 z-order**：Shell 已经把内容画在 haze 源之上，播放器又自带渐变底 | 播放器作为最外层绘制，并且**不注册**为新的 haze 源；打开时 MiniPlayer 与浮动条随原版一样隐入播放页 | 截图：播放页里不应出现底部 tab 条的残影 |
| **进度插值**：VLC 的时间回调是离散的（约每几百毫秒一次），原版用 `PlaybackPosition` 可变字段 + `SystemClock` 做帧间插值，避免进度条跳字 | `PlaybackPosition` 原样搬（它就是为这件事存在的），`SystemClock` 切片 1 已有 | 截图序列观察进度文本连续 |
| 上游分叉 | 每个搬运文件头部注明来源与改动点；正文尽量少改 | code review |

## 5. 验证计划

1. **单元测试**（JVM，不需要播放机）：
   - 三段顺序：CONTEXT 前段 + 选中曲 + 保留的 USER_QUEUE + CONTEXT 后段
   - `playFrom` 之后 USER_QUEUE 不丢；`下一首播放` 插到 `currentIndex+1`，`加入队列` 插到 USER_QUEUE 段尾（`findUserQueueInsertionIndex` 两种入参）
   - 跳转只丢被跳过的 USER_QUEUE，目标之后的 CONTEXT/AUTOPLAY 保留；向后跳转不改列表
   - 移除 / 清空 / 拖拽置换（`restoreOrder`、`avoidIdentityShuffle`、`sections` 段序不变）
   - shuffle 不动当前曲、不动 USER_QUEUE；`MAX_QUEUE_HISTORY`/`queueHistoryTrimCount` 与 REPEAT_ALL 轮转的条目数
   - `QueueBuilder.extend` 的同录音去重（同名不同 videoId）与同艺人上限
2. **行为断言**（日志 + 截图）：搜索结果第 3 行 → 整列表入队且 `queueIndex == 2`；播完自动下一首；"加入队列"不打断当前曲；REPEAT_ALL 末→首回绕；位置 >10s 时上一首是重播本曲；Esc 关闭播放页且音频继续
3. **视觉判读**（截图，中英双语）：横屏全屏播放器、展开的队列面板、行菜单
4. **几何量核对**（对照原版常量，用单元测试断，不拿缩放过的截图当量具）：`LANDSCAPE_PLAYER_MIN_WIDTH = 560.dp`、`MAX_QUEUE_HISTORY = 25`、`BACK_RESTARTS_AFTER_MS = 10_000L`，以及移植后的 transport/scrubber 尺寸常量与原版逐字一致

## 6. 本切片不做

歌词全族（歌词面板/行内歌词/偏移/时间轴，切片 3）；RDAMVM 自动续播（切片 6）；音频输出设备切换、一起听、睡眠定时（切片 6）；运动封面 `CanvasArtworkPlayer`；真玻璃 `backdrop/` + `LiquidGlass`（切片 1.5）；专辑/ playlist / 艺人详情页、曲库、设置（切片 4）；行菜单其余动作项（各自的切片）。
