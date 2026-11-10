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
        if (screenW == imageW && screenH == imageH) "Screenshot of the whole screen, ${imageW}x$imageH px: tap_at and drag take these pixels as they are."
        else "Screenshot scaled to ${imageW}x$imageH px; the screen is ${screenW}x$screenH. For tap_at and drag, multiply x and y by ${factor()}."

    private fun factor(): String = (screenW.toDouble() / imageW).let { f -> "%.3f".format(java.util.Locale.ROOT, f).trimEnd('0').trimEnd('.') }

    companion object {
        private const val HEADER = "@shot"

        /** The model's picture: short side at most this, so a phone held upright stays 720 wide as before. */
        const val MAX_SHORT = 720
        /** And long side at most this: a landscape phone (a game, an editor) gets 1600x720, not 720x324. */
        const val MAX_LONG = 1600

        /**
         * The size of the model's picture of a [screenW] x [screenH] screen: within [MAX_SHORT] and [MAX_LONG] in
         * either orientation (phone, tablet, landscape), scaled down, never up. A fixed width made a landscape
         * screen so small that a game's score couldn't be read.
         */
        fun forModel(screenW: Int, screenH: Int): Pair<Int, Int> =
            fit(screenW, screenH, minOf(MAX_SHORT.toFloat() / minOf(screenW, screenH), MAX_LONG.toFloat() / maxOf(screenW, screenH)))

        /** [maxWidth] wide at most (previews), scaled down, never up. */
        fun scaled(screenW: Int, screenH: Int, maxWidth: Int): Pair<Int, Int> = fit(screenW, screenH, maxWidth.toFloat() / screenW)

        private fun fit(w: Int, h: Int, scale: Float): Pair<Int, Int> =
            minOf(1f, scale).let { s -> (w * s).toInt().coerceAtLeast(1) to (h * s).toInt().coerceAtLeast(1) }

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
