package com.music.bitchord.data.innertube.potoken

/**
 * A mint that failed. Carried as its own type so callers can distinguish
 * "no token this time" (transient — recreate and retry) from everything else.
 */
class PoTokenException(message: String) : Exception(message)
