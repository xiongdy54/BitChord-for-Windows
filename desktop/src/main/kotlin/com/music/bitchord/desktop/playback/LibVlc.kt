package com.music.bitchord.desktop.playback

import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import java.io.File

/**
 * Where libvlc comes from.
 *
 * The app ships its own copy of libVLC — the two core DLLs and the audio-side
 * plugin tree of the official VLC 3.0.23 win64 build, materialised into
 * `desktop/resources/windows-x64/vlc` by the build's `syncLibVlc` task and
 * published into the packaged app as an app resource — so a machine that
 * never installed VLC can play. What the player never asks for (the GUI, the
 * video output chain, transcoding and stream-out, service discovery) is not
 * in the bundle; what remains is the whole audio path the engine exercises:
 * HTTP/file access, the demuxers, the decoder set (Opus, AAC, FLAC, E-AC-3
 * and friends, carried by the avcodec plugin), the audio output and filter
 * chain, and tag reading for the local library.
 *
 * Finding it needs no custom vlcj machinery. vlcj's default discovery checks
 * the directories of the `jna.library.path` system property *before* the
 * registry's installed-VLC paths (vlcj's `JnaLibraryPathDirectoryProvider`
 * sorts at -1, ahead of `WindowsInstallDirectoryProvider`'s -3), and it
 * actually test-loads the library it finds — so a bundle that is present but
 * broken falls through to the installed VLC exactly as if the bundle were
 * absent, and a machine with neither reports the same "no libvlc" as before.
 *
 * [newFactory] is the one door every `MediaPlayerFactory` in the app walks
 * through — the audio engine, the local-metadata parser, the canvas clip
 * decoder — because the discovery runs once per JVM: whichever factory is
 * built first decides where every later one loads from.
 */
object LibVlc {

    /** JNA's own search-path property; vlcj's discovery reads it first. */
    private const val JNA_LIBRARY_PATH = "jna.library.path"

    /**
     * The bundled libVLC directory — the one holding `libvlc.dll` — or null
     * when this machine has no bundle, which leaves vlcj's default discovery
     * to look the old way (registry, PATH: the installed VLC).
     *
     * Resolved once per JVM, lazily: the property write in [newFactory] and
     * the first factory construction see the same answer.
     */
    val bundledDir: File? by lazy { resolveBundledDir() }

    /**
     * Builds a `MediaPlayerFactory` the way [VlcAudioPlayer] and the other
     * libvlc consumers always have, with one difference: when a bundle
     * exists, its directory is seated at the front of `jna.library.path`
     * first, so vlcj's discovery prefers it over the machine's installed
     * VLC. Any value already in the property is kept — appended behind the
     * bundle — because JCEF shares this JVM and JNA belongs to it too.
     */
    fun newFactory(vararg args: String): MediaPlayerFactory {
        bundledDir?.let { bundle ->
            val existing = System.getProperty(JNA_LIBRARY_PATH)
            System.setProperty(
                JNA_LIBRARY_PATH,
                if (existing.isNullOrBlank()) bundle.path
                else "$bundle${File.pathSeparator}$existing",
            )
        }
        return MediaPlayerFactory(*args)
    }

    /** The bundle's candidate locations, best first; the first real one wins. */
    private fun resolveBundledDir(): File? =
        candidates().firstOrNull { it.isBundle() }

    private fun candidates(): List<File> = buildList {
        // The packaged app: the packager copies `resources/windows-x64/` into
        // the directory this property names.
        System.getProperty("compose.application.resources.dir")?.let {
            add(File(it, "vlc"))
        }
        // Dev runs (`run`, `probe`, `test`) have no such property; the bundle
        // is found against the working directory, which is the Gradle project
        // dir — or the repo root, when a launcher started from there.
        val cwd = File(System.getProperty("user.dir"))
        add(File(cwd, "resources/windows-x64/vlc"))
        add(File(cwd, "desktop/resources/windows-x64/vlc"))
    }

    /** A directory that could actually be loaded: both core DLLs present. */
    private fun File.isBundle(): Boolean =
        File(this, "libvlc.dll").isFile && File(this, "libvlccore.dll").isFile
}
