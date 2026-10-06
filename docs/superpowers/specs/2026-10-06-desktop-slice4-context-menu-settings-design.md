# BitChord 桌面版 · 切片 4 设计：桌面交互收口（右键菜单 + 播放页回挂 + 设置）

日期：2026-10-06
分支：`desktop-slice2-now-playing`
状态：实施中

## 1. 背景

切片 3 交付了 Apple Music 外壳，但行的交互还停在触屏语义的残余上：`SongRow` 的 `combinedClickable` 留着 `onLongPress/onMore` 参数，桌面源码里**没有任何调用者挂上**（`onShowActions` 零命中）。切片 2 设计里预留的 `DesktopSongActions` 行菜单在实施中让位给了播放页，被切片 3 推迟到本切片。鼠标用户对"右键一行"有硬期望；上游 14 项 `SongActionsSheet` 是 BottomSheet 语义，不能照搬。

两笔同时到期的债：播放页当年因"无处可去"拔掉的 `onOpenAlbum/onOpenArtist`（`NowPlayingScreen.kt:422` 注释、"等一个能去的 browse 页"）——切片 3 的 `Destination.Detail` 就是那个去处；`AppSettings.showNerdStats` 等七项设置没有 UI 入口，主题仍被系统绑架（用户无法强制深色）。

数据层全部就位，零新增端点：`enqueueNext/enqueueLast`（PlayerController:399/403）、`LikeState`、`radio(videoId)`、`Song.albumId/artistId` browseId 字段（Models.kt:23-25 已核实）。

## 2. 已确认的决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **右键菜单 = Compose Desktop 自带 `ContextMenuArea`**；菜单项由纯函数 `songActions(song, liked)` 构建（能力过滤 → `SongAction` 枚举列表），composable 层把枚举映射成 `ContextMenuItems.Item` | 桌面原生语义（右键即出、键盘可导航），不是 BottomSheet 仿品。能力过滤是纯函数，可单测。**回退**：若 ContextMenuArea 的焦点/样式实测不合格，退回自绘 `Popup` 菜单——组件可换，`songActions()` 不动 |
| 2 | **菜单 8 项分两组**：〔下一首播放 / 加入队列 / 点赞·取消赞 / 开始电台〕〔打开专辑 / 打开艺人 / 复制链接〕，尾组之后仅当前播放中的那首歌追加〔复制日志〕。打开专辑/艺人仅 `albumId/artistId` 非空时出现（不给死按钮） | 每项有内核或端点背书。"复制日志"沿用上游"只从播放页"的规则（SongActionsSheet.kt:359 注释：它关于*正在播放*的这首）——即右键的歌是 `player.song.value` 时才出现 |
| 3 | **"开始电台"不走 AUTOPLAY 档位**：`PlayerController.playRadio(song)` 取 `radio(videoId)` 后 `playFrom(list, 0)`，与普通合集播放同语义，复用 `playCollection` 的 loading 形态 | AUTOPLAY 档位（播完自动续）属切片 7；本切片只要"能开播一个电台队列"。放 Controller 而非 UI 层，沿用 `playCollection(browseId, label)` 先例 |
| 4 | **入口 v1 只做右键**（`ContextMenuArea` 包行）；`SongRow` 的 ⋮/`onMore` 既有参数不动，补挂推迟 | ContextMenuArea 覆盖桌面的主语义（Windows 还有菜单键）；⋮ 的悬停命中区调优是独立工作量，等右键被实测检验后再决定去留 |
| 5 | **播放页回挂**：`NowPlayingScreen` 恢复 `onOpenAlbum/onOpenArtist`（可空参数），`PlayerControls.opensPage`（:846 休眠中）复活，挂回标题（专辑）与艺人行的四处；`PlayerPage.kt` 适配层接 `nav.open(Destination.Detail(...))` | 切片 2/3 注释里明确"等一个能去的 browse 页"；`LandscapePlayer` 的两处同样复活 |
| 6 | **设置 = 侧边栏底部固定齿轮行 + `Dialog`**，不进导航栈 | 设置是窗口级而非内容级，进返回栈会让 Esc 语义变味；Apple Music 的设置也是模态窗口 |
| 7 | **设置页第一期四组**：外观（跟随系统/浅色/深色）、语言（跟随系统/English/中文）、nerd stats 开关、关于（版本 + 上游致谢） | 主题与语言最疼（切片 1 的两处"已知行为差异"）；nerd stats 补"有状态无入口"；`AppSettings` 已有 `MutableStateFlow` + properties 落盘形状，照 `shuffleEnabled` 的样子加字段 |
| 8 | **主题覆盖双通道**：`resolveDarkTheme(setting, systemDark): Boolean` 纯函数喂 `BitChordTheme(darkTheme=…)`；**同时** `applyWindowBackdrop` 深色时设 `DWMWA_USE_IMMERSIVE_DARK_MODE(=20)` | 已核实 `applyWindowBackdrop` 目前不设该属性——Mica 的明暗跟随应用的暗色声明，漏了它会出现"应用内深色、窗框 Mica 仍浅色"的割裂 |
| 9 | **语言切换 = 持久化 + 重启生效**：`AppSettings.language: StateFlow<String?>`（null=跟随系统），启动装配沿用 `bitchord.locale` 系统属性管道（Main.kt 已有，`Locale.setDefault` 在读资源之前） | 字符串资源跟随 JVM 默认 locale（Main.kt 注释）；热切换要重建整棵组合树，v1 不值。设置对话框里明示"重启后生效" |
| 10 | **`DebugLog` 补环形缓冲**（最近 200 行）+ `dump()`，"复制日志"写剪贴板（AWT `Toolkit.clipboard`） | 切片 2 决策 5 的预留；菜单项按决策 2 的门槛出现 |

## 3. 交付物

| 文件 | 内容 |
|---|---|
| `ui/components/SongContextMenu.kt`（新） | `SongAction` 枚举（PlayNext/AddToQueue/ToggleLike/StartRadio/OpenAlbum/OpenArtist/CopyLink/CopyLog）+ `songActions(song, liked, isCurrent): List<SongAction>` 纯函数 + `SongContextMenuArea(song, liked, isCurrent, onAction, content)` |
| `PlayerController.kt` | `fun playRadio(song: Song)`；PlaybackStatus 沿用 Opening |
| `ui/screens/HomeScreen.kt` / `SearchScreen.kt` / `DetailScreen.kt` / `LibraryPages.kt` / `ui/player/PlayerQueue.kt` | 歌曲行包 `SongContextMenuArea`；合集/艺人卡片行不包（v1） |
| `ui/player/NowPlayingScreen.kt` + `PlayerControls.kt` + `LandscapePlayer.kt` | `onOpenAlbum/onOpenArtist` 四处 `opensPage` 复活 |
| `ui/shell/PlayerPage.kt` + `Shell.kt` | 适配层补两参数 → `nav.open(Detail(...))` |
| `ui/screens/SettingsDialog.kt`（新）+ `Sidebar.kt` | 四组设置；底部齿轮行 |
| `data/settings/AppSettings.kt` | `themeSetting: StateFlow<ThemeSetting>`（SYSTEM/LIGHT/DARK）、`language: StateFlow<String?>`、落盘 |
| `ui/theme/BitChordTheme.kt` | `resolveDarkTheme` 纯函数；Main.kt 接线 |
| `desktop/Main.kt` | `applyWindowBackdrop(window, isDark)`；启动读语言 |
| `data/DebugLog.kt` | 环形缓冲 + `dump()` |
| `desktopStrings/{values,values-zh}/strings.xml` | 新键：菜单与设置文案；上游已有键直接复用（`play_next`/`add_to_queue`/`like`/`remove_from_liked`/`start_radio`/`open_album`/`open_artist`/`copy_log`） |

## 4. 关键技术点与风险

| 风险 | 对策 | 验证手段 |
|---|---|---|
| `ContextMenuArea` 与 `combinedClickable` 的单击/右键判定冲突 | ContextMenuArea 拦截平台右键，不走 Compose 手势系统；行 onClick 不变 | 实机：右键出菜单、单击仍播放 |
| ContextMenuArea 样式/焦点不合格 | 决策 1 回退：`DropdownMenu`（Popup）自绘 | 截图判读 |
| 电台解析耗时 | `Opening(status)` 沿用播放页/工具栏现有加载显示；请求期间行内不给二次反馈（同 playCollection） | 探针日志 |
| 深浅切换时 Mica 不同步 | 决策 8 双通道；JNA 写失败静默（runCatching 原样） | 浅色系统强制深色：窗框/chrome/内容三处一致 |
| 语言双源（设置项 vs 系统属性） | 唯一真相是设置项；系统属性只作启动覆盖（测试/CI），优先级低于设置项 | 启动日志 + 资源语言 |

## 5. 验证计划

1. **单元测试**：`songActions` 能力表（有无 albumId/artistId × 已赞/未赞 × 是否当前曲）；`resolveDarkTheme` 三态×系统两态判定表；AppSettings 新字段落盘往返；DebugLog 环形溢出取最近 N 行。
2. **行为断言**（探针）：右键"下一首播放"不打断当前曲（复用切片 2 队列断言）；"开始电台"入队且 queueIndex==0；播放页点专辑名 → 详情页且可后退。
3. **视觉判读**：菜单、设置页 × 深/浅 × 中/英。
4. **常量核对**：SongAction 枚举成员数、ThemeSetting 三态进测试。

## 6. 本切片不做

登录、下载、歌单增删（要登录）、分享、睡眠定时/歌词偏移菜单项（无功能背书）、设置页高级项（音频质量族——与下载/本地音乐同片）、语言热切换、⋮ 按钮入口、行内多选。
