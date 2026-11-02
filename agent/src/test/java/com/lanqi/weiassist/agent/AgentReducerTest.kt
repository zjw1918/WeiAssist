package com.lanqi.weiassist.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentReducerTest {
    private val base = AgentState(modelId = "gpt-6.1-sol", thinkingLevel = "medium")

    private fun msg(type: String, vararg pairs: Pair<String, Any?>) = JSONObject().put("type", type).apply {
        pairs.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
    }

    private fun model(id: String, vararg levels: String) =
        JSONObject().put("id", id).put("name", id).put("thinkingLevels", JSONArray(levels.toList())).put("contextWindow", 272000).put("input", JSONArray(listOf("text", "image")))

    private fun status(credential: String?, vararg models: JSONObject) = msg(
        "status",
        "providers" to JSONArray().put(
            JSONObject().put("id", "openai").put("name", "OpenAI")
                .put("credential", credential ?: JSONObject.NULL)
                .put("methods", JSONArray().put(JSONObject().put("type", "oauth").put("name", "OpenAI (ChatGPT subscription)").put("label", "Sign in with ChatGPT").put("subscription", true))
                    .put(JSONObject().put("type", "api_key").put("name", "OpenAI API key")))
                .put("models", JSONArray(models.toList())),
        ),
    )

    private fun reduce(s: AgentState, m: JSONObject) = AgentReducer.reduce(s, m)

    @Test fun `signed-out status shows the welcome screen with Pi's two methods`() {
        val s = reduce(base, status(null)).state
        assertEquals(Screen.Welcome, s.screen)
        assertEquals(listOf("oauth", "api_key"), s.provider!!.methods.map { it.type })
        assertTrue(s.provider!!.methods.first().subscription)
    }

    @Test fun `signed-in status goes to the connected screen`() {
        assertEquals(Screen.Connected, reduce(base, status("oauth", model("gpt-6.1-sol", "low", "medium"))).state.screen)
    }

    @Test fun `status does not interrupt a sign-in in progress`() {
        val signingIn = base.copy(screen = Screen.SigningIn(AuthMethod("oauth", "x", null, true)))
        assertTrue(reduce(signingIn, status(null)).state.screen is Screen.SigningIn)
    }

    @Test fun `a model this sign-in cannot use falls back to Pi's default`() {
        val s = reduce(base.copy(modelId = "gpt-5.4-mini"), status("oauth", model("gpt-6-luna", "off", "low"), model("gpt-6.1-sol", "low", "medium"))).state
        assertEquals("gpt-6.1-sol", s.modelId)
    }

    @Test fun `without Pi's default the first model is chosen`() {
        assertEquals("gpt-6-luna", reduce(base.copy(modelId = "nope"), status("oauth", model("gpt-6-luna", "low"))).state.modelId)
    }

    @Test fun `thinking level moves to the nearest supported level at or above it`() {
        val s = reduce(base.copy(modelId = "m", thinkingLevel = "off"), status("oauth", model("m", "low", "medium", "high"))).state
        assertEquals("low", s.thinkingLevel)
        val top = AgentReducer.selectModel(s.copy(thinkingLevel = "max"), "m")
        assertEquals("high", top.thinkingLevel) // nothing at or above "max": the highest the model has
    }

    @Test fun `auth url opens the browser and is remembered for reopening`() {
        val r = reduce(base, msg("auth_event", "event" to JSONObject().put("type", "auth_url").put("url", "https://auth.openai.com/x")))
        assertEquals(Effect.OpenBrowser("https://auth.openai.com/x"), r.effect)
        assertEquals("https://auth.openai.com/x", r.state.signIn.authUrl)
    }

    @Test fun `a prompt appears and closes only when its own id closes`() {
        val open = reduce(base, msg("auth_prompt", "id" to "p1", "prompt" to JSONObject().put("type", "manual_code").put("message", "paste").put("placeholder", "http://127.0.0.1:1455/auth/callback"))).state
        assertEquals("manual_code", open.signIn.prompt!!.type)
        assertEquals(open, reduce(open, msg("auth_prompt_closed", "id" to "p2")).state)
        assertNull(reduce(open, msg("auth_prompt_closed", "id" to "p1")).state.signIn.prompt)
    }

    @Test fun `login done connects and asks for the hello`() {
        val r = reduce(base.copy(screen = Screen.SigningIn(AuthMethod("oauth", "x", null, true))), msg("login_done"))
        assertEquals(Screen.Connected, r.state.screen)
        assertEquals(Effect.SignedIn, r.effect)
    }

    @Test fun `a cancelled login is not shown as an error, a failed one is`() {
        assertNull(reduce(base, msg("login_failed", "message" to "cancelled")).state.signIn.error)
        assertEquals("boom", reduce(base, msg("login_failed", "message" to "boom")).state.signIn.error)
    }

    @Test fun `a runtime restart is passed on to the sessions`() {
        assertEquals(Effect.RuntimeRestarted, reduce(base, msg("runtime_restarted")).effect)
    }
}
