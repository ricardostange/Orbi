package br.com.assistente.teste

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.assistente.teste.llm.*
import br.com.assistente.teste.lists.ListsRepository
import br.com.assistente.teste.lists.ListsIntegration
import br.com.assistente.teste.integrations.android.AndroidSpotifyIntegration
import br.com.assistente.teste.integrations.web.WebSearchIntegration
import br.com.assistente.teste.integrations.web.page.PageReadIntegration
import kotlinx.coroutines.runBlocking
import br.com.assistente.teste.integrations.IntegrationRegistry
import br.com.assistente.teste.integrations.spotify.SpotifyAuth
import br.com.assistente.teste.integrations.spotify.SpotifyClient
import br.com.assistente.teste.integrations.spotify.SpotifyIntegration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ChatMessage(val role: String, val text: String)

class ChatModel(app: Application) : AndroidViewModel(app) {
    val messages = mutableStateListOf<ChatMessage>()
    private fun updateAnswer(text: String) {
        answer = text
        if (messages.lastOrNull()?.role == "assistant") {
            messages[messages.lastIndex] = ChatMessage("assistant", text)
        }
    }
    var question by mutableStateOf(""); private set
    var answer by mutableStateOf("Toque em Falar para conversar com o Orbi."); private set
    var busy by mutableStateOf(false); private set
    var diagnostics by mutableStateOf(""); private set
    var spotifyConnected by mutableStateOf(false); private set
    var spotifyClientId by mutableStateOf(""); private set
    var integrationStatus by mutableStateOf("Conecte sua conta para controlar o Spotify."); private set
    private val commandLogs = br.com.assistente.teste.logs.CommandLogs(app)
    val logs = commandLogs.entries
    val logError = commandLogs.error
    fun clearLogs() { if (!busy) viewModelScope.launch(Dispatchers.IO) { commandLogs.clear() } }
    val keys = ApiKeyStore(app)
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).build()
    private val spotifyHttp = client.newBuilder().readTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
    private val spotifyAuth = SpotifyAuth(app, spotifyHttp)
    private val spotify = SpotifyIntegration({ spotifyAuth.connected }, SpotifyClient(spotifyHttp, spotifyAuth) { event -> viewModelScope.launch { appendDiagnostic(event) } })
    @Volatile private var spotifyLauncher: (() -> Unit)? = null
    fun attachSpotifyLauncher(launcher: (() -> Unit)?) { spotifyLauncher = launcher }
    private val android = AndroidSpotifyIntegration({ spotifyLauncher != null }) {
        runBlocking(Dispatchers.Main.immediate) {
            val launch = spotifyLauncher ?: error("Volte ao Orbi para abrir o Spotify.")
            launch()
        }
    }
    private val listsRepository = ListsRepository(app)
    val lists = listsRepository.lists
    val pendingListDeletion = listsRepository.deletion
    var listsReady by mutableStateOf(false); private set
    var listsBusy by mutableStateOf(false); private set
    var listsError by mutableStateOf<String?>(null); private set
    private val toolPreferences = app.getSharedPreferences("orbi-tools", Context.MODE_PRIVATE)
    var selectedTools by mutableStateOf(toolPreferences.getStringSet("selected", null)?.toSet()); private set
    private var conversationTools = selectedTools
    private val integrations = IntegrationRegistry(
        listOf(spotify, android, ListsIntegration(listsRepository), WebSearchIntegration(), PageReadIntegration()),
        { conversationTools })
    val toolCatalog get() = integrations.catalog().map { it.getJSONObject("function") }
    fun selectTool(name: String, enabled: Boolean) {
        val selected = (selectedTools ?: toolCatalog.map { it.getString("name") }.toSet()).toMutableSet()
        if (enabled) selected.add(name) else selected.remove(name)
        selectedTools = selected.toSet()
        toolPreferences.edit().putStringSet("selected", selected).apply()
    }
    private var restarting = false
    fun newConversation(ready: () -> Unit = {}) {
        if (restarting) return
        restarting = true
        viewModelScope.launch {
            try {
                activeTask?.cancel()
                client.dispatcher.cancelAll()
                activeTask?.join()
                resetConversation()
                ready()
            } finally { restarting = false }
        }
    }
    private fun resetConversation() {
        chat.clearHistory()
        conversationTools = selectedTools?.toSet()
        messages.clear()
        question = ""
        answer = "Toque em Falar para conversar com o Orbi."
        diagnostics = ""
    }
    fun dismissListsError() { listsError = null }
    fun loadLists() = listAction({ listsReady = true }) { listsRepository.load() }
    fun createList(name: String, done: () -> Unit) = listAction(done) { listsRepository.createList(name) }
    fun renameList(id: String, name: String, done: () -> Unit) = listAction(done) { listsRepository.renameList(id, name) }
    fun requestListDeletion(id: String) = listAction { listsRepository.requestDeletion(id) }
    fun confirmListDeletion(requestId: String) = listAction { listsRepository.confirmDeletion(requestId) }
    fun cancelListDeletion(requestId: String) { listsRepository.cancelDeletion(requestId) }
    fun addListItem(id: String, text: String, done: () -> Unit) = listAction(done) { listsRepository.addItem(id, text) }
    fun removeListItem(listId: String, itemId: String) = listAction { listsRepository.removeItem(listId, itemId) }
    fun completeListItem(listId: String, itemId: String, completed: Boolean) = listAction { listsRepository.setCompleted(listId, itemId, completed) }
    private fun listAction(done: () -> Unit = {}, action: () -> Unit) {
        if (listsBusy) return
        listsBusy = true
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action() }; done() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                listsError = if (e is IllegalArgumentException || e is IllegalStateException) e.message
                    else "Não foi possível salvar ou ler as listas. Tente novamente."
            } finally { listsBusy = false }
        }
    }
    val agentSettings = AgentSettings(app)
    var llmConfig by mutableStateOf(agentSettings.load()); private set
    private var chat = createChat()
    private fun createChat() = ChatClient(OpenAiCompatibleClient(client, llmConfig), integrations, agentSettings.prompts())
    fun configureLlm(config: LlmConfig) {
        check(!busy) { "Aguarde o pedido atual terminar." }
        agentSettings.save(config)
        llmConfig = config
        chat = createChat()
        resetConversation()
    }
    private var activeTask: Job? = null
    private var pendingSpotifyCallback: Uri? = null
    private var awaitingSpotifyBrowser = false
    private var browserReturnJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) { commandLogs.load() }
        loadLists()
        viewModelScope.launch {
            try {
                refreshSpotify()
                if (spotifyConnected && pendingSpotifyCallback == null)
                    integrationStatus = "Spotify autorizado neste aparelho. Use Testar conexão para verificar o acesso."
            } catch (_: Exception) { integrationStatus = "Credenciais indisponíveis. Desconecte e conecte novamente." }
        }
    }
    private suspend fun refreshSpotify() {
        val state = withContext(Dispatchers.IO) { spotifyAuth.connected to spotifyAuth.clientId }
        spotifyConnected = state.first
        spotifyClientId = state.second
    }
    fun send(text: String) {
        if (busy || restarting || text.isBlank()) return
        if (messages.isEmpty()) conversationTools = selectedTools?.toSet()
        question = text
        messages.add(ChatMessage("user", text))
        messages.add(ChatMessage("assistant", ""))
        updateAnswer("Consultando ${llmConfig.provider}…")
        diagnostics = ""
        task {
            val result = withContext(Dispatchers.IO) {
                val requestContext = kotlinx.coroutines.currentCoroutineContext()
                val key = keys.read()
                val secrets = listOfNotNull(key)
                val id = commandLogs.begin(text, secrets)
                commandLogs.event(id, "Modelo", "${llmConfig.provider} · ${llmConfig.model}", secrets)
                try {
                    val reply = chat.reply(key ?: error("Configure sua chave em Configurações → API."), text,
                        onProgress = { event ->
                            requestContext.ensureActive()
                            commandLogs.event(id, "Diagnóstico", event, secrets)
                            runBlocking(requestContext + Dispatchers.Main.immediate) { appendDiagnostic(event) }
                        }, onTrace = { title, detail -> requestContext.ensureActive(); commandLogs.event(id, title, detail, secrets) },
                        onUsage = { tokens -> commandLogs.usage(id, tokens) },
                        onText = { partial ->
                            // Apply in order on the UI dispatcher; no detached updates after completion.
                            kotlinx.coroutines.runBlocking(requestContext + Dispatchers.Main.immediate) {
                                updateAnswer(partial.ifEmpty { "Consultando ${llmConfig.provider}…" })
                            }
                        })
                    commandLogs.event(id, "Resposta final", reply, secrets)
                    commandLogs.finish(id, "Processado")
                    reply
                } catch (e: CancellationException) {
                    commandLogs.finish(id, "Interrompido")
                    throw e
                } catch (e: Exception) {
                    val detail = if (e is IllegalArgumentException || e is IllegalStateException)
                        e.message ?: "Não foi possível concluir." else if (e is java.io.IOException)
                        "Falha de conexão. Consulte o estado antes de repetir uma ação." else "Falha ao processar a resposta."
                    commandLogs.event(id, "Erro", detail, secrets)
                    commandLogs.finish(id, "Erro")
                    throw e
                }
            }
            updateAnswer(result)
        }
    }

    fun connectSpotify(id: String, open: (Uri) -> Unit) = task(integration = true) {
        val uri = withContext(Dispatchers.IO) { spotifyAuth.authorize(id.trim()) }
        refreshSpotify()
        integrationStatus = "Conclua a autorização no navegador."
        awaitingSpotifyBrowser = true
        appendDiagnostic("Spotify: navegador aberto para autorização.")
        try { open(uri) } catch (e: Exception) { awaitingSpotifyBrowser = false; throw e }
    }
    fun spotifyCallback(uri: Uri) {
        awaitingSpotifyBrowser = false
        browserReturnJob?.cancel()
        // Log only the route and parameter presence, never the code, state or full URI.
        appendDiagnostic("Retorno OAuth: esquema=${uri.scheme}; host=${uri.host}; caminho=${uri.path.orEmpty()}; código=${uri.getQueryParameter("code") != null}; state=${uri.getQueryParameter("state") != null}; erro=${uri.getQueryParameter("error") != null}.")
        // Browser callbacks must survive a chat/request already in progress.
        if (pendingSpotifyCallback != null) return
        pendingSpotifyCallback = uri
        integrationStatus = "Retorno do Spotify recebido. Aguardando validação…"
        viewModelScope.launch {
            try {
                activeTask?.join()
                task(integration = true) {
                    integrationStatus = "Conectando ao Spotify…"
                    withContext(Dispatchers.IO) {
                        spotifyAuth.complete(uri)
                    }
                    appendDiagnostic("Spotify: código validado e tokens salvos.")
                    refreshSpotify()
                    integrationStatus = "Spotify autorizado. Verificando acesso e dispositivos…"
                    checkSpotifyConnection()
                }
                activeTask?.join()
            } finally { pendingSpotifyCallback = null }
        }
    }
    fun testSpotify() = task(integration = true) {
        integrationStatus = "Testando conexão…"
        checkSpotifyConnection()
    }
    private suspend fun checkSpotifyConnection() {
        val devices = withContext(Dispatchers.IO) {
            JSONObject(spotify.execute("spotify", JSONObject().put("action", "devices"))).getJSONArray("devices")
        }
        integrationStatus = if (devices.length() == 0)
            "Spotify conectado com sucesso. Nenhum dispositivo disponível: abra o Spotify e inicie uma música, depois volte ao Orbi."
        else {
            val names = (0 until devices.length()).joinToString(", ") { devices.getJSONObject(it).optString("name", "Dispositivo") }
            "Spotify conectado com sucesso. Dispositivos: $names."
        }
    }
    fun disconnectSpotify() = task(integration = true) {
        withContext(Dispatchers.IO) {
            spotifyAuth.disconnect()
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
        activeTask = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = when (e) {
                    is java.io.IOException -> "Falha de conexão. Confira a internet. Se enviou um comando, consulte o estado antes de repetir."
                    is IllegalStateException, is IllegalArgumentException -> e.message ?: "Não foi possível concluir."
                    else -> "Não foi possível ler as credenciais ou a resposta. Confira as configurações."
                }
                if (integration) integrationStatus = "Spotify: $message" else updateAnswer(message)
            } finally {
                try { refreshSpotify() } catch (_: Exception) { spotifyConnected = false }
                busy = false
            }
        }
    }
    private fun appendDiagnostic(event: String) {
        diagnostics = (diagnostics + "\n" + event).trim().takeLast(12_000)
    }
    fun browserReturned() {
        if (!awaitingSpotifyBrowser) return
        browserReturnJob?.cancel()
        browserReturnJob = viewModelScope.launch {
            delay(1500) // Allow onNewIntent to deliver a callback before reporting a missing return.
            if (awaitingSpotifyBrowser && pendingSpotifyCallback == null) {
                integrationStatus = "O Orbi voltou ao primeiro plano, mas ainda não recebeu o retorno de autorização do Spotify. Se você já aceitou, copie o diagnóstico."
                appendDiagnostic("Spotify: app retomado sem callback OAuth recebido.")
            }
        }
    }
    fun debugReport(): String = "Orbi ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
        "Spotify: ${if (spotifyConnected) "autorizado" else "não autorizado"}\n" +
        "$integrationStatus\n\nPergunta: $question\nResposta: $answer\n\n$diagnostics"
    override fun onCleared() { client.dispatcher.cancelAll(); super.onCleared() }
}

