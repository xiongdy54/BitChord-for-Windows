# BitChord 桌面版 · 切片 3 设计：Apple Music 桌面外壳（侧边栏 + 工具栏 + 路由）

日期：2026-10-05
分支：`desktop-slice2-now-playing`（fork：xiongdy54/BitChord-for-Windows，上游 kushagrasinghx/BitChord）
状态：实施中

## 1. 背景：为什么要推倒外壳

切片 1/2 把上游的全屏播放页和队列内核完整搬了过来（165 个测试护着），但外壳是**一部居中在窗口里的手机**：52dp 磨砂顶栏 + 底部 440dp 浮动 tab 条 + MiniPlayer。这在桌面窗口里是不成立的：

- 没有侧边栏——Apple Music 的第一标识；资料库、播放列表无处安放（两个 tab 还是占位页）。
- 没有路由——`selectedTab: Int` 直接换内容，没有返回栈，因此**不可能有详情页**：点一张合集卡片只能直接开播，回不去、也进不去。
- 工具栏没有传输控制——桌面用户期望的"上一首/播放/下一首/音量"在窗口里没有常驻位置。
- `player.status` 是裸英文字符串（"resolving…"）直接进 UI。

数据层早已就位：`YtMusicRepository` 的 `browseSongs`（专辑/播放列表详情）、`artistPage`、`library`、`userPlaylists`/`libraryPlaylists`、`recents`、`moodAndGenres`/`moodGenreShelves` 全部实现但 UI 从未调用。本切片就是把这套已有数据接到一个 Apple Music 布局上。

**保留不动**：`desktop/playback/`（AudioEngine/PlayerController/队列内核）、`data/`、`ui/player/`（NowPlayingScreen 全屏播放页 + 队列）、主题（SF Pro + 取色）、`Main.kt` 的探针钩子。

## 2. 已确认的决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **外壳 = 左侧边栏 + 顶部工具栏 + 内容区**，参照 Apple Music（Windows 版，同 macOS Catalina+ 布局） | 用户指定"界面按照 Apple Music 的界面布局重新设计"。这不是给现有布局贴皮，是换骨架 |
| 2 | **导航 = 单一历史栈 + 前后指针**（浏览器语义），`Destination` 密封类 | 侧边栏行、卡片点击、工具栏前进后退都走同一个 `open/goBack/goForward`。不引入路由库——目的地总数 < 10，密封类枚举完就够了，可单测 |
| 3 | **工具栏承载传输**：前进/后退 ‹ ›、上一首/播放/下一首（原版 ic_player_* 字形）、LCD 正在播放（点击开全屏播放页）、音量（ThinSlider + 喇叭字形）、搜索框 | Apple Music 的工具栏就是这套东西。shuffle/repeat 留在全屏播放页，与原版一致 |
| 4 | **MiniPlayer、浮动 tab 条、磨砂顶栏删除**，不是搬家 | LCD 就是 mini player 的桌面形态；留着三套"正在播放"表面只会互相失步。BottomFadeScrim/TopBarBlur/LiquidGlass/OptimizedHaze 随之退役（Haze 仅剩播放页内部在使用） |
| 5 | **侧边栏分区**：Apple Music（立即聆听/浏览）→ 资料库（最近添加/歌曲/播放列表）→ 播放列表（账号歌单，登录后出现） | 与 Apple Music 一致；每一行都有真实数据端点背书。广播/艺人/专辑列表暂缺端点，不做死链接 |
| 6 | **详情页一体**：专辑/播放列表/艺人共用 `DetailScreen`（大封面头部 + 类型眉题 + 播放/随机播放 + 曲目列表 + 艺人横排分区），数据来自 `browseSongs`/`artistPage` | 上游 `DetailScreen.kt` 是 1700 行三合一页面，同一个先例。桌面版是重画而非搬运——布局按 Apple Music 详情页（居中圆角封面、小字眉题）来 |
| 7 | **窗口 < 760dp 时侧边栏收成 56dp 图标栏**，≥760dp 恢复 230dp | Apple Music 窄窗口的等价物；纯函数 `sidebarMode(width)` 可测 |
| 8 | **状态字符串改枚举**：`PlayerController.status: StateFlow<PlaybackStatus?>`（Resolving/Opening/NothingPlayable/ResolveFailed），UI 层本地化 | 裸英文进 UI 是切片 1 留下的债；枚举让 PlayerControllerTest 继续可测、字符串集中在资源里 |
| 9 | **窗口几何持久化**（大小/位置/最大化）进 `DesktopStore`，退出时写、启动时读 | 桌面应用的基本礼仪；开关状态都是纯字符串编解码，可单测 |
| 10 | **桌面专属字符串**放 `desktop/src/main/desktopStrings/`，由 `syncAppStrings` 的 Copy filter 合并进同步产物 | app/ 是上游模块保持只读（切片 2 规矩）；Compose 资源插件认的是合并后的完整文件，追加在 `</resources>` 之前即可 |
| 11 | **Esc 关播放页、Alt+←/→ 前进后退、空格播放暂停** | 空格在根 Box 的冒泡阶段处理（TextField 消费后不会再冒泡，不会误触）；Esc 规则原样保留（`escapeClosesPlayer`） |
| 12 | **不做的**：登录 UI（WebSession 已有 cookie 管道但无 WebView）、歌词、下载 UI、设置页、`onOpenAlbum/onOpenArtist` 重新挂回播放页 | 沿切片 2 的"宁可少给，不给死按钮"；详情页是本切片的主要形体，其余按原计划留给后续切片 |

## 2a. 用户参考图带来的修订（实施中途追加）

用户提供了 Apple Music Windows 版的截图作为顶栏布局的权威参照，据此修订：

| # | 修订 | 参照依据 |
|---|---|---|
| R1 | 工具栏传输改为**五键**：shuffle / 上一首 / 播放 / 下一首 / repeat（shuffle、repeat 从全屏播放页"外溢"到工具栏，两处共用同一状态） | 参照图传输区五键一排 |
| R2 | **LCD 做成悬浮面板**：圆角、页面底色、悬于 chrome 之上，而非平铺文本 | 参照图中央那块独立的显示屏面板 |
| R3 | **搜索框从工具栏右侧移到侧边栏顶部** | 参照图搜索框在侧边栏最上方 |
| R4 | 侧边栏图标**单色**（onBackground），选中态 = 中性填充 + **红色左缘指示条**；红色只出现在这一处 | 参照图侧边栏图标为单色、选中行左缘红条 |
| R5 | 窄窗口图标栏（rail）加一个搜索图标，保证搜索在窄窗口下仍可达 | 侧边栏收窄后搜索框随之消失的补偿 |

播放列表区头部的"+"、资料库的"艺人/专辑"两行、广播行仍不做——没有数据端点背书（见决策 5），参考图的完整性不改变这条底线。

## 3. 交付物

### 3.1 导航 `ui/shell/NavState.kt`（新）

```kotlin
sealed interface Destination {
    data object Home; data object Explore; data object Search
    data object LibrarySongs; data object LibraryPlaylists; data object RecentlyAdded
    data class Detail(val kind: BrowseType, val browseId: String,
                      val title: String, val subtitle: String?, val thumbnailUrl: String?)
}
class NavState {   // 可观察属性 + 纯函数历史操作（NavHistory 可单测）
    val current: State<Destination?>; val canGoBack/canGoForward: State<Boolean>
    fun open(dest); fun goBack(); fun goForward()
}
```

`ShellState` 收窄成两件事：`showPlayer` + `menuSong`（tab 字段删除）。两者都活在窗口内容之外（Main.kt 的窗口级键处理要读它们，同切片 2 的理由）。

### 3.2 外壳 `ui/shell/`

| 文件 | 内容 |
|---|---|
| `Shell.kt`（重写） | `Row { Sidebar; Column { Toolbar; 内容区(when(current)) } }` + 原样保留的全屏播放页覆盖层（含压住指针的那段 pointerInput 及其注释——那段教训仍然成立） |
| `Sidebar.kt`（新） | 分区标题（labelSmall/次色）+ 行（图标 17dp 红色 accent + 13sp 文本，32dp 高，选中 = surfaceVariant 圆角填充）。歌单区由 `LibraryViewModel.playlists` 驱动 |
| `Toolbar.kt`（新） | 52dp 高：‹ › → 上一首/播放/下一首 → LCD（26dp 封面 + 两行跑马灯，空态画 ic_logo 字标）→ 音量 → 搜索框。高度沿用旧顶栏的 52dp，工具栏与侧边栏同底色 |
| `HomePage.kt` | 合集卡片点击改为 `nav.open(Detail(...))`（不再直接 playCollection）；单曲照旧直接播 |
| `SearchPage.kt` | `onBrowseClick` 打开详情页；顶部内容边距换新常量 |

### 3.3 内容页 `ui/screens/` + `ui/`

| 文件 | 内容 |
|---|---|
| `DetailScreen.kt`（新）+ `DetailViewModel.kt`（新） | Apple Music 详情页头部（全宽模糊封面带 + 居中 160dp 圆角封面 + 类型眉题 + 大标题 + 副题 + 播放/随机播放红底胶囊）+ `SongRow(trackNumber=…)` 曲目列表 + 艺人页横排分区（复用 HomeScreen 的 internal `Shelf`/`ShelfCard`） |
| `LibraryPages.kt`（新）+ `LibraryViewModel.kt`（新） | 歌曲（`library()` 的 liked+library 列表）、播放列表（`userPlaylists`+`libraryPlaylists` 网格，复用 `libraryGrid`）、最近添加（`recents()`）。未登录显示 `library_sign_in_description` 空态 |
| `ExploreScreen.kt`（新）+ `ExploreViewModel.kt`（新） | `moodAndGenres()` 流派网格 → 点进 `moodGenreShelves` 页 → 卡片播放/进详情 |
| `PlayerPage.kt` | 保持 40 参数适配层原样；`showPlayer` 的来源注释更新为 LCD 点击 |

### 3.4 平台件

- `Main.kt`：`rememberWindowState` 换成 `DesktopStore` 读出的 `WindowPlacement`（编解码纯函数放 `ui/shell/WindowPlacement.kt`）；窗口键处理加 Alt+←/→。
- `build.gradle.kts`：`syncAppStrings` 加 desktopStrings 合并 filter。
- `desktop/src/main/desktopStrings/{values,values-zh}/strings.xml`（新，进 git）：`recently_added`、`browse`、`sidebar_apple_music`、`status_resolving/opening/nothing_playable/resolve_failed`、`volume`、`forward` 等。

### 3.5 删除

`FloatingBottomBar.kt`、`FrostedTopBar.kt`、`MiniPlayer.kt`、`BottomFadeScrim.kt`、`TopBarBlur.kt`、`LiquidGlass.kt`、`OptimizedHaze.kt`（引用全部随本切片退役；`Haptics`、`TopFadeBlur`、`Skeletons` 仍被存活页面使用，保留）。`DesktopLayoutTest` 的浮动条断言换成侧边栏/工具栏常量。

## 4. 关键技术点与风险

| 风险 | 对策 | 验证手段 |
|---|---|---|
| 全屏播放页的指针吞噬依赖"haze 源在底层"的旧解释——外壳重画后 haze 源没了 | 吞噬 pointerInput 与是否 haze 无关，原样保留；播放页自带渐变底，不依赖外壳的任何图层 | 截图：播放页下方无外壳残影；Esc 关闭后外壳完好 |
| `SongRow` 的 swipe 语义是触屏的，桌面鼠标没有 swipe | 它本来就接受 `onSwipeToQueue = null`；行操作给右键菜单是后续切片，本切片行内只留点击播放 + `trackNumber` 模式 | 详情页曲目可点播、无异常 |
| 空格键与文本框冲突 | 冒泡阶段处理：TextField 消费的按键不会到达根 Box | 单测覆盖判定函数；实机搜索框打字无误触 |
| 字符串合并 filter 与 Compose 资源任务的次序 | filter 挂在既有 `syncAppStrings` 的 `filesMatching` 上，依赖链不变 | 构建后 Res 访问器包含新增键；中英文各跑一遍 |
| 详情页请求数据（`browseSongs` 6–19s 解析风险不在此——它只 browse 不解析流） | UiState 三态 + 失败重试行，同 HomeViewModel 的形状 | 探针打开一张专辑页截图 |
| 窗口位置恢复到离屏坐标（换显示器） | 恢复前 clamp 进 `WindowPosition` 合法范围做不了（无屏幕枚举），退而只恢复大小 + 最大化，位置不存 | 说明书里写明；测试覆盖编解码往返 |

## 5. 验证计划

1. **单元测试**：NavHistory 的 open/back/forward/截断/去重；`sidebarMode` 断点；WindowPlacement 编解码往返；`spaceTogglesPlayback` 判定表；`PlaybackStatus` 枚举替换后 PlayerControllerTest 全绿；新常量（SIDEBAR_WIDTH/TOOLBAR_HEIGHT/RAIL_WIDTH/断点）进 DesktopLayoutTest。
2. **行为断言**（探针）：主页点合集卡片 → 详情页出现且 URL 栈可后退；侧边栏切歌单/资料库 → 内容区换页；LCD 点击 → 全屏播放页；Esc 关闭。
3. **视觉判读**：暗/亮两主题 × 主页/详情页/资料库/工具栏播放态截图，中英双语。
4. **常量核对**：`SIDEBAR_WIDTH = 230.dp`、`TOOLBAR_HEIGHT = 52.dp`、`SIDEBAR_RAIL_WIDTH = 56.dp`、`SIDEBAR_BREAKPOINT = 760.dp` 进测试。

## 6. 本切片不做

登录 UI 与账号切换；歌词面板；下载/本地音乐；设置页；广播 tab；播放页重新挂 `onOpenAlbum/onOpenArtist`；行右键菜单（SongActionsSheet 桌面化的切片 4 候选）。
