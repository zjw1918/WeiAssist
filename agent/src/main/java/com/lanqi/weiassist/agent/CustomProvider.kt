package com.lanqi.weiassist.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A model as a server's /models lists it: 0 / null where the server doesn't say. */
data class ServerModel(val id: String, val contextWindow: Int = 0, val images: Boolean? = null)

/** Pi's catalog entry for a model id ([match] = "<provider>/<model>"). */
data class CatalogModel(val contextWindow: Int, val images: Boolean, val match: String)

/**
 * One model of a [CustomProvider]. [contextWindow] 0 = unknown (Pi host assumes 128K). [source] says where
 * the values came from: [SOURCE_SERVER] (the server's model list), "pi:<provider>/<model>" (Pi's catalog),
 * [SOURCE_USER] (typed in the form), or null (nothing known: defaults).
 */
data class CustomModel(val id: String, val contextWindow: Int = 0, val images: Boolean = true, val source: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("images", images).apply {
        if (contextWindow > 0) put("contextWindow", contextWindow)
        if (source != null) put("source", source)
    }

    companion object {
        const val SOURCE_SERVER = "server"
        const val SOURCE_USER = "user"
        const val DEFAULT_CONTEXT = 128_000

        fun fromJson(o: JSONObject, imagesDefault: Boolean) = CustomModel(
            o.getString("id"), o.optInt("contextWindow"), o.optBoolean("images", imagesDefault),
            o.optString("source").ifEmpty { null },
        )
    }
}

/**
 * The user's own OpenAI-compatible endpoint (vLLM, Ollama, LM Studio, an API relay…). Saved in the app's
 * settings; its API key is kept with Pi's other credentials (Keystore-encrypted). Pi host builds a provider
 * from it (pi-host `customProvider`).
 */
data class CustomProvider(
    val id: String,
    val name: String,
    /** Up to and including the version path, e.g. https://example.com/v1. */
    val baseUrl: String,
    val models: List<CustomModel>,
    /** Default for models saved without their own setting (older versions had only this). */
    val images: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("baseUrl", baseUrl)
        .put("models", JSONArray(models.map { it.toJson() })).put("images", images)

    companion object {
        const val ID_PREFIX = "custom-"

        fun newId() = ID_PREFIX + java.util.UUID.randomUUID().toString().take(8)

        fun fromJson(o: JSONObject): CustomProvider {
            val images = o.optBoolean("images", true)
            val a = o.getJSONArray("models")
            // Older versions saved model ids only.
            val ms = (0 until a.length()).map { i -> a.optJSONObject(i)?.let { CustomModel.fromJson(it, images) } ?: CustomModel(a.getString(i), images = images) }
            return CustomProvider(o.getString("id"), o.getString("name"), o.getString("baseUrl"), ms, images)
        }

        fun listToJson(list: List<CustomProvider>) = JSONArray(list.map { it.toJson() })

        fun listFromJson(json: String?): List<CustomProvider> = runCatching {
            JSONArray(json ?: return emptyList()).let { a -> (0 until a.length()).map { fromJson(a.getJSONObject(it)) } }
        }.getOrDefault(emptyList())

        /** Trimmed, without trailing slashes or a pasted /chat/completions or /models. */
        fun normalizeBaseUrl(url: String): String = url.trim().trimEnd('/')
            .removeSuffix("/chat/completions").removeSuffix("/models").trimEnd('/')

        /**
         * The server's models (GET {base}/models, as OpenAI-compatible servers list them), with the context
         * window and image support where the server says (fields vary: see [describe]). When [baseUrl] lacks
         * the version path and only {base}/v1/models answers, that base is returned instead.
         */
        suspend fun fetchModels(baseUrl: String, key: String?): Pair<String, List<ServerModel>> = withContext(Dispatchers.IO) {
            val base = normalizeBaseUrl(baseUrl)
            val first = runCatching { listModels(base, key) }
            if (first.isSuccess || base.endsWith("/v1")) return@withContext base to first.getOrThrow()
            runCatching { "$base/v1" to listModels("$base/v1", key) }.getOrElse { throw first.exceptionOrNull()!! }
        }

        private fun listModels(base: String, key: String?): List<ServerModel> {
            val c = URL("$base/models").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                if (!key.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer ${key.trim()}")
                c.setRequestProperty("Accept", "application/json")
                val code = c.responseCode
                if (code !in 200..299) {
                    val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(300)
                    throw IllegalStateException("HTTP $code $err".trim())
                }
                return parseModelList(c.inputStream.bufferedReader().use { it.readText() })
            } finally {
                c.disconnect()
            }
        }

        /** A /models response: OpenAI's { data: [{ id }] }, or { models: [...] }, or bare ids. Sorted by id. */
        fun parseModelList(json: String): List<ServerModel> {
            val body = JSONObject(json)
            val data = body.optJSONArray("data") ?: body.optJSONArray("models") ?: JSONArray()
            return (0 until data.length()).mapNotNull { i ->
                val o = data.optJSONObject(i) ?: return@mapNotNull data.optString(i).takeIf { it.isNotBlank() }?.let { ServerModel(it) }
                val id = o.optString("id").ifEmpty { o.optString("name") }.ifEmpty { return@mapNotNull null }
                describe(id, o)
            }.distinctBy { it.id }.sortedBy { it.id }
        }

        /**
         * What one /models entry says beyond its id. Context window: vLLM max_model_len; OpenRouter, Together,
         * Fireworks context_length; Groq context_window; LM Studio max_context_length; llama.cpp meta.n_ctx_train.
         * Images: OpenRouter-style architecture.input_modalities (or a top-level input_modalities); magpie's
     * modalities.input.
         */
        private fun describe(id: String, o: JSONObject): ServerModel {
            val ctx = listOf(
                o.optInt("context_length"), o.optInt("context_window"), o.optInt("max_model_len"),
                o.optInt("max_context_length"), o.optJSONObject("top_provider")?.optInt("context_length") ?: 0,
                o.optJSONObject("meta")?.optInt("n_ctx_train") ?: 0,
            ).firstOrNull { it > 0 } ?: 0
            val modalities = o.optJSONObject("architecture")?.optJSONArray("input_modalities") ?: o.optJSONArray("input_modalities")
                ?: o.optJSONObject("modalities")?.optJSONArray("input")
            val images = modalities?.let { m -> (0 until m.length()).any { m.optString(it) == "image" } }
            return ServerModel(id, ctx, images)
        }

        /**
         * A model's settings from what is known, best first: the server's own figures, then Pi's catalog entry
         * for that id ([catalog], from CoreAccounts.lookupModels), then defaults (128K, reads images).
         */
        fun merge(server: ServerModel, catalog: CatalogModel?): CustomModel {
            val ctx = server.contextWindow.takeIf { it > 0 } ?: catalog?.contextWindow ?: 0
            val images = server.images ?: catalog?.images ?: true
            val source = when {
                server.contextWindow > 0 || server.images != null -> CustomModel.SOURCE_SERVER
                catalog != null -> "pi:${catalog.match}"
                else -> null
            }
            return CustomModel(server.id, ctx, images, source)
        }
    }
}
