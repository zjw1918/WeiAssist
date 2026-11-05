package com.lanqi.weiassist.dsh

import org.json.JSONArray
import org.json.JSONObject

/**
 * A Responses request for a ChatGPT sign-in, made fit for it. pi-ai (in dsh) leaves out what a sign-in rejects when it
 * sees one: OpenAI's endpoint with a key that isn't `sk-…`. Through the [KeyProxy] it sees neither (only the proxy and
 * its token), so it writes the request as for an API key. The proxy, which knows both, does what pi-ai would have done:
 * - `configuration_update` input items (the thinking level, kept out of the request to keep the prompt cache) go, and
 *   the last one's effort becomes the request's `reasoning.effort`;
 * - `max_output_tokens`, `temperature`, `prompt_cache_retention` and `prompt_cache_options` go.
 */
internal object SignInRequest {
    private const val OPENAI = "https://api.openai.com/v1"
    private val UNSUPPORTED = listOf("max_output_tokens", "temperature", "prompt_cache_retention", "prompt_cache_options")

    /** pi-ai's own test for a sign-in (isChatGPTSignIn), on the real endpoint and key; only Responses requests. */
    fun applies(upstream: String, key: String, path: String): Boolean =
        upstream.trimEnd('/') == OPENAI && !key.startsWith("sk-") && path.substringBefore('?').endsWith("/responses")

    /** The body made fit for a sign-in; anything that isn't a JSON object goes as it came. */
    fun adapt(body: ByteArray): ByteArray {
        val j = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull() ?: return body
        UNSUPPORTED.forEach { j.remove(it) }
        val input = j.optJSONArray("input")
        if (input != null) {
            var effort: Any? = null
            val kept = JSONArray()
            for (i in 0 until input.length()) {
                val item = input.opt(i)
                if (item is JSONObject && item.optString("type") == "configuration_update") {
                    item.optJSONObject("reasoning")?.let { r -> if (r.has("effort")) effort = r.get("effort") }
                } else kept.put(item)
            }
            j.put("input", kept)
            // The request carried a fixed effort while the items carried the real one.
            if (effort != null) (j.optJSONObject("reasoning") ?: JSONObject().also { j.put("reasoning", it) }).put("effort", effort)
        }
        return j.toString().toByteArray(Charsets.UTF_8)
    }
}
