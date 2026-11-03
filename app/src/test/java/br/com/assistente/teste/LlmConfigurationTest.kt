package br.com.assistente.teste

import br.com.assistente.teste.llm.*
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LlmConfigurationTest {
    private fun defaults() = LlmConfig.fromJson(JSONObject(File("src/main/assets/agent/provider.json").readText()))

    @Test fun `provider settings reach HTTP without exposing credentials in events`() {
        val config = defaults().copy(provider = "Compatível", endpoint = "https://example.com/v1/chat/completions", model = "another-model",
            temperature = 0.4, maxTokens = 512, extraBody = "{}")
        val events = mutableListOf<String>()
        var inspected = false
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals(config.endpoint, request.url.toString())
            assertEquals("Bearer private-test-key", request.header("Authorization"))
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            assertEquals("another-model", body.getString("model"))
            assertEquals(0.4, body.getDouble("temperature"), 0.001)
            assertEquals(512, body.getInt("max_tokens"))
            assertFalse(body.has("thinking"))
            assertFalse(body.has("tools"))
            assertFalse(body.toString().contains("private-test-key"))
            inspected = true
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Oi"}}]}""".toResponseBody()).build()
        }.build()
        val result = OpenAiCompatibleClient(http, config).complete("private-test-key", JSONArray(), JSONArray(), events::add)
        assertTrue(inspected)
        assertEquals("Oi", result.getString("content"))
        assertFalse(events.joinToString().contains("private-test-key"))
    }

    @Test fun `rejects insecure endpoints reserved extras and invalid generation parameters`() {
        val base = defaults()
        listOf("http://example.com/chat", "https://user:password@example.com", "https://example.com?key=secret").forEach {
            assertThrows(IllegalArgumentException::class.java) { base.copy(endpoint = it) }
        }
        assertThrows(IllegalArgumentException::class.java) { base.copy(extraBody = "{\"messages\":[]}") }
        assertThrows(IllegalArgumentException::class.java) { base.copy(temperature = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(callTimeoutSeconds = 0) }
        assertEquals(base, LlmConfig.fromJson(base.toJson()))
    }

    @Test fun `packaged prompts separate disconnected and connected instructions`() {
        val prompts = AgentPrompts { File("src/main/assets/agent/prompts/$it").readText() }
        assertTrue(prompts.availability(emptyList()).contains("Nenhuma ferramenta disponível"))
        assertFalse(prompts.availability(emptyList()).contains("Spotify: consulte"))
        assertTrue(prompts.availability(listOf("spotify")).contains("Spotify: consulte"))
        assertFalse(prompts.availability(listOf("spotify")).contains("{{tools}}"))
        assertTrue(prompts.repair.contains("NÃO foi executada"))
    }
}
