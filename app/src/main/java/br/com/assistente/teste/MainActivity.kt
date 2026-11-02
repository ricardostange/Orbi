package br.com.assistente.teste

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import java.util.Locale
import br.com.assistente.teste.integrations.IntegrationsDialog
import br.com.assistente.teste.integrations.spotify.SpotifyAuth

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val model: ChatModel by viewModels()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var listening by mutableStateOf(false)
    private var voiceError by mutableStateOf<String?>(null)
    private var spokenId = 0
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        listening = false
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(model::send)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        spokenId = savedInstanceState?.getInt("spokenId") ?: 0
        tts = TextToSpeech(this, this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB7F58A), background = Color(0xFF0E1923))) {
                var settings by remember { mutableStateOf(false) }
                var integrations by remember { mutableStateOf(false) }
                LaunchedEffect(model.replyId) {
                    if (model.replyId > spokenId) { spokenId = model.replyId; speak(model.answer) }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(24.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            if (model.question.isNotEmpty()) { Text(model.question, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(20.dp)) }
                            Text(voiceError ?: model.answer, style = MaterialTheme.typography.bodyLarge)
                        }
                        Button(onClick = { listen() }, enabled = !model.busy && !listening, modifier = Modifier.fillMaxWidth()) {
                            Text(if (model.busy) "Aguardando resposta…" else if (listening) "Ouvindo…" else "Falar")
                        }
                        TextButton(onClick = { settings = true; tts?.stop(); pending = null }, modifier = Modifier.fillMaxWidth()) { Text("Configurar API") }
                        TextButton(onClick = { integrations = true; tts?.stop(); pending = null }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (model.spotifyConnected) "Integrações · Spotify conectado" else "Integrações")
                        }
                    }
                }
                if (settings) KeyDialog(model.keys) { settings = false }
                if (integrations) IntegrationsDialog(model, ::openBrowser) { integrations = false }
            }
        }
        if (savedInstanceState == null) {
            if (intent.data?.let { SpotifyAuth.isCallback(it) } == true) handleSpotify(intent)
            else if (isAssist(intent)) window.decorView.post { listen() }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.data?.let { SpotifyAuth.isCallback(it) } == true) handleSpotify(intent)
        else if (isAssist(intent)) listen()
    }
    private fun handleSpotify(intent: Intent) {
        intent.data?.let(model::spotifyCallback)
        intent.data = null // Do not reprocess an authorization code after recreation.
        voiceError = "Retorno do Spotify recebido. Veja o resultado em Integrações."
    }
    private fun openBrowser(uri: Uri) { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    private fun isAssist(intent: Intent) = intent.action == Intent.ACTION_ASSIST || intent.action == "android.intent.action.VOICE_ASSIST"
    private fun listen() {
        if (model.busy || listening) return
        tts?.stop(); pending = null; voiceError = null
        try {
            listening = true
            speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale com o Orbi")
            })
        } catch (e: Exception) { listening = false; voiceError = "Reconhecimento de voz indisponível. Verifique o serviço de voz do celular." }
    }
    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) { tts?.language = Locale.getDefault(); pending?.let { speak(it) }; pending = null }
    }
    private fun speak(text: String) {
        if (!ready) { pending = text; return }
        val limit = TextToSpeech.getMaxSpeechInputLength().coerceAtMost(3500)
        text.chunked(limit).forEachIndexed { index, part ->
            tts?.speak(part, if(index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "reply-$index")
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("spokenId", spokenId); super.onSaveInstanceState(outState) }
    override fun onDestroy() { pending = null; tts?.stop(); tts?.shutdown(); super.onDestroy() }
}

@Composable
private fun KeyDialog(store: ApiKeyStore, close: () -> Unit) {
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = close, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("DeepSeek") },
        text = { Column {
            Text(if(store.hasKey()) "Chave salva. Cole outra para substituir." else "Cole sua chave de API.")
            Text("Suas mensagens serão enviadas ao DeepSeek. O uso consome o saldo da sua API.")
            OutlinedTextField(value, { value = it }, label = { Text("Chave de API") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            error?.let { Text(it) }
        } },
        confirmButton = { TextButton(onClick = {
            try { store.save(value.trim()); value = ""; close() }
            catch(e: Exception) { error = "Não foi possível salvar. Confira a chave." }
        }) { Text("Salvar") } },
        dismissButton = { Row {
            if(store.hasKey()) TextButton(onClick = {
                try { store.remove(); close() } catch(e: Exception) { error = "Não foi possível remover." }
            }) { Text("Remover") }
            TextButton(onClick = close) { Text("Cancelar") }
        } })
}
