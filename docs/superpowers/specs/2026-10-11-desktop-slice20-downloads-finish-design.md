# BitChord 桌面版 · 切片 20 设计:下载的收尾(整张下载、暂停恢复、目录选择)

日期:2026-10-11
分支:`desktop-slice2-now-playing`
状态:待用户复核

## 1. 背景

切片 12 顺延的三件下载事,加上切片 16 盘点的"固定下到 AppFiles/downloads":**整张专辑/歌单批量下载**(上游的 `requested` 流 + `BrowseActionsSheet` 的"Download all"行——`Downloads.kt:98-104` 的逐发布问句"queued because I asked?"桌面没有答案)、**暂停/恢复**(桌面只有 enqueue/cancel,`DownloadSession` 四态里没有 Paused;.part 文件在取消时被 abort 删除)、**下载目录选择**(`DownloadStore.folder = AppFiles.dir("downloads")` 写死,`DownloadStore.kt:40`)。上游 DetailScreen 本身没有下载按钮(它在长按 sheet 里);桌面的对应表面是 DetailHeader 的药丸排——播放/随机播放之后第三颗。

## 2. 已确认的决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **`requested` 流逐字移植**(`markRequested`/`requested`,browseId → 请求过的 videoIds 集合,永不清剪);**下载入口 = DetailHeader 第三颗药丸**,状态从 `requested`/`active`/`saved` 推:全部已存 → "已下载"态;任何一批在途/排队 → "下载中…"态;否则 "全部下载"可点,点击 = `markRequested(browseId, ids)` + 逐曲 `enqueue(song, from=browseId)` | 上游按钮的 value 三态语义(manager 状态行的 per-release 问句);桌面无长按 sheet,头部药丸是既有的动作位 |
| 2 | **暂停 = 保住 .part 的取消**:`DownloadState.Paused(fraction)`/`DownloadProgress.Paused(fraction)` 新态;`Downloads.pause(videoId)` 取消在途 job 但**不 abort part**(transfer 的取消路径按"是否被暂停"分支)、queued 行直接摘出;paused 记录保留 Song/from/part 名/扩展名/档位名;`resume(videoId)` 重新入队,**扩展名与档位名都匹配时从 part 长度续传**(`Downloader.fetch`/`fetchFromSource` 加 startOffset——两条路径本就是 Range 分块),不匹配(中途改档)从零 | 逐块 Range 抓取天然支持偏移;暂停大无损队列后从零重跑是真浪费;档位变了续传会拼坏文件,守卫是正确性的价格 |
| 3 | **`DownloadSession.Paused` 不算 settled 也不算 busy**:批未完(指标不诈完),但队列"停着"不等于"在跑"(指示器不该永远转);`State.paused` 计数;fraction 按 paused 的部分进度计 | visible 的"未被交代"语义保持:全暂停的批静默下架,管理面板里行还在 |
| 4 | **面板行动作三态**:运行/排队 → 暂停(Pause 图标);Paused → 恢复(Resume 图标);失败 → 重试;完成 → 无。行状态行加"已暂停"(桌面自拟键) | 上游无暂停先例,桌面自拟但贴既有行形态 |
| 5 | **目录选择 = 设置键 + 动态 folder**:`AppSettings.downloadFolder`(键 `download_folder`,null=默认 AppFiles/downloads,桌面自拟键——上游无此概念);`DownloadStore.folder` 从 lazy val 改为读设置的函数;记录存绝对路径,旧文件照常可播;设置对话框下载音质组加"下载位置"行(现行路径)+ 自定义时"重置为默认" | 路径记录不受影响;下载位置是桌面才有的自由度 |
| 6 | **不做**:单曲右键菜单的暂停/恢复(队列动作在管理面板,入口一处);下载速度限制;下载完的系统通知(切片 6 已判) | 宁少给 |

## 3. 交付物

| 文件 | 内容 |
|---|---|
| `download/Downloads.kt` | `requested`/`markRequested`;`Paused` 态;`pause`/`resume`;pausedMap(part 元数据);transfer/run 的暂停保留路径;Downloader/fetchFromSource 的 startOffset |
| `download/DownloadStore.kt` | `folder()` 动态化;`begin(resume)` 不删 part;`partLength(name)` |
| `download/DownloadSession.kt` | `DownloadProgress.Paused`;`paused()`;waiting/busy/fraction 语义 |
| `ui/screens/DetailScreen.kt` + `Shell.kt` + `Main.kt` | 头部第三药丸(状态推导)+ `onDownloadAll` 接线 |
| `ui/components/DownloadManagerPanel.kt` | 行三态动作 + 已暂停状态行 + paused 汇总段(静默计数) |
| `data/settings/AppSettings.kt` | `downloadFolder` 键与 setter |
| `ui/screens/SettingsDialog.kt` | 下载位置行 + 重置 |
| `desktopStrings` | `download_paused`/`download_location`/`download_location_reset`(en/zh) |
| 测试 | requested 标记;Paused 的 waiting/busy/fraction;暂停保留 part 的状态机(fake 路由);续传偏移守卫;DownloadManagerSummaryTest 补 paused 行 |

## 4. 关键技术点与风险

| 风险 | 对策 | 验证手段 |
|---|---|---|
| 暂停的取消路径与普通取消混线 | `pause()` 先登记 pausedMap 再 cancel job;transfer 的 CancellationException 分支查 pausedMap 决定 abort 还是保留 | 单测(fake 路由驱动) |
| 续传拼坏文件(档位/格式变了) | 扩展名 + 档位名双匹配才续;不匹配从零 | 单测:守卫表 |
| workers 在全暂停后退出,resume 要重新拉起 | `resume()` 走 `ensureDraining()`(enqueue 同款) | 单测 |
| 目录改变后旧记录路径失效 | 记录存绝对路径;`savedUri` 验存在 | 既有行为,代码审读 |
| 头部药丸状态推导在三流上重复计算 | `collectAsState` 三流 + 派生纯函数(可测) | 单测 |

## 5. 验证计划

1. 单元测试:§3 测试行,全套全绿。
2. 行为断言:探针/人手——专辑页点"全部下载"→ 管理面板出现整批(from 显示专辑名);中途暂停 → 行态"已暂停"、part 在盘;恢复 → 进度从断点继续(日志 `resuming at N`);切目录 → 新文件落新目录。
3. 视觉判读(`.shots/slice20/`):详情页头部三药丸(可下载态);管理面板含暂停行与恢复行。
4. 常量核对:part 名 `.$name.part` 不变;paused 记录字段;`download_folder` 键名。

## 6. 本切片不做

单曲菜单的暂停/恢复;速度限制;通知;下载完成后自动导入系统媒体库(桌面无此概念);Canvas/SlidingPlayerDeck/OriginalVersion(切片 21)。

## 7. 实施记录(2026-10-11 回填)

**交付**:决策 1–5 全部落地。`Downloads`:`requested`/`markRequested`(逐发布累积,永不清剪);`DownloadState.Paused(fraction)` + `PausedTrack`(song/from)+ `PartIdentity`(part 名/扩展名/档位/来源描述)+ `liveParts`/`resumingParts` 簿记;`pause()`(先登记后取消——transfer 的取消路径据此分辨"保字节"与"放弃";queued 行直接摘出)、`resume()`(重新入队 + 种下续传意图 + `ensureDraining`);`clear()` 连带清 paused 簿记;`run()` 档位读一次下传 `prepare(song, quality)`/`transfer(song, plan, quality)`(守卫与路由必须同档);`transfer()` 的 `resumeOffset` 守卫抽成纯函数 `resumeOffsetIfSameRendition`(扩展名+档位+来源描述三比对)、`DownloadStore.begin(keepPart)` 不删 part、`openStream(append)` 用 `FileOutputStream(part, append)`、`partLength()`;`Route.write` 加 offset 参数,`Downloader.fetch(startOffset)` 与 `fetchFromSource(startOffset)` 从偏移续 Range;暂停取消不 abort part。`DownloadSession.Paused`:`waiting` 排除 paused、新增 `paused` 计数、`busy` 不含、`fraction` 按部分进度计。`DownloadStore.folder()` 动态读 `AppSettings.downloadFolder`(键 `download_folder`,桌面自拟,入 DEVICE_LOCAL;坏路径回退默认目录);`FileStore.snapshot()` 不变。UI:DetailHeader 第三药丸(`collectionDownloadLabel`/`releaseDownloadState` 纯推导 + 资源映射,Shell 的 `onDownloadAll` = markRequested + 逐曲 enqueue(from=browseId));管理面板行双动作(运行/排队 → 暂停+取消,暂停 → 恢复+取消,失败 → 重试)+ "已暂停"状态行;设置对话框下载音质组加"下载位置"行(现行路径 + 选择器 + 重置)。desktopStrings en/zh 各四键(download_paused/resume/download_location/download_location_reset)+ 两枚药丸态键(download_all_moving/download_all_done)。

**与设计的偏离**:

1. **守卫字段从两个加到三个**(`describe` 来源比对):同扩展同档位但来源不同(源路由顶替 YouTube)的续传会拼坏文件——设计 §2 决策 2 说"扩展名与档位名都匹配时续传",实施时意识到这不够,Route 的 describe(kbps+mime 或来源摘要)入守卫,纯函数单测钉死该例。
2. **药丸状态推导未用 `requested`**:三流签名里 requested 可有可无(在途与否由 `active` 回答,已落盘由 `saved` 回答),记为简化;`requested` 的真实消费方是未来的逐发布问句(上游 BrowseActionsSheet 的 value 推导),标记照常入账。
3. **运行/排队行从单动作变双动作**(暂停+取消):设计说"运行/排队 → 暂停",实施时保留取消——上游行上有取消,一次升级不该把出路收走。

**验证**:62 类 600 测试全绿(新增 5:续传守卫两表(匹配续传/四路漂移重跑)、releaseDownloadState 三态表、markRequested 累积、Paused 在 Session 计数里的位置)。**行为断言(实机,真实批次)**:`probePlayVideoId` 真轨解析(InnerTubeX WEB_REMIX@179kbps)+ 入队 → 管理面板截图:批次条 + 运行行"正在开始" + 行尾**暂停/取消双动作** + 工具栏下载指示器;专辑页截图:头部三药丸(播放/随机播放/全部下载)。**人手验证待做**:点暂停后 .part 在盘、恢复后日志 `resuming N B`、切目录后新文件落新目录——三件都需要人手点探针没有的鼠标。

**已知瑕疵**:解析的 10s 冷启动让 6s 窗口的截图全扑空(两次重跑才抓到 14s 窗口);暂停行的恢复按钮没有截图证据(无鼠标,单测与代码审读代证);`resume()` 对queued 暂停行无 part 可续(设计预期内:入队即暂停的行从零开始)。
