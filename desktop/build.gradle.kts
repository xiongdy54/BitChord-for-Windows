import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10"
    id("org.jetbrains.compose") version "1.12.1"
}

kotlin {
    // Matches innertubex-desktop's own toolchain expectations.
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    // material3 has its own version line (1.9.x); the plugin alias picks the
    // one that matches this Compose release.
    @Suppress("DEPRECATION")
    implementation(compose.material3)
    // Compose Desktop gets Dispatchers.Main from the Swing event loop.
    // Kept at 1.11.0 to line up with the coroutines-core that
    // innertubex-desktop brings in transitively.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")

    // ---- YouTube Music data layer: same versions as the Android app ----
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("io.ktor:ktor-client-core:3.5.2")
    implementation("io.ktor:ktor-client-okhttp:3.5.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
    // JitPack serves no Gradle module metadata for the KMP root coordinate
    // (.module is a 404), so the desktop variant has to be named explicitly —
    // the same reason the Android app depends on innertubex-android.
    implementation("com.github.MetrolistGroup.innertubex:innertubex-desktop:v0.7.2")

    // ---- Stream extraction: NewPipe solves signatures and n-parameter throttling ----
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
    implementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("com.google.protobuf:protobuf-javalite:4.35.0")
    implementation("org.mozilla:rhino:1.8.1")
    implementation("org.mozilla:rhino-engine:1.8.1")

    // ---- Artwork ----
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")

    // ---- Playback: libvlc, needs VLC installed on the machine ----
    implementation("uk.co.caprica:vlcj:4.12.1")
    // vlcj logs through SLF4J; without a provider its warnings — including the
    // ones that explain a refused stream — go nowhere.
    implementation("org.slf4j:slf4j-simple:2.0.17")
}

// Headless smoke test for the ported data layer — see Probe.kt.
tasks.register<JavaExec>("probe") {
    group = "verification"
    description = "Browse and search YouTube Music from the command line."
    mainClass.set("com.music.bitchord.desktop.ProbeKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    (project.findProperty("probeQuery") as String?)?.let { args(it) }
    if (project.findProperty("probePlay") == "true") jvmArgs("-Dbitchord.probePlay=true")
}

compose.desktop {
    application {
        mainClass = "com.music.bitchord.desktop.MainKt"
        // Without this the JVM prints the console's codepage and the Chinese
        // in the log comes out as mojibake once redirected to a file.
        jvmArgs += listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
        // Debug hooks — see Main.kt for what they do and how to use them.
        listOf("bitchord.probeQuery", "bitchord.probeAutoplay", "bitchord.autoExitMs").forEach { key ->
            (project.findProperty(key) as String?)?.let { jvmArgs += "-D$key=$it" }
        }
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "BitChord"
            packageVersion = "0.1.0"
            description = "BitChord for Windows"
        }
    }
}
