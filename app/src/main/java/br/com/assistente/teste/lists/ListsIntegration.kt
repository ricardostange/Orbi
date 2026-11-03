package br.com.assistente.teste.lists

import br.com.assistente.teste.integrations.Integration
import org.json.JSONArray
import org.json.JSONObject

class ListsIntegration(private val repository: ListsRepository) : Integration {
    override val id = "lists"
    override val connected = true
    override val tools = listOf(
        tool("list_lists", "Consulte as listas reais e seus IDs antes de resolver nomes.", emptyMap()),
        tool("create_list", "Crie uma lista com nome não vazio e único.", mapOf("name" to "string")),
        tool("rename_list", "Renomeie uma lista pelo ID consultado.", mapOf("list_id" to "string", "new_name" to "string")),
        tool("delete_list", "Solicite confirmação na tela para excluir a lista e seus itens. Esta chamada NÃO exclui: aguarde o usuário confirmar na interface.", mapOf("list_id" to "string")),
        tool("list_items", "Consulte IDs e estados reais dos itens. Inclua concluídos para resolver nomes ambíguos.", mapOf("list_id" to "string", "include_completed" to "boolean")),
        tool("add_item", "Adicione um item de texto não vazio à lista pelo ID. Para vários itens, execute uma chamada por item e confira cada resultado.", mapOf("list_id" to "string", "text" to "string")),
        tool("remove_item", "Remova exatamente o item consultado, pelo ID e lista. Homônimos exigem esclarecimento.", mapOf("list_id" to "string", "item_id" to "string")),
        tool("set_item_completed", "Marque ou desmarque exatamente o item consultado.", mapOf("list_id" to "string", "item_id" to "string", "completed" to "boolean"))
    )
    private fun tool(name: String, description: String, fields: Map<String, String>) = JSONObject()
        .put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("additionalProperties", false)
                .put("properties", JSONObject().apply { fields.forEach { (key, type) -> put(key, JSONObject().put("type", type)) } })
                .put("required", JSONArray(fields.keys.toList()))))

    override fun execute(tool: String, arguments: JSONObject): String {
        val schema = tools.firstOrNull { it.getJSONObject("function").getString("name") == tool }
            ?: error("Ferramenta desconhecida.")
        val fields = schema.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        require(arguments.keys().asSequence().toSet() == fields.keys().asSequence().toSet()) { "Argumentos inválidos para $tool." }
        fields.keys().forEach { field ->
            val value = arguments.get(field)
            require(if (fields.getJSONObject(field).getString("type") == "boolean") value is Boolean else value is String && value.isNotBlank()) { "Argumento inválido: $field." }
        }
        fun listId() = arguments.getString("list_id")
        val result = JSONObject().put("ok", true)
        when (tool) {
            "list_lists" -> result.put("lists", JSONArray().apply {
                repository.load().forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("item_count", it.items.size)) }
            })
            "create_list" -> result.put("list_id", repository.createList(arguments.getString("name")))
            "rename_list" -> repository.renameList(listId(), arguments.getString("new_name"))
            "delete_list" -> {
                val request = repository.requestDeletion(listId())
                result.put("deleted", false).put("requires_confirmation", true).put("list_id", request.listId)
                    .put("message", "Exclusão pendente: confirme ou cancele na tela do Orbi. Nenhum dado foi excluído.")
            }
            "list_items" -> {
                val list = repository.load().firstOrNull { it.id == listId() } ?: error("Lista não encontrada.")
                result.put("list_id", list.id).put("name", list.name).put("items", JSONArray().apply {
                    list.items.filter { arguments.getBoolean("include_completed") || !it.completed }.forEach {
                        put(JSONObject().put("id", it.id).put("text", it.text).put("completed", it.completed))
                    }
                })
            }
            "add_item" -> result.put("item_id", repository.addItem(listId(), arguments.getString("text")))
            "remove_item" -> repository.removeItem(listId(), arguments.getString("item_id"))
            "set_item_completed" -> repository.setCompleted(listId(), arguments.getString("item_id"), arguments.getBoolean("completed"))
        }
        return result.toString()
    }
}
