package br.com.assistente.teste

import br.com.assistente.teste.integrations.IntegrationRegistry
import br.com.assistente.teste.llm.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class LlmUsageTest {
    private val usage = mutableListOf<Long?>()
    private fun client(vararg envelopes: String): ChatClient {
        val replies = ArrayDeque(envelopes.toList())
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val next = replies.removeFirst()
            if (next == "network_error") throw IOException("test")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(next.toResponseBody()).build()
        }.build()
        return ChatClient(OpenAiCompatibleClient(http, LlmConfig.fromJson(JSONObject(File("src/main/assets/agent/provider.json").readText()))),
            IntegrationRegistry(emptyList()), AgentPrompts { File("src/main/assets/agent/prompts/$it").readText() })
    }
    private fun envelope(message: String = """{"role":"assistant","content":"Ok"}""", tokens: String? = "{\"total_tokens\":100}", finish: String = "stop") =
        JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject(message)).put("finish_reason", finish)))
            .apply { tokens?.let { put("usage", JSONObject(it)) } }.toString()

    @Test fun `sums actual usage over tool and final response rounds`() {
        val call = """{"role":"assistant","tool_calls":[{"id":"t1","type":"function","function":{"name":"unknown","arguments":"{}"}}]}"""
        client(envelope(call, "{\"prompt_tokens\":80,\"completion_tokens\":20,\"total_tokens\":100}"),
            envelope(tokens = "{\"prompt_tokens\":120,\"completion_tokens\":30}"))
            .reply("key", "test", onUsage = usage::add)
        assertEquals(listOf(100L, 150L), usage)
        assertEquals(250L, usage.filterNotNull().sum())
    }
    @Test fun `does not double count cached or reasoning breakdowns`() {
        assertEquals(100L, LlmUsage.total(JSONObject("""{"total_tokens":100,"prompt_tokens":80,"completion_tokens":20,"prompt_cache_hit_tokens":60,"completion_tokens_details":{"reasoning_tokens":10}}""")))
        assertEquals(100L, LlmUsage.total(JSONObject("""{"prompt_tokens":80,"completion_tokens":20,"prompt_cache_hit_tokens":60}""")))
        assertEquals(0L, LlmUsage.total(JSONObject("{\"total_tokens\":0}")))
    }
    @Test fun `missing or invalid usage is unknown not an estimate`() {
        for (value in listOf(null, JSONObject(), JSONObject("{\"prompt_tokens\":100}"), JSONObject("{\"total_tokens\":-1}"), JSONObject("{\"total_tokens\":1.5}")))
            assertNull(LlmUsage.total(value))
        client(envelope(tokens = null)).reply("key", "test", onUsage = usage::add)
        assertEquals(listOf<Long?>(null), usage)
    }
    @Test fun `truncated response still reports consumed tokens once`() {
        assertThrows(IllegalStateException::class.java) {
            client(envelope(finish = "length")).reply("key", "test", onUsage = usage::add)
        }
        assertEquals(listOf(100L), usage)
    }
    @Test fun `network failure reports unknown consumption once`() {
        assertThrows(IOException::class.java) { client("network_error").reply("key", "test", onUsage = usage::add) }
        assertEquals(listOf<Long?>(null), usage)
    }
}
