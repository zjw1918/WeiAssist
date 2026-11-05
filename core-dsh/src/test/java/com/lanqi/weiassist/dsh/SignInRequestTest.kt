package com.lanqi.weiassist.dsh

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInRequestTest {
    @Test fun `only a Responses request to OpenAI with a sign-in token is a sign-in's`() {
        assertTrue(SignInRequest.applies("https://api.openai.com/v1", "eyJhbGci", "/responses"))
        assertTrue(SignInRequest.applies("https://api.openai.com/v1/", "eyJhbGci", "/responses?x=1"))
        assertFalse(SignInRequest.applies("https://api.openai.com/v1", "sk-proj-1", "/responses"))
        assertFalse(SignInRequest.applies("https://api.deepseek.com", "eyJhbGci", "/responses"))
        assertFalse(SignInRequest.applies("https://api.openai.com/v1", "eyJhbGci", "/models"))
    }

    @Test fun `the thinking level moves from its input items into the request, and what a sign-in rejects goes`() {
        // As pi-ai wrote it on the phone: the request that OpenAI refused.
        val body = """{"model":"gpt-5.5","stream":true,"store":false,"max_output_tokens":32000,"prompt_cache_key":"s1",
            "prompt_cache_retention":"24h","temperature":1,
            "reasoning":{"effort":"medium","summary":"auto"},"include":["reasoning.encrypted_content"],
            "input":[{"type":"additional_tools","role":"developer","tools":[]},{"role":"developer","content":"be brief"},
            {"type":"configuration_update","reasoning":{"effort":"low"}},
            {"role":"user","content":[{"type":"input_text","text":"hi"}]},
            {"type":"configuration_update","reasoning":{"effort":"high"}}]}"""
        val out = JSONObject(String(SignInRequest.adapt(body.toByteArray())))
        val input = out.getJSONArray("input")
        assertEquals(3, input.length())
        for (i in 0 until input.length()) assertFalse(input.getJSONObject(i).optString("type") == "configuration_update")
        assertEquals("additional_tools", input.getJSONObject(0).getString("type"))
        assertEquals("high", out.getJSONObject("reasoning").getString("effort"))
        assertEquals("auto", out.getJSONObject("reasoning").getString("summary"))
        for (k in listOf("max_output_tokens", "temperature", "prompt_cache_retention")) assertFalse(k, out.has(k))
        assertEquals("s1", out.getString("prompt_cache_key"))
        assertEquals("gpt-5.5", out.getString("model"))
    }

    @Test fun `a request without a thinking level keeps its own reasoning`() {
        val out = JSONObject(String(SignInRequest.adapt("""{"reasoning":{"effort":"low"},"input":[]}""".toByteArray())))
        assertEquals("low", out.getJSONObject("reasoning").getString("effort"))
    }

    @Test fun `a body that isn't JSON goes as it came`() {
        val body = "not json".toByteArray()
        assertTrue(body.contentEquals(SignInRequest.adapt(body)))
    }
}
