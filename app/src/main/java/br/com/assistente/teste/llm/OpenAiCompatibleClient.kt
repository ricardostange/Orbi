package br.com.assistente.teste.llm

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

fun interface LlmGateway {
    fun complete(key: String, messages: JSONArray, tools: JSONArray, onProgress: (String) -> Unit): JSONObject
}

/** One HTTP call. The agent owns history, tool dispatch and bounded iteration. */
class OpenAiCompatibleClient(client: OkHttpClient, private val config: LlmConfig) : LlmGateway {
    private val http = client.newBuilder()
        .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(config.callTimeoutSeconds, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    override fun complete(key: String, messages: JSONArray, tools: JSONArray, onProgress: (String) -> Unit): JSONObject {
        val body = JSONObject(config.extraBody).put("model", config.model).put("messages", messages)
            .put("stream", false).put("temperature", config.temperature).put("max_tokens", config.maxTokens)
        if (tools.length() > 0) body.put("tools", tools).put("tool_choice", "auto")
        val request = Request.Builder().url(config.endpoint).header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return http.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                when (response.code) {
                    401, 403 -> "Chave recusada. Confira sua configuração de API."
                    402 -> "Saldo insuficiente na conta do provedor."
                    429 -> "Limite de requisições atingido. Tente novamente em instantes."
                    else -> "Provedor indisponível (HTTP ${response.code}). Tente novamente."
                }
            }
            val choice = try {
                JSONObject(response.body?.string() ?: error("Resposta vazia."))
                    .getJSONArray("choices").getJSONObject(0)
            } catch (_: org.json.JSONException) { error("Resposta incompatível com chat/completions. Confira endpoint e modelo.") }
            val result = choice.getJSONObject("message")
            // Do not log the raw body, credentials, user text, or tool arguments.
            onProgress("LLM: finish_reason=${choice.optString("finish_reason", "não informado")}; chamadas estruturadas=${result.optJSONArray("tool_calls")?.length() ?: 0}")
            result
        }
    }
}
