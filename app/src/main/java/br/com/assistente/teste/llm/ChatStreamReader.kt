package br.com.assistente.teste.llm

import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject

/** SSE decoding only. Never dispatches tools, including on truncated streams. */
internal object ChatStreamReader {
    fun read(source: BufferedSource, onUsage: (JSONObject?) -> Unit,
        onContent: () -> Unit, onText: (String) -> Unit): JSONObject {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = sortedMapOf<Int, JSONObject>()
        var finish: String? = null
        var done = false
        var usageReported = false
        var characters = 0L
        var textHidden = false
        var lastUpdate = 0L
        val data = StringBuilder()
        fun event() {
            if (data.isEmpty()) return
            val payload = data.toString()
            data.setLength(0)
            if (payload.trim() == "[DONE]") { done = true; return }
            val chunk = JSONObject(payload)
            check(!chunk.has("error")) { "O provedor interrompeu a resposta." }
            chunk.optJSONObject("usage")?.let {
                check(!usageReported) { "Uso de tokens duplicado no stream." }
                usageReported = true
                onUsage(it)
            }
            val choices = chunk.optJSONArray("choices") ?: return
            for (i in 0 until choices.length()) {
                val choice = choices.getJSONObject(i)
                check(choice.getInt("index") == 0) { "Índice de resposta inesperado." }
                val delta = choice.optJSONObject("delta") ?: JSONObject()
                val content = delta.opt("content") as? String ?: ""
                val thought = delta.opt("reasoning_content") as? String ?: ""
                val fragments = delta.optJSONArray("tool_calls")
                check(finish == null || (content.isEmpty() && thought.isEmpty() && (fragments == null || fragments.length() == 0))) {
                    "Conteúdo recebido após o fim da resposta."
                }
                if (content.isNotEmpty() || (fragments != null && fragments.length() > 0)) onContent()
                text.append(content)
                reasoning.append(thought)
                if (fragments != null) for (j in 0 until fragments.length()) {
                    val fragment = fragments.getJSONObject(j)
                    val index = fragment.getInt("index")
                    check(index in 0..39) { "Índice de ferramenta inválido." }
                    val call = calls.getOrPut(index) { JSONObject().put("function", JSONObject().put("name", "").put("arguments", "")) }
                    for (field in listOf("id", "type")) {
                        (fragment.opt(field) as? String)?.takeIf { it.isNotEmpty() }?.let { value ->
                            check(!call.has(field) || call.getString(field) == value) { "Identificador de ferramenta inconsistente." }
                            call.put(field, value)
                        }
                    }
                    fragment.optJSONObject("function")?.let { fn ->
                        val target = call.getJSONObject("function")
                        for (field in listOf("name", "arguments")) {
                            (fn.opt(field) as? String)?.let { target.put(field, target.getString(field) + it) }
                        }
                    }
                }
                if (calls.isNotEmpty()) {
                    if (!textHidden) onText("")
                    textHidden = true
                } else if (content.isNotEmpty() && (lastUpdate == 0L || System.nanoTime() - lastUpdate >= 50_000_000)) {
                    onText(text.toString())
                    lastUpdate = System.nanoTime()
                }
                (choice.opt("finish_reason") as? String)?.let { finish = it }
            }
        }
        try {
            while (!done) {
                val line = source.readUtf8LineStrictOrNull() ?: break
                characters += line.length
                check(characters <= 4_000_000) { "Resposta do provedor excedeu o limite." }
                if (line.isEmpty()) event()
                else if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).removePrefix(" "))
                }
            }
            if (!done && data.isNotEmpty()) event()
            check(done && finish in setOf("stop", "tool_calls")) {
                "O provedor não concluiu a resposta (finish_reason=${finish ?: "ausente"}). Nenhuma ferramenta desta resposta foi executada."
            }
            val result = JSONObject().put("role", "assistant").put("content", text.toString())
            if (reasoning.isNotEmpty()) result.put("reasoning_content", reasoning.toString())
            if (calls.isNotEmpty()) {
                check(finish == "tool_calls" && calls.keys.toList() == (0 until calls.size).toList()) { "Lote de ferramentas incompleto." }
                val ids = mutableSetOf<String>()
                calls.values.forEach { call ->
                    check(call.optString("id").isNotBlank() && ids.add(call.getString("id")) && call.optString("type") == "function") { "Chamada de ferramenta inválida." }
                    val fn = call.getJSONObject("function")
                    check(fn.getString("name").isNotBlank()) { "Nome de ferramenta ausente." }
                    JSONObject(fn.getString("arguments")) // Validate every call before returning the batch.
                }
                result.put("tool_calls", JSONArray(calls.values.toList()))
            } else {
                check(finish == "stop") { "O provedor não enviou as ferramentas." }
                onText(text.toString())
            }
            return result
        } finally {
            if (!usageReported) onUsage(null)
        }
    }

    private fun BufferedSource.readUtf8LineStrictOrNull(): String? =
        if (exhausted()) null else readUtf8LineStrict(1_000_000)
}
