package br.com.assistente.teste.integrations

import org.json.JSONArray
import org.json.JSONObject

/** Validate the shared schema subset before dispatch; providers retain domain validation. */
object ToolArguments {
    fun validate(value: Any, schema: JSONObject, path: String = "arguments") {
        val type = schema.optString("type")
        val valid = when (type) {
            "object" -> value is JSONObject
            "array" -> value is JSONArray
            "string" -> value is String
            "boolean" -> value is Boolean
            "integer" -> value is Number && value.toDouble().isFinite() && value.toDouble() % 1.0 == 0.0
            "number" -> value is Number && value.toDouble().isFinite()
            "" -> true
            else -> false
        }
        require(valid) { "Tipo inválido em $path: esperado $type." }
        schema.optJSONArray("enum")?.let { options ->
            require((0 until options.length()).any { options.get(it) == value }) {
                "Valor fora das opções permitidas em $path."
            }
        }
        if (value is Number) {
            require(!schema.has("minimum") || value.toDouble() >= schema.getDouble("minimum")) { "Valor abaixo do mínimo em $path." }
            require(!schema.has("maximum") || value.toDouble() <= schema.getDouble("maximum")) { "Valor acima do máximo em $path." }
        }
        if (value is JSONObject) {
            val properties = schema.optJSONObject("properties") ?: JSONObject()
            schema.optJSONArray("required")?.let { required ->
                for (i in 0 until required.length()) {
                    val key = required.getString(i)
                    require(value.has(key) && !value.isNull(key)) { "Campo obrigatório ausente em $path.$key." }
                }
            }
            value.keys().forEach { key ->
                require(properties.has(key) || schema.optBoolean("additionalProperties", true)) { "Campo desconhecido em $path.$key." }
                properties.optJSONObject(key)?.let { validate(value.get(key), it, "$path.$key") }
            }
        }
        if (value is JSONArray) {
            require(!schema.has("minItems") || value.length() >= schema.getInt("minItems")) { "Poucos itens em $path." }
            require(!schema.has("maxItems") || value.length() <= schema.getInt("maxItems")) { "Muitos itens em $path." }
            schema.optJSONObject("items")?.let { itemSchema ->
                for (i in 0 until value.length()) validate(value.get(i), itemSchema, "$path[$i]")
            }
        }
    }
}
