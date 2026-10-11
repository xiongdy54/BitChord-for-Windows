# BitChord 桌面版 · 切片 19 设计:记忆(搜索历史、收听历史、备份、更新、语言热切换)

日期:2026-10-11
分支:`desktop-slice2-now-playing`(fork:xiongdy54/BitChord-for-Windows,上游 kushagrasinghx/BitChord)
状态:待用户复核

## 1. 背景

本片收六笔挂账,主题是"应用记住什么":**搜索历史不持久化**(`SearchViewModel.kt:176` 只存内存 20 条,关窗即失;原版 `SearchHistory` 是 `bitchord_settings` 里的 JSON 串,键 `search_history`,与备份同文件同命运)、**收听历史页**(原版 `HistoryScreen.kt` 93 行,`FEmusic_history` 整页,点一行以整表为队列播放;桌面 `YtMusicRepository.history()` 已移植却零调用方,侧栏没有去处)、**登录后主页"最近播放"货架**(`HomeViewModel.kt:35` 把 `recentlyPlayedLoading` 焊死为 false、从不请求——其 KDoc"nothing is signed in yet"已成陈旧谎言,切片 10 已有登录;仓库里 `homeRecentlyPlayed()` 与 HomeScreen 的 `RECENTS_TITLE` 骨架全部在场等线)、**数据导出/导入**(原版 `Backup.kt`:typed PrefValue 的 JSON,settings + 听歌统计,凭据除外;桌面无 ListeningStats,settings 一侧照搬)、**应用内更新检查**(原版 `AppUpdateChecker` 轮询 GitHub releases/latest 一次/启动,数值点分比较;桌面零代码)、**语言热切换**(切片 4 挂账至今:`Locale.setDefault` 在 `main()` 里只设一次,设置行写着"重启后生效";桌面 compose-resources 的 `getSystemEnvironment()` 每次调用读 `Locale.getDefault()`——`key(localeTag)` 重建子树即热生效,不必等任何库)。

## 2. 已确认的决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **SearchHistory 独立对象,持久化与上游同键同形**:JSON `List<SearchHistoryEntity>` 存进 settings.properties 的 `search_history` 键,经 **AppSettings 自己的 FileStore 实例**(`internal val store` 访问器)——两个 FileStore 同文件互相清场的陷阱(Main.kt:546 的注释)就此绕开;20 条上限、id 忽略大小写去重、remove/clear/reload 语义逐字对齐 | 上游搜索历史与 AppSettings 同文件,所以 Android 备份自带搜索历史;桌面同键同形,两平台的备份互相可读 |
| 2 | **SearchViewModel 换源 + 补 submit 记录**:`_history` 换成 `SearchHistory.recent`;`submit()` 补上游 `recordSearch()` 的偏好链(typeahead 命中轨 → 结果首轨 → 原文 `q:` 兜底);SearchPage 的实体点按记录照旧 | 桌面此前只记点按不记查询,是上游语义的一半 |
| 3 | **History 页 = 侧栏目的地**:NavState 加 `History`;Sidebar 全高行 + rail 行(浏览之后);Shell 路由分支;`HistoryPage` 每次进入重取(上游语义,无 loaded 闩)、登出态显示登录横幅、点一行 `playFrom(songs, index, HISTORY)` 以整表为队列 | `YtMusicRepository.history()` 与 `PlaybackSourceType.HISTORY` 都是现成件;上游页就是一张 SongRow 表 |
| 4 | **主页最近播放 = 登录时抢先发布**:`HomeViewModel` 挂 `var signedIn: () -> Boolean`;loadHome/refresh 里签名态先取 `homeRecentlyPlayed()` 并以 `prepend=true` 发布(仓库自建的货架标题恒为 "Recents",seenTitles 去重会顶掉核心流里 YouTube 自己的同名货架——上游 :1476 语义的自然落法);`recentlyPlayedLoading` 转真,HomeScreen 的骨架分支随之复活 | Main.kt 的 `accounts.onSessionChanged → home.refresh()` 已在场,只差货架本身 |
| 5 | **Backup 桌面形**:同 `BackupFile`/`PrefValue` 字段名(跨平台互读),`listening: []`(桌面无 ListeningStats);导出 = AppSettings 原始键值(FileStore 加 `snapshot()` 只读面)滤除 SECRETS(`paxsenix_api_key`)与 DEVICE_LOCAL(`output_device_id/name`、`local_music_folders`);导入 = 已知键的 setter 链(显式 ~30 键,未知键跳过)+ `SearchHistory.reload()`;文件选择用 JFileChooser(本地音乐先例),About 组两行 | 上游的 typed-value 理由(JSON 数字回到 Kotlin 类型的歧义)原样成立;setter 链让每个 collector(EQ、旋钮)自然跟上,不新增 store 写面 |
| 6 | **更新检查 = fork 的 releases/latest + 打开发布页**:URL 指 `xiongdy54/BitChord-for-Windows`(上游 releases 是 Android APK,桌面 0.1.0-desktop 对它恒显过期);`check()` 一次/启动(静默失败)+ About 组"检查更新"行;可用时桌面形对话框(标题、notes、`java.awt.Desktop.browse(releaseUrl)`)——APK 下载/静默安装的那一半是平台语义,桌面没有对应物 | 数值点分比较 `isNewer`/`parseVersion` 逐字移植;`.msi/.exe` 资产检查保留在 UpdateInfo 里备用,动作先只做浏览 |
| 7 | **语言热切换 = `key(localeTag)` 重建 Shell 子树**:`shellState`/`nav` 提升到 key 之外(记住的状态不丢);`Locale.setDefault` 在 LaunchedEffect 里跟随;语言组的"重启生效"注记删除(translucent 窗口仍保留);Innertube 的 `currentLanguage` 本就是逐调用 getter,自动跟上 | 桌面环境每次调用读 `Locale.getDefault()`(已对着 1.12.1 的字节码核实),重建子树即全部字符串重解析;导航与播放状态在提升的持有者里,热切换不炸会话 |
| 8 | **探针**:`bitchord.probeLocaleSwitch=<tag>`(启动 5 秒后写 `AppSettings.setLanguage`)——热切换的自动化证据:启动 en、5 秒后切 zh、截图应为中文界面,重启验证不参与 | 运行时切换没有鼠标点不了设置行;probe 是本仓的既有验法 |
| 9 | **不做**:ListeningStats/Replay 页(备份的 `listening` 恒空,导入 Android 听歌月度数据暂无处安放——收到即忽略,记为已知边界);更新包下载与静默安装(决策 6);账号级搜索历史(上游同拒) | 宁少给;每一项留了接缝 |

## 3. 交付物

| 文件 | 内容 |
|---|---|
| `data/settings/SearchHistory.kt`(新) | 对象 + 纯函数(merge/cap/dedupe 拆出可测);`AppSettings.internal val store` |
| `ui/SearchViewModel.kt` | history 换源;submit 记录链 |
| `ui/shell/NavState.kt`/`Sidebar.kt`/`Shell.kt` | History 目的地、行、路由 |
| `ui/screens/HistoryPage.kt`(新) | 每次进入重取、登录横幅、SongRow 表 |
| `ui/LibraryViewModel.kt` | `history` 状态 + `refreshHistory()` |
| `ui/HomeViewModel.kt` + `desktop/Main.kt` | signedIn 钩子、prepend 发布、loading 真值 |
| `data/backup/Backup.kt`(新) | BackupFile/PrefValue、exportTo/importFrom、setter 链 |
| `data/settings/AppSettings.kt` | `store` 访问器、`exportPrefs()`、SECRETS/DEVICE_LOCAL |
| `data/FileStore(DesktopStore.kt)` | `snapshot(): Map<String,String>` 只读面 |
| `data/AppUpdateChecker.kt`(新) | check/parseVersion/isNewer、fork URL、available 流 |
| `ui/screens/SettingsDialog.kt` | About 组:导出/导入/检查更新行 + 更新可用对话框 |
| `desktop/Main.kt` + `build.gradle.kts` | 启动检查、`probeLocaleSwitch`、`key(localeTag)`、hoist |
| 测试 | SearchHistory merge 表、Backup 过滤/验证表、isNewer 数值表(1.10>1.9、beta 规则、-desktop 后缀)、HomeViewModel prepend 去重表 |

## 4. 关键技术点与风险

| 风险 | 对策 | 验证手段 |
|---|---|---|
| `key()` 重建后 remember 的会话状态丢失 | shellState/nav 提升至 key 外;ViewModel/PlayerController 本就活在组合外 | probeLocaleSwitch 截图:切换后侧栏选中态、播放中状态不丢 |
| `stringResource` 是否吃运行时 Locale | 已核 `ResourceEnvironment_desktopKt.getSystemEnvironment()` 逐调用读 `Locale.getDefault()`;若某调用点 remember 了环境,重建子树也会重算 | probeLocaleSwitch 截图 |
| 导入把两台机器的设置搅在一起 | 上游"replace, not merge"照搬:已知键全量覆盖、未知键跳过、设备本地键保留;验证 app 标签与 schema 后才落笔 | 单测 |
| JFileChooser 阻塞 EDT | 与本地音乐选夹同一先例(SettingsDialog:715 已如此);导出/导入本体在 Dispatchers.IO | 代码审读 |
| Home 货架去重把核心流的"Recents"误伤 | 上游语义即"替换 YouTube 自己的陈旧货架";seenTitles 以标题为准,prepend 先落座 | 单测:prepend 后同题货架只留一份 |
| GitHub 无 releases(404) | check() 静默吞掉,行上无态 | 手动:fork 暂无 release 时行点击无异常 |

## 5. 验证计划

1. **单元测试**:§3 测试行,全套保持全绿。
2. **行为断言**:`probeLocaleSwitch=zh` 从 en 启动 → 5 秒后截图为中文(热切换证据);登录态主页截图出现"最近播放"货架;History 页截图;设置 About 组截图(导出/导入/检查更新)。
3. **备份往返**:导出 → 改一设置 → 导入 → 设置回来(人手)。
4. **常量核对**:20 条上限、`q:` 前缀、`bitchord` app 标签、schema 1。

## 6. 本切片不做

ListeningStats/Replay(决策 9);更新包下载安装(决策 6);Canvas 登录与 SlidingPlayerDeck、OriginalVersion(切片 21);下载合集批量/暂停恢复/目录选择(切片 20)。
