# BitChord 桌面版 · 切片 17 设计：真玻璃渲染管线（Liquid Glass）

日期：2026-10-11
分支：`desktop-slice2-now-playing`（fork：xiongdy54/BitChord-for-Windows，上游 kushagrasinghx/BitChord）
状态：待用户复核

## 1. 背景

切片 1 的"本切片不做"把它划给了"切片 1.5"，此后再无人认领：**真玻璃渲染管线**（上游 `ui/components/backdrop/` 26 文件——Kyant0/backdrop v2.0.0 的源码内置版——加 `LiquidGlass.kt` 集成胶水）。桌面侧一直以 Mica + 纯色 chrome 代替；上游在 Android 12+ 用 `RenderEffect` + AGSL 给浮动导航条与停靠播放控件做的**取景折射玻璃**（blur + 饱和度 + 圆角矩形透镜折射 + 7 采样色散 + 高光描边），桌面至今没有对应物。

上游规模（逐文件核实）：`backdrop/` 约 2700 行 + `LiquidGlass.kt` 200 行；五个 AGSL 着色器（共用圆角矩形 SDF 前奏、双版本透镜折射、两种高光）**本来就是 SkSL**——AGSL 即 Skia 的 SkSL，可近乎逐字移植。桌面 Compose 1.12.1 / skiko 0.150.1 的能力已逐一验证：`RuntimeEffect.makeForShader`、`RuntimeShaderBuilder.uniform/makeShader`、`ImageFilter.makeRuntimeShader(builder, "content", input)`（即 `createRuntimeShaderEffect` 的等价物）、`asComposeRenderEffect()`（desktop 互操作扩展）、`rememberGraphicsLayer`/`GraphicsLayer.record`、`Canvas.nativeCanvas`。

## 2. 已确认的决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **语义移植而非逐文件搬运**：上游是"效果 DSL 全家桶"（InnerShadow、Ambient 高光、CombinedBackdrop、多种 Backdrop 源），桌面当前唯一消费者是 `liquidGlass` 一条管线。只移植 LiquidGlass 用到的路径：LayerBackdrop 录制 → 取景采样 → colorControls(饱和 1.5) → blur(8dp) → 透镜折射（色散版）→ Default 高光 → Default 阴影 → surface 0.4 tint | "宁可少给，不给死按钮"；其余效果等有消费者再进 |
| 2 | **着色器逐字搬运**：`RoundedRectSDF` 前奏 + `RoundedRectRefractionWithDispersionShaderString` + `DefaultHighlightShaderString` 三个字符串原样进 `ui/glass/GlassShaders.kt`；uniform 驱动表照抄（size/offset/cornerRadii/refractionHeight/amount/depthEffect/chromaticAberration；amount 以负数传入） | AGSL = SkSL；同源移植即同源等价 |
| 3 | **渲染桥重写为 skiko**：`android.graphics.RuntimeShader`→`RuntimeShaderBuilder`；`createRuntimeShaderEffect`→`ImageFilter.makeRuntimeShader`；`createChainEffect`→滤镜参数嵌套（色彩→模糊→折射，折射在最外吃模糊输出）；`BlurMaskFilter`→skia `MaskFilter.makeBlur`（高光描边用）；效果链挂在 `GraphicsLayer.renderEffect` 上 | 桥层（internal/RenderEffect.kt、internal/Paint.kt）是上游仅有的两处 android.graphics 深依赖 |
| 4 | **`backdropScale = 0.33` 全保留**：采样层按 1/3 线性分辨率录制，折射的 `size`/`offset`/`cornerRadii` uniform 一律乘 0.33（上游 `BackdropEffectScope.update` 的语义）；`RuntimeEffect` 编译按字符串缓存（进程一次） | 上游的性能护栏在桌面同样成立；0.33 也与上游 Haze inputScale 一致 |
| 5 | **桌面应用面 = 全屏播放器的底部控制簇**（竖版 transport Column，现无任何材质）——上游 `GlassNavBar`"docked now-playing controls"的桌面对应物；录制器挂在播放器内容根（`layerBackdrop` 语义：record-then-blit，一帧只画一次） | 桌面 shell 是侧栏布局，内容不从工具栏下滚过，导航条玻璃无景可采；播放器内的控制簇浮在封面/网格背景上，是唯一"背后有内容"的玻璃位 |
| 6 | **设置两键进外观组**：`liquid_glass`（默认 false，上游同）+ `reduce_dynamic_blur`（默认 false；玻璃的实底回退 = surface + 发丝描边，同时是其既有语义）；两键桌面 subtitle 走 `desktopStrings`（上游 subtitle 写着 Android 12+，桌面不适用） | reduceDynamicBlur 在桌面数据层存在已久、无 UI（切片 16 盘点的缺口之一），本片一并补齐 |
| 7 | **能力门控**：桌面无 SDK 分级，`isGlassSupported()` = skia RuntimeEffect 可编译（常真，保留函数形状与 try 编译回退）；`reduceDynamicBlur` 优先级最高（关玻璃也关上游已移植的歌词模糊等） | 上游三级门控（SDK31/33/设置）中桌面只有设置一级 |
| 8 | **`lightweightLiquidGlass` 与 DetailScreen 的玻璃 chip 不做** | DetailScreen 玻璃位依赖上游页面细节，属消费面而非管线；管线落地后随时可接 |

## 3. 交付物（`ui/glass/`）

- `GlassShaders.kt`——三个 SkSL 字符串逐字搬运（注明来源与 uniform 表）
- `LayerBackdrop.kt`——`LayerBackdrop`（GraphicsLayer + root 偏移）、`rememberAppBackdrop`、`LocalAppBackdrop`、`Modifier.layerBackdrop`（record-then-blit）
- `GlassEffects.kt`——效果链构建：饱和 ColorMatrix → `makeBlur` → `makeRuntimeShader`（uniform 全表、0.33 换算、cornerRadii 从 `RoundedCornerShape` 提取并钳制）；`RuntimeEffect` 进程级缓存
- `LiquidGlass.kt`——`LocalLiquidGlassEnabled`、上游 Apple 匹配常量（VIBRANCY=1、BLUR_RADIUS_DP=8、LENS_*、SURFACE_OPACITY=0.4、GLASS_RESOLUTION_SCALE=0.33）、`Modifier.liquidGlass(shape)`（采样→滤镜→高光描边→阴影→tint→内容五步）、`isGlassSupported()`
- `NowPlayingScreen`：播放器内容根挂 `layerBackdrop`，底部控制簇包 `liquidGlass` 玻璃位（竖版；横版跟随其布局另接）
- `AppSettings`：`liquidGlass` 键（reduceDynamicBlur 已有）；`SettingsDialog` 外观组两行开关；`Main.kt` 提供两个 CompositionLocal
- `desktopStrings`：`liquid_glass_subtitle_desktop` 等（en/zh）

## 4. 关键技术点与风险

| 风险 | 对策 | 验证手段 |
|---|---|---|
| `makeRuntimeShader` 的 content 采样坐标空间与 uniform 换算错位（0.33 缩放双层坐标） | 折射 uniform 全部用**采样层像素**（即乘 0.33 后的 size/corner），与上游 `BackdropEffectScope.update` 一致；offset=(0,0)（桌面不做 blur 外扩 padding） | 玻璃位截图：折射带应出现在圆角边缘、向内弯曲 |
| `GraphicsLayer.record` 的 scale 次序（先 scale 后 translate 的仿射叠加） | 采样块内 `scale(0.33, pivot=Zero)` 后 `translate(delta)`，采样区数学在注释里写死；单测驱动一个 fake backdrop 层核对采样偏移 | 单测 + 截图 |
| 高光描边需要 skia Paint（shader + MaskFilter） | `drawIntoCanvas` + `nativeCanvas` 扩展（已验证存在），skia `Paint.shader = highlightShader`、`mode=Stroke`、`maskFilter=blur`；RuntimeShaderBuilder.makeShader(uniforms) 产 Shader | 截图：45° 高光渐变可见 |
| SkSL 编译失败静默 | `isGlassSupported()` 启动时 try-compile 三个 shader，失败即降级为实底路径并 TrackLog | 单测：三 shader makeForShader 不抛 |
| 播放器已有 Haze hazeSource 层与玻璃录制层叠加的成本 | 录制只在 `liquidGlass` 开启时挂载（上游 `glassSamplesBackdrop` 同语义）；0.33 采样 + 层缓存把每帧成本压平 | 探针截图帧率无肉眼卡顿 |
| reduceDynamicBlur 回退路径与玻璃互斥语义 | 上游语义照抄：reduce-blur 时玻璃 modifier 直接画实底 + 发丝边，录制器停挂 | 开关切换截图两态 |

## 5. 验证计划

1. **行为断言**（自动化）：三 shader 编译不抛；cornerRadii 提取/钳制；效果链 uniform 换算（0.33）；饱和矩阵数值；`isGlassSupported` 缓存
2. **视觉判读**（截图，`.shots/slice17/`）：播放器底部控制簇玻璃开启/关闭两态 × 深浅色；reduceDynamicBlur 开启的实底回退态
3. **反向验证**：liquidGlass 默认关——播放器渲染路径与切片 15 记录的截图逐像素一致（探针对照）

## 6. 本切片不做

`GlassNavBar` 折叠条全家（上游 1267 行的 iOS-26 导航条——桌面无浮动 tab 条可挂）；`lightweightLiquidGlass` 与 DetailScreen 玻璃 chip；InnerShadow/Ambient 高光/CombinedBackdrop 等无消费者的效果族；MeshGradient/ArtworkMeshBackdrop 的 palette 管线重写（桌面已用自写量化器落地，另属切片 1 遗产）；`high_performance_mode` 刷新率族。

## 7. 实施记录（2026-10-11 回填）

### 与设计的三处偏离

1. **坐标系教训**：桌面 Compose 的 `GraphicsLayer.record` 以 `IntSize` 为尺寸参数（与安卓按 Density 解读不同），采样块的仿射次序最终落成 `withTransform { scale(0.33, pivot=Zero); translate(delta) }` ——先缩放后平移，采样数学在代码注释里写死（backdrop 像素 `-delta` 映到采样原点）。
2. **`clipOutline` 不存在于 Compose 公共 API**（上游 backdrop 库自带了一份 internal 扩展），桌面用 `clipPath(Path().apply { addOutline(outline) })` 等价实现。
3. **`reduceDynamicBlur` 顺势转为持久键**：它在桌面数据层存在已久、无 UI 也无持久化（切片 16 盘点里"有键无 UI"的四个之一）；本片给了它设置行与落盘，成为玻璃的实底回退门控——上游同键语义。

### 验证结果

- **单元测试全绿**（全套含存量）：新增 `GlassTest`——两个 SkSL 着色器在本机 Skia 编译不抛（即 `isGlassSupported` 的启动门控为真）、饱和 ColorMatrix 行和为 1（灰不动）且红/蓝系数跨 1 分居（色度被拉开）、`glassFilter` 以绘制路径同款调用构建不抛。
- **视觉判读**（`.shots/slice17/`）：竖版播放器（探针强制 980×1450）玻璃**开启/关闭两态**对照——开启态底部控制簇坐在一块圆角玻璃面上：半透明、主题 tint、透视到模糊的封面内容（右下透出封面红色），控件照常可读；关闭态与此前切片的渲染逐像素一致（反向验证通过）。横版（宽窗）布局玻璃位未接（设计第 5 条的范围即竖版），横版玻璃随 LandscapePlayer 布局另片。
- **门控行为**：`bitchord.liquidGlass` 探针覆盖（内存 only、不落盘——theme 探针的同一纪律）；reduce-blur 开启时玻璃 modifier 直接画 surface 实底 + 发丝边。

