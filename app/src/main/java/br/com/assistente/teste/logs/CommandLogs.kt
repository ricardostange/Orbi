package br.com.assistente.teste.logs

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class LogEvent(val time: Long, val title: String, val detail: String)
data class CommandLog(val id: String, val time: Long, val command: String, val status: String,
    val events: List<LogEvent> = emptyList()) {
    fun report(): String = buildString {
        appendLine("Comando: $command\nEstado: $status")
        events.forEach { appendLine("\n${java.util.Date(it.time)} · ${it.title}\n${it.detail}") }
    }
}

/** Sanitizes before storage, including JSON encoded inside tool arguments/content. */
object LogSanitizer {
    private val fields = setOf("apikey", "authorization", "accesstoken", "refreshtoken", "token",
        "clientsecret", "password", "code", "state", "codeverifier", "email")
    fun clean(text: String, secrets: List<String> = emptyList()): String {
        fun redact(value: String): String {
            var safe = value
            secrets.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach { safe = safe.replace(it, "[redigido]") }
            return safe.replace(Regex("(?i)Bearer\\s+[^\\s\"\\\\]+"), "Bearer [redigido]")
                .replace(Regex("sk-[A-Za-z0-9_-]+"), "[redigido]")
                .replace(Regex("(?i)([?&](?:code|state|access_token|refresh_token|api_key)=)[^&\\s]+"), "$1[redigido]")
        }
        fun cleanValue(item: Any): Any = when (item) {
            is JSONObject -> JSONObject().apply { item.keys().forEach { key ->
                put(key, if (key.lowercase().filter { it.isLetterOrDigit() } in fields) "[redigido]" else cleanValue(item.get(key)))
            } }
            is JSONArray -> JSONArray().apply { for (i in 0 until item.length()) put(cleanValue(item.get(i))) }
            is String -> {
                val value = item.trim()
                val nested = if (value.startsWith("{")) runCatching { JSONObject(value) }.getOrNull()
                    else if (value.startsWith("[")) runCatching { JSONArray(value) }.getOrNull() else null
                if (nested != null) cleanValue(nested).toString() else redact(item)
            }
            else -> item
        }
        return cleanValue(text).toString().let(::redact)
    }
}

/** Private bounded history; disk errors never stop an assistant command. Call on IO. */
class CommandLogs(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "command-log.json"))
    private val state = MutableStateFlow<List<CommandLog>>(emptyList())
    val entries = state.asStateFlow()
    private val errors = MutableStateFlow<String?>(null)
    val error = errors.asStateFlow()
    private var loaded = false

    @Synchronized fun load() {
        if (loaded) return
        loaded = true
        try {
            val data = file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
            val array = JSONArray(data)
            state.value = (0 until minOf(array.length(), 50)).map { i ->
                val row = array.getJSONObject(i)
                val events = row.getJSONArray("events")
                CommandLog(row.getString("id"), row.getLong("time"), row.getString("command"),
                    row.getString("status").let { if (it == "Em andamento") "Interrompido" else it },
                    (0 until events.length()).map { j -> events.getJSONObject(j).let {
                        LogEvent(it.getLong("time"), it.getString("title"), it.getString("detail"))
                    } })
            }
        } catch (_: java.io.FileNotFoundException) { /* First launch. */ }
        catch (_: Exception) { errors.value = "Não foi possível ler o histórico salvo." }
    }
    @Synchronized fun begin(command: String, secrets: List<String>): String {
        load()
        val entry = CommandLog(UUID.randomUUID().toString(), System.currentTimeMillis(),
            LogSanitizer.clean(command, secrets).take(4000), "Em andamento")
        state.value = (listOf(entry) + state.value).take(50)
        save()
        return entry.id
    }
    @Synchronized fun event(id: String, title: String, detail: String, secrets: List<String>) {
        load()
        state.value = state.value.map { entry ->
            if (entry.id != id) entry else {
                val remaining = 120_000 - entry.events.sumOf { it.detail.length + it.title.length }
                if (remaining <= 0 || entry.events.size >= 200) {
                    if (title == "Resposta final" || title == "Erro") entry.copy(events = entry.events +
                        LogEvent(System.currentTimeMillis(), title, LogSanitizer.clean(detail, secrets).take(4000)))
                    else if (entry.events.lastOrNull()?.title == "Limite do log") entry
                    else entry.copy(events = entry.events + LogEvent(System.currentTimeMillis(), "Limite do log",
                        "Etapas adicionais foram omitidas para limitar o tamanho deste registro."))
                } else {
                    val safe = LogSanitizer.clean(detail, secrets)
                    val limit = minOf(remaining, 16_000)
                    val clipped = if (safe.length > limit) safe.take(limit) + "\n[conteúdo abreviado]" else safe
                    entry.copy(events = entry.events + LogEvent(System.currentTimeMillis(), LogSanitizer.clean(title, secrets).take(200), clipped))
                }
            }
        }
        save()
    }
    @Synchronized fun finish(id: String, status: String) {
        state.value = state.value.map { if (it.id == id) it.copy(status = status) else it }
        save()
    }
    @Synchronized fun clear() { load(); state.value = emptyList(); save() }
    private fun save() {
        var stream: java.io.FileOutputStream? = null
        try {
            val data = JSONArray().apply { state.value.forEach { row -> put(JSONObject()
                .put("id", row.id).put("time", row.time).put("command", row.command).put("status", row.status)
                .put("events", JSONArray().apply { row.events.forEach { put(JSONObject()
                    .put("time", it.time).put("title", it.title).put("detail", it.detail)) } })) } }.toString()
            stream = file.startWrite()
            stream.write(data.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            errors.value = null
        } catch (_: Exception) {
            stream?.let { file.failWrite(it) }
            errors.value = "O log está disponível nesta sessão, mas não foi possível salvá-lo no aparelho."
        }
    }
}
