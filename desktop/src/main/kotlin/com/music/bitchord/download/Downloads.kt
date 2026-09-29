package com.music.bitchord.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Desktop stand-in for the download manager's public state. Nothing is
 * downloaded yet, so [saved] is empty and the "downloaded" badge a row draws
 * off it never appears — the manager itself belongs to a later slice.
 */
object Downloads {
    private val _saved = MutableStateFlow<Map<String, String>>(emptyMap())
    val saved: StateFlow<Map<String, String>> = _saved
}
