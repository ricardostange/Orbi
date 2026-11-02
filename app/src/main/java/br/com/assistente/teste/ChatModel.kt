package br.com.assistente.teste

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ChatModel(app: Application) : AndroidViewModel(app) {
    var question by mutableStateOf(""); private set
    var answer by mutableStateOf("Toque em Falar para conversar com o Orbi."); private set
    var busy by mutableStateOf(false); private set
    var replyId by mutableIntStateOf(0); private set
    val keys = ApiKeyStore(app)
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).build()
    fun send(text: String) {
        if (busy || text.isBlank()) return
        question = text
        busy = true
        answer = "Consultando DeepSeek…"
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val key = keys.read() ?: error("Configure sua chave em Configurar API.")
                    val messages = JSONArray().put(JSONObject().put("role", "system").put("content",
                        "Você é Orbi, um assistente de conversa. Responda no idioma do usuário, de forma clara e concisa, adequada para leitura em voz alta. Você não tem ferramentas nem acesso aos aplicativos do celular."))
                        .put(JSONObject().put("role", "user").put("content", text))
                    val body = JSONObject().put("model", "deepseek-flash").put("messages", messages)
                        .put("stream", false).put("thinking", JSONObject().put("type", "disabled"))
                        .put("max_tokens", 2048).toString()
                    val request = Request.Builder().url("https://api.deepseek.com/chat/completions")
                        .header("Authorization", "Bearer $key")
                        .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error(when(response.code) {
                            401, 403 -> "Chave recusada. Confira sua configuração de API."
                            402 -> "Saldo insuficiente na conta DeepSeek."
                            429 -> "Limite de requisições atingido. Tente novamente em instantes."
                            else -> "DeepSeek indisponível (HTTP ${response.code}). Tente novamente."
                        })
                        val content = JSONObject(response.body?.string() ?: error("Resposta vazia."))
                            .getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                        check(content.isNotEmpty()) { "DeepSeek retornou uma resposta vazia." }
                        content
                    }
                }
                answer = result
                replyId++
            } catch (e: java.io.IOException) {
                answer = "Não foi possível conectar ao DeepSeek. Verifique a internet e tente novamente."
            } catch (e: Exception) {
                answer = if(e is IllegalStateException) e.message ?: "Não foi possível concluir." else "Não foi possível ler a chave ou a resposta. Confira a configuração de API."
            } finally { busy = false }
        }
    }
    override fun onCleared() { client.dispatcher.cancelAll(); super.onCleared() }
}
