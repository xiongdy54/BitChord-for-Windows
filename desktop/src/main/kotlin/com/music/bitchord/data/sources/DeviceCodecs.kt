package com.music.bitchord.data.sources

/**
 * What this particular machine can actually decode.
 *
 * Upstream asks Android's codec list whether an E-AC-3 (JOC) decoder exists —
 * the codec is licensed, so its decoder ships with the vendor image rather
 * than with the OS, and the app must ask before offering the rendition. The
 * desktop question does not exist: playback goes through libvlc, which carries
 * its own decoder set with every install, E-AC-3 and its JOC extension
 * included. There is no vendor lottery to probe, so the answer is always yes
 * and the [com.music.bitchord.data.settings.AppSettings.dolbyAtmos] switch is
 * the only gate left.
 */
object DeviceCodecs {

    /** Test seam, carried from upstream: a caller can stand in for a lesser decoder set. */
    @Volatile
    internal var forced: Boolean? = null

    /** Whether an E-AC-3 (JOC) stream would find a decoder. Always yes on libvlc. */
    val playsDolbyAtmos: Boolean
        get() = forced ?: true
}
