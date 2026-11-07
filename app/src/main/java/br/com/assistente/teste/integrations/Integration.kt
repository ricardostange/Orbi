package br.com.assistente.teste.integrations

import org.json.JSONArray
import org.json.JSONObject

/** Providers own authentication and operations; chat only knows this contract. Runs on IO. */
interface Integration {
    val id: String
    val connected: Boolean
    val tools: List<JSONObject>
    fun execute(tool: String, arguments: JSONObject): String
}

class IntegrationRegistry(
    private val providers: List<Integration>,
    private val selectedTools: () -> Set<String>? = { null }
) {
    init {
        require(providers.map { it.id }.distinct().size == providers.size)
        val names = providers.flatMap { it.tools }.map { it.getJSONObject("function").getString("name") }
        require(names.distinct().size == names.size)
    }
    fun catalog(): List<JSONObject> = providers.flatMap { it.tools }
    private fun selected(name: String) = selectedTools()?.contains(name) != false
    fun tools() = JSONArray().apply {
        providers.filter { it.connected }.flatMap { it.tools }
            .filter { selected(it.getJSONObject("function").getString("name")) }.forEach { put(it) }
    }
    fun execute(name: String, arguments: JSONObject): String {
        require(selected(name)) { "Ferramenta desmarcada para esta conversa." }
        val provider = providers.firstOrNull { integration ->
            integration.connected && integration.tools.any { it.getJSONObject("function").getString("name") == name }
        } ?: error("Integração desconectada ou ferramenta desconhecida.")
        val schema = provider.tools.first { it.getJSONObject("function").getString("name") == name }
            .getJSONObject("function").getJSONObject("parameters")
        ToolArguments.validate(arguments, schema)
        return provider.execute(name, arguments)
    }
}
