package br.com.assistente.teste.integrations.web

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** A dedicated, unauthenticated client: never shares LLM/Spotify credentials. */
class DuckDuckGoClient(client: OkHttpClient = OkHttpClient()) : SearchHtmlSource {
    private val http = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()

    override fun fetch(query: String): String {
        val url = ENDPOINT.toHttpUrl().newBuilder().addQueryParameter("q", query).build()
        val request = Request.Builder().url(url).header("Accept", "text/html")
            .header("User-Agent", "Orbi/1.0").build()
        try {
            http.newCall(request).execute().use { response ->
                if (response.code in listOf(202, 403, 429)) {
                    throw WebSearchException("blocked", "O buscador bloqueou a consulta. Tente mais tarde.")
                }
                if (!response.isSuccessful) {
                    throw WebSearchException("http_error", "Busca indisponível (HTTP ${response.code}).")
                }
                val body = response.body ?: throw WebSearchException("invalid_response", "Busca sem conteúdo.")
                val type = body.contentType()
                if (type?.type != "text" || type.subtype != "html") {
                    throw WebSearchException("invalid_response", "O buscador não retornou HTML.")
                }
                val source = body.source()
                if (source.request(MAX_BYTES + 1)) {
                    throw WebSearchException("response_too_large", "Resposta do buscador excedeu o limite.")
                }
                return source.readString(type.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
            }
        } catch (e: InterruptedIOException) {
            throw WebSearchException("timeout", "A busca excedeu o tempo de espera.")
        } catch (e: IOException) {
            throw WebSearchException("network", "Não foi possível conectar ao buscador.")
        }
    }

    companion object {
        const val ENDPOINT = "https://lite.duckduckgo.com/lite/"
        const val MAX_BYTES = 512_000L
    }
}
