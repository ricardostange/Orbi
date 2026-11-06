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
    fun completeWithUsage(key: String, messages: JSONArray, tools: JSONArray,
        onProgress: (String) -> Unit, onUsage: (Long?) -> Unit): JSONObject = complete(key, messages, tools, onProgress)
}

/** Counts reported by the provider, never estimates from text or token limits. */
object LlmUsage {
    fun total(usage: JSONObject?): Long? {
        fun count(name: String): Long? {
            val value = usage?.opt(name) as? Number ?: return null
            return value.toLong().takeIf { it >= 0 && value.toDouble().isFinite() && it.toDouble() == value.toDouble() }
        }
        count("total_tokens")?.let { return it }
        val input = count("prompt_tokens") ?: return null
        val output = count("completion_tokens") ?: return null
        return if (input <= Long.MAX_VALUE - output) input + output else null
    }
}

/** One HTTP call. The agent owns history, tool dispatch and bounded iteration. */
class OpenAiCompatibleClient(client: OkHttpClient, private val config: LlmConfig) : LlmGateway {
    private val http = client.newBuilder()
        .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(config.callTimeoutSeconds, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    override fun complete(key: String, messages: JSONArray, tools: JSONArray, onProgress: (String) -> Unit): JSONObject =
        completeWithUsage(key, messages, tools, onProgress) {}

    override fun completeWithUsage(key: String, messages: JSONArray, tools: JSONArray,
        onProgress: (String) -> Unit, onUsage: (Long?) -> Unit): JSONObject {
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
            val envelope = try {
                JSONObject(response.body?.string() ?: error("Resposta vazia."))
            } catch (_: org.json.JSONException) { error("Resposta incompatível com chat/completions. Confira endpoint e modelo.") }
            // Even a truncated or otherwise unusable response can consume tokens.
            onUsage(LlmUsage.total(envelope.optJSONObject("usage")))
            val choice = envelope.getJSONArray("choices").getJSONObject(0)
            val finishReason = choice.optString("finish_reason")
            check(finishReason != "length" && finishReason != "content_filter") {
                "O modelo não concluiu a resposta (finish_reason=$finishReason). Nenhuma ferramenta desta resposta foi executada."
            }
            envelope.optJSONObject("usage")?.let { usage ->
                onProgress("LLM: tokens entrada=${usage.optLong("prompt_tokens", -1)}; saída=${usage.optLong("completion_tokens", -1)}; total=${usage.optLong("total_tokens", -1)}")
            }
            val result = choice.getJSONObject("message")
            // Do not log the raw body, credentials, user text, or tool arguments.
            onProgress("LLM: finish_reason=${choice.optString("finish_reason", "não informado")}; chamadas estruturadas=${result.optJSONArray("tool_calls")?.length() ?: 0}")
            result
        }
    }
}
