package br.com.assistente.teste.llm

import org.json.JSONArray
import org.json.JSONObject

/** Diagnostics are opt-in to view/copy; never include tokens or authorization payloads. */
object SafeDiagnostics {
    private val secretFields = setOf("access_token", "refresh_token", "token", "authorization", "api_key", "client_secret", "code", "state", "email")
    fun json(value: Any): String {
        fun clean(item: Any): Any = when (item) {
            is JSONObject -> JSONObject().apply {
                item.keys().forEach { key -> put(key, if (key.lowercase() in secretFields && item.get(key) !is JSONObject) "[redigido]" else clean(item.get(key))) }
            }
            is JSONArray -> JSONArray().apply { for (i in 0 until minOf(item.length(), 20)) put(clean(item.get(i))) }
            is String -> item.replace(Regex("(?i)Bearer\\s+[^\\s\"]+"), "Bearer [redigido]").take(500)
            else -> item
        }
        return clean(value).toString().take(6000)
    }
}
