# BitChord 桌面版 · 切片 21 设计:播放器的体面(滑走的甲板、音视频切换、Canvas 的门)

日期:2026-10-11
分支:`desktop-slice2-now-playing`
状态:待用户复核

## 1. 背景

最后一批挂账:**SlidingPlayerDeck**(队列滚动时滑走整个下半播放器——上游 `NowPlayingScreen.kt:452-499` 用一个 Animatable 同时驱动像素与占高,桌面切片 2 的记录里写着"deck 未搬,行为丢失",`InlineQueue` 的 `collapsePlayerOnScroll/onRevealPlayer/onHidePlayer` 三参一直在场等线)、**音/视频版本切换**(切片 8 顺延;上游 `MainActivity:995-1080` 的 resolve→distinct 检查→保位换流;桌面 `YtMusicRepository.resolveAudio/resolveVideo` 是零调用的死缝;上游字符串 `convert_to_audio/video` 现成)、**Spotify Canvas 登录**(切片 13 顺延;桌面 `SpotifyToken.accessToken/clientToken` 双双返回 null,`SpotifyCanvas` 只能靠 REST 回退之外的空转;上游从用户持有的 `sp_dc` 设置出发,离屏 WebView 铸 bearer)、**`spotify_canvas_auto_hide`**(上游默认开的五秒收甲板;桌面 `NowPlayingScreen.kt:725-730` 的注释等着它)。

## 2. 已确认的决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **SlidingPlayerDeck 逐字移植**:一个 Animatable 拥有像素与占高(layout 报告 `height*reveal`,加权兄弟自行回补);竖版底部控制块(玻璃位在内)包进甲板;`InlineQueue` 接通 `collapsePlayerOnScroll=true` + `onRevealPlayer/onHidePlayer` → `queueControlsOpen`;visible = `!queueOpen || queueControlsOpen` | 上游的组件是布局级动画的教科书;桌面的队列是覆盖层,滚走控制块让队列 takeover 的语义原样成立;歌词表/Canvas 态并入决策 3 的条件 |
| 2 | **音视频切换 = PlayerController.swapToVersion(target)**:onQueue 内守卫(仍是当前行、无在途换流、无在途 blend)→ IO 上 resolve(音频走 `resolveAudio`,视频走 `resolveVideo`)→ distinct 检查(解析回自己=无切换)→ 静音试听引擎验 URL(质量换流的既有纪律)→ `engine.play(candidate, startAtMs=engine.timeMs)` 保位落流 + `queue.replaceRange(current, current+1, target)` 换行;`isVideoOrigin=true` 随行 | 上游语义的桌面同构;`replaceRange` 是 QueueTimeline 现成 API;试听复用 `auditionProves` |
| 3 | **入口 = 播放器右键菜单**:`PlayerContextMenuArea` 加 `onToggleAudioVersion`,行文案按当前行取向(`isVideo` → "转换为音频",否则 "转换为视频");转换进行中去重(`swapToVersion` 的 reentry 守卫) | 上游 SongActionsSheet 的行语义;桌面播放器右键即上游"从播放器开 sheet" |
| 4 | **Canvas 门 = sp_dc 设置 + 直连端点铸 token**:`AppSettings.spotifySpdcToken`(键 `spotify_spdc_token`,上游同键名;入 SECRETS 不进备份);`SpotifyToken.accessToken()` 铸自 `open.spotify.com/api/token?reason=transport&productType=web_player`(sp_dc 作 cookie),缓存至过期前 30s;`clientToken()` 保持 null——canvaz-cache 的 GraphQL 路径退场,REST 回退路径(bearer 即可、Client-Token 可选)接管;设置对话框 Canvas 簇加 sp_dc 字段(PillTextField,WebDAV 行同形) | 端点是 web player 同一条门;上游因该门被闸改走 WebView 收割——桌面照搬收割要离屏 CEF 截 JS,是独立大件;REST 回退让 Canvas 在 bearer 下工作,失败即静默落回 Apple/Tidal/社区,链路不断 |
| 5 | **auto-hide 甲板 = 桌面化的"演出中"**:桌面 Canvas 以视频替封面呈现(无手机全屏态),"presentation" 即"Canvas 正在渲染且播放中";`spotify_canvas_auto_hide`(键上游同,默认 true)+ 设置行(Canvas 簇);条件齐(autoHide && 演出中 && 播放中 && 无拖动/刮擦 && 歌词表关 && 队列关)五秒收甲板;**玩家面上任何指针移动或点击即刻唤回**(`pointerInput` 监听 Move/Press → controlsOpen=true——手机"点哪都醒"的桌面同义) | 上游 :1105-1125 的倒计时逻辑逐条对应;不动即收、动即醒是同一行为的鼠标方言 |
| 6 | **不做**:OriginalVersion 钉集(上游"恢复原版/升级音质"属于模块源 QualityUpgrade 引擎的翻案语义,桌面无该引擎,钉了无处用);离屏 CEF 的 web player 收割(决策 4);smart version alignment(上游的音频对齐偏移,另一套测量) | 每一项都有接缝记录 |

## 3. 交付物

| 文件 | 内容 |
|---|---|
| `ui/player/NowPlayingScreen.kt` | `SlidingPlayerDeck` 组件;竖版控制块入甲板;`queueControlsOpen`/`canvasControlsOpen`/`canvasPresenting` 状态;倒计时与指针唤醒;InlineQueue 三参接通;菜单回调 |
| `ui/player/PlayerQueue.kt` | (无改——三参已在) |
| `desktop/playback/PlayerController.kt` | `toggleAudioVersion()` + `swapToVersion` 内部(reentry 守卫、试听、保位落流、换行) |
| `ui/player/PlayerContextMenu.kt` | 版本切换行(convert_to_audio/video 按取向) |
| `data/canvas/SpotifyToken.kt` | bearer 铸造(sp_dc cookie → 端点)、缓存、失败静默 |
| `data/settings/AppSettings.kt` | `spotifySpdcToken`、`spotifyCanvasAutoHide` |
| `ui/screens/SettingsDialog.kt` | sp_dc 字段行 + auto-hide 行(Canvas 簇) |
| `desktopStrings` | 桌面专属缺键(en/zh) |
| 测试 | swapToVersion 守卫表(fake 引擎:换行换流保位);SpotifyToken 缓存/过期算术;ReleaseDownloadState 式的纯函数(若抽出) |

## 4. 关键技术点与风险

| 风险 | 对策 | 验证手段 |
|---|---|---|
| 甲板收起把玻璃位一起收走 | 甲板包住整个控制块(玻璃 modifier 在内层)——玻璃随块滑走,与上游一致 | 截图:滚动队列两态 |
| 换流与质量换流/blend 打架 | reentry 守卫 + `crossfade.isTransitioning` 检查 + 仍当前行检查(上游同款) | 单测守卫表 |
| 端点被闸(上游绕道的理由) | 失败静默 null;Canvas 链自然落回其它源;日志一行 | 代码审读 + 无凭据时链路照旧 |
| 指针监听的性能 | Move 事件只写一个布尔(状态去重),无重组风暴 | 代码审读 |
| sp_dc 是凭据 | 入 SECRETS 不进备份;输入框隐私形态 | 代码审读 |

## 5. 验证计划

1. 单元测试:§3 测试行,全套全绿。
2. 行为断言:探针截图——队列上滑滚动后控制块滑走/回顶唤回两态;播放器右键菜单含版本切换行。
3. 视觉判读(`.shots/slice21/`):甲板两态(zh-dark);右键菜单。
4. 常量核对:5s 闲置、30s 提前过期、SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS。

## 6. 本切片不做

OriginalVersion 钉集(决策 6);离屏 CEF 收割(决策 4);smart alignment;横版布局的甲板(竖版先行,横版 pane 结构不同)。

## 7. 实施记录(2026-10-11 回填)

**交付**:决策 1–5 全部落地。`SlidingPlayerDeck` 逐字移植(app :452-499)+ 上游两常量(IDLE 5s / ANIMATION 420ms);竖版底部控制块(玻璃位在内)包进甲板,`InlineQueue` 三参接通(`collapsePlayerOnScroll=true` → `queueControlsOpen`),切片 2 记录的"行为丢失"注记退役;Canvas 门:`spotifyCanvasAutoHide`(上游键,默认 true)+ `spotifySpdcToken`(上游键,入 SECRETS)、`SpotifyToken` 直连端点铸 bearer(sp_dc cookie,缓存至过期前 30s,`tokenEndpoint` internal 可指),`clientToken()` 如实恒 null(GraphQL 路径退场、REST 回退接管——它本就把 Client-Token 当可选项);倒计时条件表照上游(演出中/开着/开着 autoHide/播放中/不在刮擦/歌词表关/队列关),桌面"演出中" = `heroClip != null && canvasRendered && isPlaying`;玩家根加 `pointerInput` 监听 Move/Press 唤回(`canvasControlsOpen = true`);设置对话框 Canvas 簇:sp_dc 隐私字段(PillTextField isPassword)+ Save + auto-hide 行。音视频切换:`PlayerController.toggleAudioVersion()`(`resolveAlternateVersion` 测试缝,生产走 `resolveAudio/resolveVideo`)——守卫链(在途切换/在途质量换流/仍当前行/distinct/blend 等让/超时搁置)照质量换流纪律;播放中走试听后的保位落流(engine.play startAtMs=timeMs + applyCurrentEqualizer),暂停行走"下回播放生效"的换行(上游无此分支——Media3 的 swapToVersion 对暂停行也直接换;桌面引擎没有 open-and-hold 的版本落点,保位重放会在恢复时从断点放新版,与"转换"的语义一致,记录为桌面形状);`PlayerContextMenuArea` 版本行(按 `isVideo` 取向,convert_to_audio/video 上游字符串);菜单预览探针同步加演示行。

**与设计的偏离**:

1. **暂停行的转换语义**:设计未区分;实施定为"换行不换流"(下一回播放即新版),因为桌面引擎没有上游 `swapToVersion` 的暂停落点——保位重放一个暂停的流会立刻出声,违背"暂停"二字。单测钉死("a paused row converts without touching the engine")。
2. **菜单预览探针的演示行**:菜单截图走的是 Main.kt 自建演示项(真实弹窗 PrintWindow 不可见),版本行进演示列表才可截——真实菜单的构建器(`PlayerContextMenuArea`)与演示行同文案。
3. **倒计时条件少了 volume.dragging**:桌面音量条是 ThinSlider 滑杆,无上游的 drag 状态可读;刮擦(scrub.scrubbing)在场,音量拖动时 Move 事件本身会唤回(pointerInput 先于滑杆消费),行为等价。

**验证**:62 类 603 测试全绿(新增 3:视频行转换保位换流且 isVideoOrigin 随行、暂停行只换行不动引擎、resolve 回同行的 no-op)。**行为断言(截图,`.shots/slice21/`)**:播放器右键菜单——"转换为视频"列于睡眠定时器之下、行菜单之上(真实流播放中,136kbps 徽章在列)。**人手验证待做**:队列上滑滚动的甲板滑走/回顶唤回两态(需要鼠标);Canvas 播放中的五秒收甲板与指针唤醒(需要有 Canvas 的曲目与 sp_dc);sp_dc 有值时 Spotify Canvas 实际出片(端点可用性)。

**已知瑕疵**:甲板两态与 Canvas 门的视觉证据依赖人手交互,探针无鼠标;`clientToken()` 恒 null 使 canvaz-cache GraphQL 路径退场,Canvas 依赖 REST 回退(该路径的可用性随 Spotify 而定,失败静默落回其它画布源);横版播放器未接甲板(pane 结构不同,竖版先行)。
