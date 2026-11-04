package com.lanqi.weiassist.agent

import java.security.SecureRandom

/** The pure parts of the gateway's chat handling: commands and pairing codes. Unit-tested (ChatCommandsTest). */
object ChatCommands {
    /** A slash command: [name] lower-case without the slash, [args] what follows it. */
    data class Command(val name: String, val args: String)

    /**
     * "/new", "/stop@MyBot", "/start ABCD2345" as a [Command]; null for ordinary text. A command addressed
     * to another bot ("/stop@OtherBot") is not ours: null as well, so it is just text.
     */
    fun parse(text: String, bot: String?): Command? {
        val t = text.trim()
        val m = COMMAND.find(t) ?: return null
        val target = m.groupValues[2]
        if (target.isNotEmpty() && bot != null && !target.equals(bot, ignoreCase = true)) return null
        return Command(m.groupValues[1].lowercase(), t.substring(m.range.last + 1).trim())
    }

    /** The pairing code in a stranger's message: "/start CODE" (Telegram's deep link sends that), "/pair CODE" or the bare code. */
    fun pairingCode(text: String, bot: String?): String? {
        val c = parse(text, bot)
        val raw = when {
            c == null -> text.trim()
            c.name == "start" || c.name == "pair" -> c.args
            else -> return null
        }
        return raw.uppercase().takeIf { CODE.matches(it) }
    }

    /** A fresh pairing code: [CODE_LENGTH] letters and digits that can't be mistaken for each other. */
    fun newPairingCode(random: SecureRandom = SecureRandom()) =
        String(CharArray(CODE_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })

    /** Case-insensitive, in constant time. */
    fun sameCode(a: String, b: String): Boolean {
        val x = a.uppercase().toByteArray()
        val y = b.uppercase().toByteArray()
        return java.security.MessageDigest.isEqual(x, y)
    }

    private const val CODE_LENGTH = 8
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private val CODE = Regex("^[$ALPHABET]{$CODE_LENGTH}$")
    private val COMMAND = Regex("""^/([A-Za-z0-9_]{1,32})(?:@([A-Za-z0-9_]+))?(?=\s|$)""")
}
