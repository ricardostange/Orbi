package br.com.assistente.teste

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.assistente.teste.integrations.IntegrationRegistry
import br.com.assistente.teste.integrations.spotify.SpotifyAuth
import br.com.assistente.teste.integrations.spotify.SpotifyClient
import br.com.assistente.teste.integrations.spotify.SpotifyIntegration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ChatModel(app: Application) : AndroidViewModel(app) {
    var question by mutableStateOf(""); private set
    var answer by mutableStateOf("Toque em Falar para conversar com o Orbi."); private set
    var busy by mutableStateOf(false); private set
    var replyId by mutableIntStateOf(0); private set
    var spotifyConnected by mutableStateOf(false); private set
    var spotifyClientId by mutableStateOf(""); private set
    var integrationStatus by mutableStateOf("Conecte sua conta para controlar o Spotify."); private set
    val keys = ApiKeyStore(app)
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).build()
    private val spotifyHttp = client.newBuilder().readTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
    private val spotifyAuth = SpotifyAuth(app, spotifyHttp)
    private val spotify = SpotifyIntegration({ spotifyAuth.connected }, SpotifyClient(spotifyHttp, spotifyAuth))
    private val integrations = IntegrationRegistry(listOf(spotify))
    private val chat = ChatClient(client, integrations)

    init {
        viewModelScope.launch {
            try { refreshSpotify() } catch (_: Exception) { integrationStatus = "Credenciais indisponíveis. Desconecte e conecte novamente." }
        }
    }
    private suspend fun refreshSpotify() {
        val state = withContext(Dispatchers.IO) { spotifyAuth.connected to spotifyAuth.clientId }
        spotifyConnected = state.first
        spotifyClientId = state.second
    }
    fun send(text: String) {
        if (busy || text.isBlank()) return
        question = text
        answer = "Consultando DeepSeek…"
        task {
            val result = withContext(Dispatchers.IO) {
                chat.reply(keys.read() ?: error("Configure sua chave em Configurar API."), text)
            }
            answer = result
            replyId++
        }
    }
    fun connectSpotify(id: String, open: (Uri) -> Unit) = task(integration = true) {
        val uri = withContext(Dispatchers.IO) { spotifyAuth.authorize(id.trim()) }
        refreshSpotify()
        open(uri)
        integrationStatus = "Conclua a autorização no navegador."
    }
    fun spotifyCallback(uri: Uri) = task(integration = true) {
        withContext(Dispatchers.IO) {
            spotifyAuth.complete(uri)
            chat.clearHistory()
        }
        refreshSpotify()
        integrationStatus = "Spotify conectado. Peça uma música ou use os controles."
    }
    fun disconnectSpotify() = task(integration = true) {
        withContext(Dispatchers.IO) {
            spotifyAuth.disconnect()
            chat.clearHistory()
        }
        refreshSpotify()
        integrationStatus = "Spotify desconectado neste aparelho. Para revogar a permissão, remova o Orbi na conta Spotify."
    }
    fun spotifyControl(action: String) = task(integration = true) {
        withContext(Dispatchers.IO) { spotify.execute("spotify", JSONObject().put("action", action)) }
        integrationStatus = "Comando enviado: ${if (action == "play") "retomar" else if (action == "pause") "pausar" else "próxima"}."
    }
    private fun task(integration: Boolean = false, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = when (e) {
                    is java.io.IOException -> "Falha de conexão. Confira a internet. Se enviou um comando, consulte o estado antes de repetir."
                    is IllegalStateException, is IllegalArgumentException -> e.message ?: "Não foi possível concluir."
                    else -> "Não foi possível ler as credenciais ou a resposta. Confira as configurações."
                }
                if (integration) integrationStatus = message else answer = message
            } finally {
                try { refreshSpotify() } catch (_: Exception) { spotifyConnected = false }
                busy = false
            }
        }
    }
    override fun onCleared() { client.dispatcher.cancelAll(); super.onCleared() }
}
