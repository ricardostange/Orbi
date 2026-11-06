package br.com.assistente.teste.integrations

import br.com.assistente.teste.integrations.web.page.*
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
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class PageReadTest {
    private val url = "https://example.org/article"
    private val prose = "Brasília tem museus, parques e arquitetura. O museu abre às nove horas e recebe visitantes durante o dia. "
    private fun html(content: String = "<p>${prose.repeat(15)}</p>") =
        "<html><head><title>Visita a Brasília</title></head><body><nav>MENU DESCARTÁVEL</nav><main><article><h1>Visita</h1>$content</article></main><footer>RODAPÉ DESCARTÁVEL</footer><script>secret()</script></body></html>"
    private fun page(body: String = html()) = PageHtml(url, body.toByteArray())

    @Test fun `real article and documentation HTML preserve relevant content`() {
        for ((name, expected) in listOf("jsoup-release.html" to "1.21.2", "jsoup-selectors.html" to "select")) {
            val bytes = javaClass.getResource("/page/$name")!!.readBytes()
            val result = PageExtractor().extract(PageHtml("https://jsoup.org/", bytes))
            assertEquals("readability", result.extraction)
            assertTrue(result.text.contains(expected))
            assertTrue(result.text.length > 200)
            assertFalse(result.text.contains("analytics.ahrefs.com"))
        }
    }
    private fun error(code: String, action: () -> Unit) =
        assertEquals(code, assertThrows(PageException::class.java, action).code)
    private fun client(response: (Request, Int) -> Response): PageClient {
        var count = 0
        return PageClient(OkHttpClient.Builder().addInterceptor { response(it.request(), count++) }.build())
    }
    private fun response(request: Request, status: Int = 200, body: String = html(), type: String = "text/html; charset=utf-8", location: String? = null) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
            .body(body.toResponseBody(type.toMediaType())).apply { location?.let { header("Location", it) } }.build()

    @Test fun `Readability with current Jsoup extracts article and removes clutter`() {
        val result = PageExtractor().extract(page())
        assertEquals("readability", result.extraction)
        assertTrue(result.text.contains("Brasília"))
        assertTrue(result.text.contains("nove horas"))
        assertFalse(result.text.contains("DESCARTÁVEL"))
        assertFalse(result.text.contains("secret()"))
        assertFalse(result.text.contains("<p>"))
    }

    @Test fun `short pages fall back and preserve table rows and inline text`() {
        val result = PageExtractor().extract(page(html("<p>Olá <strong>mundo</strong>!</p><table><tr><th>Dia</th><th>Hora</th></tr><tr><td>Sábado</td><td>09:00</td></tr></table>")))
        assertEquals("fallback", result.extraction)
        assertTrue(result.text.contains("Olá mundo!"))
        assertTrue(result.text.contains("Sábado | 09:00"))
        assertFalse(result.text.contains("DESCARTÁVEL"))
    }

    @Test fun `detects charset from HTML and handles malformed markup`() {
        val bytes = "<meta charset='windows-1252'><main><p>Ação em Brasília &amp; café".toByteArray(charset("windows-1252"))
        val text = PageExtractor().extract(PageHtml(url, bytes)).text
        assertTrue(text.contains("Ação em Brasília & café"))
    }

    @Test fun `empty script only and challenge pages return explicit errors`() {
        error("empty") { PageExtractor().extract(page("<html><script>render()</script></html>")) }
        error("blocked") { PageExtractor().extract(page("<form id='challenge-form'>Verify</form>")) }
        val normal = html() + "<div class='g-recaptcha'></div>"
        assertTrue(PageExtractor().extract(page(normal)).text.contains("Brasília"))
    }

    @Test fun `large text has explicit limit and large DOM is rejected`() {
        val result = PageExtractor().extract(page(html("<p>${prose.repeat(1200)}</p>")))
        assertEquals(100_000, result.text.length)
        assertTrue(result.limited)
        error("too_large") { PageExtractor().extract(page("<i></i>".repeat(50_001))) }
    }

    @Test fun `continuation uses same snapshot and never loses text`() {
        var calls = 0
        val body = html("<p>${prose.repeat(140)}</p>")
        val reader = PageReader(PageSource { calls++; page(body) })
        val expected = PageExtractor().extract(page(body)).text
        var part = reader.read(url)
        val full = StringBuilder(part.getString("text"))
        assertTrue(part.getBoolean("has_more"))
        while (part.getBoolean("has_more")) {
            part = reader.read(url, part.getInt("next_offset"))
            assertTrue(part.getString("text").length <= 6000)
            full.append(part.getString("text"))
        }
        assertEquals(expected, full.toString())
        assertEquals(1, calls)
        assertFalse(part.has("next_offset"))
        error("invalid_offset") { reader.read(url, expected.length) }
    }

    @Test fun `expired and evicted snapshots require restart and final URL can continue`() {
        var now = 0L
        var calls = 0
        val reader = PageReader(PageSource { calls++; PageHtml(url, html("<p>${prose.repeat(100)}</p>").toByteArray()) }, clock = { now })
        val first = reader.read("https://example.org/redirect")
        reader.read(first.getString("url"), first.getInt("next_offset"))
        assertEquals(1, calls)
        now = 300_001
        error("expired") { reader.read(url, 6000) }
        reader.read(url)
        assertEquals(2, calls)
        val bounded = PageReader(PageSource { PageHtml(it, html().toByteArray()) })
        repeat(4) { bounded.read("https://example.org/$it") }
        error("expired") { bounded.read("https://example.org/0", 1) }
    }

    @Test fun `rejects private URLs credentials ports and non HTTP protocols`() {
        for (bad in listOf("file:///etc/passwd", "javascript:alert(1)", "https://localhost", "https://127.0.0.1", "https://10.0.0.2", "https://169.254.169.254", "https://[::1]", "https://[fc00::1]", "https://[::ffff:127.0.0.1]", "https://example.org:8080", "https://user:password@example.org")) {
            error("invalid_url") { PageAddress.parse(bad) }
        }
        assertEquals("https://example.org/", PageAddress.parse("http://example.org/#part").toString())
        assertEquals(url, PageAddress.parse(url).toString())
    }

    @Test fun `DNS rejects private or mixed answers`() {
        val publicAddress = InetAddress.getByName("93.184.216.34")
        val privateAddress = InetAddress.getByName("192.168.1.1")
        assertEquals(listOf(publicAddress), PublicPageDns(object : Dns { override fun lookup(hostname: String) = listOf(publicAddress) }).lookup("example.org"))
        assertThrows(UnknownHostException::class.java) { PublicPageDns(object : Dns { override fun lookup(hostname: String) = listOf(publicAddress, privateAddress) }).lookup("example.org") }
    }

    @Test fun `follows relative redirects without credentials and validates each target`() {
        val ok = client { request, count ->
            assertNull(request.header("Authorization")); assertNull(request.header("Cookie"))
            if (count == 0) response(request, 302, location = "/final") else {
                assertEquals("/final", request.url.encodedPath); response(request)
            }
        }.fetch(url)
        assertEquals("https://example.org/final", ok.url)
        error("invalid_url") { client { request, _ -> response(request, 302, location = "http://127.0.0.1/") }.fetch(url) }
        error("redirect") { client { request, _ -> response(request, 302, location = "/loop") }.fetch(url) }
    }

    @Test fun `checks status MIME size and transport before extraction`() {
        error("blocked") { client { r, _ -> response(r, 403) }.fetch(url) }
        error("http_error") { client { r, _ -> response(r, 404) }.fetch(url) }
        error("unsupported_content") { client { r, _ -> response(r, type = "application/pdf") }.fetch(url) }
        error("too_large") { client { r, _ -> response(r, body = "x".repeat(2_000_001)) }.fetch(url) }
        error("timeout") { client { _, _ -> throw SocketTimeoutException() }.fetch(url) }
        error("network") { client { _, _ -> throw IOException("private detail") }.fetch(url) }
    }

    @Test fun `schema stays compact and registry rejects invalid arguments`() {
        val registry = IntegrationRegistry(listOf(PageReadIntegration(PageReader(PageSource { page() }))))
        assertTrue(registry.tools().toString().length < 600)
        for (args in listOf("{}", "{\"url\":1}", "{\"url\":\"$url\",\"offset\":-1}", "{\"url\":\"$url\",\"offset\":0.5}", "{\"url\":\"$url\",\"extra\":true}"))
            assertThrows(ToolArgumentException::class.java) { registry.execute("read_page", JSONObject(args)) }
        val result = JSONObject(registry.execute("read_page", JSONObject().put("url", "file:///bad")))
        assertEquals("invalid_url", result.getString("error_type"))
    }

    @Test fun `agent can search then read without changing its dispatcher or system prompt`() {
        var rounds = 0
        var reads = 0
        val registry = IntegrationRegistry(listOf(
            WebSearchIntegration(WebSearch(SearchHtmlSource { "<table><tr><td><a class='result-link' href='$url'>Museu</a></td></tr></table>" })),
            PageReadIntegration(PageReader(PageSource { reads++; page() }))
        ))
        val gateway = object : LlmGateway {
            override fun complete(key: String, messages: JSONArray, tools: JSONArray, onProgress: (String) -> Unit): JSONObject {
                rounds++
                if (rounds == 3) {
                    val result = JSONObject(messages.getJSONObject(messages.length()-1).getString("content"))
                    assertTrue(result.getString("text").contains("nove horas"))
                    return JSONObject().put("role", "assistant").put("content", "Abre às nove. Fonte: $url")
                }
                val name = if (rounds == 1) "web_search" else "read_page"
                val args = if (rounds == 1) JSONObject().put("query", "museu") else JSONObject().put("url", url)
                return JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject()
                    .put("id", "call$rounds").put("type", "function").put("function", JSONObject().put("name", name).put("arguments", args.toString()))))
            }
        }
        val events = mutableListOf<String>()
        val agent = ChatClient(gateway, registry, AgentPrompts { File("src/main/assets/agent/prompts/$it").readText() })
        assertTrue(agent.reply("key", "Horários", onTrace = { name, _ -> events.add(name) }).contains("nove"))
        assertEquals(1, reads)
        assertTrue(events.any { it.startsWith("Retorno de ferramenta · read_page") })
    }

    @Test fun `live page read uses production HTTP and extractor`() {
        assumeTrue(System.getenv("ORBI_PAGE_LIVE_TEST") == "1")
        val result = PageReader().read(System.getenv("ORBI_PAGE_URL") ?: "https://jsoup.org/cookbook/extracting-data/selector-syntax")
        assertTrue(result.getString("text").length > 200)
        assertFalse(result.getString("text").contains("analytics.ahrefs.com"))
        println("Page read: ${result.getString("url")}; ${result.getString("extraction")}; ${result.getInt("total_chars")} characters")
    }
}
