package br.com.assistente.teste.llm

import br.com.assistente.teste.integrations.IntegrationRegistry
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

class ChatClient(
    private val gateway: LlmGateway,
    private val integrations: IntegrationRegistry,
    private val prompts: AgentPrompts
) {
    private val recentTurns = ArrayDeque<String>()
    private var omittedTurns = 0
    private val historyCharacterBudget = 16_000

    fun clearHistory() {
        recentTurns.clear()
        omittedTurns = 0
    }

    private fun remember(turn: JSONArray) {
        val serialized = turn.toString()
        recentTurns.addLast(serialized)
        while (recentTurns.size > 5 || recentTurns.sumOf { it.length } > historyCharacterBudget) {
            recentTurns.removeFirst()
            omittedTurns++
        }
    }

    fun reply(key: String, text: String, onProgress: (String) -> Unit = {},
        onTrace: (String, String) -> Unit = { _, _ -> }): String {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", prompts.system))
        for (turn in recentTurns) {
            val previous = JSONArray(turn)
            for (i in 0 until previous.length()) messages.put(previous.getJSONObject(i))
        }
        val turnStart = messages.length()
        messages.put(JSONObject().put("role", "user").put("content", text))
        val tools = integrations.tools()
        val toolNames = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
        val availability = prompts.availability(toolNames)
        messages.getJSONObject(0).put("content", messages.getJSONObject(0).getString("content") + " " + availability)
        if (omittedTurns > 0) {
            val system = messages.getJSONObject(0)
            system.put("content", system.getString("content") +
                "\nContexto limitado: $omittedTurns turnos anteriores foram omitidos por orçamento. Se uma referência depender deles, peça esclarecimento; não invente o conteúdo.")
        }
        onProgress("Contexto: ${recentTurns.size} turnos anteriores; ${recentTurns.sumOf { it.length }} caracteres; $omittedTurns omitidos.")
        onProgress("Ferramentas disponíveis: ${toolNames.joinToString().ifBlank { "nenhuma (conecte o Spotify em Integrações)" }}")
        var repairedTextCall = false
        var calls = 0
        val seenCallIds = mutableSetOf<String>()
        repeat(12) { step ->
            onProgress("LLM: solicitação ${step + 1}; mensagens=${messages.toString().length} caracteres; schemas=${tools.toString().length} caracteres")
            onTrace("Envio à IA · etapa ${step + 1}", JSONObject().put("messages", messages).put("tools", tools).toString())
            val started = System.nanoTime()
            val message = try {
                gateway.complete(key, messages, tools, onProgress)
            } catch (e: Exception) {
                // A model/network failure after dispatch must not erase already executed actions.
                if (calls > 0 && messages.getJSONObject(messages.length() - 1).optString("role") == "tool") {
                    val interrupted = JSONArray()
                    for (i in turnStart until messages.length()) {
                        val item = messages.getJSONObject(i)
                        if (item.optString("role") != "system") interrupted.put(item)
                    }
                    interrupted.put(JSONObject().put("role", "assistant").put("content",
                        "Processamento interrompido após ferramentas. Os resultados acima foram recebidos; não repita ações automaticamente. Consulte o estado se necessário."))
                    remember(interrupted)
                }
                throw e
            }
            onTrace("Resposta da IA · etapa ${step + 1} · ${(System.nanoTime() - started) / 1_000_000} ms", message.toString())
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls == null || toolCalls.length() == 0) {
                val content = message.optString("content").trim()
                check(content.isNotEmpty() && content != "null") { "O provedor retornou uma resposta vazia." }
                if (looksLikeTextCall(content)) {
                    onProgress("Chamada recebida como texto: não executada.")
                    check(tools.length() > 0) { "Spotify não conectado. A IA escreveu uma chamada como texto, mas nenhuma ação foi executada. Conecte em Configurações → Integrações." }
                    check(!repairedTextCall) { "A IA repetiu uma chamada como texto em vez de usar tool_calls. Essa chamada não foi executada. Copie o diagnóstico para investigar." }
                    repairedTextCall = true
                    messages.put(message)
                    messages.put(JSONObject().put("role", "system").put("content", prompts.repair))
                    return@repeat
                }
                messages.put(message)
                remember(JSONArray().apply {
                    for (i in turnStart until messages.length()) {
                        val item = messages.getJSONObject(i)
                        if (item.optString("role") != "system") put(item)
                    }
                })
                return content
            }
            check(calls + toolCalls.length() <= 40) { "Limite de ações por pedido atingido. Algumas ações podem ter sido executadas; consulte o estado atual das listas e do Spotify antes de repetir." }
            // Validate the entire envelope before allowing any side effect in this batch.
            val ids = (0 until toolCalls.length()).map { index ->
                val call = toolCalls.getJSONObject(index)
                val id = call.optString("id")
                check(id.isNotBlank() && call.optString("type") == "function" &&
                    call.optJSONObject("function")?.optString("name")?.isNotBlank() == true) {
                    "Chamada de ferramenta inválida. Nenhuma ação deste lote foi executada."
                }
                id
            }
            check(ids.distinct().size == ids.size && ids.none { it in seenCallIds }) {
                "O modelo repetiu um identificador de chamada. O lote não foi executado; consulte o estado antes de repetir."
            }
            seenCallIds.addAll(ids)
            messages.put(message)
            for (i in 0 until toolCalls.length()) {
                calls++
                val call = toolCalls.getJSONObject(i)
                val function = call.getJSONObject("function")
                val name = function.getString("name")
                val toolStarted = System.nanoTime()
                onTrace("Chamada de ferramenta · $name", call.toString())
                val result = try {
                    val args = JSONObject(function.getString("arguments"))
                    onProgress("Executando ferramenta: $name")
                    integrations.execute(name, args)
                } catch (e: CancellationException) { throw e
                } catch (e: java.io.IOException) {
                    JSONObject().put("error", "Falha de conexão. O comando pode ter sido recebido; consulte o estado antes de repetir.")
                        .put("error_type", "transport").put("outcome", "unknown").toString()
                } catch (e: br.com.assistente.teste.integrations.ToolArgumentException) {
                    JSONObject().put("error", e.message).put("error_type", "invalid_arguments")
                        .put("outcome", "not_executed").toString()
                } catch (e: org.json.JSONException) {
                    JSONObject().put("error", "Argumentos ou resposta JSON inválidos. Consulte o estado antes de repetir uma ação.")
                        .put("error_type", "invalid_json").put("outcome", "unknown").toString()
                } catch (e: Exception) {
                    JSONObject().put("error", if (e is IllegalStateException || e is IllegalArgumentException)
                        e.message ?: "Ação inválida." else "Não foi possível executar a ação.")
                        .put("error_type", e.javaClass.simpleName).put("outcome", "unknown").toString()
                }
                onTrace("Retorno de ferramenta · $name · ${(System.nanoTime() - toolStarted) / 1_000_000} ms", result)
                val error = runCatching { JSONObject(result).optString("error") }.getOrDefault("")
                onProgress(if (error.isBlank()) "Ferramenta $name: resultado recebido." else "Ferramenta $name: $error")
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", call.getString("id")).put("content", result))
            }
        }
        error("Limite de etapas atingido. Consulte o estado atual das listas e do Spotify antes de repetir o pedido; algumas ações podem ter sido executadas.")
    }
    private fun looksLikeTextCall(content: String): Boolean {
        val raw = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (raw.startsWith("<｜DSML｜") || raw.startsWith("<tool_call") || raw.startsWith("<function=")) return true
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        return json.has("tool_calls") || json.has("function_call") ||
            (json.has("name") && json.has("arguments")) ||
            (json.optString("name") == "spotify" && json.has("parameters"))
    }

}

