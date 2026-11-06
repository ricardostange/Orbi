package br.com.assistente.teste.integrations.web

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup

/** Parses Lite table rows, keeping each snippet attached to its own result. */
class DuckDuckGoParser {
    fun parse(html: String): List<SearchResult> {
        val doc = Jsoup.parse(html, DuckDuckGoClient.ENDPOINT)
        if (doc.select("#challenge-form, #img-form, .anomaly-modal, form[action*=anomaly.js]").isNotEmpty()) {
            throw WebSearchException("blocked", "O buscador pediu uma verificação humana. Tente mais tarde.")
        }
        doc.select("script, style, noscript").remove()
        val links = doc.select("a.result-link")
        if (links.isEmpty()) {
            if (doc.select(".no-results, .no-results__message").isNotEmpty() ||
                doc.select("td").any { it.text().startsWith("No results found for") }) return emptyList()
            throw WebSearchException("unexpected_html", "O formato da página de busca não foi reconhecido.")
        }
        val results = mutableListOf<SearchResult>()
        val seen = mutableSetOf<String>()
        for (link in links) {
            val url = destination(link.attr("href")) ?: continue
            val title = link.text().trim().take(180)
            if (title.isEmpty() || !seen.add(url)) continue
            var row = link.closest("tr")?.nextElementSibling()
            var snippet = ""
            while (row != null && row.select("a.result-link").isEmpty()) {
                val text = row.selectFirst(".result-snippet")
                if (text != null) { snippet = text.text().trim().take(600); break }
                row = row.nextElementSibling()
            }
            results.add(SearchResult(title, url, snippet))
            if (results.size == 5) break
        }
        if (results.isEmpty()) throw WebSearchException("unexpected_html", "A busca não retornou links válidos.")
        return results
    }

    private fun destination(raw: String): String? {
        var url = DuckDuckGoClient.ENDPOINT.toHttpUrl().resolve(raw) ?: return null
        if (url.host == "duckduckgo.com" || url.host.endsWith(".duckduckgo.com")) {
            if (url.encodedPath != "/l/") return null
            url = url.queryParameter("uddg")?.toHttpUrlOrNull() ?: return null
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.host == "duckduckgo.com" || url.host.endsWith(".duckduckgo.com")) return null
        return url.toString().takeIf { it.length <= 1_200 }
    }
}
