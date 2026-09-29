# BitChord 桌面版 · 切片 1 实施计划（视觉基座 + 主页 + 搜索）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 BitChord 安卓版的视觉地基（字体/主题/封面取色/Mesh 渐变/磨砂外壳）、主页与搜索页搬到 Compose Multiplatform 桌面端，观感与交互尽量与原版一致。

**Architecture:** 延续已验证的"复制-适配"模式：把 `app/` 下的 Compose 源码按原包名复制进 `desktop/`，只替换 Android 专有实现（`androidx.palette`、`ColorUtils`、haptics、insets、IME、RenderEffect 玻璃），`app/` 一个字节都不改。本切片走原版的**非玻璃回退路径**（API<31 的真实代码路径）。

**Tech Stack:** Kotlin 2.4.10 / Compose Multiplatform 1.12.1（桌面）/ Haze 1.3.1 / Coil 3.6.3 / CMP compose-resources / JUnit（`kotlin("test")`）。

**Spec:** `docs/superpowers/specs/2026-09-29-desktop-slice1-visual-foundation-design.md`

## Global Constraints

- 分支 `windows-desktop`；**`app/` 零改动**（本机无 Android SDK，无法验证安卓构建）。
- `desktop/` 是独立 Gradle 构建；所有命令需先 `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"`，构建用 `./gradlew -p desktop <task>`。
- 从 `app/` 复制的文件**保持原包名与文件名**，文件头加一行来源注释；改动集中在少量替换处，便于日后与上游对照。
- 内容列宽 **1080dp 居中**（窗口 <1080dp 时等于全宽）；底部浮动 tab 保留，宽度上限沿用原版 `FLOATING_BAR_MAX_WIDTH = 440.dp`。
- 不引入任何 `android.*`/`androidx.*`（`androidx.compose.*` 除外，桌面端由 CMP 提供）。
- 验证必须包含：**截图**（多尺寸/多 DPI、中英双语）+ **几何量核对**（10dp gutter / 150dp 卡 / hero ≤320dp / bar ≤440dp / 列 1080dp）+ **行为断言**（tab 切换、建议、点歌出声、翻页）。
- 每个 Task 结束都提交；提交信息用英文（仓库惯例），正文说明来源文件与改动点。

---

### Task 1: 桌面端测试基座 + 主题与字体

**Files:**
- Modify: `desktop/build.gradle.kts`（测试依赖、字体资源源集）
- Create: `desktop/src/main/kotlin/com/music/bitchord/ui/theme/BitChordTheme.kt`（源自 `app/.../ui/theme/Theme.kt`）
- Create: `desktop/src/test/kotlin/com/music/bitchord/ui/theme/BitChordThemeTest.kt`

**Interfaces:**
- Produces: `BitChordTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)`；`val SFProDisplay: FontFamily`；`val AccentRed: Color`。后续所有页面都套这一层。

- [ ] **Step 1: 加测试基座与字体源集**

`desktop/build.gradle.kts` 的 `dependencies { }` 里加：

```kotlin
    testImplementation(kotlin("test"))
```

文件末尾加：

```kotlin
// 字体资产与安卓版共享同一份文件，避免在仓库里重复存 11MB。
sourceSets["main"].resources.srcDir(rootProject.file("../app/src/main/res/font"))

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 2: 写失败测试**

`desktop/src/test/kotlin/com/music/bitchord/ui/theme/BitChordThemeTest.kt`：

```kotlin
package com.music.bitchord.ui.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BitChordThemeTest {

    /** 五个字重都必须加载成功，缺一个 Compose 会静默回退到最近的字重。 */
    @Test
    fun `sf pro display carries every weight the type scale asks for`() {
        val weights = listOf(FontWeight.W400, FontWeight.W500, FontWeight.W600, FontWeight.W700, FontWeight.W800)
        assertEquals(5, SFProDisplay.fonts.size)
        weights.forEach { weight ->
            assertTrue(SFProDisplay.fonts.any { it.weight == weight }, "missing weight $weight")
        }
    }

    /** 字型表必须整表套上 SF Pro，而不是留几档在默认字体上。 */
    @Test
    fun `every style in the scale uses sf pro`() {
        val typography = bitChordTypography()
        listOf(
            typography.displayLarge, typography.headlineLarge, typography.headlineMedium,
            typography.titleLarge, typography.titleMedium, typography.bodyLarge,
            typography.bodyMedium, typography.labelMedium, typography.labelSmall,
        ).forEach { assertNotNull(it.fontFamily, "style without a family") }
        assertEquals(34.sp, typography.displayLarge.fontSize)
        assertEquals(FontWeight.W800, typography.displayLarge.fontWeight)
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `./gradlew -p desktop test --console=plain --tests "*BitChordThemeTest*"`
Expected: 编译失败（`SFProDisplay` / `bitChordTypography` 未定义）。

- [ ] **Step 4: 移植主题**

从 `app/src/main/java/com/music/bitchord/ui/theme/Theme.kt` 复制，改动：

1. 删除 `SystemBarIcons` / `StatusBarIcons` / `findWindow` 三个函数与 `android.app.Activity`、`WindowCompat`、`LocalView`、`R` 的 import（桌面没有系统状态栏）。
2. 字体改为从 classpath 资源加载（资源源集已在 Step 1 指到 `app/src/main/res/font`，文件在 resources 根下）：

```kotlin
val SFProDisplay = FontFamily(
    Font(resource = "sf_pro_display_regular.otf", weight = FontWeight.W400),
    Font(resource = "sf_pro_display_medium.otf", weight = FontWeight.W500),
    Font(resource = "sf_pro_display_semibold.otf", weight = FontWeight.W600),
    Font(resource = "sf_pro_display_bold.otf", weight = FontWeight.W700),
    Font(resource = "sf_pro_display_heavy.otf", weight = FontWeight.W800),
)
```

3. `private val BitChordTypography` 与 `withFamily` 保持原样；`BitChordTheme` 保持原样；把字型表改成 `internal fun bitChordTypography(): Typography`（内部构造成员私有，供测试断言）。
4. 原 `Theme.kt` 里的 `DarkColors`/`LightColors` 配色**逐字段照抄**。

**若 `androidx.compose.ui.text.platform.Font(resource = …)` 在该 CMP 版本不可用**，退回 classpath 读字节的写法（打包后依然有效）：

```kotlin
private fun bundledFont(name: String, weight: FontWeight) = Font(
    identity = name,
    data = checkNotNull(object {}.javaClass.getResourceAsStream("/$name")) { "missing font $name" }
        .use { it.readBytes() },
    weight = weight,
)
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew -p desktop test --console=plain --tests "*BitChordThemeTest*"`
Expected: `BUILD SUCCESSFUL`，2 个测试通过。

- [ ] **Step 6: 提交**

```bash
git add desktop/build.gradle.kts desktop/src/main/kotlin/com/music/bitchord/ui/theme/BitChordTheme.kt desktop/src/test/kotlin/com/music/bitchord/ui/theme/BitChordThemeTest.kt
git commit -m "feat(desktop): port the theme and load SF Pro from the app's font assets"
```

---

### Task 2: 封面取色管线（替 androidx.palette 与 ColorUtils）

**Files:**
- Create: `desktop/src/main/kotlin/com/music/bitchord/ui/theme/ColorMath.kt`
- Create: `desktop/src/main/kotlin/com/music/bitchord/ui/theme/ArtworkQuantiser.kt`
- Create: `desktop/src/main/kotlin/com/music/bitchord/ui/theme/ArtworkPalette.kt`（源自 `app/.../ui/theme/ArtworkPalette.kt`）
- Test: `desktop/src/test/kotlin/com/music/bitchord/ui/theme/ColorMathTest.kt`、`ArtworkQuantiserTest.kt`

**Interfaces:**
- Produces: `internal fun colorToHsl(argb: Int): FloatArray`、`internal fun hslToColor(hsl: FloatArray): Color`、`internal fun relativeLuminance(argb: Int): Float`、`internal fun averageRelativeLuminance(pixels: IntArray): Float`、`internal fun adaptedArtworkSaturation(source: Float, minimum: Float, maximum: Float): Float`
- Produces: `internal data class Swatch(val rgb: Int, val population: Int)`、`internal data class ArtworkSeed(val dominant: Color, val vibrant: Color, val edge: Color, val topBandLuminance: Float)`、`internal fun seedOf(pixels: IntArray, width: Int, height: Int): ArtworkSeed?`
- Produces: `@Composable fun rememberArtworkPalette(imageUrl: String?, dark: Boolean = …, artPx: Int = CARD_ART_PX): ArtworkPalette`、`@Composable fun rememberArtworkTopBandLuminance(imageUrl: String?, artPx: Int = CARD_ART_PX): Float?`、`data class ArtworkPalette(...)` 七字段同原版。

- [ ] **Step 1: 写 ColorMath 的失败测试**

```kotlin
package com.music.bitchord.ui.theme

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorMathTest {

    @Test
    fun `hsl round trip keeps a colour`() {
        listOf(0xFF3B7DD8.toInt(), 0xFFD84F3B.toInt(), 0xFF101010, 0xFFF2F2F7.toInt()).forEach { argb ->
            val back = hslToColor(colorToHsl(argb))
            assertTrue(abs((back.value shr 16 and 0xFF) - (argb shr 16 and 0xFF)) <= 1, "red drifted for $argb")
            assertTrue(abs((back.value shr 8 and 0xFF) - (argb shr 8 and 0xFF)) <= 1, "green drifted for $argb")
            assertTrue(abs((back.value and 0xFF) - (argb and 0xFF)) <= 1, "blue drifted for $argb")
        }
    }

    /** WCAG 相对亮度：白 1.0、黑 0.0、中灰约 0.2159。 */
    @Test
    fun `relative luminance matches the wcag reference values`() {
        assertTrue(abs(relativeLuminance(0xFFFFFFFF.toInt()) - 1f) < 0.001f)
        assertTrue(abs(relativeLuminance(0xFF000000.toInt()) - 0f) < 0.001f)
        assertTrue(abs(relativeLuminance(0xFF808080.toInt()) - 0.2159f) < 0.01f)
    }

    /** 中性色不能被"提饱和"改造成红色——原版注释里踩过的坑。 */
    @Test
    fun `neutral stays neutral`() {
        assertEquals(0f, adaptedArtworkSaturation(0f, minimum = 0.20f, maximum = 0.62f))
        assertEquals(0.1f, adaptedArtworkSaturation(0.1f, minimum = 0.20f, maximum = 0.62f))
        assertEquals(0.20f, adaptedArtworkSaturation(0.30f, minimum = 0.20f, maximum = 0.62f))
        assertEquals(0.62f, adaptedArtworkSaturation(0.90f, minimum = 0.20f, maximum = 0.62f))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew -p desktop test --console=plain --tests "*ColorMathTest*"`
Expected: 编译失败（`colorToHsl` 等未定义）。

- [ ] **Step 3: 实现 ColorMath**

```kotlin
package com.music.bitchord.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/** `androidx.core.graphics.ColorUtils.colorToHSL` 的桌面等价物（H 0..360, S/L 0..1）。 */
internal fun colorToHsl(argb: Int): FloatArray {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val lightness = (max + min) / 2f
    val delta = max - min
    if (delta == 0f) return floatArrayOf(0f, 0f, lightness)
    val saturation = if (lightness > 0.5f) delta / (2f - max - min) else delta / (max + min)
    val hue = when (max) {
        r -> ((g - b) / delta + if (g < b) 6f else 0f)
        g -> (b - r) / delta + 2f
        else -> (r - g) / delta + 4f
    } * 60f
    return floatArrayOf(hue, saturation, lightness)
}

/** `ColorUtils.HSLToColor` 的桌面等价物（同样的标准 HSL→RGB）。 */
internal fun hslToColor(hsl: FloatArray): Color {
    val h = hsl[0] / 360f
    val s = hsl[1].coerceIn(0f, 1f)
    val l = hsl[2].coerceIn(0f, 1f)
    if (s == 0f) return Color(l, l, l)
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    fun channel(t: Float): Float {
        var x = t
        if (x < 0f) x += 1f
        if (x > 1f) x -= 1f
        return when {
            x < 1f / 6f -> p + (q - p) * 6f * x
            x < 1f / 2f -> q
            x < 2f / 3f -> p + (q - p) * (2f / 3f - x) * 6f
            else -> p
        }
    }
    return Color(channel(h + 1f / 3f), channel(h), channel(h - 1f / 3f))
}

/** WCAG 相对亮度：在线性光下平均，绝不用 gamma 编码后的 RGB。 */
internal fun relativeLuminance(argb: Int): Float {
    fun linear(channel: Int): Float {
        val srgb = channel / 255f
        return if (srgb <= 0.04045f) srgb / 12.92f else ((srgb + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }
    return 0.2126f * linear(argb shr 16 and 0xFF) +
        0.7152f * linear(argb shr 8 and 0xFF) +
        0.0722f * linear(argb and 0xFF)
}

internal fun averageRelativeLuminance(pixels: IntArray): Float {
    if (pixels.isEmpty()) return 0f
    return pixels.sumOf { relativeLuminance(it).toDouble() }.div(pixels.size).toFloat()
}

/**
 * 中性色不因"提饱和"被改造成红色：低于阈值时原样保留，高于阈值才夹到区间内。
 * 与原版 `adaptedArtworkSaturation` 语义一致。
 */
internal fun adaptedArtworkSaturation(source: Float, minimum: Float, maximum: Float): Float {
    val saturation = source.coerceIn(0f, 1f)
    return if (saturation < CHROMATIC_SATURATION_THRESHOLD) {
        saturation
    } else {
        saturation.coerceIn(minimum, maximum)
    }
}

/** 低于此值，提饱和会把量化噪声显成色偏。 */
private const val CHROMATIC_SATURATION_THRESHOLD = 0.12f
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew -p desktop test --console=plain --tests "*ColorMathTest*"`
Expected: PASS（3 个测试）。

- [ ] **Step 5: 写量化器的失败测试**

```kotlin
package com.music.bitchord.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtworkQuantiserTest {

    private fun solid(width: Int, height: Int, argb: Int) = IntArray(width * height) { argb }

    /** 七成红三成蓝：dominant 必须是红（按像素数），vibrant 也来自红。 */
    @Test
    fun `dominant follows population, not saturation`() {
        val pixels = IntArray(100).also { px ->
            for (i in 0 until 70) px[i] = 0xFFC02020.toInt()
            for (i in 70 until 100) px[i] = 0xFF2030FF.toInt()
        }
        val seed = seedOf(pixels, width = 10, height = 10)!!
        assertTrue(seed.dominant.red > seed.dominant.blue, "dominant should be the red majority")
    }

    /** 单色图的 edge 就是它自己的颜色（底部 18% 的均值）。 */
    @Test
    fun `edge colour averages the bottom band`() {
        val pixels = solid(20, 20, 0xFF445566.toInt())
        val seed = seedOf(pixels, width = 20, height = 20)!!
        listOf(seed.edge.red, seed.edge.green, seed.edge.blue).forEach {
            assertTrue(it > 0.20f && it < 0.45f, "unexpected edge component $it")
        }
    }

    /** 顶带亮度：全白图接近 1，全黑图接近 0。 */
    @Test
    fun `top band luminance separates light from dark artwork`() {
        assertTrue(seedOf(solid(20, 20, 0xFFFFFFFF.toInt()), 20, 20)!!.topBandLuminance > 0.95f)
        assertTrue(seedOf(solid(20, 20, 0xFF000000.toInt()), 20, 20)!!.topBandLuminance < 0.05f)
    }

    /** 全黑图不能返回 null——原版允许它成为 palette（页面用深色变体）。 */
    @Test
    fun `a black sleeve still yields a seed`() {
        val seed = seedOf(solid(8, 8, 0xFF000000.toInt()), 8, 8)
        assertTrue(seed != null)
    }
}
```

- [ ] **Step 6: 运行确认失败**

Run: `./gradlew -p desktop test --console=plain --tests "*ArtworkQuantiserTest*"`
Expected: 编译失败（`seedOf` 未定义）。

- [ ] **Step 7: 实现量化器**

`ArtworkQuantiser.kt`（`androidx.palette` 的中位切分等价物，产出带人口数的色块）：

```kotlin
package com.music.bitchord.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.sqrt

private const val SWATCH_COUNT = 24

/** 底部 18%：一次足够宽的模糊会把它平均掉的那条带。 */
private const val EDGE_BAND = 0.18f

/** 顶部 10%：手机状态栏图标压住的那条带。 */
private const val TOP_BAND = 0.10f

internal data class Swatch(val rgb: Int, val population: Int)

internal data class ArtworkSeed(
    val dominant: Color,
    val vibrant: Color,
    val edge: Color,
    val topBandLuminance: Float,
)

/** 中位切分：反复把人口最多、通道跨度最大的箱子按该通道中位数切两半。 */
internal fun medianCutSwatches(pixels: IntArray, maxColors: Int): List<Swatch> {
    if (pixels.isEmpty()) return emptyList()
    var boxes: List<List<Int>> = listOf(pixels.toList())
    while (boxes.size < maxColors) {
        val box = boxes.maxByOrNull { it.size } ?: break
        if (box.size < 2) break
        var channel = 0
        var widest = -1
        for (c in 0..2) {
            val shift = 16 - c * 8
            var min = 255
            var max = 0
            for (pixel in box) {
                val v = pixel shr shift and 0xFF
                if (v < min) min = v
                if (v > max) max = v
            }
            if (max - min > widest) {
                widest = max - min
                channel = c
            }
        }
        if (widest <= 0) break
        val shift = 16 - channel * 8
        val sorted = box.sortedBy { it shr shift and 0xFF }
        val mid = sorted.size / 2
        boxes = boxes.filterNot { it === box } + listOf(sorted.subList(0, mid), sorted.subList(mid, sorted.size))
    }
    return boxes.filter { it.isNotEmpty() }.map { box ->
        var r = 0L; var g = 0L; var b = 0L
        for (pixel in box) {
            r += pixel shr 16 and 0xFF
            g += pixel shr 8 and 0xFF
            b += pixel and 0xFF
        }
        val n = box.size
        Swatch(
            rgb = (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt(),
            population = n,
        )
    }
}

/**
 * 原版用两组 androidx Palette（清过滤 / 默认过滤）取 dominant 与 vibrant；
 * 这里一次中位切分 + 两组筛选，判据与原版一致：dominant 按人口，
 * vibrant 按 `饱和度 × √人口`。
 */
internal fun seedOf(pixels: IntArray, width: Int, height: Int): ArtworkSeed? {
    val swatches = medianCutSwatches(pixels, SWATCH_COUNT)
    if (swatches.isEmpty()) return null
    // 默认过滤的近似：丢掉近黑、近白与过灰的色块，只留"像颜色"的候选。
    val accentCandidates = swatches.filter { swatch ->
        val hsl = colorToHsl(swatch.rgb)
        hsl[2] in 0.12f..0.92f && hsl[1] >= CHROMATIC_SATURATION_THRESHOLD
    }.ifEmpty { swatches }
    val dominant = swatches.maxBy { it.population }
    val vibrant = accentCandidates.maxBy { colorToHsl(it.rgb)[1] * sqrt(it.population.toFloat()) }
    return ArtworkSeed(
        dominant = Color(dominant.rgb),
        vibrant = Color(vibrant.rgb),
        edge = bottomEdgeColor(pixels, width, height),
        topBandLuminance = topBandRelativeLuminance(pixels, width, height),
    )
}

/** 底部带均值：够宽的模糊留下的就是均值，不是某个"重要"色块。 */
internal fun bottomEdgeColor(pixels: IntArray, width: Int, height: Int): Color {
    val band = (height * EDGE_BAND).toInt().coerceIn(1, height)
    var r = 0L; var g = 0L; var b = 0L
    var count = 0
    for (y in (height - band) until height) {
        for (x in 0 until width) {
            val pixel = pixels[y * width + x]
            r += pixel shr 16 and 0xFF
            g += pixel shr 8 and 0xFF
            b += pixel and 0xFF
            count++
        }
    }
    if (count == 0) return Color.Black
    return Color((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
}

internal fun topBandRelativeLuminance(pixels: IntArray, width: Int, height: Int): Float {
    val band = (height * TOP_BAND).toInt().coerceIn(1, height)
    val slice = IntArray(width * band)
    for (y in 0 until band) {
        for (x in 0 until width) slice[y * width + x] = pixels[y * width + x]
    }
    return averageRelativeLuminance(slice)
}
```

签名固定为：`internal fun seedOf(pixels: IntArray, width: Int, height: Int): ArtworkSeed?`。

- [ ] **Step 8: 运行确认通过**

Run: `./gradlew -p desktop test --console=plain --tests "*ArtworkQuantiserTest*"`
Expected: PASS（4 个测试）。

- [ ] **Step 9: 移植 ArtworkPalette.kt**

从 `app/.../ui/theme/ArtworkPalette.kt` 复制，改动只有三处：

1. 像素来源：`rememberArtworkSeed` 里 Coil 结果改为 Skia 位图取像素：

```kotlin
val result = SingletonImageLoader.get(context).execute(request)
val image = (result as? SuccessResult)?.image ?: return@LaunchedEffect
val bitmap = image.asSkiaBitmap()
val width = bitmap.width
val height = bitmap.height
val pixels = IntArray(width * height)
bitmap.readPixels(pixels, 0, 0, width, height)
val found = withContext(Dispatchers.Default) { seedOf(pixels, width, height) } ?: return@LaunchedEffect
```

2. `LocalContext.current` → `PlatformContext.INSTANCE`（coil3 桌面），`SingletonImageLoader.get(context)` 保持。
3. `collectAsStateWithLifecycle()` → `collectAsState()`；`ColorUtils.calculateLuminance` → Compose 的 `Color.luminance()`；`withHsl` 用 Step 3 的 `colorToHsl`/`hslToColor`。
4. `internal fun topBandScrimAlpha`、`relativeLuminance`、`averageRelativeLuminance`、`adaptedArtworkSaturation` 已在 ColorMath.kt 定义——原文件里的副本删除，改为同包直接引用。

- [ ] **Step 10: 编译 + 提交**

Run: `./gradlew -p desktop compileKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`。

```bash
git add desktop/src/main/kotlin/com/music/bitchord/ui/theme desktop/src/test/kotlin/com/music/bitchord/ui/theme
git commit -m "feat(desktop): replace androidx.palette and ColorUtils with a desktop quantiser"
```

---

### Task 3: 字符串资源管线（复用原版 strings.xml）

**Files:**
- Modify: `desktop/build.gradle.kts`
- Create: `desktop/src/main/kotlin/com/music/bitchord/desktop/Probe.kt`（加一个打印中文字符串的模式）

**Interfaces:**
- Produces: 生成的 `Res.string.*` 访问器（包名 `com.music.bitchord.desktop.resources`），后续页面用 `stringResource(Res.string.xxx)` 取文案。

- [ ] **Step 1: 接资源管线**

`desktop/build.gradle.kts` 追加：

```kotlin
// 只搬运 strings.xml：app/src/main/res 里还有 layout/、mipmap-*、values-v31/
// 这些 CMP 资源插件不认的类型，直接指过去会解析失败。
val syncAppStrings by tasks.registering(Copy::class) {
    from(rootProject.file("../app/src/main/res")) {
        include("values/strings.xml")
        include("values-zh/strings.xml")
    }
    into(layout.buildDirectory.dir("bcComposeResources"))
}

compose.resources {
    customDirectory("composeResources", layout.buildDirectory.dir("bcComposeResources"))
    packageOfResClass = "com.music.bitchord.desktop.resources"
    generateResClass = always
}

tasks.matching {
    it.name.startsWith("generateComposeResClass") ||
        it.name.startsWith("generateResourceAccessors") ||
        it.name.startsWith("prepareComposeResources") ||
        it.name.startsWith("convertXmlValueResources") ||
        it.name.startsWith("copyNonXmlValueResources")
}.configureEach { dependsOn(syncAppStrings) }
```

- [ ] **Step 2: 验证访问器生成**

Run: `./gradlew -p desktop generateComposeResClass --console=plain`
Expected: `BUILD SUCCESSFUL`；`desktop/build/generated/compose/resourceGenerator/...` 下能搜到 `shelf_recents` 之类的键名。

- [ ] **Step 3: 写一个用字符串的编译期探针**

在 `Probe.kt` 的 `probe(args)` 开头加：

```kotlin
    println("── strings ───────────────────────────")
    println("field name: " + getString(Res.string.search_hint_or_equivalent))
```

（实现时用 `values/strings.xml` 里真实存在的键名替换；目的是让"资源能取到值"这件事进入编译与运行路径。）

- [ ] **Step 4: 运行确认两种语言都有值**

Run: `./gradlew -p desktop probe --console=plain | head -20`
Expected: 打印出英文文案；把系统 locale 临时设为 zh 的方式（`-Duser.language=zh -Duser.country=CN`）再跑一次，应打印中文文案。若 CMP 资源不支持该 JVM 参数切换，记录实际行为并在 spec 的验证计划里注明"中文以截图为准"。

- [ ] **Step 5: 提交**

```bash
git add desktop/build.gradle.kts desktop/src/main/kotlin/com/music/bitchord/desktop/Probe.kt
git commit -m "feat(desktop): take string resources from the app's own strings.xml"
```

---

### Task 4: 平台 no-op（haptics / insets / IME）与手绘图标

**Files:**
- Create: `desktop/src/main/kotlin/com/music/bitchord/ui/haptics/Haptics.kt`（桌面版）
- Create: `desktop/src/main/kotlin/com/music/bitchord/ui/icons/BitChordIcons.kt`（原样复制）
- Create: `desktop/src/main/kotlin/com/music/bitchord/ui/utils/DesktopInsets.kt`

**Interfaces:**
- Produces: `object Haptic { fun light(); fun medium(); fun heavy() }`、`@Composable fun rememberHaptics(): Haptic`（签名与原版一致，桌面实现为空转）
- Produces: `@Composable fun Modifier.statusBarsPaddingDesktop(): Modifier`（返回 `this`，供从原版复制的文件中把 `statusBarsPadding()` 换名使用）

- [ ] **Step 1: 抄图标与 haptics 接口**

复制 `app/.../ui/icons/BitChordIcons.kt` 原样（纯 `ImageVector`，零 Android 依赖）；haptics 按原版公开签名写桌面空实现，文件头注明"桌面无振动器，空转"。

- [ ] **Step 2: 编译 + 提交**

Run: `./gradlew -p desktop compileKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`。

```bash
git add desktop/src/main/kotlin/com/music/bitchord
git commit -m "feat(desktop): desktop stand-ins for haptics and system insets"
```

---

### Task 5: 外壳（窗口 + tab 状态机 + 磨砂顶栏 + 浮动 tab 栏 + MiniPlayer）

**Files:**
- Copy: `app/.../ui/components/{Common,Skeletons,SearchField,RemoteArtwork,BottomFadeScrim,TopFadeBlur,OptimizedHaze,FrostedTopBar,FloatingBottomBar,MiniPlayer}.kt` → `desktop/src/main/kotlin/com/music/bitchord/ui/components/`
- Copy: `app/.../ui/player/{MeshGradient,ArtworkMeshBackdrop}.kt` → `desktop/src/main/kotlin/com/music/bitchord/ui/player/`
- Create: `desktop/src/main/kotlin/com/music/bitchord/ui/shell/Shell.kt`（新写：1080dp 居中容器 + 4 tab 状态机 + 顶栏/底栏组装）
- Modify: `desktop/src/main/kotlin/com/music/bitchord/desktop/Main.kt`
- Delete: `desktop/src/main/kotlin/com/music/bitchord/desktop/ui/SearchScreen.kt`（被移植版取代）

**Interfaces:**
- Consumes: Task 1 的 `BitChordTheme`；Task 2 的 `rememberArtworkPalette`；Task 4 的 `Haptic`
- Produces: `@Composable fun Shell(state: ShellState, player: PlayerController)`；`class ShellState { var selectedTab: Int; … }`

- [ ] **Step 1: 复制组件并跑编译循环**

```bash
cd "/c/Users/xiongdy/Documents/vibecoding/BitChord for Windows"
mkdir -p desktop/src/main/kotlin/com/music/bitchord/ui/components desktop/src/main/kotlin/com/music/bitchord/ui/player
for f in Common Skeletons SearchField RemoteArtwork BottomFadeScrim TopFadeBlur OptimizedHaze FrostedTopBar FloatingBottomBar MiniPlayer; do
  cp "app/src/main/java/com/music/bitchord/ui/components/$f.kt" desktop/src/main/kotlin/com/music/bitchord/ui/components/
done
cp app/src/main/java/com/music/bitchord/ui/player/MeshGradient.kt app/src/main/java/com/music/bitchord/ui/player/ArtworkMeshBackdrop.kt desktop/src/main/kotlin/com/music/bitchord/ui/player/
```

- [ ] **Step 2: 编译，按报错逐个清 Android 符号**

Run: `./gradlew -p desktop compileKotlin --console=plain 2>&1 | grep "^e: " | sed 's|.*/desktop/src/main/kotlin/com/music/bitchord/||' | sort -u`

逐条处理，已知的类别：
- `statusBarsPadding()`/`navigationBarsPadding()` → `statusBarsPaddingDesktop()`（Task 4）
- `LocalContext` 用于 Coil 请求 → `PlatformContext.INSTANCE`
- `haptics` → Task 4 的空实现
- `SDK_INT` / `Build.VERSION` 判断 → 删除判断，取"玻璃不可用"分支（本切片的非玻璃路径）
- `androidx.palette` / `ColorUtils` / `android.graphics.Bitmap` → Task 2 的替代
- `LocalConfiguration.current.screenHeightDp` → 用窗口高度（`LocalWindowInfo.current.containerSize`）
- `collectAsStateWithLifecycle()` → `collectAsState()`
- `stringResource(R.string.x)` → `stringResource(Res.string.x)`（Task 3）

每修完一类就重跑编译，直到没有 `^e:` 行。

- [ ] **Step 3: 写 Shell**

新文件，把原版 MainActivity 的外壳部分搬过来（：2940-3300 区间的组装顺序）：

```kotlin
@Composable
fun Shell(state: ShellState, player: PlayerController) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            FrostedTopBar(title = state.topBarTitle, scrolled = state.scrolled, onBack = state.onBack)
            Box(Modifier.weight(1f)) {
                AnimatedContent(state.route) { route ->
                    when (route) {
                        Route.Home -> HomeScreen(...)
                        Route.Search -> SearchScreen(...)
                        else -> NotPortedPlaceholder(route)
                    }
                }
            }
        }
        MiniPlayer(...)   // 底部，居中，宽度上限 FLOATING_BAR_MAX_WIDTH
        FloatingBottomBar(selectedTab = state.selectedTab, onSelect = state::select, ...)
    }
}
```

**内容列居中**：在 `Column` 内层加一层

```kotlin
const val CONTENT_MAX_WIDTH = 1080
Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
    Box(Modifier.widthIn(max = CONTENT_MAX_WIDTH.dp).fillMaxWidth()) { /* 页面 */ }
}
```

- [ ] **Step 4: 改 Main.kt 用 Shell，并删掉简版界面**

`Main.kt` 的 `MaterialTheme(colorScheme = DarkColors) { SearchScreen(player, …) }` 换成 `BitChordTheme { Shell(state, player) }`；删除 `desktop/src/main/kotlin/com/music/bitchord/desktop/ui/SearchScreen.kt`。

- [ ] **Step 5: 起窗口验证**

Run: `./gradlew -p desktop run -Pbitchord.autoExitMs=25000 --console=plain`
Expected: 窗口出现，四个 tab 可点，顶栏与底部浮动条有磨砂（非玻璃路径）；用 computer-use 截图确认。

- [ ] **Step 6: 提交**

```bash
git add -A desktop/src
git commit -m "feat(desktop): port the shell — frosted top bar, floating tabs, mini player"
```

---

### Task 6: 主页（HomeViewModel + HomeScreen）

**Files:**
- Copy: `app/.../ui/screens/HomeScreen.kt` → `desktop/src/main/kotlin/com/music/bitchord/ui/screens/HomeScreen.kt`
- Create: `desktop/src/main/kotlin/com/music/bitchord/ui/HomeViewModel.kt`
- Modify: `desktop/src/main/kotlin/com/music/bitchord/data/settings/AppSettings.kt`（补 `reduceAnimation`、`libraryViewType`）

**Interfaces:**
- Consumes: `YtMusicRepository.home()/homeRecentlyPlayed()/homeSupplement()/moreHome()`（已移植）
- Produces: `class HomeViewModel(scope: CoroutineScope) { val state: StateFlow<UiState>; fun loadHome(); fun refresh(); fun loadMore() }`

- [ ] **Step 1: 从原版 MainViewModel 摘出主页所需状态**

原版 `MainViewModel.kt:1409-1560` 是主页的数据流（`loadHome` / `homeRecentlyPlayed` / `homeSupplement` / `moreHome`）。桌面版新建 `HomeViewModel`，只搬这一段与它的 `UiState` 使用方式，去掉 Media3/通知/下载相关引用。仓库调用保持原样，签名不改。

- [ ] **Step 2: 复制 HomeScreen 并清依赖**

复制文件后按 Task 5 Step 2 的同一套替换处理（`stringResource`、insets、haptics、取色），直到编译无错。

- [ ] **Step 3: 接线到 Shell 的 Home 路由，跑起来验证**

Run: `./gradlew -p desktop run -Pbitchord.autoExitMs=40000 --console=plain`
Expected: 主页出现大标题、货架卡片、封面图；未登录时顶部有 SignInBanner；滚动到底加载更多。

- [ ] **Step 4: 截图与几何量核对**

用 computer-use 截图，逐项核对并记录实测值：页面 gutter 10dp、货架卡 150dp、hero 卡 ≤320dp、内容列 1080dp（窗口 1180 时应左右各留 50dp）。

- [ ] **Step 5: 提交**

```bash
git add desktop/src
git commit -m "feat(desktop): port the home feed"
```

---

### Task 7: 搜索页（SearchViewModel + 原版 SearchScreen）

**Files:**
- Copy: `app/.../ui/screens/SearchScreen.kt` → `desktop/src/main/kotlin/com/music/bitchord/ui/screens/SearchScreen.kt`
- Create: `desktop/src/main/kotlin/com/music/bitchord/ui/SearchViewModel.kt`

**Interfaces:**
- Consumes: `YtMusicRepository.searchPage/searchContinuation/searchSuggestions/searchTypeahead`
- Produces: `class SearchViewModel(scope: CoroutineScope) { val state: StateFlow<…>; fun onQueryChange(q: String); fun selectFilter(f: SearchFilter); fun loadMore() }`

- [ ] **Step 1: 复制并清依赖**（同 Task 5 Step 2 的替换清单；重点是 IME `keyboardController.show()` 桌面 no-op、`LocalFocusManager` 保留）
- [ ] **Step 2: 建 SearchViewModel**，搬原版 `MainViewModel.kt:1839-1960` 的搜索段
- [ ] **Step 3: 接线 + 运行验证**

Run: `./gradlew -p desktop run -Pbitchord.probeQuery="周杰伦 晴天" -Pbitchord.autoExitMs=60000 --console=plain`
Expected: 输入即出建议、回车出结果、筛选标签可切、TopResult 卡在首位、点歌出声。

- [ ] **Step 4: 截图 + 提交**

```bash
git add desktop/src
git commit -m "feat(desktop): port search — filters, suggestions, history, results"
```

---

### Task 8: 占位页与路由收口

**Files:**
- Create: `desktop/src/main/kotlin/com/music/bitchord/ui/screens/NotPortedPlaceholder.kt`
- Modify: `desktop/src/main/kotlin/com/music/bitchord/ui/shell/Shell.kt`

- [ ] **Step 1: 写占位页**：居中显示页面名 + "Not ported yet — slice N" 文案，套主题，不假装是成品。
- [ ] **Step 2: 接线** Explore / Library / 详情 → 占位；MiniPlayer 点击保持不响应（切片 2 接全屏播放页）。
- [ ] **Step 3: 运行确认每个 tab 可达且不崩**

Run: `./gradlew -p desktop run -Pbitchord.autoExitMs=40000 --console=plain`
Expected: 四个 tab 逐个点开，未移植的显示占位。

- [ ] **Step 4: 提交**

```bash
git add desktop/src
git commit -m "feat(desktop): placeholder pages for the screens later slices bring"
```

---

### Task 9: 验收（多尺寸/DPI、双语、行为断言）与收口

**Files:**
- Create: `desktop/src/test/kotlin/com/music/bitchord/ui/DesktopLayoutTest.kt`
- Modify: `docs/superpowers/specs/2026-09-29-desktop-slice1-visual-foundation-design.md`（补"实测结果"一节）

- [ ] **Step 1: 写版式常量的回归测试**

```kotlin
class DesktopLayoutTest {
    @Test fun `content column is capped at 1080dp`() {
        assertEquals(1080, CONTENT_MAX_WIDTH)
    }

    @Test fun `floating bar cap matches the app's`() {
        assertEquals(440.dp, FLOATING_BAR_MAX_WIDTH)
        assertEquals(150.dp, SHELF_CARD_WIDTH)
        assertEquals(10.dp, PAGE_GUTTER)
    }

    @Test fun `hero cards stay under their ceiling at any window width`() {
        assertTrue(heroCardWidth(1180.dp) <= 320.dp)
        assertTrue(heroCardWidth(400.dp) < 320.dp)
    }
}
```

- [ ] **Step 2: 跑全部测试**

Run: `./gradlew -p desktop test --console=plain`
Expected: 全部 PASS。

- [ ] **Step 3: 视觉验收（人工判读）**：窗口 1080×720、1440×900、1920×1080 各截一张；DPI 100% 与 150% 各一张；中英各一套。逐张核对：内容列居中留白、卡片尺寸、字体为 SF Pro（笔画特征）、磨砂可见、无 Android 系统栏残留。
- [ ] **Step 4: 行为断言**：tab 切换 4 次不崩；搜索建议出现；点歌出声（VLC 时间轴推进）；货架触底加载更多。
- [ ] **Step 5: 把实测结果写回 spec，提交推送**

```bash
git add -A
git commit -m "test(desktop): verify slice 1 — layout constants, screenshots, playback"
git push origin windows-desktop
```

---

## Self-Review

- **Spec coverage**：spec 的 6 条决策各自落到 Task 1（主题/字体）、2（取色）、3（字符串）、5（版式 C/非玻璃）、5+8（外壳与占位）、1-9（分支与 app 零改动在 Global Constraints）。验证计划三项（截图/几何量/行为）落在 Task 6/7/9。
- **Placeholder scan**：算法部分（ColorMath、量化器、三个版式常量测试）都是可直接粘贴的真实实现；Task 5/6/7 的"搬运+清 Android 符号"步骤给出了确切的复制命令、替换清单与验证命令，被搬运文件本身（如 HomeScreen 926 行）不入计划正文，执行时以 `app/` 源文件为准。
- **Type consistency**：`seedOf(pixels, width, height)`、`ArtworkSeed`、`rememberArtworkPalette`、`CONTENT_MAX_WIDTH`、`ShellState`、`statusBarsPaddingDesktop`、`Res.string.*` 在定义处与引用处一致。
