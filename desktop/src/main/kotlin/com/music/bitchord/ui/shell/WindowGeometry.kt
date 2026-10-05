package com.music.bitchord.ui.shell

/**
 * The window's size and maximised state, as the app persists them.
 *
 * The position is deliberately not among them: a restored position can land on
 * a monitor that is no longer there, and without a screen enumeration there is
 * no honest clamp — a window that opens where the user cannot reach it is worse
 * than one that opens where the OS puts it. Size and maximisation carry the
 * session's shape; the OS keeps the address.
 */
data class WindowGeometry(
    val width: Int,
    val height: Int,
    val maximized: Boolean,
) {
    fun encode(): String = "$width,$height,${if (maximized) 1 else 0}"

    companion object {
        /**
         * @return the placement encoded in [raw], or null — an absent, empty or
         *   unparseable value all read as "no remembered placement". Every field
         *   is checked, because a hand-edited or truncated properties file must
         *   not be able to produce a zero-width window.
         */
        fun parseOrNull(raw: String?): WindowGeometry? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(',')
            if (parts.size != 3) return null
            val width = parts[0].trim().toIntOrNull() ?: return null
            val height = parts[1].trim().toIntOrNull() ?: return null
            if (width <= 0 || height <= 0) return null
            val maximized = when (parts[2].trim()) {
                "0" -> false
                "1" -> true
                else -> return null
            }
            return WindowGeometry(width, height, maximized)
        }
    }
}
