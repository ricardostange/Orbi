package br.com.assistente.teste.integrations.web.page

import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

data class PageText(val url: String, val title: String, val text: String, val extraction: String, val limited: Boolean)

/** Readability owns article selection; Jsoup handles encoding and plain-text structure. */
class PageExtractor {
    fun extract(page: PageHtml): PageText {
        val doc = Jsoup.parse(page.bytes.inputStream(), page.charset, page.url)
        if (doc.getAllElements().size > 50_000) throw PageException("too_large", "A página tem estrutura grande demais.")
        if (doc.select("#challenge-form, #challenge-running, #cf-challenge-running, form[action*=anomaly.js]").isNotEmpty() ||
            (doc.body().text().length < 500 && doc.select(".g-recaptcha, .h-captcha").isNotEmpty()))
            throw PageException("blocked", "A página pediu uma verificação humana.")
        val title = doc.title().trim().take(240)
        doc.select("script, style, noscript, nav, footer, aside, form, iframe, svg, canvas, template, [hidden], [aria-hidden=true]").remove()
        val broad = doc.selectFirst("main, [role=main], article") ?: doc.body()
        val article = try { Readability4J(page.url, doc.clone()).parse().articleContent }
            catch (_: Exception) { null }
        val selected = if (article != null && article.text().length >= 200) article else broad
        val text = plainText(selected)
        if (text.isBlank()) throw PageException("empty", "Não foi encontrado texto legível; a página pode depender de JavaScript.")
        return PageText(page.url, title, text.take(MAX_TEXT), if (selected === article) "readability" else "fallback", text.length > MAX_TEXT)
    }

    private fun plainText(root: Element): String {
        val out = StringBuilder()
        val blocks = setOf("p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr", "pre", "blockquote")
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when {
                    node is TextNode -> out.append(node.text().replace(Regex("\\s+"), " "))
                    node.nodeName() in blocks || node.nodeName() == "br" -> out.append('\n')
                    node.nodeName() in setOf("td", "th") -> out.append(" | ")
                }
            }
            override fun tail(node: Node, depth: Int) {
                if (node.nodeName() in blocks) out.append('\n')
            }
        }, root)
        return out.toString().lines().map { it.trim().removePrefix("| ") }
            .filter { it.isNotEmpty() }.joinToString("\n\n")
    }
    companion object { const val MAX_TEXT = 100_000 }
}
