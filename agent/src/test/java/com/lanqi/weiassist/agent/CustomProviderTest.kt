package com.lanqi.weiassist.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomProviderTest {
    @Test fun `context window and image support are read from the fields servers use`() {
        val ms = CustomProvider.parseModelList("""{"object":"list","data":[
            {"id":"vllm-model","max_model_len":32768},
            {"id":"router/model","context_length":200000,"architecture":{"input_modalities":["text","image"]}},
            {"id":"groq-model","context_window":131072},
            {"id":"lmstudio-model","max_context_length":8192},
            {"id":"llamacpp-model","meta":{"n_ctx_train":65536}},
            {"id":"text-only","architecture":{"input_modalities":["text"]}},
            {"id":"magpie-model","context_window":500000,"modalities":{"input":["text","image"]}},
            {"id":"plain"}
        ]}""").associateBy { it.id }
        assertEquals(32768, ms.getValue("vllm-model").contextWindow)
        assertEquals(200000, ms.getValue("router/model").contextWindow)
        assertEquals(true, ms.getValue("router/model").images)
        assertEquals(131072, ms.getValue("groq-model").contextWindow)
        assertEquals(8192, ms.getValue("lmstudio-model").contextWindow)
        assertEquals(65536, ms.getValue("llamacpp-model").contextWindow)
        assertEquals(false, ms.getValue("text-only").images)
        assertEquals(500000, ms.getValue("magpie-model").contextWindow)
        assertEquals(true, ms.getValue("magpie-model").images)
        assertEquals(0, ms.getValue("plain").contextWindow)
        assertNull(ms.getValue("plain").images)
    }

    @Test fun `the server's figures win, then Pi's catalog, then defaults`() {
        val catalog = CatalogModel(1_000_000, false, "deepseek/deepseek-flash")
        assertEquals(CustomModel("m", 32768, false, CustomModel.SOURCE_SERVER), CustomProvider.merge(ServerModel("m", 32768), catalog))
        assertEquals(CustomModel("m", 1_000_000, false, "pi:deepseek/deepseek-flash"), CustomProvider.merge(ServerModel("m"), catalog))
        assertEquals(CustomModel("m", 0, true, null), CustomProvider.merge(ServerModel("m"), null))
    }

    @Test fun `whether a model thinks, and the efforts it takes, are read from magpie's and OpenRouter's lists`() {
        val ms = CustomProvider.parseModelList("""{"data":[
            {"id":"codex/gpt","reasoning":true,"reasoning_levels":["low","medium","high","xhigh","max","ultra"]},
            {"id":"magpie-plain","reasoning":false},
            {"id":"or/thinker","supported_parameters":["tools","reasoning"]},
            {"id":"or/plain","supported_parameters":["tools"]},
            {"id":"bare"}
        ]}""").associateBy { it.id }
        assertEquals(true, ms.getValue("codex/gpt").reasoning)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max", "ultra"), ms.getValue("codex/gpt").efforts)
        assertEquals(false, ms.getValue("magpie-plain").reasoning)
        assertEquals(true, ms.getValue("or/thinker").reasoning)
        assertNull(ms.getValue("or/thinker").efforts)
        assertEquals(false, ms.getValue("or/plain").reasoning)
        assertNull(ms.getValue("bare").reasoning)

        // The server's word, else Pi's catalog, else not; efforts only for a model that thinks.
        val thinks = CatalogModel(200_000, true, "openai/o9", reasoning = true)
        assertEquals(true, CustomProvider.merge(ServerModel("x"), thinks).reasoning)
        assertEquals(false, CustomProvider.merge(ServerModel("x", reasoning = false), thinks).reasoning)
        assertEquals(false, CustomProvider.merge(ServerModel("x"), null).reasoning)
        assertNull(CustomProvider.merge(ServerModel("x", reasoning = false, efforts = listOf("low")), null).efforts)

        val saved = CustomProvider("custom-g", "G", "http://x/v1", listOf(CustomProvider.merge(ms.getValue("codex/gpt"), null)))
        assertEquals(saved, CustomProvider.fromJson(saved.toJson()))
    }

    @Test fun `providers saved as model ids by the previous version still load`() {
        val old = JSONObject("""{"id":"custom-1","name":"Old","baseUrl":"https://x/v1","models":["a","b"],"images":false}""")
        val p = CustomProvider.fromJson(old)
        assertEquals(listOf(CustomModel("a", images = false), CustomModel("b", images = false)), p.models)
        assertEquals(p, CustomProvider.fromJson(p.toJson()))
    }
}
