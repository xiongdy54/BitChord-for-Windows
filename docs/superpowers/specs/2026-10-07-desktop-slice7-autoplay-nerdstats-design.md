# BitChord 桌面版 · 切片 7 设计：AutoPlay 自动续播 + NerdStats 全量（测量、徽章与落盘）

日期：2026-10-07
分支：`desktop-slice2-now-playing`
状态：设计中

## 1. 背景

切片 2 搬播放页时，AutoPlay 与 NerdStats 两族整块删去，现场留下了等待的注记：

- `NowPlayingScreen.kt:427` 与 `:2260`（决策 6 注）：`InlineQueue` 被硬编码 `autoplayEnabled = false`——AUTOPLAY 分区的标题在渲染（`PlayerQueue.kt:372` `AutoplayHeading`），但"没有东西填充这个 tier"，开关也不出现；
- `PlayerController.playRadio`（`desktop/playback/PlayerController.kt:338`）头注："filling that tier — 'when the queue runs out, keep going' — is a later slice's decision"；
- `PlayerControls.kt:230-248` 的音质说明块只读 `effectiveAudioQuality == LOSSLESS`，`SleeveNerdStats`（`:271`）只显示解析器上报的码率与来源；
- `AppSettings.showNerdStats`（`data/settings/AppSettings.kt:122`）是会话内流，没有落盘键——设置页的开关重启即忘。

上游规模：`playback/Autoplay.kt` 117 行（纯逻辑，五个函数两个常量）；补给循环在 `PlaybackService.loadAutoplayForCurrentTrack()`（`:2274`，含空结果有界重试、种子去重、repeat-all 的 stash/restore、开关关闭时剪除未播的 AUTOPLAY 行）；`data/NerdStats.kt` 347 行（Snapshot 模型 + 测量/声明两路数据 + 竞速状态）；测量来自 ExoPlayer 的 `AnalyticsListener`（`PlaybackService.kt:1020-1140`）；UI 徽章状态机在 `PlayerControls.kt` 的 `PlaybackQualityLabel`/`LosslessOrStats`（约 240/1319 行处）。

桌面已就位的条件（逐项核实过）：`QueueTier.AUTOPLAY` 与全部 tier 语义在位（`QueueCoordinator` 的 AUTOPLAY 跳转晋升、`findUserQueueInsertionIndex` 的"USER 在前、AUTOPLAY 在后"不变量）；`QueueBuilder.extend(existing, candidates, limit)` 去重已在（`playback/QueueBuilder.kt:34`）；`YtMusicRepository.radio(videoId)`（`data/YtMusicRepository.kt:550`）与 `search(query, filter)`（`:316`）在位；`PlayerQueue.kt` 已渲染 AUTOPLAY 分区与标题；vlcj 的 `MediaPlayer` 可从 `media().info()`（轨道编解码/采样率/声道）与 `media().statistics()`（码率）取测量值。

媒体会话（上游 `PlaybackService` 的 Media3 席位、通知、媒体键）与音质升级引擎（`QualityUpgrade.kt` 802 行 + 隐藏播放器试听 + 无缝换流）是两块各自独立的大工程，桌面对应物分别是 SMTC 与第二引擎实例的试听换流——都**不进本切片**（见 §6）。

## 2. 已确认的决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **`playback/Autoplay.kt` 桌面形移植**：`MAX_QUEUED_AUTOPLAY`、`autoplayQueueNeedsRefresh`、`youtubeSeedFor`、`loadAutoplayTracks` 逐字；`autoplayEnabledFor`/`autoplaySupplierId` 两个派对函数不搬 | ListenTogether 未移植，派对参数无来源可给；其余四个是纯 JVM 逻辑，依赖（`YtMusicRepository.search/radio`、`QueueBuilder.extend`、`TrackMatcher`、`QueueCoordinator.asQueueEntry`）桌面全部在位 |
| 2 | **`AppSettings.autoplay` 落盘**（键 `autoplay`，默认 true，同上游） | 上游键名与默认值照搬；设置页加"播放"组放开关 |
| 3 | **补给循环 = `PlayerController` 的新成员，钩在 `resolveAndPlay` 成功路径上**：每条曲目开始出声后，若 `autoplayQueueNeedsRefresh(autoplay, repeatAll==ALL, currentIndex, itemCount, loadInProgress)` 成立则发起一批 `loadAutoplayTracks`，经 `QueueTimeline.appendAutoplay()` 追加到队尾并标 AUTOPLAY tier；`autoplaySeed` 铭牌照搬——同一曲目触发的多次回调只补一次 | 上游触发点是 `onMediaItemTransition`，桌面等价的"一条曲目真正开始播放"就是 resolve 成功把 URL 交给引擎的那一刻；供给循环与队列编排同在 [queueDispatcher] 上，无新线程模型 |
| 4 | **空结果有界重试照搬**（`MAX_AUTOPLAY_EMPTY_REFRESHES` × `AUTOPLAY_EMPTY_REFRESH_DELAY_MS`，仍以"当前曲目是队尾"为条件） | 上游同款语义，防止电台接口偶发空响应让开启的 AutoPlay 永远沉默 |
| 5 | **开关关闭即剪除**：`setAutoplay(false)` 时取消在途 load、清种子、`QueueTimeline.dropAutoplayTail()` 倒序移除未播的 AUTOPLAY 行 | 上游 `toggleAutoplayFromNotification` → `dropAutoplayTracksFromQueue`（`:2415`）的桌面形 |
| 6 | **repeat-all 的 stash/restore 照搬**：切到 ALL 时把 AUTOPLAY 尾巴摘下收起（`stashAutoplayTracks` 语义），切回 OFF 时若种子同曲且无重复则放回（`restoreAutoplayTracks` 语义） | 上游 `:2428`/`:2449`；不做则 repeat-all 会在循环里反复撞见建议曲目，语义走样 |
| 7 | **`dontRepeatSuggestions` 不搬** | 它吃的 sessionSongHistory 在上游是服务级状态机（播放历史持久化族），桌面无此基建；`QueueBuilder.extend` 对当前队列的去重已覆盖"这批不重复"的最小语义 |
| 8 | **`NerdStats.kt` 扩到桌面形 Snapshot**：`codecLabel`/`bitrateKbps`/`sampleRateHz`/`channels`/`bitDepth`/`sourceName` + 派生（`isLossless`/`isHiRes`/`isHiQuality`/`downgraded`）；`current` 流在换曲时清空；`racingLossless` 保留空集（升级引擎未移植，没有竞速在飞，徽章的"升级中"态自然不亮） | 上游 `data/NerdStats.kt` 的派生逻辑纯 JVM 逐字；测量/声明两路里声明路桌面已有（`StreamResolver` 写 `onStreamPicked`），测量路见决策 9 |
| 9 | **测量 = `VlcAudioPlayer` 从 vlcj 取**：`media().info()` 的 `AudioTrackInfo`（codec/采样率/声道）+ `media().statistics()` 的码率，经 `AudioEngine` 新的 `onAudioStats` 回调进 `PlayerController` → `NerdStats.current`；FLAC STREAMINFO 深度解析不做（vlcj 不暴露位深，`bitDepth` 恒 null，Hi-Res 判定退化为采样率门） | ExoPlayer 的 AnalyticsListener 无桌面对应物，vlcj 的 info/statistics 是最接近的诚实来源；位深拿不到就写明拿不到，不猜 |
| 10 | **质量徽章 = `LosslessOrStats` 状态机桌面形**：替换 `PlayerControls.kt:230-248` 的说明块——测量到位显示"无损 / Hi-Res 无损 / 高音质 / 数据 saver"档位与测量行，测量未到显示声明档；"升级中"两态留在代码里但数据源不存在时不可达 | 徽章读的是 [NerdStats.current] 与 [AppSettings.effectiveAudioQuality]，不依赖升级引擎；竞速态的接入面留着，切片 8 引擎一来就接上 |
| 11 | **`showNerdStats` 落盘**（键 `show_nerd_stats`，默认 false，同上游） | 现状是会话内流，重启即忘，与"设置"的名义不符 |
| 12 | **队列 UI 接线**：`AutoplayHeading` 加 Switch（`autoplayEnabled` + 回调，上游 `PlayerQueue.kt:336-340` 的 `AutoplayHeading` 同形），`InlineQueue` 的 `autoplayEnabled` 从 `AppSettings.autoplay` 读入，`NowPlayingScreen` 决策 6 注删除 | `PlayerQueue` 的分区渲染已在，缺的只是开关与填充——正是本片补的两件事 |

## 3. 交付物

| 文件 | 内容 |
|---|---|
| `playback/Autoplay.kt`（新，桌面形） | 决策 1 的四函数一常量 |
| `data/settings/AppSettings.kt` | `autoplay` 落盘 + `showNerdStats` 落盘 |
| `desktop/playback/PlayerController.kt` | 补给循环、种子铭牌、空结果重试、开关剪除、repeat-all stash/restore、`onAudioStats` 接线 |
| `playback/QueueTimeline.kt` | `appendAutoplay(songs)`、`dropAutoplayTail()`、`stashAutoplayTracks()`/`restoreAutoplayTracks()` |
| `desktop/playback/AudioEngine.kt` + `VlcAudioPlayer.kt` | `onAudioStats` 回调 + vlcj 测量 |
| `data/NerdStats.kt` | Snapshot 模型 + 派生 + `current` 流 + `clearOnTrackChange` |
| `ui/player/PlayerControls.kt` | 质量徽章状态机 + `SleeveNerdStats` 测量行 |
| `ui/player/PlayerQueue.kt` + `NowPlayingScreen.kt` | `AutoplayHeading` 开关 + 决策 6 注退场 |
| `ui/screens/SettingsDialog.kt` | "播放"组（AutoPlay 开关）+ NerdStats 开关换用落盘流 |
| 测试 | `autoplayQueueNeedsRefresh` 判定表（上游 `AutoplayFallbackTest` 移植）+ stash/restore 往返 + drop 尾巴 + Snapshot 派生表 + 补给循环状态机（假 repo seam） |

## 4. 关键技术点与风险

| 风险 | 对策 | 验证手段 |
|---|---|---|
| 补给循环被一次换曲的多次回调重复触发 | `autoplaySeed` 铭牌（同曲一次）+ `loadInProgress` 门，与上游同款双保险 | 补给循环状态机单测 |
| 追加的 AUTOPLAY 行破坏 USER-在前的插入不变量 | 追加只发生在队尾，`findUserQueueInsertionIndex` 的扫描天然把它当尾部边界；`QueueCoordinatorTest` 既有断言护航 | 既有队列测试全绿 |
| vlcj 的 `media().info()` 在部分流上返回 null/无音频轨道 | 回调允许 null——`NerdStats.current` 清空而非猜值；徽章退回声明档 | 实机探针：播一条 YouTube 流与一条本地下载文件各读一次 |
| 换曲时测量残留（上一条的 codec 标到这一条上） | `NerdStats.current` 在 `resolveAndPlay` 入口清空，统计回调只写新值 | 单测 + 实机 |
| stash/restore 与手动队列编辑竞态（收起的行被 `moveRow` 撞见） | 收起的行离开列表即无索引可撞；restore 的"种子同曲且无重复"门照搬 | stash/restore 往返单测 |
| 设置页开关在播放中翻转时 UI 与队列不同步 | 开关回调同在 [queueDispatcher] 上执行剪除；`AppSettings.autoplay` 是单一事实源，UI 读流 | 实机 |

## 5. 验证计划

1. **单元测试**：判定表移植 + 新增状态机（假 `radio`/`search` seam 注入 `loadAutoplayTracks` 的调用方）、stash/restore、drop 尾巴、Snapshot 派生。
2. **行为断言**：探针实机——播完一条短队列到尾部，观察 DebugLog 的 autoplay 补给行与队列分区出现建议行；关开关看尾巴剪除。
3. **视觉判读**：队列 AutoPlay 分区（有行/空两态 + 开关）、质量徽章各档 × 深/浅 → `.shots/slice7/`。
4. **常量核对**：`MAX_QUEUED_AUTOPLAY = 10`、重试上限与间隔、`HI_QUALITY_KBPS = 256` 进测试。

## 6. 本切片不做

SMTC/媒体会话（媒体键、系统覆盖层、位置上报——独立切片，Windows 侧要走 WinRT interop，工程量与风险自成一片）；音质升级引擎与无损竞速（上游 `QualityUpgrade.kt` 802 行 + 隐藏播放器试听 + 无缝换流，桌面需要第二 `AudioEngine` 实例与换流接缝，随 SMTC 一起排切片 8）；`dontRepeatSuggestions`（决策 7）；Android Auto 浏览树、媒体通知、Discord RPC、Scrobble、桌面小组件（无对应物或无服务面）；音质切换中对当前曲目的 audition 接缝（同属切片 8）。
