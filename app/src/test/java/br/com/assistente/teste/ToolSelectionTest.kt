package br.com.assistente.teste

import br.com.assistente.teste.integrations.Integration
import br.com.assistente.teste.integrations.IntegrationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolSelectionTest {
    @Test fun `selection filters schemas and dispatcher but catalog includes disconnected tools`() {
        var providerConnected = true
        var calls = 0
        var selected: Set<String>? = setOf("read")
        val provider = object : Integration {
            override val id = "test"
            override val connected get() = providerConnected
            override val tools = listOf("read", "write").map { name ->
                JSONObject("""{"type":"function","function":{"name":"$name","description":"Test","parameters":{"type":"object","properties":{},"additionalProperties":false}}}""")
            }
            override fun execute(tool: String, arguments: JSONObject): String { calls++; return "{}" }
        }
        val registry = IntegrationRegistry(listOf(provider)) { selected }
        assertEquals(1, registry.tools().length())
        assertEquals("read", registry.tools().getJSONObject(0).getJSONObject("function").getString("name"))
        assertThrows(IllegalArgumentException::class.java) { registry.execute("write", JSONObject()) }
        registry.execute("read", JSONObject())
        assertEquals(1, calls)
        selected = emptySet()
        assertEquals(0, registry.tools().length())
        assertThrows(IllegalArgumentException::class.java) { registry.execute("read", JSONObject()) }
        selected = null
        assertEquals(2, registry.tools().length())
        providerConnected = false
        assertEquals(0, registry.tools().length())
        assertEquals(2, registry.catalog().size)
        assertThrows(IllegalStateException::class.java) { registry.execute("read", JSONObject()) }
        assertEquals(1, calls)
    }
}
