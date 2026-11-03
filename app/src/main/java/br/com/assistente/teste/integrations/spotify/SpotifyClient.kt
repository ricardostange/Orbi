package br.com.assistente.teste.integrations.spotify

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class SpotifyClient(private val client: OkHttpClient, private val auth: SpotifyAuth) : SpotifyTransport {
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
            client.newCall(request).execute().use { response ->
                if (response.code == 401 && attempt == 0) return@use
                if (!response.isSuccessful) {
                    val reason = runCatching {
                        JSONObject(response.body?.string().orEmpty()).optJSONObject("error")?.optString("reason").orEmpty()
                    }.getOrDefault("")
                    error(SpotifyErrors.api(response.code, reason, response.header("Retry-After")))
                }
                val raw = response.body?.string().orEmpty()
                if (raw.isBlank()) return JSONObject().put("ok", true)
                    .put("message", if (method == "GET") "Nenhuma reprodução ou dado disponível." else "Comando aceito pelo Spotify.").toString()
                val data = JSONObject(raw)
                prune(data)
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
