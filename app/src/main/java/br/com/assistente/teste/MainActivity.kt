package br.com.assistente.teste

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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

class MainActivity : ComponentActivity() {
    private val model: ChatModel by viewModels()
    private var listening by mutableStateOf(false)
    private var voiceError by mutableStateOf<String?>(null)
    private var integrations by mutableStateOf(false)
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        listening = false
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(model::send)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        integrations = savedInstanceState?.getBoolean("integrations") ?: false
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB7F58A), background = Color(0xFF0E1923))) {
                var settings by remember { mutableStateOf(false) }
                val clipboard = LocalClipboardManager.current
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(24.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            SelectionContainer { Column {
                                if (model.question.isNotEmpty()) { Text(model.question, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(20.dp)) }
                                Text(voiceError ?: model.answer, style = MaterialTheme.typography.bodyLarge)
                            } }
                            TextButton(onClick = { clipboard.setText(AnnotatedString(voiceError ?: model.answer)) }) { Text("Copiar resposta") }
                            HorizontalDivider()
                            SelectionContainer { Text(model.integrationStatus, modifier = Modifier.padding(vertical = 12.dp)) }
                            TextButton(onClick = { clipboard.setText(AnnotatedString(model.debugReport())) }) { Text("Copiar diagnóstico") }
                            if (model.diagnostics.isNotBlank()) {
                                SelectionContainer { Text(model.diagnostics, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        Button(onClick = { listen() }, enabled = !model.busy && !listening, modifier = Modifier.fillMaxWidth()) {
                            Text(if (model.busy) "Aguardando resposta…" else if (listening) "Ouvindo…" else "Falar")
                        }
                        TextButton(onClick = { settings = true }, modifier = Modifier.fillMaxWidth()) { Text("Configurar API") }
                        TextButton(onClick = { integrations = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (model.spotifyConnected) "Integrações · Spotify conectado" else "Integrações")
                        }
                    }
                }
                if (settings) KeyDialog(model.keys) { settings = false }
                if (integrations) IntegrationsDialog(model, ::openBrowser) { integrations = false }
            }
        }
        if (intent.data?.let { SpotifyAuth.isCallback(it) } == true) handleSpotify(intent)
        else if (savedInstanceState == null && isAssist(intent)) window.decorView.post { listen() }
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
        voiceError = null
        integrations = true
    }
    private fun openBrowser(uri: Uri) { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    private fun isAssist(intent: Intent) = intent.action == Intent.ACTION_ASSIST || intent.action == "android.intent.action.VOICE_ASSIST"
    private fun listen() {
        if (model.busy || listening) return
        voiceError = null
        try {
            listening = true
            speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale com o Orbi")
            })
        } catch (e: Exception) { listening = false; voiceError = "Reconhecimento de voz indisponível. Verifique o serviço de voz do celular." }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("integrations", integrations); super.onSaveInstanceState(outState) }

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
