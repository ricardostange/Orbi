package br.com.assistente.teste.llm

/** Markdown is loaded verbatim; no behavior instructions are hidden in the UI. */
class AgentPrompts(read: (String) -> String) {
    val system = read("system.md").trim()
    private val spotify = read("spotify.md").trim()
    val repair = read("repair.md").trim()
    private val connected = read("connected.md").trim()
    private val disconnected = read("disconnected.md").trim()
    init { require(listOf(system, spotify, repair, connected, disconnected).all { it.isNotBlank() }) }
    fun availability(names: List<String>): String = if (names.isEmpty()) disconnected else
        connected.replace("{{tools}}", names.joinToString()) + if ("spotify" in names) "\n$spotify" else ""
}
