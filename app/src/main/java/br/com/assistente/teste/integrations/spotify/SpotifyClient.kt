package br.com.assistente.teste.integrations.spotify

import br.com.assistente.teste.llm.SafeDiagnostics
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class SpotifyClient(private val client: OkHttpClient, private val auth: SpotifyAuth, private val trace: (String) -> Unit = {}) : SpotifyTransport {
    override fun request(method: String, path: String, query: Map<String, String>, body: JSONObject?): String {
        val url = "https://api.spotify.com/v1/".toHttpUrl().newBuilder().apply {
            addPathSegments(path)
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        repeat(2) { attempt ->
            val token = auth.token(forceRefresh = attempt == 1)
            val payload = if (method == "GET") null else (body?.toString() ?: "")
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
                .method(method, payload).build()
            val started = System.nanoTime()
            trace("Spotify HTTP: $method /$path; query=${SafeDiagnostics.json(JSONObject(query))}; body=${body?.let(SafeDiagnostics::json) ?: "vazio"}")
            client.newCall(request).execute().use { response ->
                trace("Spotify HTTP: status=${response.code}; duração_ms=${(System.nanoTime() - started) / 1_000_000}")
                if (response.code == 401 && attempt == 0) return@use
                if (!response.isSuccessful) {
                    val failure = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrDefault(JSONObject())
                    trace("Spotify erro: ${SafeDiagnostics.json(failure)}")
                    val error = failure.optJSONObject("error")
                    val reason = error?.optString("reason").orEmpty().ifBlank {
                        if (error?.optString("message").orEmpty().contains("insufficient", ignoreCase = true)) "INSUFFICIENT_SCOPE" else ""
                    }
                    error(SpotifyErrors.api(response.code, reason, response.header("Retry-After")))
                }
                val raw = response.body?.string().orEmpty()
                if (raw.isBlank()) return JSONObject().put("ok", true)
                    .put("message", if (method == "GET") "Nenhuma reprodução ou dado disponível." else "Comando aceito pelo Spotify.").toString()
                val data = JSONObject(raw)
                prune(data)
                trace("Spotify resposta: ${SafeDiagnostics.json(data)}")
                val compact = data.toString()
                return if (compact.length <= 24_000) compact else JSONObject()
                    .put("truncated", true).put("data_excerpt", compact.take(24_000))
                    .put("hint", "Use paginação para os próximos resultados.").toString()
            }
        }
        error("Não foi possível autenticar no Spotify.")
    }
    // Keep names, IDs, URIs and attribution links, but avoid sending images and irrelevant metadata to the LLM.
    private fun prune(value: Any) {
        when (value) {
            is JSONObject -> {
                listOf("images", "available_markets", "external_ids", "preview_url").forEach(value::remove)
                value.keys().asSequence().toList().forEach { prune(value.get(it)) }
            }
            is JSONArray -> for (i in 0 until value.length()) prune(value.get(i))
        }
    }
}

