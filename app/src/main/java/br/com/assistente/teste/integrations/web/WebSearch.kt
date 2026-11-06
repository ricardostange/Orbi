package br.com.assistente.teste.integrations.web

data class SearchResult(val title: String, val url: String, val snippet: String)

class WebSearchException(val code: String, message: String) : Exception(message)

fun interface SearchHtmlSource {
    fun fetch(query: String): String
}

/** Provider-independent boundary; HTML and HTTP never reach the agent. */
class WebSearch(
    private val source: SearchHtmlSource = DuckDuckGoClient(),
    private val parser: DuckDuckGoParser = DuckDuckGoParser()
) {
    fun search(query: String): List<SearchResult> {
        val normalized = query.trim().replace(Regex("\\s+"), " ")
        if (normalized.isEmpty() || normalized.length > 300) {
            throw WebSearchException("invalid_query", "A busca deve ter entre 1 e 300 caracteres.")
        }
        return parser.parse(source.fetch(normalized))
    }
}
