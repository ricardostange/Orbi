package br.com.assistente.teste.llm

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Public connection settings only. Credentials remain in ApiKeyStore/Android Keystore. */
data class LlmConfig(
    val provider: String,
    val endpoint: String,
    val model: String,
    val temperature: Double,
    val maxTokens: Int,
    val connectTimeoutSeconds: Long,
    val readTimeoutSeconds: Long,
    val callTimeoutSeconds: Long,
    val extraBody: String
) {
    init {
        val url = endpoint.toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "O endpoint deve ser HTTPS, sem credenciais, query ou fragmento."
        }
        require(provider.isNotBlank() && model.isNotBlank()) { "Informe provedor e modelo." }
        require(temperature.isFinite() && temperature in 0.0..2.0) { "Temperatura deve estar entre 0 e 2." }
        require(maxTokens in 1..32768) { "Limite de tokens inválido." }
        require(connectTimeoutSeconds in 1..300 && readTimeoutSeconds in 1..300 && callTimeoutSeconds in 1..600) { "Timeout inválido." }
        val extras = JSONObject(extraBody)
        require(extras.keys().asSequence().all { it in setOf("thinking", "reasoning_effort", "top_p", "frequency_penalty", "presence_penalty") }) {
            "Parâmetro extra não permitido."
        }
    }
    fun toJson(): JSONObject = JSONObject().put("provider", provider).put("endpoint", endpoint).put("model", model)
        .put("temperature", temperature).put("max_tokens", maxTokens)
        .put("connect_timeout_seconds", connectTimeoutSeconds).put("read_timeout_seconds", readTimeoutSeconds)
        .put("call_timeout_seconds", callTimeoutSeconds).put("extra_body", JSONObject(extraBody))

    companion object {
        fun fromJson(json: JSONObject) = LlmConfig(
            json.getString("provider"), json.getString("endpoint"), json.getString("model"),
            json.getDouble("temperature"), json.getInt("max_tokens"), json.getLong("connect_timeout_seconds"),
            json.getLong("read_timeout_seconds"), json.getLong("call_timeout_seconds"),
            json.optJSONObject("extra_body")?.toString() ?: "{}"
        )
    }
}
