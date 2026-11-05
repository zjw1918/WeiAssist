package com.lanqi.weiassist.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Sign-in and providers, for a core that keeps them itself (Pi does: its credential store and model catalog).
 * Its account events (`status`, `auth_event`, `auth_prompt`, `login_done`, …) come through [AgentCore.events].
 * The app has one accounts core. Other cores take their model settings from it.
 */
interface CoreAccounts {
    suspend fun login(provider: String, method: String)
    /** Answer a sign-in prompt (an `auth_prompt` event). */
    suspend fun answer(promptId: String, value: String)
    suspend fun cancelLogin()
    suspend fun logout(provider: String)

    /** The user's own providers, read at start (also after a runtime restart). Each: { id, name, baseUrl, models: [ids], images }. */
    var customProviders: () -> JSONArray
    /** The custom providers changed; [key], if given, is [provider]'s new API key. */
    suspend fun setCustomProviders(list: JSONArray, provider: String? = null, key: String? = null)

    /** Catalog entries for these model ids: { id: { contextWindow, images, match } }, only for ids it knows. */
    suspend fun lookupModels(ids: List<String>): JSONObject
    /** The API key saved for [provider], if it signed in with one. */
    fun savedApiKey(provider: String): String?
}
