# BitChord 桌面版 · 切片 10 设计:登录 UI(YouTube Music 账号)

日期:2026-10-11
分支:`desktop-slice2-now-playing`
状态:已完成(实施记录见 §7)

## 1. 背景

切片 9 的"本切片不做"点名了它:"登录 UI 与账号切换(需要桌面 WebView substrate,独立决策)"。`auth/WebSession.kt` 的 KDoc 也早已写下判词:"sign-in is a WebView flow and the desktop build browses signed out"——桌面 `WebSession` 只剩 `normalizeDataSyncId` 十一行,注释明说是"Android WebSession 在桌面剩下的全部"。

而桌面**下方**其实早就挖好了沟,只等上游来水:

- 数据层全会话端点就位:`YtMusicRepository` 的 `library()`/`history()`/`userPlaylists()`/`account()`/`accountChannels()`/`rate()` 全部依赖登录态,现在对游客一律失败;
- `Innertube` 的会话面完整:`cookie` setter(带 scope/visitor/channel 连带清理)、`adoptSessionScope`(收页面的 ytcfg)、`selectChannel`、`ensureSessionScope`(shell 兜底拉取)、`refreshSessionScope`——但**没有任何调用方**:`Innertube.cookie` 从未被赋值,启动即游客;
- 点赞已是乐观写回(`PlayerPage.toggleSongLike` → `YtMusicRepository.rate`,未登录时抛错回滚)——登录对它是纯解锁,零新策略。

上游规模(逐文件核实过):`YtMusicLoginScreen.kt` 205 行(Android WebView 登录 + ytcfg 探针)、`WebSession.kt` 154 行(`BrowserSession` cookie 罐 + `CapturedSession`)、`AuthStore.kt` 228 行(加密落盘 + `hasApiSid`)、`AccountSessions.kt` 101 行(多账号注册表)、`MainViewModel.kt` 会话段(`onWebSession`/`selectProfile`/`removeAccount`/`signOut`)约 300 行。

本切片的核心未决事只有一件:**桌面 WebView substrate**。独立决策,见决策 1。

## 2. 已确认的决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **substrate = JCEF,经 `me.friwi:jcefmaven:152.0.6`**(Chromium 152);浏览器以 `CefClient.createBrowser(url, osr=false, …)` 造出,`uiComponent` 经 `androidx.compose.ui.awt.SwingPanel` 嵌进 Compose;`--enable-native-access=ALL-UNNAMED` 进 jvmArgs | 调研结论(逐项对 Maven Central/GitHub/源码 jar 核实):① `org.jetbrains.compose.jcef` **不存在**(repo1 目录、metadata、Central 搜索三路 404,旧教程是幻觉);② kdroidfilter `composewebview`(WebView2/wry)裸 Window 可用的只有 EOL 的 1.0.0-beta-02,其 Windows `WryCookieManager` **线程崩溃 bug 恰在 cookie API 上**——cookie 是本切片命脉,不能踩;其后继 `dev.nucleusframework:composewebview:1.0.3` 要求整 app 迁 Nucleus Tao 窗口壳,过于侵入;③ JavaFX WebView 是老 WebKit,Google 登录常拒,最劣;④ 手写 WebView2 COM 绑定无成熟收益。JCEF 的 cookie API(`visitUrlCookies(includeHttpOnly=true)`、`setCookie(url, CefCookie)` 带 domain/path/expiry、`deleteCookies(url, name)`、`flushStore()`)与 Android `BrowserSession` 的四件事一一对应;Chromium 内核对 accounts.google.com(2FA/Passkey/风控页)最友好;SwingPanel 嵌入不动现有窗口结构 |
| 2 | **CEF 二进制按需下载(默认模式)**:`CefAppBuilder.setInstallDir(AppFiles.dir("cef"))`,首次登录时下载解压(~100MB 压缩),此后复用;`ProgressHandler` 打进 DebugLog | 不把 171MB natives 塞进 MSI:登录流程本身就要网络,首登多等一次是诚实的代价;离线捆绑(natives 依赖)留作"若反馈糟糕再改"的已知备选 |
| 3 | **登录窗口 = 独立 Compose `Dialog`(模态)**,约 880×640:顶栏(标题 = `sign_in_youtube_music`/`choose_profile`、状态行、页面就绪后〔使用此身份〕按钮)、主体 = 浏览器。Android `MainActivity.kt:3900` 那块全屏 webSession 的桌面形 | 主窗口是透明 + Mica 的 Skia 画布,重量级 Chromium 子窗口叠上去必出 airspace 撕裂;独立原生子窗口天然规避。模态语义 = "这是一次打断"(Android 全屏 webSession 同理),且 Compose Dialog 默认模态免设置 |
| 4 | **cookie 罐 = CEF 全局 manager,`cache_path = AppFiles.dir("cef")` 持久化**(Android WebView 罐的桌面对应物)。桌面 `BrowserSession` 三件事逐语义移植:`clearGoogleCookies()`(按名过期,host-only 与 domain-wide 两种形态都盖,后 `flushStore`)、`installGoogleCookies(header)`(SWITCH_CHANNEL 前装回快照)、`capture()`(读 music.youtube.com 的全部 cookie 含 HttpOnly → `AuthStore.hasApiSid` 把关 → ytcfg 探针 → `CapturedSession`) | Android 的两条铁律原样成立:**登出不清浏览器罐**的后果(下次登录被直通放行为刚登出的账号)与**登 Google Logout 端点会连坐已存账号**的禁令,都写在 `WebSession.kt` 的 KDoc 里,桌面照抄 |
| 5 | **ytcfg 探针走 `CefMessageRouter`**:JCEF 的 `executeJavaScript` 无结果回调;探针 JS(上游 `YTCFG_PROBE` 原文,末尾把结果 `JSON.stringify` 后经 `window.cefQuery` 送回 Java 侧 handler)。`CefMessageRouter` 在 browser 创建时挂到 client | JCEF 取 JS 结果的标准通道;探针语义(从**活页面**读身份,而非事后服务端猜)是上游整个设计的支点,原样保留。实现时若 MessageRouter 与 wry/OSR 有摩擦,备选是 executeJavaScript 写 `window.__probe` 再轮询——不引入第二个 substrate |
| 6 | **AuthStore 桌面形 + DPAPI**:文件 `AppFiles.file("auth.properties")`(FileStore 承载);cookie 与会话 JSON 落盘前经 `com.sun.jna.platform.win32.Crypt32Util.cryptProtectData`(用户作用域,本地已验证 jna-platform 5.16 在 classpath)编码,存 `dpapi:<base64>`;读取认前缀,解不开 = 视为无会话(fail-closed:换机器/换用户拿走的文件给不出错误身份)。Crypt32Util 整体抛(非 Windows 理论分支)= 退明文存,Android EncryptedPrefs 降级语义的对应物 | 凭据不进 `settings.properties`(Android 把 AuthStore 与 SharedPreferences 分开的同一理由);DPAPI 是 Windows 的"加密等价物",免密钥管理;`Crypt32Util` 是 jna-platform 自带,零新依赖。不移植:legacy 迁移(桌面无 legacy)、discordToken/webdavPassword/smbPassword(各自的切片) |
| 7 | **`AccountSessions.kt` 逐字移植,org.json → kotlinx.serialization**:`GoogleAccountSession`/`YouTubeProfile`/`sessionId`/`profileId`/`flattenedProfiles`/`adjacentProfile` 原样;JSON 编解码换 @Serializable,存储形状(`google_account_sessions_v2` 键与字段名)不变 | 纯 JVM 数据层,100 行;序列化库换掉是唯一必要偏离,形状不变保证 KDoc 里"升级不登出"的承诺在同一套语义下成立 |
| 8 | **新 `auth/AccountController.kt` = MainViewModel 会话段的桌面形**:`signedIn`/`accounts`/`activeAccountId`/`activeProfileId`/`account` 五条 StateFlow;`onWebSession(session, mode)` **验证先行**(设 cookie + `adoptSessionScope` + `selectChannel` → `account()` 20s 超时、失败 750ms 重试一次 → 失败回滚 cookie/channel 并回调 false,成功才 upsert 注册表 + 翻流程);`selectProfile`(cookie 在手,直接换 dataSyncId/pageId,不开浏览器);`removeAccount`;`signOut`;`loadChannels()`(`accountChannels()` 检测结果并进注册表,Android `persistDetectedProfiles` 的合并语义)。登录后的重载(home + library 三页)不进 controller:Main.kt 以回调接线 | 上游把这套放在 MainViewModel,桌面的"MainViewModel 会话段"就是一个专门对象;验证先行的理由(`MainViewModel.kt:2585` 的注释:半途的 channel 选择器不得成为持久化的破账号)照搬。校验器 `suspend () -> Result<Account>` 以构造参数注入(默认 `YtMusicRepository::account`),测试换假件——与 PlayerController 收 engine 同形 |
| 9 | **设置入口**:设置对话框顶部新增〔账号〕组——未登录:〔登录 YouTube Music〕行(副行 `library_sign_in_description`);已登录:身份行(名字 + 邮箱)+〔收听身份〕行(开 `AccountDialog`)+〔退出登录〕行。`AccountDialog` = 身份单选表(注册表 profiles ∪ 检测到的 channels,选中之行是**本 app 的选择**而非 YouTube 的)+〔在 YouTube Music 中选择〕(SWITCH_CHANNEL 登录窗)+〔添加账号〕(SIGN_IN 登录窗)+〔移除此账号〕(仅多账号时)。账号组是设置里"我是谁"的问题,输出设备行的既有模式(行 → 子对话框)直接复用 | 切片 9 的决策 7 已立判例:"设置级"的决定进设置对话框;身份是典型的设置级长期决定。子对话框单选 + 全部动作收进一处,是 Android `AccountChannelDialog` + 账号页的桌面收敛形;〔使用此身份〕/〔在 YouTube Music 中选择〕分工照抄上游(页内选是快路,YT 站内换是权威慢路) |
| 10 | **启动恢复**:Main.kt 里 `AuthStore.activeSession` → `Innertube.cookie = …` **然后** `Innertube.selectChannel(profile…)`(顺序固定:cookie setter 会清 channelOverride);**不**主动 `adoptSessionScope`,让 `ensureSessionScope` 在首个签名请求时经 shell 惰性验证 | 存活的会话直接进场;shell 兜底路径(`Innertube.kt:359`)本就是为"有 cookie 无 scope"设计的,启动时多打一发 shell 请求是浪费。顺序铁律来自 cookie setter 的 KDoc |
| 11 | **字符串**:复用上游合并拷贝——`sign_in_youtube_music`/`choose_profile`/`use_this_profile`/`checking`/`profile_unavailable`/`switch_profile_hint`/`signed_in_as`/`sign_out`/`add_account`/`accounts`/`brand_account`/`library_sign_in_description`/`close` 全部已存在;桌面新增仅 `account`(组标题)与 `listen_as`(〔收听身份〕——Android `AccountChannelDialog` 的 "Listen as" 是硬编码英文,桌面把它做正)+ `remove_account` | 桌面字符串族的规矩:能复用上游绝不另写;硬编码英文只值得一次修正而非一次移植 |
| 12 | **不做**:Discord / Last.fm 登录(RPC 与 scrobble 是切片 8 的既有判词);WebDAV/SMB 凭据存储(随本地音乐切片);一起听;`stepProfile` 手势/快捷键换身份(桌面的身份切换走设置,无对应手势域);WebView2 substrate(决策 1 的否决记录);CEF natives 进 MSI(决策 2);点赞同步策略改动(乐观写回已就位) | 宁少给,不给死功能 |

## 3. 交付物

| 文件 | 内容 |
|---|---|
| `desktop/build.gradle.kts` | `me.friwi:jcefmaven:152.0.6` 依赖;`--enable-native-access=ALL-UNNAMED` 进 run/应用 jvmArgs |
| `auth/AccountSessions.kt`(新) | 注册表数据类 + kotlinx.serialization 编解码 + sessionId/profileId/邻位选择(逐字移植) |
| `auth/AuthStore.kt`(新) | DPAPI 包装(`DpapiCipher`,protect/unprotect/降级)+ FileStore 落盘 + sessions/active/select/upsert/remove/signOut/hasApiSid |
| `auth/WebSession.kt`(改) | 保留 `normalizeDataSyncId`;新增 `CapturedSession`(与上游同形)与桌面 `BrowserSession`(CEF cookie manager 三件事) |
| `auth/AccountController.kt`(新) | 会话流程对象:onWebSession/selectProfile/removeAccount/signOut/loadChannels + 五条 StateFlow + 会话变更回调 |
| `auth/YtMusicLoginWindow.kt`(新) | 登录模态窗:JCEF 生命周期(CefApp lazy 单例、client/browser 创建与释放)、顶栏状态机(页面就绪/校验中/不可用)、`captureRequest` 计数语义、ytcfg 探针(MessageRouter) |
| `ui/screens/AccountDialog.kt`(新) | 身份单选 + 在 YT 中选择 + 添加账号 + 移除/退出(OutputDeviceDialog 的行模式) |
| `ui/screens/SettingsDialog.kt` | 顶部〔账号〕组(未登录/已登录两态) |
| `ui/shell/Shell.kt` + `ShellState` | `showLogin`/`loginMode` 状态、登录窗宿主、设置新接线(AccountController 穿参) |
| `desktop/Main.kt` | AccountController 构造(注入 home/library 重载回调)、启动恢复(cookie → selectChannel)、quit 时释放 CEF |
| `desktopStrings/{values,values-zh}/strings.xml` | `account`/`listen_as`/`remove_account` |
| 测试 | `AccountSessionsTest`(JSON 往返/邻位)、`AuthStoreTest`(hasApiSid 表含 `__Secure-3PAPISID` 陷阱/upsert/select/remove/回退/DPAPI 往返与降级)、`AccountControllerTest`(验证失败回滚不落盘/成功翻流程/注册表去重按 email/切换身份不开校验之外的网络) |

## 4. 关键技术点与风险

| 风险 | 对策 | 验证手段 |
|---|---|---|
| CEF 首次下载失败(网络受限、磁盘被管) | builder 全程 `runCatching`;失败 = 登录不可用,设置行降级提示,app 其余部分无恙(CefApp 只在登录窗打开时 lazy 启动) | 断网开登录窗 → 状态行如实报错、app 不崩 |
| JCEF JS 取结果的通道与探针格式 | 决策 5:MessageRouter 主案;探针返回**字符串**(上游返回对象是给 WebView 序列化用的,桌面显式 stringify 是同义改写不是语义偏离);探针 null(错误页/未落地)是合法答案,照上游回"不可用" | 实机登录后日志:探针命中 + `adopted page scope` |
| 模态 Dialog 内 SwingPanel/CEF 子窗口的尺寸与焦点联动 | SwingPanel 把 AWT 重量的 browser 组件挂进 Dialog 的 ComposePanel 布局树,尺寸随布局走;键盘焦点穿越 Swing 边界是 JCEF 已知课题,若输入法/按键有丟,在 CefSettings/JcefArgs 层面解决,不动结构 | 实机:登录页输入账号/密码/2FA 全程键入无丢字;窗口缩放后页面无残影 |
| `__Secure-3PAPISID` 陷阱(上游 `hasApiSid` KDoc 的原案) | `hasApiSid` 逐字移植 + 测试表钉死三个合法名 | 单测 |
| DPAPI 跨用户/跨机器不可解 | 读侧 fail-closed(视同无会话),写侧先解旧值失败即覆盖;`DpapiCipher` 抽象为可注入,测试不碰真 DPAPI 也能测注册表逻辑 | 单测(假 cipher)+ 实机(真 DPAPI 往返) |
| CEF 与 SMTC/VLC/Coil 同进程 | CefApp 全局单例、独立线程域,登录窗关闭只 `close()` browser 不杀 CefApp(下次复用);quit 时 `CefApp.getInstance().dispose()` 兜底 | 实机:登录→取消→再登录,第二次不用重下载;退出无挂起进程 |
| Google 风控对嵌入浏览器的挑战页 | 可见浏览器,人手过验证(与 Android WebView 同体验);UA 不伪装(CefApp 默认即 Chrome) | 实机登录一次 |
| 登录窗模态挡住 SMTC 媒体键 | 模态只挡主窗口输入,SMTC 走系统层不动 | 常识核对 |

## 5. 验证计划

1. **单元测试**:§3 测试行所列,既有 41 类全绿。
2. **实机(人手)**:真实登录一次 Google 账号——捕获后日志出 `acting as channel`/`adopted page scope`,库三页与历史变真,点赞写回成功(不再回滚);退出登录后回到游客;再次登录不被直通放行(BrowserSession 清罐生效);多频道账号在页内选身份后再保存;〔添加账号〕并存第二个 Google 账号并互切。
3. **视觉判读**:`.shots/slice10/`——登录窗(Google 登录页)、设置〔账号〕组未登录态、已登录态、AccountDialog 单选态;双语 × 深浅取代表。
4. **常量核对**:登录 URL 串(上游 `LOGIN_URL` 逐字)、`MUSIC_ORIGIN`、`hasApiSid` 三个合法名、探针键名六枚。

## 6. 本切片不做

Discord 登录与 RPC、Last.fm scrobble(切片 8 既有判词);WebDAV/SMB/本地音乐(独立切片,凭据存储骨架可后接);一起听;`stepProfile` 的手势/快捷键;Equalizer/交叉淡入淡出/运动封面/下载管理面板/本地扫描(Goal 序列的后件);CEF natives 离线捆绑(决策 2 备选);语言热切换;模块源体系。

## 7. 实施记录(2026-10-11 回填)

**交付**:决策 1–11 全部落地。`auth/AccountSessions.kt`(注册表数据类 + kotlinx.serialization 编解码,存储形状与上游 `google_account_sessions_v2` 逐字段一致,org.json 的"缺省全写、null 略去、空 id 回退派生 id、空 cookie 条目跳过"语义在 codec 里重现);`auth/AuthStore.kt`(`DpapiCipher`——JNA `Crypt32Util`,`dpapi:<base64>` 前缀,protect 失败降明文、unprotect 失败按无会话 fail-closed;cipher 可注入;键名与 Android v2 相同;`hasApiSid` 连 KDoc 逐字);`auth/WebSession.kt`(新增 `WebSessionMode`/`CapturedSession`/CEF 版 `BrowserSession`:清 Google cookie 按"枚举含 HttpOnly → 按名 deleteCookies + 过期对(域内/宿主两形)"、装快照按 origin 重发 `Secure`、读 music 全量含 HttpOnly 且读后 flush);`auth/AccountController.kt`(五条 StateFlow;`onWebSession` 验证先行 + 失败回滚 + email 去重;`selectProfile` 零网络;`removeAccount` 回退;`signOut` 多账号语义;`loadChannels`+`persistDetectedProfiles` 占位 id 合并逐语义;`restoreActiveSession` cookie 先于 channel;`onSessionChanged` 钩子);`auth/YtMusicLoginWindow.kt`(模态 880×640,`CefRuntime` lazy 单例,窗口化 Chromium,`CefDisplayHandler.onAddressChange` 驱动 pageReady,探针走 `window.cefQuery` MessageRouter,`captureRequest` 计数语义);`ui/screens/AccountDialog.kt`(收听身份单选 + 在 YT 中选择 + 添加账号 + 移除/退出);设置对话框〔账号〕组;Shell 宿主登录窗(`ShellState.showLogin/loginMode`);Main.kt 接线(启动恢复、会话变更重载 home+library 三页、quit 时 `CefRuntime.shutdown()`、`probeDestination=signin`);双语字符串。

**与设计的偏离**:

1. **`listen_as` 上游已有**(设计 §11 以为 Android 硬编码需要新增)——直接用合并拷贝的键;桌面字符串只新增 `account`/`remove_account`,比设计少一个。
2. **退出路径的原生崩溃,实施期发现并修复**:`autoExit` 在登录窗开着时退出,`CefApp.dispose()` 在浏览器仍存活时于 `CefApp.N_Shutdown()` 原生崩溃(`hs_err`:`EXCEPTION_ACCESS_VIOLATION` @ jcef.dll)。修复:`CefRuntime` 登记打开的 `BrowserHolder`,`shutdown()` 先经各 holder 关浏览器(`close(true)` + client/router dispose,cookie 店顺带落盘)再 dispose app;复跑原崩溃路径干净退出。
3. **浏览器表面被 Three 重试证明脚本盲**:PrintWindow 对 Chromium 152 的渲染子窗口(`CefBrowserWindow → Chrome_WidgetWin_1`)拿到的恒是白图——主窗拍、加 `--disable-gpu`/`--disable-gpu-compositing` 拍、对子窗口句柄直拍,三路全白(Chromium 的 DirectComposition 表面绕过窗口自身 DC);CopyFromScreen 在本会话整屏黑(既有记载)。页面确实在加载:`onAddressChange` 日志两度记录 `accounts.google.com/v3/signin/identifier?continue=https://music.youtube.com/…`。**登录窗内浏览器面的视觉验收留待人手**(切片 4 右键、切片 8 SMTC 的同一先例);GPU 关闭保留,但注释改写真理由——登录表单页不需要 GPU,软件渲染在 RDP/虚拟机/残缺 GPU 栈的机器上确定性最好,不是为了截图。
4. **`AccountController` 多一条 `channelLister` 注入缝**(与 `validator` 同形),`loadChannels` 的合并逻辑进测试而网络不进。
5. **探针 10s 上界**(上游 `evaluateJavascript` 无界)——模态对话框挂死在"checking"比全屏更不可原谅。
6. **验证失败的回弹**:捕获被 controller 拒(验证 false)时窗口回就绪态(按钮重新可点、提示行可见)而非显示 `profile_unavailable`——该串只用于"页面上没有已登录身份"。窗口自持状态,签名里没有回调能把它按下去。
7. **Android-only 件按设计丢弃**:MainViewModel 的 listener cache(`cacheCurrentListener`,桌面无逐身份 UI 缓存)、`removeAccount` 的 legacy `cookie` 键写回(设计禁 legacy);`adjacentProfile` 移植并测试但无手势/快捷键接线(决策 12)。

**验证**:44 类 398 测试全绿(新增 38:AccountSessions 11、AuthStore 16——含 `__Secure-3PAPISID` 陷阱表、形近名钉死、外来 cipher fail-closed、明文降级、真 DPAPI 往返按假设守门;AccountController 11——假 validator/channelLister,拒绝回滚不动注册表、email 去重、换身份不再验证、占位合并、多账号退出)。实机探针:CEF 首开完成下载解压(`%LOCALAPPDATA%\BitChord\cef`,进度进 DebugLog)、二开走 `LOCATING→INITIALIZING→INITIALIZED` 免下载;登录窗打开且页面导航至 Google 登录页(日志);autoExit-with-open-dialog 崩溃修复后 45s 干净退出无残留 java 进程;`probeDestination=settings` 截图两态。截图 `.shots/slice10/`:设置〔账号〕组未登录态 zh-dark + en-light、登录窗(浏览器面白 = 已证脚本盲)。

**已知瑕疵**:登录窗内浏览器表面脚本盲(偏离 3)——真实登录、cookie 捕获、库/历史/点赞写回、多账号并存、SWITCH_CHANNEL 装回快照,均待人手验证(设计 §5.2 本就计划人手);`CefApp` 是进程级单例,auth 测试不触它(单测只测纯逻辑与假件),真 DPAPI 往返测试在非 Windows 上按 assumption 跳过;CEF 下载进度日志的百分比口径不是 0–100(jcefmaven 的 value 语义超出分数),纯日志美观问题;设置对话框既有探针污染 `settings.properties` 的瑕疵与此片无关仍在。
