package com.lanqi.weiassist.phone

/**
 * A screenshot for the model, with the sizes it needs to tap what it sees: the screen's (what tap_at takes) and
 * the picture's. A virtual display may be 720 px wide while the phone's own screen is 1080, so a fixed factor
 * sends taps off the screen.
 */
data class Shot(val jpegBase64: String, val screenW: Int, val screenH: Int, val imageW: Int, val imageH: Int) {
    /** As the phone tool's string answer: one header line, then the base64 JPEG. */
    fun pack(): String = "$HEADER ${screenW}x$screenH ${imageW}x$imageH\n$jpegBase64"

    /** What the model reads with the picture. */
    fun caption(): String =
        if (screenW == imageW && screenH == imageH) "Screenshot of the whole screen, ${imageW}x$imageH px: tap_at takes these pixels as they are."
        else "Screenshot scaled to ${imageW}x$imageH px; the screen is ${screenW}x$screenH. For tap_at, multiply x and y by ${factor()}."

    private fun factor(): String = (screenW.toDouble() / imageW).let { f -> "%.3f".format(java.util.Locale.ROOT, f).trimEnd('0').trimEnd('.') }

    companion object {
        private const val HEADER = "@shot"

        /** [maxWidth] scales the picture down, never up. */
        fun scaled(screenW: Int, screenH: Int, maxWidth: Int): Pair<Int, Int> {
            val scale = minOf(1f, maxWidth.toFloat() / screenW)
            return (screenW * scale).toInt() to (screenH * scale).toInt()
        }

        /** A packed shot, or null for anything else (an error or other message for the model). */
        fun parse(s: String): Shot? {
            if (!s.startsWith("$HEADER ")) return null
            val nl = s.indexOf('\n').takeIf { it > 0 } ?: return null
            val parts = s.substring(HEADER.length + 1, nl).split(' ')
            val (sw, sh) = parts.getOrNull(0)?.split('x')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 } ?: return null
            val (iw, ih) = parts.getOrNull(1)?.split('x')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 } ?: return null
            return Shot(s.substring(nl + 1), sw, sh, iw, ih)
        }
    }
}
