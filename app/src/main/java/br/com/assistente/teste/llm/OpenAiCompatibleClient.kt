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
    fun stream(key: String, messages: JSONArray, tools: JSONArray, onProgress: (String) -> Unit,
        onUsage: (Long?) -> Unit, onText: (String) -> Unit): JSONObject =
        completeWithUsage(key, messages, tools, onProgress, onUsage)
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
        onProgress: (String) -> Unit, onUsage: (Long?) -> Unit): JSONObject =
        stream(key, messages, tools, onProgress, onUsage) {}

    override fun stream(key: String, messages: JSONArray, tools: JSONArray, onProgress: (String) -> Unit,
        onUsage: (Long?) -> Unit, onText: (String) -> Unit): JSONObject {
        val started = System.nanoTime()
        var firstContent = false
        fun contentArrived() {
            if (!firstContent) {
                firstContent = true
                onProgress("LLM: primeiro conteúdo=${(System.nanoTime() - started) / 1_000_000} ms")
            }
        }
        fun reportUsage(usage: JSONObject?) {
            onUsage(LlmUsage.total(usage))
            if (usage != null) {
                fun value(name: String) = usage.opt(name)?.takeUnless { it == JSONObject.NULL }?.toString() ?: "não informado"
                val cached = usage.opt("prompt_cache_hit_tokens")?.takeUnless { it == JSONObject.NULL }
                    ?: usage.optJSONObject("prompt_tokens_details")?.opt("cached_tokens")?.takeUnless { it == JSONObject.NULL }
                onProgress("LLM: tokens entrada=${value("prompt_tokens")}; saída=${value("completion_tokens")}; total=${value("total_tokens")}; cache hit=${cached ?: "não informado"}; cache miss=${value("prompt_cache_miss_tokens")}")
            }
        }
        val body = JSONObject(config.extraBody).put("model", config.model).put("messages", messages)
            .put("stream", true).put("stream_options", JSONObject().put("include_usage", true)).put("temperature", config.temperature).put("max_tokens", config.maxTokens)
        if (tools.length() > 0) body.put("tools", tools).put("tool_choice", "auto")
        val request = Request.Builder().url(config.endpoint).header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return try {
            http.newCall(request).execute().use { response ->
                check(response.isSuccessful) {
                    when (response.code) {
                        401, 403 -> "Chave recusada. Confira sua configuração de API."
                        402 -> "Saldo insuficiente na conta do provedor."
                        429 -> "Limite de requisições atingido. Tente novamente em instantes."
                        else -> "Provedor indisponível (HTTP ${response.code}). Tente novamente."
                    }
                }
                if (response.body?.contentType()?.subtype == "event-stream") {
                    return@use ChatStreamReader.read(response.body!!.source(), ::reportUsage, ::contentArrived, onText)
                }
                val envelope = try {
                    JSONObject(response.body?.string() ?: error("Resposta vazia."))
                } catch (_: org.json.JSONException) { error("Resposta incompatível com chat/completions. Confira endpoint e modelo.") }
                // Even a truncated or otherwise unusable response can consume tokens.
                reportUsage(envelope.optJSONObject("usage"))
                val choice = envelope.getJSONArray("choices").getJSONObject(0)
                val finishReason = choice.optString("finish_reason")
                check(finishReason != "length" && finishReason != "content_filter") {
                    "O modelo não concluiu a resposta (finish_reason=$finishReason). Nenhuma ferramenta desta resposta foi executada."
                }
                val result = choice.getJSONObject("message")
                // Do not log the raw body, credentials, user text, or tool arguments.
                onProgress("LLM: finish_reason=${choice.optString("finish_reason", "não informado")}; chamadas estruturadas=${result.optJSONArray("tool_calls")?.length() ?: 0}")
                contentArrived()
                result
            }
        } finally {
            onProgress("LLM: duração total=${(System.nanoTime() - started) / 1_000_000} ms")
        }
    }
}
