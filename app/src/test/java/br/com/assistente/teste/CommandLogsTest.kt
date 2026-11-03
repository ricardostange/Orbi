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
}
