package com.music.bitchord.data.metadata

import com.music.bitchord.data.AppFiles
import com.music.bitchord.data.DebugLog as Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The local scan's metadata index — what makes a return to the library page
 * cheap.
 *
 * Upstream's scan is instant because MediaStore already holds every tag; a
 * desktop walk has to open each file. Without an index, every page entry paid
 * that cost again (293 files: ~1.8s warm, 9.8s on the verification machine's
 * first full scan). This stores each file's reader answer under its path,
 * validated by the file's own size and modification time — the two facts a
 * filesystem changes whenever the bytes or the tags do. Everything else about
 * the scan is unchanged: the walk, the filters and the row merge still happen
 * on every entry; only files that are new or changed are parsed.
 *
 * A file that could not be parsed is cached too, with its empty answer: a
 * broken tag must not cost a native parse on every visit. The one exception
 * is a machine with no libvlc at all — the session reports itself unavailable
 * and the caller skips the save, so turning VLC on later is not frozen out by
 * a cache full of filename-only rows.
 *
 * The index is disposable. A corrupt file, a schema bump or a failed write is
 * an empty cache, never a failed scan; a deleted entry is pruned by the next
 * save because only the current candidates are written back.
 */
@Serializable
internal data class LocalMediaIndex(
    val version: Int,
    val entries: Map<String, Entry> = emptyMap(),
) {

    @Serializable
    internal data class Entry(
        val size: Long,
        val modifiedMs: Long,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val durationMs: Long? = null,
    ) {
        fun toInfo(): LocalMediaMeta.Info = LocalMediaMeta.Info(title, artist, album, durationMs)
    }

    companion object {
        /** Bumped when [Entry]'s meaning changes; an older file reads as empty. */
        internal const val VERSION = 1

        private const val FILE_NAME = "local_music_index.json"
        private const val TAG = "BitChord"

        private val json = Json { ignoreUnknownKeys = true }

        fun load(): Map<String, Entry> = loadFrom(AppFiles.file(FILE_NAME))

        fun save(entries: Map<String, Entry>) = saveTo(AppFiles.file(FILE_NAME), entries)

        /** Split from [load] so the round trip can be tested over a temp file. */
        internal fun loadFrom(file: File): Map<String, Entry> {
            if (!file.isFile) return emptyMap()
            return runCatching {
                val parsed = json.decodeFromString<LocalMediaIndex>(file.readText())
                if (parsed.version == VERSION) parsed.entries else emptyMap()
            }.onFailure {
                Log.d(TAG, "local index unreadable, scanning cold: ${it.message}")
            }.getOrDefault(emptyMap())
        }

        internal fun saveTo(file: File, entries: Map<String, Entry>) {
            runCatching {
                val target = file.absoluteFile
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, "${target.name}.tmp")
                temp.writeText(json.encodeToString(LocalMediaIndex(VERSION, entries)))
                try {
                    Files.move(
                        temp.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: Exception) {
                    // A filesystem without atomic moves still gets the index.
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }.onFailure {
                Log.d(TAG, "local index not saved: ${it.message}")
            }
        }
    }
}
