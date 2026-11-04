package com.lanqi.weiassist.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Asking the user to allow a risky action (Send, Pay, Delete…) in several places at once: the overlay
 * card on the phone and, for a conversation from Telegram, buttons in that chat. Pure, so it is
 * unit-tested (ConfirmationsTest).
 */
object Confirmations {
    /**
     * Ask every one of [asks] at the same time; the first real answer wins and the other questions are
     * withdrawn (cancelled). An ask returns null when it can't ask (or failed): then the others decide.
     * Nobody can answer, or no answer within [timeoutMs] (null: wait for ever): not allowed.
     */
    suspend fun first(asks: List<suspend () -> Boolean?>, timeoutMs: Long? = null): Boolean = coroutineScope {
        if (asks.isEmpty()) return@coroutineScope false
        val answers = Channel<Boolean?>(asks.size)
        val jobs = asks.map { ask ->
            launch {
                val a = try { ask() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                answers.send(a)
            }
        }
        try {
            val wait = suspend {
                var left = asks.size
                var answer: Boolean? = null
                while (answer == null && left > 0) { answer = answers.receive(); left-- }
                answer ?: false
            }
            (if (timeoutMs == null) wait() else withTimeoutOrNull(timeoutMs) { wait() }) ?: false
        } finally {
            jobs.forEach { it.cancel() }
        }
    }

    /**
     * A button that pays or orders: allowed only on the phone itself, never from a chat (whoever holds the
     * Telegram account shouldn't be able to spend the user's money with one tap). Same words as pi-host's RISKY.
     */
    fun isPayment(label: String) = label.length <= 20 && PAYMENT.containsMatchIn(label.trim())

    private val PAYMENT = Regex(
        "^(pay|buy|buy now|checkout|check out|place order|submit order|confirm order|confirm payment|transfer)\\b" +
            "|^(支付|付款|立即支付|确认支付|确认付款|去支付|购买|立即购买|马上抢|下单|提交订单|确认订单|结算|去结算|转账)",
        RegexOption.IGNORE_CASE,
    )
}
