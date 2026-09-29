# BitChord 桌面版 · 切片 1 设计：视觉基座 + 主页 + 搜索

日期：2026-09-29
分支：`windows-desktop`（fork：xiongdy54/BitChord-for-Windows，上游 kushagrasinghx/BitChord）
状态：待用户复核

## 1. 背景

桌面版已存在的部分（均已实测跑通，见 `desktop/`）：InnerTube 数据层、流解析（InnerTubeX 无 PoToken → NewPipe 兜底）、libvlc 播放、一个极简搜索+播放条界面。

本切片的目标是把原版安卓应用的**视觉地基**（字体、材质、磨砂、封面取色、Mesh 渐变）、**主页**、**搜索页**搬到桌面，最大程度还原观感与交互。后续切片：2 Now Playing + 队列，3 歌词，5 桌面浮窗 + Dock，4 库/设置，6 长尾。

## 2. 已确认的决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **方案一：复制-适配到 `desktop/`**，`app/` 零改动 | 本机无 Android SDK，改 `app/` 无法验证安卓构建是否被破坏；该模式已在数据层验证 |
| 2 | **版式 C**：内容列居中 1080dp（窗口 <1080dp 时等于原版全宽），保留底部浮动 tab | 兼顾编排感与空间利用；浮动条本身上限 440dp，两侧自动居中 |
| 3 | **玻璃效果分期**：本切片交付**非玻璃回退路径**（原版 API<31 的真实代码路径） | `backdrop/` 包 + `LiquidGlass` 依赖 `android.graphics.RenderEffect` 与 AGSL 着色器，是唯一必须重写渲染管线的部分 → 切片 1.5 |
| 4 | **字符串**：CMP `compose.resources` + `customDirectory`，从原版 `strings.xml` **构建期拷贝**（不提交第二份） | 原版界面 100% 走 `stringResource`（HomeScreen 39 处、零硬编码），XML 仅用 string/plurals/string-array 三种标签，均在 CMP 支持范围内 |
| 5 | **字体**：desktop 资源源集指向 `app/src/main/res/font` | 避免在仓库重复存 5 个 SF Pro 字重共 11MB |
| 6 | **深浅色**：跟随系统（原版默认行为） | 手动切换在原版属于设置页，随切片 4 一起接 |

## 3. 交付物

包名与目录结构与 `app/` 保持一致（便于日后与上游对照搬运）；`app/` 下文件只读、不改。

**视觉地基**
- `data/.../theme/Theme.kt`：BitChordTheme（深浅配色 + SF Pro 全覆盖字型表），删除 `SystemBarIcons`/`LocalView` 等 Android 部分
- `ui/theme/ArtworkPalette.kt`：保留全部取色/配色/动画逻辑，替换三处实现——像素读取（Coil3 → Skia）、中位切分量化器（自写，替 `androidx.palette`）、HSL/亮度换算（自写，替 `androidx.core.graphics.ColorUtils`）
- `ui/player/MeshGradient.kt`：Compose Canvas 径向渐变 + 相位动画（可直接搬，仅取色部分换实现）
- `ui/components/ArtworkBackdrop.kt`、`OptimizedHaze.kt`：走 Haze 库（桌面可用），去掉 `SDK_INT` 判断

**外壳**
- 桌面版 MainActivity 等价物：`Window` + 4 tab 状态机（HOME/EXPLORE/LIBRARY/SEARCH）+ `AnimatedContent` 路由
- `FrostedTopBar`、`FloatingBottomBar`、`MiniPlayer`（非玻璃路径）；`ui/icons/BitChordIcons.kt` 直接搬（纯 `ImageVector`，无平台依赖）
- `haptics` → 桌面 no-op；`statusBarsPadding`/`navigationBarsPadding` → 0；IME `keyboardController` → no-op

**页面**
- 主页：`HomeScreen`（大标题、SignInBanner、Recents、Hero/Shelf 货架、骨架屏、错误重试、触底分页）
- 搜索：`SearchScreen`（筛选标签、typeahead 建议、历史、TopResult、分区结果、分页）
- 状态层：`HomeViewModel` / `SearchViewModel` —— 从原版 `MainViewModel`（2861 行，混有 Media3 会话/通知/下载等 Android 依赖）**按屏幕增量重建**，只取本切片所需
- 占位页：Explore / Library / 详情 / 设置 → `NotPortedPlaceholder`（明确标注"尚未移植"，不是半成品）
- MiniPlayer 的点击在全屏播放页移植前**保持不响应**（切片 2 接入 `NowPlayingScreen`）；tab 切换、点歌播放、搜索保持可用

## 4. 关键技术点与风险

| 风险 | 对策 | 验证手段 |
|---|---|---|
| 取色量化器 | 自写中位切分（~150 行，产出按 population 的色块 + 饱和度×√population 的 accent 评分，与原算法同构） | 同一张封面在桌面与安卓取值对照（数值级） |
| CMP 解析原版 strings.xml | 不直接指向 `app/src/main/res`（该目录含 `layout/`、`mipmap-*`、`values-v31/` 等 CMP 不认的类型，会解析失败）。改为 Gradle `Copy` 任务把 `values*/strings.xml` 复制到 `desktop/build/generated/bcComposeResources/`，`customDirectory` 指向它；首版只复制 en+zh 控制变量。**若任务依赖无法稳定挂上资源生成任务，退回把 en+zh 的 strings.xml 直接提交进 `desktop/src/main/composeResources/`** | 编译期：CMP 资源任务成功生成访问器；运行期：中文界面截图 |
| Haze 1.3.1 桌面行为 | 保持 `inputScale`/`hazeSource` 用法不变，若 Skia 下不等价则退回纯色 scrim | 截图对比：顶栏/播放条的模糊可见性 |
| Coil3 桌面取像素 | `ImageLoader.execute` + Skia bitmap 取像素 | 取色结果非空且随封面变化 |
| `LocalConfiguration`/insets 等 Android-only | 桌面用窗口尺寸替代；逐文件清理 `import android.*` | 编译期无 Android 符号 |
| 上游分叉 | 每个搬运文件头部注明来源；正文尽量少改（改动点集中在少量替换处） | code review |

## 5. 验证计划

1. **行为断言**（自动化）：tab 切换、搜索建议与 typeahead、点歌出声（已有播放链路）、货架分页、错误重试路径
2. **视觉判读**（截图）：1080 / 1440 / 1920 三种窗宽 × 100% / 150% DPI；中英双语各一套
3. **几何量核对**（对照原版源码常量）：页面 gutter 10dp、货架卡 150dp、hero 卡 ≤320dp、浮动条 ≤440dp、内容列 1080dp
4. **反向验证**：占位页确实显示"尚未移植"，不会让人误以为是成品

## 6. 本切片不做

真玻璃渲染管线（`backdrop/` + `LiquidGlass` → 切片 1.5）；Now Playing 全屏与队列（切片 2）；歌词（切片 3）；浮窗与 Dock（切片 5）；设置/库/详情/历史/下载/账号/Scrobbling（切片 4、6）；en/zh 之外的 13 种语言（随切片 4 的设置页一起接）；Replay/Equalizer 等附属页。

## 7. 实施记录（2026-09-29，实施中回填）

### 与设计的三处偏离

1. **字符串资源目录**：设计写的是 `compose.resources { customDirectory(...) }`。实施发现该版本插件的 `PrepareComposeResourcesTask` **不读这个配置**（仍找 `preparedResources/main/composeResources`，由 `src/main/composeResources` 推导）。改为把 Gradle Copy 的产物放进插件默认目录 `desktop/src/main/composeResources/`，并加进 `.gitignore` —— 仍然只有一份字符串真相（`app/src/main/res`）。
2. **insets / IME 替身不需要**：设计里准备写 `statusBarsPaddingDesktop()` 等替身；实测 Compose Desktop 1.12.1 **自带** `statusBarsPadding`/`navigationBarsPadding`（`WindowInsets_notMobileKt`）与 `SoftwareKeyboardController`，搬运文件可原样保留这些调用。
3. **主页卡片点击**：实施后发现未登录主页的**每一张卡都是合集**（10 张卡、10 个 browseId、0 个 videoId，探针实测），而合集本该打开详情页（切片 4），于是点击成了死路（用户实测反馈"点了没反应"）。按详情页自身的播放行为补上：`browseSongs(browseId)` 取曲目并播第一首；取回需要一次往返，期间在 tab 栏上方显示 "opening …"。

### 验证结果

- **单元测试 14 个全绿**：字体/字型表 3、取色管线 8（HSL 往返、WCAG 亮度、量化器）、版式常量 3（`CONTENT_MAX_WIDTH=1080`、`FLOATING_BAR_MAX_WIDTH=440.dp`、`SHELF_CARD_WIDTH=150.dp`、`PAGE_GUTTER=10.dp`、hero ≤320dp）。
- **行为断言**：主页货架真实加载（截图）；点合集卡 → 取合集 → 解析 → 播放（日志 `browse:…ok` + `total resolve: 6.4s` + 截图里迷你播放器的进度在走）；搜索提交 → 结果分区渲染 → 首行歌曲播放（日志 + 截图）。
- **双语**：中文截图（立即收听/首页/探索/资料库/搜索）与英文截图（Listen Now/Home/Explore/Library/Search）各一张，来自同一份 `strings.xml`。
- **多尺寸**：1180×780 与 1550×974（屏幕上限）各一张；宽窗下内容列明显居中、左右留白，未铺满窗口（精确的 1080dp 由单元测试断言，不用缩放过的截图当量具）。
- **两处已知行为差异**（非缺陷）：这台机器的 Windows 是浅色主题，应用跟随系统画浅色（与原版一致；强制深色属切片 4 设置页）；窗口标题栏仍是系统默认样式。

