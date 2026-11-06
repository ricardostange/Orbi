package br.com.assistente.teste

import br.com.assistente.teste.llm.*
import br.com.assistente.teste.integrations.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.buffer
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ChatStreamTest {
    private fun chunk(delta: JSONObject = JSONObject(), finish: String? = null, usage: JSONObject? = null): String =
        "data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0)
            .put("delta", delta).put("finish_reason", finish ?: JSONObject.NULL)))
            .put("usage", usage ?: JSONObject.NULL).toString() + "\n\n"
    private fun text(value: String) = chunk(JSONObject().put("content", value))
    private fun tool(index: Int, id: String? = null, name: String? = null, args: String = ""): String =
        chunk(JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", index)
            .apply { if (id != null) put("id", id).put("type", "function") }
            .put("function", JSONObject().put("arguments", args).apply { if (name != null) put("name", name) }))))
    private val done = "data: [DONE]\n\n"
    private val tokens = JSONObject("""{"prompt_tokens":80,"completion_tokens":20,"total_tokens":100,"prompt_cache_hit_tokens":60,"prompt_cache_miss_tokens":20}""")

    @Test fun `text arrives before completion and usage appears once`() {
        val updates = mutableListOf<String>()
        val usage = mutableListOf<Long?>()
        var firstContent = 0
        val result = ChatStreamReader.read(Buffer().writeUtf8(": keepalive\n\n" + chunk() + text("Olá ") + text("mundo") + chunk(finish="stop", usage=tokens) + done),
            { usage.add(LlmUsage.total(it)) }, { firstContent++ }, updates::add)
        assertEquals("Olá mundo", result.getString("content"))
        assertEquals("Olá ", updates.first())
        assertEquals("Olá mundo", updates.last())
        assertEquals(listOf(100L), usage)
        assertEquals(2, firstContent)
    }
    @Test fun `emits text before reading the rest of the network body`() {
        val first = Buffer().writeUtf8(text("Primeiro"))
        val rest = Buffer().writeUtf8(text(" resto") + chunk(finish="stop") + done)
        var displayed = false
        val network = object : okio.Source {
            override fun timeout() = okio.Timeout.NONE
            override fun close() {}
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (first.size > 0) return first.read(sink, byteCount)
                assertTrue("UI must update before the remaining response is read", displayed)
                return rest.read(sink, byteCount)
            }
        }
        val result = ChatStreamReader.read(network.buffer(), {}, {}, { if (it == "Primeiro") displayed = true })
        assertEquals("Primeiro resto", result.getString("content"))
    }
    @Test fun `assembles interleaved tool arguments and accepts separate usage chunk`() {
        val raw = tool(0,"a","one","{\"x\":") + tool(1,"b","two","{") + tool(0,args="1}") + tool(1,args="}") +
            chunk(finish="tool_calls") + "data: {\"choices\":[],\"usage\":{\"total_tokens\":100}}\n\n" + done
        val counts = mutableListOf<Long?>()
        val result = ChatStreamReader.read(Buffer().writeUtf8(raw), { counts.add(LlmUsage.total(it)) }, {}, {})
        val calls = result.getJSONArray("tool_calls")
        assertEquals(2, calls.length())
        assertEquals("{\"x\":1}", calls.getJSONObject(0).getJSONObject("function").getString("arguments"))
        assertEquals(listOf(100L), counts)
    }
    @Test fun `preserves optional reasoning for tool roundtrips without displaying it`() {
        val updates = mutableListOf<String>()
        val raw = chunk(JSONObject().put("reasoning_content", "internal")) +
            tool(0,"a","one","{}") + chunk(finish="tool_calls") + done
        val result = ChatStreamReader.read(Buffer().writeUtf8(raw), {}, {}, updates::add)
        assertEquals("internal", result.getString("reasoning_content"))
        assertTrue(updates.all { it.isEmpty() })
    }
    @Test fun `rejects incomplete streams malformed arguments and unsafe finish reasons`() {
        val prefix = tool(0,"a","one","{}")
        for (raw in listOf(prefix, prefix + done, prefix + chunk(finish="tool_calls"),
            prefix + chunk(finish="length", usage=tokens) + done,
            prefix + chunk(finish="content_filter") + done,
            tool(0,"a","one","{") + chunk(finish="tool_calls") + done,
            tool(1,"a","one","{}") + chunk(finish="tool_calls") + done,
            prefix + tool(1,"a","two","{}") + chunk(finish="tool_calls") + done)) {
            assertThrows(Exception::class.java) { ChatStreamReader.read(Buffer().writeUtf8(raw), {}, {}, {}) }
        }
    }
    @Test fun `truncated generation still reports actual usage and absent usage is unknown`() {
        val counts = mutableListOf<Long?>()
        assertThrows(IllegalStateException::class.java) {
            ChatStreamReader.read(Buffer().writeUtf8(text("parcial") + chunk(finish="length",usage=tokens) + done),
                { counts.add(LlmUsage.total(it)) }, {}, {})
        }
        assertEquals(listOf(100L), counts)
        counts.clear()
        ChatStreamReader.read(Buffer().writeUtf8(text("Ok") + chunk(finish="stop") + done), { counts.add(LlmUsage.total(it)) }, {}, {})
        assertEquals(listOf<Long?>(null), counts)
    }
    private fun gateway(vararg replies: String): OpenAiCompatibleClient {
        val queue = ArrayDeque(replies.toList())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val payload = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            assertTrue(payload.getBoolean("stream"))
            assertTrue(payload.getJSONObject("stream_options").getBoolean("include_usage"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(queue.removeFirst().toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        return OpenAiCompatibleClient(client, LlmConfig.fromJson(JSONObject(File("src/main/assets/agent/provider.json").readText())))
    }
    @Test fun `gateway records first content total time and cache without credentials`() {
        val events = mutableListOf<String>()
        val counts = mutableListOf<Long?>()
        gateway(text("Oi") + chunk(finish="stop", usage=tokens) + done)
            .stream("secret-key", JSONArray(), JSONArray(), events::add, counts::add, {})
        assertEquals(1, events.count { it.contains("primeiro conteúdo=") })
        assertEquals(1, events.count { it.contains("duração total=") })
        assertTrue(events.any { it.contains("cache hit=60; cache miss=20") })
        assertFalse(events.any { it.contains("secret-key") })
        assertEquals(listOf(100L), counts)
    }
    @Test fun `agent executes only complete batch and asks model for final answer`() {
        var executed = 0
        val integration = object : Integration {
            override val id = "test"
            override val connected = true
            override val tools = listOf(JSONObject("""{"type":"function","function":{"name":"one","parameters":{"type":"object","properties":{}}}}"""))
            override fun execute(tool: String, arguments: JSONObject): String { executed++; return "{\"ok\":true}" }
        }
        val registry = IntegrationRegistry(listOf(integration))
        val prompts = AgentPrompts { File("src/main/assets/agent/prompts/$it").readText() }
        val partial = tool(0,"a","one","{}")
        val broken = ChatClient(gateway(partial), registry, prompts)
        assertThrows(Exception::class.java) { broken.reply("key", "test") }
        assertEquals(0, executed)
        val valid = ChatClient(gateway(partial + chunk(finish="tool_calls",usage=tokens) + done,
            text("Feito") + chunk(finish="stop",usage=tokens) + done), registry, prompts)
        val counts = mutableListOf<Long?>()
        assertEquals("Feito", valid.reply("key", "test", onUsage=counts::add))
        assertEquals(1, executed)
        assertEquals(listOf(100L,100L), counts)
    }
}
