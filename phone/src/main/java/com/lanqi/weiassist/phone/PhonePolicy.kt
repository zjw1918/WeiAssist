package com.lanqi.weiassist.phone

/**
 * The pure rules of the phone tools, the same for every AI core: how a screen is read, what a button or a
 * field is called, which buttons are irreversible. [PhoneTools] applies them. No Android, so all of it is
 * unit-tested (PhoneToolsTest).
 */
object PhonePolicy {
    /** Phone actions in one task; a runaway loop is stopped here. Notes don't count. */
    const val MAX_STEPS = 120

    /**
     * Separates an action's result from the screen it left. A core that prunes old screens from its context
     * looks for it (Pi: pi-host phone-tools.ts `SCREEN_NOW`, which must stay equal).
     */
    const val SCREEN_NOW = "\n\nScreen now:\n"

    const val STEP_LIMIT = "Step limit ($MAX_STEPS) reached. Stop and report what you have done and found so far."
    const val DECLINED = "The user declined this action. Stop and tell the user."

    private val ELEMENT = Regex("""^\[(\d+)]\s+(.*)$""")

    /** A read_screen's elements by id: `[id] Class "text" (description) flags @x,y` lines. */
    fun parseScreen(screen: String): Map<Int, String> = screen.lineSequence()
        .mapNotNull { ELEMENT.find(it) }
        .associate { it.groupValues[1].toInt() to it.groupValues[2] }

    private fun first(line: String, vararg patterns: Regex): String? =
        patterns.firstNotNullOfOrNull { p -> p.find(line)?.groupValues?.get(1)?.ifEmpty { null } }

    private val HINT = Regex("""hint="([^"]*)"""")
    private val TEXT = Regex(""""([^"]*)"""")
    private val DESCRIPTION = Regex("""\(([^)]*)\)""")
    private val VIEW_ID = Regex("""id=(\S+)""")

    /** A readable name for an input field from its read_screen line: hint, text, description or view id. */
    fun fieldName(line: String?): String = line?.let { first(it, HINT, TEXT, DESCRIPTION, VIEW_ID) } ?: "field"

    /** The button's own words from a read_screen line: its text, else its description. */
    fun labelOf(line: String): String = (first(line, TEXT, DESCRIPTION) ?: "").trim()

    // Button labels that mean "irreversible", in English and Chinese UIs (social, mail, shopping, payment).
    private val RISKY = Regex(
        "^(send|post|publish|reply|pay|buy|buy now|checkout|check out|place order|submit order|confirm order|confirm payment|delete|remove|transfer|submit)\\b" +
            "|^(发送|发布|回复|评论|支付|付款|立即支付|确认支付|确认付款|去支付|购买|立即购买|马上抢|下单|提交订单|确认订单|结算|去结算|删除|移除|转账|提交|确认收货)",
        RegexOption.IGNORE_CASE,
    )

    /** Long texts are content (an email subject in a list), not a command, so only short labels count. */
    fun risky(label: String): Boolean = label.isNotEmpty() && label.length <= 20 && RISKY.containsMatchIn(label.trim())
}
