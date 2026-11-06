package br.com.assistente.teste.integrations

import br.com.assistente.teste.integrations.web.*
import br.com.assistente.teste.llm.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

class WebSearchTest {
    private val fixture get() = javaClass.getResource("/web/duckduckgo-lite.html")!!.readText()
    private val parser = DuckDuckGoParser()
    private fun row(url: String, title: String, snippet: String? = null) =
        "<tr><td><a class='result-link' href='$url'>$title</a></td></tr>" +
            (snippet?.let { "<tr><td class='result-snippet'>$it</td></tr>" } ?: "")
    private fun error(code: String, block: () -> Unit) {
        assertEquals(code, assertThrows(WebSearchException::class.java, block).code)
    }
    private fun http(status: Int = 200, type: String = "text/html; charset=utf-8", body: String = fixture,
        inspect: (Request) -> Unit = {}) = DuckDuckGoClient(OkHttpClient.Builder().addInterceptor { chain ->
        inspect(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
            .body(body.toResponseBody(type.toMediaType())).build()
    }.build())

    @Test fun `real Lite HTML produces five clean results`() {
        val results = parser.parse(fixture)
        assertEquals(5, results.size)
        assertEquals("Kotlin and Android | Android Developers", results[0].title)
        assertEquals("https://developer.android.com/kotlin/", results[0].url)
        assertEquals("https://kotlinlang.org/docs/android-overview.html", results[1].url)
        assertTrue(results.all { it.snippet.isNotBlank() && !it.snippet.contains("<") && !it.url.contains("uddg=") })
    }

    @Test fun `missing snippet never steals the next result snippet`() {
        val results = parser.parse("<table>" + row("https://a.test", "A") +
            row("https://b.test", "B &amp; C", "Only <b>B</b><script>bad()</script>") + "</table>")
        assertEquals("", results[0].snippet)
        assertEquals("Only B", results[1].snippet)
        assertEquals("B & C", results[1].title)
    }

    @Test fun `rejects unsafe links and deduplicates decoded destinations`() {
        val results = parser.parse("<table>" + row("javascript:alert(1)", "Unsafe") +
            row("https://user:secret@a.test", "Credentials") + row("//duckduckgo.com/y.js?ad=1", "Ad") +
            row("https://a.test/", "First") + row("//duckduckgo.com/l/?uddg=https%3A%2F%2Fa.test%2F", "Duplicate") +
            row("https://b.test/", "Second") + "</table>")
        assertEquals(listOf("First", "Second"), results.map { it.title })
    }

    @Test fun `caps fields and skips oversized URLs without truncating destinations`() {
        val results = parser.parse("<table>" + row("https://a.test/" + "a".repeat(1200), "Long URL") +
            row("https://b.test", "b".repeat(500), "s".repeat(2000)) + "</table>")
        assertEquals(1, results.size)
        assertEquals(180, results[0].title.length)
        assertEquals(600, results[0].snippet.length)
    }

    @Test fun `empty results differ from challenge and changed HTML`() {
        assertTrue(parser.parse(javaClass.getResource("/web/duckduckgo-empty.html")!!.readText()).isEmpty())
        assertTrue(parser.parse("<table><tr><td>No results found for <b>xyz</b></td></tr></table>").isEmpty())
        assertTrue(parser.parse("<div class='no-results'>No results</div>").isEmpty())
        error("blocked") { parser.parse("<form id='challenge-form'>Challenge</form>") }
        error("unexpected_html") { parser.parse("<html><body>Something changed</body></html>") }
        error("unexpected_html") { parser.parse("<table>" + row("javascript:bad()", "Invalid") + "</table>") }
    }

    @Test fun `real Portuguese response preserves accents and snippets`() {
        val results = parser.parse(javaClass.getResource("/web/duckduckgo-portuguese.html")!!.readText())
        assertEquals(5, results.size)
        assertTrue(results.first().title.contains("República"))
        assertTrue(results.all { it.snippet.isNotBlank() })
    }

    @Test fun `query is encoded as one parameter and request is unauthenticated`() {
        val query = "Brasília & café + C++? #museu"
        val html = http { request ->
            assertEquals("lite.duckduckgo.com", request.url.host)
            assertEquals(query, request.url.queryParameter("q"))
            assertEquals(setOf("q"), request.url.queryParameterNames)
            assertEquals("text/html", request.header("Accept"))
            assertNull(request.header("Authorization"))
            assertNull(request.header("Cookie"))
        }.fetch(query)
        assertEquals(5, parser.parse(html).size)
    }

    @Test fun `status MIME and body size are checked before parsing`() {
        for (status in listOf(202, 403, 429)) error("blocked") { http(status).fetch("test") }
        for (status in listOf(302, 500)) error("http_error") { http(status).fetch("test") }
        error("invalid_response") { http(type = "application/json", body = "{}").fetch("test") }
        error("response_too_large") { http(body = "x".repeat(512_001)).fetch("test") }
    }

    @Test fun `transport failures have compact specific errors`() {
        for ((exception, code) in listOf(SocketTimeoutException() to "timeout", IOException("private details") to "network")) {
            val client = DuckDuckGoClient(OkHttpClient.Builder().addInterceptor { throw exception }.build())
            error(code) { client.fetch("test") }
        }
    }

    @Test fun `validates and normalizes query before any HTTP call`() {
        val queries = mutableListOf<String>()
        val search = WebSearch(SearchHtmlSource { queries.add(it); fixture })
        error("invalid_query") { search.search("   ") }
        error("invalid_query") { search.search("x".repeat(301)) }
        assertTrue(queries.isEmpty())
        search.search("  Brasília\n  museu  ")
        assertEquals(listOf("Brasília museu"), queries)
    }

    @Test fun `registry exposes concise schema and rejects malformed arguments`() {
        val registry = IntegrationRegistry(listOf(WebSearchIntegration(WebSearch(SearchHtmlSource { fixture }))))
        assertTrue(registry.tools().toString().length < 500)
        for (args in listOf("{}", "{\"query\":null}", "{\"query\":3}", "{\"query\":\"x\",\"url\":\"x\"}")) {
            assertThrows(ToolArgumentException::class.java) { registry.execute("web_search", JSONObject(args)) }
        }
        val result = JSONObject(registry.execute("web_search", JSONObject().put("query", "Kotlin")))
        assertEquals(5, result.getJSONArray("results").length())
        assertFalse(result.toString().contains("<table"))
        assertTrue(result.toString().length < 11_000)
        assertEquals("invalid_query", JSONObject(registry.execute("web_search", JSONObject().put("query", " "))).getString("error_type"))
    }

    @Test fun `web tool errors do not leak HTML or get mistaken for empty results`() {
        val integration = WebSearchIntegration(WebSearch(SearchHtmlSource { "<form id='challenge-form'>secret html</form>" }))
        val result = JSONObject(integration.execute("web_search", JSONObject().put("query", "test")))
        assertEquals("blocked", result.getString("error_type"))
        assertFalse(result.has("results"))
        assertFalse(result.toString().contains("secret html"))
    }

    @Test fun `agent dispatches search and receives compact result in the tool message and log`() {
        var rounds = 0
        var searches = 0
        val registry = IntegrationRegistry(listOf(WebSearchIntegration(WebSearch(SearchHtmlSource { searches++; fixture }))))
        val gateway = object : LlmGateway {
            override fun complete(key: String, messages: JSONArray, tools: JSONArray, onProgress: (String) -> Unit): JSONObject {
                rounds++
                if (rounds == 1) return JSONObject("""{"role":"assistant","tool_calls":[{"id":"web1","type":"function","function":{"name":"web_search","arguments":"{\"query\":\"Kotlin Android\"}"}}]}""")
                val message = messages.getJSONObject(messages.length() - 1)
                assertEquals("tool", message.getString("role"))
                assertEquals("web1", message.getString("tool_call_id"))
                assertEquals(5, JSONObject(message.getString("content")).getJSONArray("results").length())
                return JSONObject().put("role", "assistant").put("content", "Consulte https://developer.android.com/kotlin/")
            }
        }
        val events = mutableListOf<String>()
        val client = ChatClient(gateway, registry, AgentPrompts { File("src/main/assets/agent/prompts/$it").readText() })
        assertTrue(client.reply("key", "Pesquise Kotlin", onTrace = { title, _ -> events.add(title) }).contains("https://"))
        assertEquals(1, searches)
        assertEquals(2, rounds)
        assertTrue(events.any { it.startsWith("Retorno de ferramenta · web_search") })
    }

    /** Opt-in: real network must not make normal CI flaky. */
    @Test fun `live DuckDuckGo returns HTML that the production parser accepts`() {
        assumeTrue(System.getenv("ORBI_WEB_LIVE_TEST") == "1")
        val query = System.getenv("ORBI_WEB_QUERY") ?: "Kotlin Android"
        val results = WebSearch().search(query)
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.url.startsWith("https://") || it.url.startsWith("http://") })
        println("Live search: ${results.size} results; first=${results.first().url}")
    }
}
