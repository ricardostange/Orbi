package br.com.assistente.teste.integrations.web.page

import org.json.JSONObject

/** Bounded in-memory snapshots keep continuation offsets stable; nothing is persisted. */
class PageReader(
    private val source: PageSource = PageClient(),
    private val extractor: PageExtractor = PageExtractor(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private data class Snapshot(val page: PageText, val expires: Long)
    private val cache = LinkedHashMap<String, Snapshot>()

    @Synchronized fun read(url: String, offset: Int = 0): JSONObject {
        val key = PageAddress.parse(url).toString()
        if (offset < 0) throw PageException("invalid_offset", "Offset inválido.")
        cache.entries.removeAll { it.value.expires <= clock() }
        var snapshot = cache[key] ?: cache.values.firstOrNull { it.page.url == key }
        if (snapshot == null) {
            if (offset != 0) throw PageException("expired", "A leitura expirou. Reabra a URL com offset 0.")
            val page = extractor.extract(source.fetch(key))
            snapshot = Snapshot(page, clock() + 300_000)
            if (cache.size >= 3) cache.remove(cache.keys.first())
            cache[key] = snapshot
        }
        val page = snapshot.page
        if (offset >= page.text.length) throw PageException("invalid_offset", "Offset fora do texto disponível.")
        var end = minOf(offset + CHUNK, page.text.length)
        if (end < page.text.length && page.text[end - 1].isHighSurrogate()) end--
        return JSONObject().put("url", page.url).put("title", page.title)
            .put("text", page.text.substring(offset, end)).put("offset", offset)
            .put("total_chars", page.text.length).put("extraction", page.extraction)
            .put("content_limited", page.limited).put("has_more", end < page.text.length).apply {
                if (end < page.text.length) put("next_offset", end)
            }
    }
    companion object { const val CHUNK = 6_000 }
}
