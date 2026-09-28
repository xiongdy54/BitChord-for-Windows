package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FD8FF),
    background = Color(0xFF08080B),
    surface = Color(0xFF16161C),
    onSurface = Color(0xFFE6E6EA),
)

fun main() {
    val probe = probeEnvironment()
    probe.forEach(::println)
    application {
        Window(
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(width = 1180.dp, height = 780.dp),
            title = "BitChord for Windows",
        ) {
            MaterialTheme(colorScheme = DarkColors) {
                Scaffold(probe)
            }
        }
    }
}

@Composable
private fun Scaffold(lines: List<String>) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text("BitChord for Windows", fontSize = 34.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "Desktop scaffold is up · Compose Multiplatform",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
        Column(
            modifier = Modifier.padding(top = 18.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            lines.forEach {
                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
            }
        }
    }
}

private fun probeEnvironment(): List<String> = buildList {
    add("Java ${System.getProperty("java.version")} · ${System.getProperty("java.vendor")}")
    add("OS ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
    val vlcFound = runCatching { NativeDiscovery().discover() }.getOrDefault(false)
    if (!vlcFound) {
        add("libvlc: not found — playback will be unavailable")
        return@buildList
    }
    System.getProperty("jna.library.path")?.let { add("libvlc: $it") }
    val init = runCatching { MediaPlayerFactory().release() }
    add(
        if (init.isSuccess) "libvlc init: ok"
        else "libvlc init: failed (${init.exceptionOrNull()?.message})"
    )
}
