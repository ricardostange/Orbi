package br.com.assistente.teste

import br.com.assistente.teste.llm.*
import java.io.File
import br.com.assistente.teste.integrations.IntegrationRegistry
import br.com.assistente.teste.integrations.spotify.SpotifyIntegration
import br.com.assistente.teste.integrations.spotify.SpotifyTransport
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatClientTest {
    private var spotifyConnected = false
    private var executed = 0
    private val events = mutableListOf<String>()
    private val requests = mutableListOf<JSONArray>()
    private val responses = ArrayDeque<Pair<Int, String>>()
    private val client = ChatClient(OpenAiCompatibleClient(OkHttpClient.Builder().addInterceptor { chain ->
        val buffer = Buffer()
        chain.request().body!!.writeTo(buffer)
        requests.add(JSONObject(buffer.readUtf8()).getJSONArray("messages"))
        val (status, message) = responses.removeFirst()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(status).message("test")
            .body("""{"choices":[{"message":$message}]}""".toResponseBody()).build()
    }.build(), LlmConfig.fromJson(JSONObject(File("src/main/assets/agent/provider.json").readText()))), IntegrationRegistry(listOf(SpotifyIntegration({ spotifyConnected }, SpotifyTransport { _, _, _, _ ->
        executed++
        "{\"ok\":true}"
    }))), AgentPrompts { File("src/main/assets/agent/prompts/$it").readText() })

    private fun answer(text: String) {
        responses.addLast(200 to JSONObject().put("role", "assistant").put("content", text).toString())
    }

    @Test fun `includes recent requests and answers in order`() {
        answer("Primeira resposta")
        client.reply("key", "Primeiro pedido")
        answer("Segunda resposta")
        client.reply("key", "Segundo pedido")
        val messages = requests.last()
        assertEquals(4, messages.length())
        assertEquals("Primeiro pedido", messages.getJSONObject(1).getString("content"))
        assertEquals("Primeira resposta", messages.getJSONObject(2).getString("content"))
        assertEquals("Segundo pedido", messages.getJSONObject(3).getString("content"))
    }

    @Test fun `keeps only five complete previous turns`() {
        repeat(7) {
            answer("Resposta $it")
            client.reply("key", "Pedido $it")
        }
        val messages = requests.last()
        assertEquals(12, messages.length())
        assertEquals("Pedido 1", messages.getJSONObject(1).getString("content"))
        assertEquals("Pedido 6", messages.getJSONObject(11).getString("content"))
    }

    @Test fun `preserves tool calls and error results without executing them again`() {
        responses.addLast(200 to """{"role":"assistant","content":null,"tool_calls":[{"id":"call1","type":"function","function":{"name":"unknown","arguments":"{}"}}]}""")
        answer("Ferramenta indisponível")
        client.reply("key", "Execute")
        answer("Entendido")
        client.reply("key", "E agora?")
        val messages = requests.last()
        assertEquals("call1", messages.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("tool", messages.getJSONObject(3).getString("role"))
        assertEquals("call1", messages.getJSONObject(3).getString("tool_call_id"))
        assertTrue(messages.getJSONObject(3).getString("content").contains("error"))
        assertEquals(3, requests.size)
    }

    @Test fun `clear and size limit remove whole turns`() {
        answer("x".repeat(60_000))
        client.reply("key", "Pedido enorme")
        answer("Ok")
        client.reply("key", "Novo pedido")
        assertEquals(2, requests.last().length())
        client.clearHistory()
        answer("Ok")
        client.reply("key", "Sem histórico")
        assertEquals(2, requests.last().length())
    }

    @Test fun `failed requests are not saved as completed turns`() {
        responses.addLast(500 to "{}")
        assertThrows(IllegalStateException::class.java) { client.reply("key", "Falha") }
        answer("Ok")
        client.reply("key", "Tente outro pedido")
        assertEquals(2, requests.last().length())
    }
    @Test fun `structured tool call executes once and sends result back before final answer`() {
        spotifyConnected = true
        responses.addLast(200 to """{"role":"assistant","content":null,"tool_calls":[{"id":"call1","type":"function","function":{"name":"spotify","arguments":"{\"action\":\"pause\"}"}}]}""")
        answer("Pausado")
        assertEquals("Pausado", client.reply("private-key", "Pause", events::add))
        assertEquals(1, executed)
        assertEquals("tool", requests.last().getJSONObject(3).getString("role"))
        assertTrue(events.any { it.contains("Executando ferramenta: spotify") })
        assertFalse(events.joinToString().contains("private-key"))
    }
    @Test fun `trace captures ordered model exchanges and correlated tool results`() {
        spotifyConnected = true
        responses.addLast(200 to """{"role":"assistant","content":null,"tool_calls":[{"id":"trace-call","type":"function","function":{"name":"spotify","arguments":"{\"action\":\"pause\"}"}}]}""")
        answer("Pausado")
        val trace = mutableListOf<Pair<String, String>>()
        client.reply("private-key", "Pause", onTrace = { title, detail -> trace.add(title to detail) })
        assertEquals(6, trace.size)
        assertTrue(trace[0].first.startsWith("Envio à IA"))
        assertTrue(trace[1].first.startsWith("Resposta da IA"))
        assertTrue(trace[2].first.startsWith("Chamada de ferramenta"))
        assertTrue(trace[2].second.contains("trace-call"))
        assertTrue(trace[3].first.startsWith("Retorno de ferramenta"))
        assertTrue(trace[4].second.contains("trace-call"))
        assertTrue(trace[5].second.contains("Pausado"))
        assertFalse(trace.toString().contains("private-key"))
        assertEquals(1, executed)
    }

    @Test fun `text call is repaired through API rather than executed as text`() {
        spotifyConnected = true
        answer("""{"name":"spotify","arguments":{"action":"pause"}}""")
        responses.addLast(200 to """{"role":"assistant","content":null,"tool_calls":[{"id":"call1","type":"function","function":{"name":"spotify","arguments":"{\"action\":\"pause\"}"}}]}""")
        answer("Pausado")
        assertEquals("Pausado", client.reply("key", "Pause", events::add))
        assertEquals(1, executed)
        assertEquals(3, requests.size)
        assertTrue(events.any { it.contains("como texto: não executada") })
    }
    @Test fun `disconnected or repeatedly malformed calls cannot execute`() {
        val malformed = """{"name":"spotify","arguments":{"action":"pause"}}"""
        answer(malformed)
        val disconnected = assertThrows(IllegalStateException::class.java) { client.reply("key", "Pause") }
        assertTrue(disconnected.message!!.contains("não conectado"))
        assertEquals(0, executed)
        spotifyConnected = true
        answer(malformed)
        answer(malformed)
        assertThrows(IllegalStateException::class.java) { client.reply("key", "Pause") }
        assertEquals(0, executed)
        assertEquals(3, requests.size)
    }

}

