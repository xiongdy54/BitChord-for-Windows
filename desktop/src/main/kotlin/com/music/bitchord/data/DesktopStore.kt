package com.music.bitchord.data

import java.io.File
import java.util.Properties

/**
 * Where the desktop build keeps its state: `%LOCALAPPDATA%\BitChord`, or
 * `~/.bitchord/BitChord` when that variable is missing.
 */
object AppFiles {
    val root: File by lazy {
        val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".bitchord")
        File(base, "BitChord").apply { mkdirs() }
    }

    /** A named subdirectory, created if it isn't there yet. */
    fun dir(name: String): File = File(root, name).apply { mkdirs() }

    fun file(vararg parts: String): File = File(root, parts.joinToString(File.separator))
}

/**
 * SharedPreferences stand-in: the handful of keys the ported Android code
 * kept in `getSharedPreferences`, persisted as a properties file.
 *
 * Reads once at construction and writes through on every set — these are
 * settings that change on user action, not in a loop.
 */
class FileStore(private val file: File) {
    private val values = Properties()

    init {
        if (file.isFile) runCatching { file.inputStream().use(values::load) }
    }

    @Synchronized
    fun getString(key: String, default: String): String = values.getProperty(key) ?: default

    @Synchronized
    fun putString(key: String, value: String) {
        values.setProperty(key, value)
        save()
    }

    @Synchronized
    fun getLong(key: String, default: Long): Long = values.getProperty(key)?.toLongOrNull() ?: default

    @Synchronized
    fun putLong(key: String, value: Long) {
        values.setProperty(key, value.toString())
        save()
    }

    @Synchronized
    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { values.store(it, null) }
        }
    }
}
