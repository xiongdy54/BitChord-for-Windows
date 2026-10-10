# BitChord 桌面版 · 切片 12 设计:下载管理面板 + 工具栏下载钮

日期:2026-10-11
分支:`desktop-slice2-now-playing`
状态:设计定稿(实施记录见 §7,实施后回填)

## 1. 背景

切片 6 决策 10 原文:"不做 DownloadManagerSheet 与工具栏下载钮(`DownloadSession` 全量移植,其 `visible` 流照常驱动将来那条)……管理表是批量大队列的人机工程,等实测反馈再定"。本片回收那一票。

盘点结论(逐文件核实):**状态层桌面已 100% 就绪,本片几乎纯 UI 移植**。`DownloadSession.kt`(249 行)切片 6 已逐字移植(diff 仅一行注释)且 `Downloads.kt` 九处接线(queued/running/done/failed/retitle/forget);`state: StateFlow<State>` 的 `visible/busy/fraction/waiting/finished/failed` 今天就在桌面上流动,只是无人消费。上游面是一个文件两件套:`DownloadManagerSheet.kt`(474 行)= `TopBarDownloadButton`(顶栏指示器,`AnimatedVisibility(visible = session.visible)`,忙碌画 26dp 进度环、失败 ErrorOutline、空闲 DownloadDone)+ `DownloadManagerSheet`(标题 + `State.summary()` 计数汇总 + 批级进度条 + `LazyColumn` 按 sequence 排序、380dp 高度帽,行 = 封面(settled 盖图标罩)+ ExplicitSongTitle + 状态行(queued / 细进度条+%d / saved_to_bitchord_folder / Failed.reason 原文)+ 行尾动作(失败重试 / 未定取消))+ `State.summary()` 扩展(三个 plurals " · " 连接)。宿主:`MainActivity:3185` 钮在账号钮左侧,`:3689` `ModalBottomSheet` 宿主,**关闭时 `markSeen()`**(签收的是"结果",不是进入)。字符串全家(`downloads_progress`/plurals 三枚/`nothing_downloading`/`retry`/`cancel_all`/`clear` 等)已随合并拷贝进场——**零新增**。

桌面现状:`DownloadsPage` 只显示已完成;Toolbar(465 行)无下载钮位(纯 junction,加钮 = 加参数 + 音量后槽位);Shell 宿主惯例(`ShellState` 布尔 + 顶层 Dialog 块)在切片 10 登录窗、切片 4 设置对话框已成范式。

## 2. 已确认的决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **`DownloadManagerSheet.kt` 近逐字移植**(组件三件:TopBarDownloadButton 的**按钮体**、Sheet、Row、summary 扩展;`AnimatedVisibility` 语义保留) | 474 行里 Android 特有项只有 import 层(stringResource/collectAsStateWithLifecycle/Haptic 参数)与 ModalBottomSheet 宿主层;状态机、plurals、图标、布局全部原样 |
| 2 | **宿主 = 窗口级 `Dialog`**(居中,~420dp 宽,照设置对话框的 Surface 形);`ShellState.showDownloadsManager` + Shell 顶层宿主块(照 SleepTimerDialog 形);**关闭回调里 `DownloadSession.markSeen()`**(上游 KDoc 的语义原样:签收发生在退出时) | 上游 ModalBottomSheet 是"手机的全局面板",桌面的对应物是切片 4 确立的窗口级对话框;380dp 列表帽原样保留,Dialog 高度自适应 |
| 3 | **工具栏钮落点 = 音量控件之后**(toolbar 右段末端、窗口三钮 overlay 之内侧);`Toolbar` 新参 `onOpenDownloadsManager`;按钮自身仍是 `AnimatedVisibility(visible = session.visible)`——**它自己决定要不要出现**,Shell 只递流 | 上游"self-deciding"的 KDoc 语义原样;音量之后是 toolbar 右段的自然空位,窗口三钮是独立 overlay 不参与排布;toolbar 是纯 junction,值进回调出(现有 KDoc 的规矩) |
| 4 | **`requested` 流与整张/歌单批量下载不入本片**(切片 6 偏离 1 明确"随 BrowseActionsSheet 一起回",与上游解耦) | 上游设计上管理面板与合集下载是两件事;本片已是回收票,不再夹带 |
| 5 | **探针**:`probeDestination=downloadsmanager` 打开管理面板;工具栏指示器可见性由 `probeDownload=true` 的真实批次驱动(入队一轨,`visible` 流真实流转) | 既有探针管线的自然延伸;指示器是自决组件,不需要假状态 |
| 6 | **不做**:音质标签列(上游面板本就不显示,音质在设置);暂停/恢复单行(上游无此状态——只有取消);通知/前台服务替代物(切片 6 已判:桌面 worker 排空 + 页内/指示器反馈);`local:playlist:` 下载集入口 | 宁少给,不给上游没有的功能 |

## 3. 交付物

| 文件 | 内容 |
|---|---|
| `ui/components/DownloadManagerPanel.kt`(新) | 三件套移植:指示器钮体(进度环/失败/完成三态 + plurals 文案)、面板(汇总行 + 批级进度 + 行列列表 + Cancel all/Clear)、行(四态状态行 + 行尾动作) |
| `ui/shell/Shell.kt` | `ShellState.showDownloadsManager`;宿主块(关闭即 markSeen);Toolbar 新参 |
| `ui/shell/Toolbar.kt` | `onOpenDownloadsManager` 参数 + 音量后槽位 |
| `desktop/Main.kt` | 接线;`probeDestination=downloadsmanager` |
| `build.gradle.kts` | (如需)新探针键登记 |
| 测试 | `State.summary()` 计数表(plurals 的参数组合)、`visible` 流的 sign-in/sign-out 时序(既有 `DownloadSessionTest` 补行) |

## 4. 关键技术点与风险

| 风险 | 对策 | 验证手段 |
|---|---|---|
| `AnimatedVisibility` 在桌面 toolbar 的布局跳动(出现/消失推挤音量) | 上游同形(顶栏也推挤);若实机判读不适,改 Box 叠放钉右——先移植原形,截图判读说了算 | 截图:批次运行中/空闲两态 |
| Dialog 与 toolbar 指示器的状态同源(session.visible) | 同一条 `DownloadSession.state` 流,两处各自 collect——无第三状态可漂移 | 代码审读 |
| plurals 在桌面资源管线的可用性 | `pluralStringResource` 已有桌面先例(SleepTimerDialog);三枚 plurals 随合并拷贝在场 | 编译 + 面板汇总行截图 |
| 面板列表 380dp 帽在小窗口溢出 | Dialog 自身可滚动(Column verticalScroll,照设置对话框) | 小窗口截图 |

## 5. 验证计划

1. **单元测试**:§3 测试行,既有 48 类全绿。
2. **行为断言(实机)**:`probeDownload=true` 跑一轨 → 工具栏指示器出现(进度环)→ 完成后转 DownloadDone → 打开面板 → 关闭后指示器消失(markSeen);失败态经坏 videoId 驱动。
3. **视觉判读**:`.shots/slice12/`——工具栏指示器(运行中)、管理面板(批次进行中/全完成两态)zh-dark + en-light 取代表。
4. **常量核对**:进度环 26dp、列表帽 380dp、" · " 汇总连接符、行高 44dp 封面。

## 6. 本切片不做

`requested` 流与整张/歌单批量下载(独立小片);暂停/恢复;通知;`local:playlist:` 下载集;运动封面、交叉淡入淡出、Equalizer(Goal 后件);本地音乐/登录(已在切片 10/11 收讫)。

## 7. 实施记录(实施后回填)

(待回填:交付、偏离、验证、已知瑕疵)
