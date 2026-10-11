# BitChord 桌面版 · 切片 15 设计:Equalizer(音频设置族)

日期:2026-10-11
分支:`desktop-slice2-now-playing`
状态:已完成(实施记录见 §7)

## 1. 背景

切片 9 判词:"Equalizer(VLC 有现成均衡器 API,随'音频设置'族另排)"。

上游定性(逐文件核实):**不是** `android.media.audiofx.Equalizer`,而是自研 **10 槽参数 SVF DSP**(`EqualizerProcessor.kt` 397 行,ExoPlayer Float32 链内)+ 纯 Kotlin 曲线模型。模型层:`EqualizerCurve.kt`(215 行,**零 Android 依赖**——`EqLayout` 10 槽:0..6 手动段 60/150/400/1k/2.5k/6k/14k Hz 两端 shelf 中间 bell、7..9 tone pad 三段;`EqCurve` gains+Qs+自动 preamp;`manualCurve()`/`toneCurve(x,y,focused)`/`preampFor()` 96 点频响扫峰;`sectionGainDb()` 单段解析式)、`EqualizerPreset.kt`(51 行,16 预设 + CUSTOM,7 值表)。UI:`EqualizerScreen.kt`(917 行:开关行、Dynamic/Manual 分段、TonePad 自绘 puck、BandFader 七竖滑、BalanceControl、PresetSheet)。设置:7 个 flow(`equalizerEnabled/mode/toneX/toneY/focused/balance/bands`,bands 单键逗号串)。链序 Spatial → Equalizer → TransitionFilter;**EQ 与 Automix 族无耦合**(平行前级,各自旁路)。双 DSP 实例 A/B 对应双 sink,同值喂。

vlcj 契合度(javap 已核实):`MediaPlayerFactory.equalizer()` 给 `bands()`(libvlc 固定 10 段 31.25Hz…16kHz)、`newEqualizer()`、`Equalizer.setPreamp/setAmp(s)`(±20dB 语义,容纳上游 ±12);应用点**每个 MediaPlayer 实例**(`audio().setEqualizer()`);跨引擎不继承——但 PlayerController 的 handoff 已有补投位(切片 11 的 outputDevice 在创建 `:282` 与 handoff `:337` 双处补投的先例),EQ 沿用同模式即可跟随角色互换。字符串 27 条已随合并拷贝进场,**零翻译**;README 已声明桌面砍掉 System equalizer 集成,自研 EQ 不在砍单内。

## 2. 已确认的决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **模型层逐字移植**:`EqualizerCurve.kt` + `EqualizerPreset.kt`(~266 行纯 Kotlin,零 Android 依赖) | 这两层是 EQ 的全部智力资产(布局/曲线数学/preamp/预设表);`sectionGainDb()` 正好是"上游曲线 → VLC 频点"映射的现成工具 |
| 2 | **DSP 不移植,引擎用 vlcj 内建 10 段均衡器**:`EqualizerProcessor.kt`(397 行 SVF)在桌面无采样级插桩点;映射层新写(~60–100 行):`toneCurve/manualCurve` 出的 EqCurve 在 VLC 的 10 个固定频点采样求和(dB 相加)写 `setAmps` + `setPreamp`(上游 `preampFor` 现成);上游 16 预设经同一映射,不用 VLC 自带预设名 | libvlc 均衡器是切片 9 判词点名的 substrate;频点不同(7 段 60–14k 对 10 段 31.25–16k)映射即答案 |
| 3 | **语义缺口的处置**(逐条记录):balance 滑条**不做**(libvlc 无连续 balance,只有声道模式——宁缺毋假);TonePad 的 Broad/Focused(Q)区分**近似保留**(频点采样保留大致形状,差异记 KDoc);预设切换无滑移(libvlc 即时跳变,上游 18ms 追逐不搬,记降级);`equalizer_bitstream_*`/`system_equalizer*` 四条字符串不用 | substrate 的边界如实呈现,不假装上游形状 |
| 4 | **UI = `EqualizerDialog`(窗口级子对话框)**:上游 917 行全屏的桌面收敛形——开关行 + DYNAMIC/Manual 分段 + TonePad(自绘 puck 手势直移)+ BandFader(七竖滑直移)+ 预设选择(PresetSheet 收进对话框内的行展开);删系统均衡器/bitstream/声场(balance)件;挂设置对话框"播放"组新行 | 桌面对话框范式(切片 4/10/12)已成家族;TonePad/BandFader 是纯 Compose 绘制可直移 |
| 5 | **设置模型**:AppSettings 加 6 flow——`equalizerEnabled/mode(DYNAMIC|MANUAL)/toneX/toneY/focused/bands`(键名照上游 `equalizer_*`;`balance` 键不移植,决策 3);派生 `equalizerPreset` 反查照上游 | 键名对齐保"升级不丢"语义;bands 单键逗号串照上游编码 |
| 6 | **引擎接线**:`AudioEngine` 加 `setEqualizer(EqualizerTuning?)` 默认空实现(切片 9 决策 4/14 决策 3 判例:假件与未来引擎不付假数据);`VlcAudioPlayer` 覆写(持一个缓存 Equalizer 对象,变更时重建重投,跨 media 保持性按探针实测后写 KDoc——照 `setOutputDevice` 的记录先例);`PlayerController` 三处补投:引擎创建时(`:282` 同位)、crossfade handoff 时(`:337` 同位)、设置收集器(仿上游 `applyEqualizer` 收 7 flow 按模式调曲线);试听引擎不喂 EQ(上游同) | handoff 跟随是角色互换正确性的一部分;三处补投位全部现成 |
| 7 | **不做**:balance;SVF DSP;滑移;`system_equalizer*` 跳转与 bitstream 提示行;Automix 族(14 号判词不变) | substrate 边界 + 已判词项 |
| 8 | **探针**:`bitchord.probeEqualizer=<预设名或 bands 串>` 无头设 EQ 并打印 VLC 频点映射表(上游曲线 7 值 → VLC 10 带采样值),验证映射数学;`probeDestination=equalizer` 开对话框截图 | EQ 的听感不可脚本化,映射数学可以;频响形状由测试表钉死 |

## 3. 交付物

| 文件 | 内容 |
|---|---|
| `playback/EqualizerCurve.kt` + `EqualizerPreset.kt`(新) | 逐字移植(~266 行) |
| `desktop/playback/EqualizerMapper.kt`(新) | EqCurve → VLC 10 带采样映射(~80 行:频点求和 + preamp) |
| `desktop/playback/AudioEngine.kt` + `VlcAudioPlayer.kt` | `setEqualizer` 成员 + 落地(缓存 Equalizer/重投/KDoc 探针记录) |
| `desktop/playback/PlayerController.kt` | 三处补投 + 设置收集器 |
| `data/settings/AppSettings.kt` | 6 flow + setter(上游键名) |
| `ui/screens/EqualizerDialog.kt`(新) | 开关 + 双模式 + TonePad + BandFader + 预设行(~600–750 行) |
| `ui/screens/SettingsDialog.kt` | 播放组〔均衡器〕行 |
| `desktop/Main.kt` + `build.gradle.kts` + `Probe.kt` | 探针 + 键登记 |
| 测试 | `EqualizerCurveTest`(曲线端点/preamp/预设反查,上游若已有测试对齐之)、`EqualizerMapperTest`(映射表:FLAT 全零/tone 极端/手动形状)、`EqualizerSettingsTest`(键/编码) |

## 4. 关键技术点与风险

| 风险 | 对策 | 验证手段 |
|---|---|---|
| 7 段模型 → 10 带采样的形状失真(shelf/bell 频点错位) | `sectionGainDb()` 是解析式,采样即真值;映射测试表钉死 FLAT/tone 极端/手动三形 | 单测 |
| Equalizer 跨 media 是否保持(VLC 内部行为未文档化) | 探针实测(set EQ → 换曲 → 读回);不保持则 VlcAudioPlayer 在每次 play 后重投 | 探针日志 + KDoc 记录 |
| TonePad 手势在窗口对话框内的坐标(DP ↔ 归一化) | 上游手势数学直移(纯比例计算),对话框尺寸不同不影响 | 截图判读 |
| 设置变更即时生效(播放中拖滑条) | 设置收集器 → setEqualizer 重投;vlcj 即时生效预期 | 实机(人手) |
| 双引擎 EQ 状态漂移(handoff 后待机引擎持旧 EQ) | 补投位同 outputDevice 先例,handoff 必投 | 单测(handoff 后新引擎 tuning 一致) |

## 5. 验证计划

1. **单元测试**:§3 测试行,既有 53 类全绿。
2. **行为断言(实机探针)**:`probeEqualizer=rock`(预设名)打印 10 带映射表;`probeDestination=equalizer` 截图。
3. **视觉判读**:`.shots/slice15/`——均衡器对话框(Dynamic 态 TonePad/Manual 态七滑条/预设展开)zh-dark + en-light 取代表;设置页回归。
4. **实机(人手)**:开 EQ 拖 tone pad → 音色即时变化;七滑条拉高 14k → 明显亮;预设切换;crossfade handoff 后 EQ 仍在;关 EQ → 直通。

## 6. 本切片不做

balance(决策 3);SVF DSP 与滑移;Automix 族;`system_equalizer*`/bitstream 行;Goal 序列至此收讫(登录 UI、本地音乐扫描全家含 WebDAV、下载管理面板、运动封面、交叉淡入淡出均已在前片完成)。

## 7. 实施记录(2026-10-11 回填)

**交付**:决策 1–8 落地。`playback/EqualizerCurve.kt`(220 行逐字,仅两段描述未移植 DSP 的 KDoc 按 mapper substrate 改述)+ `EqualizerPreset.kt`(16 预设 + CUSTOM + `matching()` 逐字);`desktop/playback/EqualizerMapper.kt`(`EqualizerTuning` + `equalizerTuning()`:上游曲线在 libvlc 10 固定频点解析求和、±20dB 夹紧、**IEEE `-0.0` 归一**——移植的 `-peak` preamp 对平坦曲线产出负零,引擎边界统一正零,使"FLAT 映射为零"在位模式层成立);`AudioEngine.setEqualizer(EqualizerTuning?)` 默认空实现;`VlcAudioPlayer` 缓存一个 vlcj Equalizer、null/关即真摘除(`libvlc_media_player_set_equalizer(player, NULL)` 已核实是真旁路而非十条平坦滤波)、每次 `play()/prepare()` 后防御性重投(persistence 不可观测——libvlc 3.x 无 get-equalizer API,防御重投是 KDoc 写明的答案);PlayerController 三处补投(待机引擎创建、crossfade handoff、`watchEqualizer()` 收集器 combine 六流仿上游 `applyEqualizer`),试听引擎不喂;AppSettings 六 flow(上游键名,`EqualizerMode` 枚举,clamp setter,派生 `equalizerPreset`,bands 单键逗号串,LyricsSettings 编码先例);`EqualizerDialog.kt`(965 行:TonePad/BandFader/snapGain 手势数学逐字,460dp Dialog,预设行内展开,alpha 0.38 禁用置灰 220ms tween,Broad/Focused 选择行);设置行(`equalizer`+`equalizer_subtitle` 上游键);`probeEqualizer`(打印 7 槽→10 带映射表并对真实 libvlc 应用)+ `probeEqualizerPreview`(内联面板——嵌套对话框 PrintWindow 不可及,输出设备面板的先例)。

**与设计的偏离**:

1. **Mapper 落位 `desktop/playback/`**(设计 §3 允许二选一):`EqualizerTuning` 是 `AudioEngine.setEqualizer` 的形状,与它装扮的引擎同住。
2. **`-0.0` 归一超出设计清单**:位模式层的"FLAT 即零"要求它;非数值问题而是符号位问题。
3. **`probeEqualizerPreview` 增设**:截图管线不可及嵌套对话框(既有判例),预览内联渲染同一组合函数。
4. 上游字符串 `equalizer_subtitle` 的中文译文写的是系统均衡器文案("你设备的系统面板"),桌面设置行会带出该措辞——app/ 不动,译文措辞的修整属上游事,记录在案。

**验证**:56 类 535 测试全绿(新增 33:EqualizerCurveTest 14——端点/tone 极端/preamp ≤ 0/预设 `matching()` 往返;EqualizerMapperTest 10——FLAT 全零位模式/tone 极端形状/手动已知表/夹紧/preamp 直通;EqualizerSettingsTest 8——键持久化/编码/无 balance 键)。实机探针:`probeEqualizer=rock` 打印 7 槽→10 带映射表(preamp −4.4dB 直通,FLAT 全零)并成功应用于真实 libvlc 播放器。截图 `.shots/slice15/equalizer-panel-zh-dark.png`(禁用置灰态:开关/分段/TonePad/提示/Broad 行)。**人手验证待做**:听感(EQ 中曲变更、handoff 持久、关即摘)、Manual 态七滑条与预设展开截图、crossfade handoff 后 EQ 仍在的耳朵确认。

**已知瑕疵**:均衡器听感与跨曲持久属人手域(设计 §5.4 本就计划);`equalizer_subtitle` 中文措辞牵上游;libvlc persistence 无读回 API,防御重投是设计决定而非缺陷,已 KDoc 记录。
