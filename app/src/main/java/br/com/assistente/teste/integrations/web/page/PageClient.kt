package br.com.assistente.teste.integrations.web.page

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class PageException(val code: String, message: String) : Exception(message)
data class PageHtml(val url: String, val bytes: ByteArray, val charset: String? = null)
fun interface PageSource { fun fetch(url: String): PageHtml }

/** Public web only; the same policy applies to every redirect and resolved address. */
object PageAddress {
    fun parse(value: String): HttpUrl {
        val url = value.takeIf { it.length <= 2048 }?.toHttpUrlOrNull()
            ?: throw PageException("invalid_url", "Informe uma URL HTTP ou HTTPS válida.")
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.port !in listOf(80, 443) ||
            !url.host.contains('.') && !url.host.contains(':') ||
            url.host == "localhost" || url.host.endsWith(".localhost") || url.host.endsWith(".local")) {
            throw PageException("invalid_url", "Use uma página pública sem credenciais, na porta 80 ou 443.")
        }
        if (url.host.contains(':') || url.host.all { it.isDigit() || it == '.' }) {
            val address = try { InetAddress.getByName(url.host) } catch (_: UnknownHostException) {
                throw PageException("invalid_url", "Endereço inválido.")
            }
            if (!isPublic(address)) throw PageException("invalid_url", "Endereços locais ou privados não são permitidos.")
        }
        // Android blocks cleartext by default. Upgrade old search links instead of
        // relaxing the app-wide policy (LLM and OAuth must remain HTTPS-only).
        return url.newBuilder().scheme("https").port(443).fragment(null).build()
    }

    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 255 }
        return if (bytes.size == 4) {
            bytes[0] !in listOf(0, 10, 127) && bytes[0] < 224 &&
                !(bytes[0] == 100 && bytes[1] in 64..127) &&
                !(bytes[0] == 169 && bytes[1] == 254) &&
                !(bytes[0] == 172 && bytes[1] in 16..31) &&
                !(bytes[0] == 192 && bytes[1] == 168) &&
                !(bytes[0] == 198 && bytes[1] in 18..19)
        } else {
            // Only global unicast; excludes ULA, mapped/translation and link-local ranges.
            bytes[0] and 0xe0 == 0x20 && !(bytes[0] == 0x20 && bytes[1] == 0x02) &&
                !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0 && bytes[3] == 0)
        }
    }
}

class PublicPageDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !PageAddress.isPublic(it) })
            throw UnknownHostException("Endereço não público.")
        return addresses
    }
}

class PageClient(client: OkHttpClient = OkHttpClient()) : PageSource {
    private val http = client.newBuilder().dns(PublicPageDns())
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()

    override fun fetch(url: String): PageHtml {
        var target = PageAddress.parse(url)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        try {
            repeat(5) { hop ->
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw PageException("timeout", "A leitura excedeu o tempo de espera.")
                val call = http.newCall(Request.Builder().url(target)
                    .header("Accept", "text/html, application/xhtml+xml").header("User-Agent", "Orbi/1.0").build())
                call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
                call.execute().use { response ->
                    if (response.code in listOf(301, 302, 303, 307, 308)) {
                        val location = response.header("Location")
                        val next = location?.let { target.resolve(it) }
                            ?: throw PageException("redirect", "Redirecionamento inválido.")
                        if (hop == 4) throw PageException("redirect", "A página redirecionou muitas vezes.")
                        target = PageAddress.parse(next.toString())
                    } else {
                        if (response.code in listOf(401, 403, 429))
                            throw PageException("blocked", "A página exige acesso ou bloqueou a leitura.")
                        if (!response.isSuccessful) throw PageException("http_error", "Página indisponível (HTTP ${response.code}).")
                        val body = response.body ?: throw PageException("empty", "Página sem conteúdo.")
                        val type = body.contentType()
                        if (!(type?.type == "text" && type.subtype == "html") &&
                            !(type?.type == "application" && type.subtype == "xhtml+xml"))
                            throw PageException("unsupported_content", "Esta ferramenta lê páginas HTML; o endereço retornou outro formato.")
                        val source = body.source()
                        if (source.request(MAX_BYTES + 1)) throw PageException("too_large", "O HTML excedeu o limite de 2 MB.")
                        return PageHtml(target.toString(), source.readByteArray(), type?.charset()?.name())
                    }
                }
            }
        } catch (_: InterruptedIOException) {
            throw PageException("timeout", "A leitura excedeu o tempo de espera.")
        } catch (_: IOException) {
            throw PageException("network", "Não foi possível acessar a página pública.")
        }
        throw PageException("redirect", "Não foi possível concluir o redirecionamento.")
    }
    companion object { const val MAX_BYTES = 2_000_000L }
}
