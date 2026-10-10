# BitChord 桌面版 · 切片 11 设计:本地音乐扫描 + WebDAV

日期:2026-10-11
分支:`desktop-slice2-now-playing`
状态:设计定稿(实施记录见 §7,实施后回填)

## 1. 背景

切片 9 的"本切片不做"把它列为"独立切片"之首:"本地音乐扫描与 `LocalMusicScreen` 全家(含 WebDAV)"。这是剩余欠账里**最大的一族**——它是"资料库"一词在桌面第一次对不登录的用户也成立的东西。

上游盘点(逐文件核实,行数为实际行数):

- **扫描**:`data/LocalMediaRepository.kt`(426 行,object 单例,**无后台设施**——无前台服务/Worker/Room,进页面即扫 MediaStore,结果零持久化;只有排序/视图/文件夹 URI 落盘)。两级过滤:`IS_MUSIC` + 可选 `filterNonMusicAudio`(≥30s、非闹钟/通知/铃声/播客、扩展名白名单 mp3/m4a/flac/ogg/opus/aac/webm、路径黑名单 `/alarms/` 等);`getDownloadedSongs()` 兼收下载目录里的无记录文件(MediaMetadataRetriever 读元数据)。本地歌曲的 `videoId` **就是** URI 串(`videoIdByUri[contentUri] ?: contentUri`,L283;无记录手放文件 `videoId = uriStr`,L408)。
- **WebDAV**:`data/webdav/` 四件——`WebDavClient.kt`(362 行,**手写** OkHttp PROPFIND depth 0/1 + DOM multistatus + Basic 认证,BFS 递归列库、testConnection、putFile/ensureCollection,避 Nextcloud 的 Depth:infinity 拒绝)、`WebDavConfig.kt`(URL 归一化、`webdav:<fileUrl>` 编址、`BROWSE_ID="local:webdav"`)、`WebDavRepository.kt`(listing→Song 行,专辑=父文件夹名,标题来自文件名 "Artist - Title" 切分,封面=同目录图片)、`WebDavCoilAuth.kt`(Coil3 Interceptor 补 Authorization)。**纯 JVM,零新依赖**(上游无 Sardine/dav4jvm;桌面已有 OkHttp)。
- **远程库共享底座**(`data/remote/`,WebDAV/SMB 共用):`RemoteSong.kt`(44,文件名→Song)、`RemoteListing.kt`(18)、`RemoteArtwork.kt`(38,同目录图片词干优先)、`EmbeddedArt.kt`(**470 行,纯 JVM ranged-read 嵌入封面解析**:ID3v2/FLAC/MP4 box/Ogg,`Reader` 接口抽象 HTTP Range 与本地偏移读)、`RemoteArtReader.kt`(109,http+smb 后端)、`RemoteArtworkStore.kt`(121,封面磁盘缓存)。桌面 `ui/components/RemoteArtwork.kt` 的注释早已预告:"RemoteArtworkStore … comes over with the remote libraries in a later slice"——本片就是它等的那个。
- **UI**:`ui/screens/LocalMusicScreen.kt`(1726 行,三 tab Songs/Artists/Albums、LIST/GRID 双视图、搜索、排序菜单、歌手/专辑 drill-down、长按多选 + 上传 WebDAV/删除);`MainActivity` 的 `isDeviceFolder()` 把 `local:all`/`local:downloads`/`local:webdav`/`local:smb` 四个 browseId 全部路由到这**一个**页面;`SourcesScreen.kt`(909 行)承载 WebDAV/SMB 连接编辑器入口;`WebDavUploads.kt`(378 行,复制曲目到服务器,冲突挂起等 UI 答 overwrite/keep-both/skip)+ 上传通知(109 行,Android-bound)。
- **凭据**:WebDAV 密码在 Android `AuthStore`(EncryptedSharedPreferences)。

桌面现状:Song 模型的 `localUri/localPath/…` 字段早已在场(逐行同构);播放链的"本地优先" seam 已通(`PlayerController.resolveUrl` = `Downloads.verifiedSavedUri(videoId) ?: StreamResolver.resolve(videoId)`,但只认 Downloads 记录);VlcAudioPlayer 天然吃本地路径与 http URL(headers 参数已存在,googlevideo 的映射先例在案);**读标签侧完全空白**(Tagger 家族只写不读);`Http.kt` 无 WebDAV 拦截器;AppSettings 无任何 localMusic/webdav 键;AuthStore 的 DPAPI 就位但 webdavPassword 字段缺席。

## 2. 已确认的决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **范围:本地扫描(Songs/Artists/Albums + 播放)+ WebDAV(浏览/播放/封面/凭据/上传)。SMB 全家不做**(独立成片) | ① slice 名只点名 WebDAV;② SMB 需要外部依赖 smbj 0.15.0(+500–700 行);③ 上游 `SmbDataSource` 是 ExoPlayer 专属,libvlc 对 `smb://` 无既有通道,播放侧要新决策;④ UI 层二者共享同构路径——本片在桌面**主动摘除** SMB:桌面 LocalMusic 页只接 `local:all` + `local:webdav`,编辑器只做 WebDAV。宁少给,不给半接的 |
| 2 | **`LocalMusicScreen` 逐形移植**(1726 行的桌面形):三 tab、双视图、搜索、排序、drill-down、多选。桌面挂点:`Destination.LocalMusic` 新枚举 + sidebar Library 区新行〔本地音乐〕+ Shell when 分支。**不并入** `local:downloads`——桌面 sidebar 已有〔Downloaded songs〕行(`DownloadsPage`,切片 6),重复成两行没有意义;上游四合一是 Android 单入口所致 | 上游页面本体基本可移植(Android 特有项仅 BackHandler 级小改);DownloadsPage 与上游 `local:downloads` 的职责重叠以"桌面已定型"优先 |
| 3 | **扫描引擎 = 桌面新写 `data/LocalMediaRepository.kt`**:`File.walkBottomUp` 遍历 + 上游过滤逻辑原样搬(扩展名白名单、路径黑名单段、`localMusicFolders` 前缀过滤——桌面持久化的是**绝对路径列表**而非 SAF tree URI);字段映射:dateAdded/dateModified ← 文件时间戳,时长/标签 ← 决策 4 的读取器。**同构上游:无后台设施、无缓存,进页面即扫**(1000 文件量级秒级可接受;并发解析见决策 4) | 上游"扫描即现算"是被核实过的事实,不是简化;桌面目录遍历与 MediaStore 查询同构;过滤常量(白名单/黑名单/30s)逐值保留 |
| 4 | **元数据读取 = 新 `data/metadata/` 读侧**:① 标题/歌手/专辑/时长走 **vlcj media meta**(`MediaPlayerFactory.media().parse()` 同步解析后读 meta)——零新依赖、全格式、libvlc 已在依赖里;② 嵌入封面走 **EmbeddedArt 逐字移植**(其 `Reader` 接口天然支持本地文件偏移读)。解析在 `Dispatchers.IO` + 有限并发(4)里跑;文件名 "Artist - Title" 切分(RemoteSong 语义)优先,vlcj parse 补缺 | MediaMetadataRetriever 的桌面等价物只有两条路:vlcj meta(已有依赖)或引入 tag 库(jaudiotagger 等,新依赖 + 只覆盖部分容器);vlcj 的 parse 是阻塞 native,IO 池 + 并发上限是给定的纪律 |
| 5 | **本地歌曲 id 约定照上游**:`videoId = 绝对路径`,`localUri = 同值`(上游 videoId=URI 串的直接对应;桌面无 content://)。播放分支:`PlayerController.resolveUrl` 加两枝——id 是存在的本地文件路径 → 直接给引擎;`id.startsWith("webdav:")` → 流 URL + Authorization 头(经既有 `AudioEngine.play(url, headers)` seam,VlcAudioPlayer 的 media-options 映射补 Basic 认证项)。`localUri` 在场且存在时优先于网络解析(Song.toMediaItem 的桌面形) | 上游 id 约定核实于 LocalMediaRepository L283/L408;VLC 吃本地路径是既证事实(下载播放 1223fb7);认证头通道已存在,只补选项映射 |
| 6 | **WebDAV 全家逐字移植**:`WebDavClient/WebDavConfig/WebDavRepository/RemoteSong/RemoteListing/RemoteArtwork/EmbeddedArt/RemoteArtworkStore` + `RemoteArtReader` 的 http 后端;`Http.kt` 补 `webDavInterceptor`(按 host 配 `WebDavAuth` volatile 参数池,AppSettings init/编辑时 publish——上游 `AppSettings.kt:1682` 同形);Coil 认证走 `WebDavCoilAuth` 移植;封面磁盘缓存照 `RemoteArtworkStore` | 1100–1300 行纯 JVM,逐字移植是本项目对这类代码的全部纪律;`EmbeddedArt` 是本片最值钱的移植件(470 行 ranged-read 解析器,本地与 WebDAV 两个后端同时受益) |
| 7 | **凭据与设置**:AuthStore 加 `webdavPassword`(DPAPI,密码族的第一位成员,注释摘掉"未移植"的旧话);AppSettings 加 `webdavUrl/webdavUsername` + `localMusicFolders: List<String>` + `LocalMusicSort` 枚举(TITLE_ASC/DESC/DATE_ADDED/DATE_MODIFIED)+ 本地页视图类型(照上游 `AppSettings.kt:588` 键名) | DPAPI 在切片 10 就位,密码落点是现成的;键名对齐上游保证 KDoc 的"升级不丢"语义同构 |
| 8 | **设置入口**:设置对话框新增〔本地音乐〕与〔WebDAV〕两族——本地:文件夹列表行(开 AWT `JFileChooser` DIRECTORIES_ONLY 原生目录选择)+ 排序行;WebDAV:URL/用户名/密码行 + 〔测试连接〕(走 `WebDavClient.testConnection`,结果行内反馈)+ 〔断开〕。上游 `SourcesScreen` 的编辑器语义收敛进设置对话框(桌面设置已是唯一设置面) | 桌面无 SAF;JFileChooser 是 JVM 内建;设置对话框的〔账号〕组先例(切片 10)已确立"行 → 子对话框/行内编辑"的模式 |
| 9 | **上传做,通知不做**:`WebDavUploads` 的逻辑移植(File 流版,比 Android 的 SAF 流简单),多选 → 〔上传到 WebDAV〕→ 冲突三选(overwrite/keep-both/skip + apply-to-all)对话框;进度在页内状态行,通知渠道(109 行 Android-bound)裁掉 | 上传是本地库页面的核心出口之一,桌面文件直读反而更简单;通知在桌面无等价物(切片 9 判例:没有对应 substrate 的东西不假装有) |
| 10 | **字符串**:上游 `local_music*`/`webdav*`/`sort_*` 家族(~30 键)随合并拷贝自动进场,零新增;`smb_*` 键合并了也不用;桌面新增仅目录选择相关两三键(`local_music_folder_pick` 等) | 字符串族的老规矩;上游 17 语言目录的维护成本不进桌面 |
| 11 | **不做**:SMB 全家(决策 1);后台扫描/扫描缓存/持久化扫描结果(上游没有);上传通知(决策 9);`local:downloads` 并入(决策 2);`local:playlist:` 下载集下载(独立小件,不属于本族核心);Automix 的 `LocalAudioSource`(上游注释自认 slice 外) | 宁少给,不给死功能 |

## 3. 交付物

| 文件 | 内容 |
|---|---|
| `data/LocalMediaRepository.kt`(新) | 文件系统扫描 + 过滤常量 + 文件夹前缀过滤 + Song 行构建(videoId=路径) |
| `data/metadata/LocalMediaMeta.kt`(新) | vlcj meta 读取(并发 4、IO 池)+ 文件名切分;EmbeddedArt 的本地文件 Reader |
| `data/webdav/WebDavClient.kt` 等 4 件(新) | 逐字移植(OkHttp/DOM 依赖全在) |
| `data/remote/RemoteSong.kt` 等 6 件(新) | 逐字移植;`RemoteArtReader` 只带 http 后端(SMB 后端随 SMB 片走) |
| `data/Http.kt` | `webDavInterceptor` + `WebDavAuth` publish 接线 |
| `auth/AuthStore.kt` | `webdavPassword`(DPAPI) |
| `data/settings/AppSettings.kt` | `webdavUrl/Username`、`localMusicFolders`、`LocalMusicSort`、本地页视图类型 |
| `desktop/playback/PlayerController.kt` | `resolveUrl` 本地路径 / webdav 两枝 + localUri 优先;`VlcAudioPlayer` headers→options 补 Authorization |
| `ui/screens/LocalMusicPage.kt`(新) | 三 tab + 双视图 + 搜索 + 排序 + drill-down + 多选(上传/删除)的桌面形 |
| `ui/screens/WebDavUploadDialog.kt`(新) | 上传进度 + 冲突三选 |
| `ui/screens/SettingsDialog.kt` | 〔本地音乐〕/〔WebDAV〕两族(目录选择、排序、连接编辑、测试连接) |
| `ui/shell/NavState.kt` + `Sidebar.kt` + `Shell.kt` | `Destination.LocalMusic` + sidebar 行 + when 分支 |
| `ui/components/RemoteArtwork.kt` | `RemoteArtworkStore` 接线(兑现注释预告) |
| `desktopStrings/{values,values-zh}` | 桌面新增 2–3 键 |
| `desktop/Main.kt` + `Probe.kt` | 接线;`probeLocalScan`(无头扫描冒烟)、`probeDestination=localmusic` |
| 测试 | 扫描过滤表(白名单/黑名单/时长/文件夹)、文件名切分、WebDavConfig 归一化/编址、LocalMediaSort 排序语义、resolveUrl 分支表 |

## 4. 关键技术点与风险

| 风险 | 对策 | 验证手段 |
|---|---|---|
| vlcj parse 阻塞与耗时(逐文件 native 调用) | 只在扫描线程池(并发 4、IO 派发);文件名切分先行,vlcj 只补缺;超时兜底(单文件 parse 限时) | `probeLocalScan` 计时:千文件级目录的总耗时日志 |
| libvlc 全局单例与播放器实例的相互作用 | media parse 用独立的 `MediaPlayerFactory` 实例(不与 AudioEngine 的 player 共享),parse 后即释放 media | 扫描同时播放不卡顿(实机) |
| WebDAV 服务器方言(Nextcloud/Synology/纯 Apache) | 逐字移植的 depth 0/1 PROPFIND 已是上游踩坑后的形状;`testConnection` 行内报错文本原样透出 | 上游已验证的形状;实机对 Nextcloud(人手,待环境) |
| 嵌入封面 ranged-read 对本地文件的路径 | EmbeddedArt 的 `Reader` 接口直接给 `RandomAccessFile` 后端(与 http Range 同接口) | 单测:对测试音频文件断言解析出的封面字节 |
| 扫描目录含超大目录/无权限目录 | `runCatching` 逐目录;文件数上限照上游 maxFiles 10k | 单测(黑名单 + 权限目录跳过) |
| 多选/长按在桌面的手势 | 桌面既有 Ctrl/Shift 点选语义(菜单/列表先例)——若无先例,checkbox 列 + 全选行 | 截图判读 |
| JFileChooser 阻塞主线程 | `Dispatchers.IO` 弹出,回主线程落设置 | 实机 |
| 字符串键在上游 17 目录中的漂移 | 桌面只读 en+zh 合并拷贝(既有管线),不新增翻译义务 | 构建通过即证 |

## 5. 验证计划

1. **单元测试**:§3 测试行所列,既有 44 类全绿。
2. **行为断言(实机探针)**:`probeLocalScan` 对本机音乐目录输出扫描表(条数/耗时/样例行);`probeDestination=localmusic` 截图管线。
3. **视觉判读**:`.shots/slice11/`——本地音乐页(Songs tab,LIST)zh-dark、Artists/Albums tab、设置〔本地音乐〕/〔WebDAV〕两族、(若有环境)WebDAV 已连接态。
4. **实机(人手)**:选一个真实音乐目录 → 页面列出 → 播放 → 排序/搜索/drill-down;WebDAV 连一台真实服务器(Nextcloud 或同类)→ 浏览/播放/封面 → 多选上传一首 → 冲突对话框。
5. **常量核对**:扩展名白名单七种、路径黑名单段、30s 阈值、maxFiles 10k、`local:` 前缀编址。

## 6. 本切片不做

SMB 全家(独立成片:`SmbConfig/SmbClient/SmbRepository/SmbConnection/SmbCoverFetcher` 移植 + smbj 依赖 + libvlc 对 smb:// 的播放通道 + UI 摘除的回填);下载管理面板、运动封面、交叉淡入淡出、Equalizer(Goal 序列后件);后台扫描与缓存;上传通知;`local:playlist:` 下载集;一起听;Discord/Last.fm;语言热切换;模块源体系。

## 7. 实施记录(实施后回填)

(待回填:交付、偏离、验证、已知瑕疵)
