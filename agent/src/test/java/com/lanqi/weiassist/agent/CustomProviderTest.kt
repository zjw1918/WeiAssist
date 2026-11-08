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

    @Test fun `providers saved as model ids by the previous version still load`() {
        val old = JSONObject("""{"id":"custom-1","name":"Old","baseUrl":"https://x/v1","models":["a","b"],"images":false}""")
        val p = CustomProvider.fromJson(old)
        assertEquals(listOf(CustomModel("a", images = false), CustomModel("b", images = false)), p.models)
        assertEquals(p, CustomProvider.fromJson(p.toJson()))
    }
}
