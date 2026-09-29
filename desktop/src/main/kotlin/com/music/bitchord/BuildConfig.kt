package com.music.bitchord

/**
 * The fields of Android's generated BuildConfig that the ported code reads.
 *
 * Desktop has no build flavors, so [FLAVOR] is the release one — the dev-flavor
 * branches in the ported bars (which draw a "DEV" marker) stay compiled but
 * never fire.
 */
object BuildConfig {
    const val FLAVOR = "prod"
    const val VERSION_NAME = "0.1.0-desktop"
}
