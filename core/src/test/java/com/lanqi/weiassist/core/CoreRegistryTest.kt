package com.lanqi.weiassist.core

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreRegistryTest {
    private class FakeCore(id: String) : AgentCore {
        override val info = CoreInfo(id, id.uppercase())
        override val events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 8)
        var host: ToolHost? = null
        override fun bind(host: ToolHost) { this.host = host }
        override suspend fun start() = JSONObject()
        override suspend fun configure(session: String, config: SessionConfig) {}
        override suspend fun prompt(session: String, input: UserInput) {}
        override suspend fun steer(session: String, input: UserInput) {}
        override suspend fun abort(session: String) {}
        override suspend fun dispose(session: String) {}
        override fun destroy() {}
    }

    @Test fun `finds cores by id and uses the default for new sessions`() {
        val pi = FakeCore("pi")
        val dsh = FakeCore("dsh")
        val r = CoreRegistry(listOf(pi, dsh), defaultId = "pi")
        assertSame(dsh, r["dsh"])
        assertNull(r["gone"])
        assertSame(pi, r.default)
        r.defaultId = "dsh"
        assertSame(dsh, r.default)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses an unknown default`() { CoreRegistry(listOf(FakeCore("pi")), defaultId = "dsh") }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses two cores with one id`() { CoreRegistry(listOf(FakeCore("pi"), FakeCore("pi"))) }

    @Test fun `core ids are safe file names and don't clash with the store's own files`() {
        listOf("Pi", "", "pi-2", "../x", "meta", "chat", "a".repeat(17)).forEach { id ->
            assertTrue(id, runCatching { CoreInfo(id, "x") }.isFailure)
        }
        CoreInfo("dsh2", "x")
    }

    @Test fun `every core gets the one tool host`() {
        val a = FakeCore("pi")
        val b = FakeCore("dsh")
        val host = ToolHost()
        CoreRegistry(listOf(a, b)).bind(host)
        assertSame(host, a.host)
        assertSame(host, b.host)
    }

    @Test fun `events are tagged with their core`() = runBlocking {
        val a = FakeCore("pi")
        val b = FakeCore("dsh")
        val r = CoreRegistry(listOf(a, b))
        val got = async { r.events.take(2).toList() }
        // Let the collector subscribe to both cores before they emit.
        repeat(5) { yield() }
        a.events.emit(JSONObject().put("type", "idle"))
        b.events.emit(JSONObject().put("type", "error"))
        val events = got.await().associate { it.core to it.message.getString("type") }
        assertEquals(mapOf("pi" to "idle", "dsh" to "error"), events)
    }

    @Test fun `tool host answers registered calls only, once per type`() = runBlocking {
        val host = ToolHost()
        host.register("before_tool") { m -> m.getString("session") == "s1" }
        assertTrue(host.handles("before_tool"))
        assertFalse(host.handles("phone"))
        assertEquals(true, host.call("before_tool", JSONObject().put("session", "s1")))
        assertTrue(runCatching { host.register("before_tool") { true } }.isFailure)
        assertTrue(runCatching { host.call("phone", JSONObject()) }.isFailure)
    }
}
