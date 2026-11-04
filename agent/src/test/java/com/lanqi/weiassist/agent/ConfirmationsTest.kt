package com.lanqi.weiassist.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationsTest {
    @Test fun firstAnswerWinsAndTheOtherQuestionIsWithdrawn() = runBlocking {
        val withdrawn = CompletableDeferred<Unit>()
        val ok = Confirmations.first(listOf(
            { try { awaitCancellation() } finally { withdrawn.complete(Unit) } },
            { delay(20); true },
        ))
        assertTrue(ok)
        assertTrue(withdrawn.isCompleted)
    }

    @Test fun anAskThatCannotAskOrFailsLeavesItToTheOthers() = runBlocking {
        assertFalse(Confirmations.first(listOf({ null }, { error("network") }, { delay(20); false })))
        assertTrue(Confirmations.first(listOf({ null }, { delay(20); true })))
    }

    @Test fun nobodyToAnswerOrNoAnswerInTimeIsNo() = runBlocking {
        assertFalse(Confirmations.first(emptyList()))
        assertFalse(Confirmations.first(listOf({ null }, { null })))
        assertFalse(Confirmations.first(listOf({ awaitCancellation() }), timeoutMs = 50))
    }

    @Test fun payments() {
        listOf("Pay", "Buy now", "Place order", "立即支付", "提交订单", "转账").forEach { assertTrue(it, Confirmations.isPayment(it)) }
        listOf("Send", "Delete", "发送", "Payment methods and a long label here", "").forEach { assertFalse(it, Confirmations.isPayment(it)) }
        assertEquals(false, Confirmations.isPayment("Reply"))
    }
}
