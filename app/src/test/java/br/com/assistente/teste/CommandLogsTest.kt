package br.com.assistente.teste

import android.content.Context
import br.com.assistente.teste.logs.CommandLogs
import br.com.assistente.teste.logs.LogSanitizer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CommandLogsTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        CommandLogs(context).clear()
    }
    @Test fun `keeps newest 50 commands with all their events across reload`() {
        val logs = CommandLogs(context)
        repeat(53) {
            val id = logs.begin("Pedido $it", emptyList())
            logs.event(id, "Resultado", "Resposta $it", emptyList())
            logs.finish(id, "Concluído")
        }
        val reopened = CommandLogs(context).also { it.load() }.entries.value
        assertEquals(50, reopened.size)
        assertEquals("Pedido 52", reopened.first().command)
        assertEquals("Pedido 3", reopened.last().command)
        assertEquals("Resposta 52", reopened.first().events.single().detail)
        assertEquals("Concluído", reopened.first().status)
    }
    @Test fun `unfinished command is marked interrupted after reopening`() {
        CommandLogs(context).begin("Teste", emptyList())
        val reopened = CommandLogs(context).also { it.load() }
        assertEquals("Interrompido", reopened.entries.value.single().status)
        reopened.clear()
        assertTrue(CommandLogs(context).also { it.load() }.entries.value.isEmpty())
    }
    @Test fun `redacts nested credentials and known secrets before disk and copy`() {
        val logs = CommandLogs(context)
        val id = logs.begin("Use private-value", listOf("private-value"))
        val payload = JSONObject().put("arguments", JSONObject().put("refresh_token", "oauth-secret")
            .put("nested", JSONObject().put("apiKey", "other-secret"))).put("content", "Bearer header-secret").toString()
        logs.event(id, "Ferramenta", payload, listOf("private-value"))
        logs.finish(id, "Erro")
        val report = CommandLogs(context).also { it.load() }.entries.value.single().report()
        listOf("private-value", "oauth-secret", "other-secret", "header-secret").forEach { assertFalse(report.contains(it)) }
        assertTrue(report.contains("[redigido]"))
        val encoded = JSONObject().put("arguments", """{"access_token":"encoded-secret","action":"pause"}""").toString()
        assertFalse(LogSanitizer.clean(encoded).contains("encoded-secret"))
        assertTrue(LogSanitizer.clean(encoded).contains("pause"))
    }
    @Test fun `bounds long traces and signals abbreviation`() {
        val logs = CommandLogs(context)
        val id = logs.begin("Longo", emptyList())
        repeat(250) { logs.event(id, "Etapa", "x".repeat(20_000), emptyList()) }
        val entry = logs.entries.value.single()
        assertTrue(entry.events.size <= 201)
        assertTrue(entry.report().length < 150_000)
        assertTrue(entry.report().contains("abreviado"))
    }

    @Test fun `totals and monotonic duration persist independently of clipped traces`() {
        var clock = 1000L
        val logs = CommandLogs(context) { clock }
        val id = logs.begin("Busca", emptyList())
        logs.usage(id, 1200)
        repeat(9) { logs.event(id, "Etapa", "x".repeat(20_000), emptyList()) }
        logs.usage(id, 450)
        clock = 3456
        logs.finish(id, "Processado")
        clock = 9999
        logs.finish(id, "Processado")
        val row = CommandLogs(context).also { it.load() }.entries.value.single()
        assertEquals(1650L, row.totalTokens)
        assertTrue(row.tokensComplete)
        assertEquals(2456L, row.durationMs)
        assertTrue(row.metrics().contains("1.650"))
        assertTrue(row.metrics().contains("2,5 s"))
        assertTrue(row.report().contains(row.metrics()))
    }

    @Test fun `missing usage is partial and interrupted duration is never invented`() {
        val logs = CommandLogs(context)
        val id = logs.begin("Teste", emptyList())
        logs.usage(id, 100)
        logs.usage(id, null)
        assertTrue(logs.entries.value.single().metrics().contains("100 (parcial)"))
        val row = CommandLogs(context).also { it.load() }.entries.value.single()
        assertFalse(row.tokensComplete)
        assertNull(row.durationMs)
        assertEquals("Interrompido", row.status)
    }

    @Test fun `legacy records show unavailable metrics and still load`() {
        java.io.File(context.filesDir, "command-log.json").writeText(
            """[{"id":"old","time":123,"command":"Antigo","status":"Processado","events":[]}]""")
        val row = CommandLogs(context).also { it.load() }.entries.value.single()
        assertNull(row.totalTokens)
        assertNull(row.durationMs)
        assertEquals("Tokens: — · Tempo: —", row.metrics())
    }
}
