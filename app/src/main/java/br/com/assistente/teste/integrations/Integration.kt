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

class IntegrationRegistry(private val providers: List<Integration>) {
    init {
        require(providers.map { it.id }.distinct().size == providers.size)
        val names = providers.flatMap { it.tools }.map { it.getJSONObject("function").getString("name") }
        require(names.distinct().size == names.size)
    }
    fun tools() = JSONArray().apply {
        providers.filter { it.connected }.flatMap { it.tools }.forEach { put(it) }
    }
    fun execute(name: String, arguments: JSONObject): String {
        val provider = providers.firstOrNull { integration ->
            integration.connected && integration.tools.any { it.getJSONObject("function").getString("name") == name }
        } ?: error("Integração desconectada ou ferramenta desconhecida.")
        return provider.execute(name, arguments)
    }
}
