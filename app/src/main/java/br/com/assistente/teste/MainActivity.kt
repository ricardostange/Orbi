package br.com.assistente.teste

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import java.util.Locale
import br.com.assistente.teste.ui.AppSection
import br.com.assistente.teste.ui.OrbiApp

class MainActivity : ComponentActivity() {
    private val model: ChatModel by viewModels()
    private var listening by mutableStateOf(false)
    private var voiceError by mutableStateOf<String?>(null)
    private var section by mutableStateOf(AppSection.HOME)
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
        section = savedInstanceState?.getString("section")?.let { runCatching { AppSection.valueOf(it) }.getOrNull() } ?: AppSection.HOME
        setContent {
            OrbiApp(model, section, { section = it }, listening, voiceError, ::listen, ::sendText, ::openBrowser,
                integrations, { integrations = it })
        }
        if (intent.data != null && intent.action == Intent.ACTION_VIEW) handleSpotify(intent)
        else if (savedInstanceState == null && isAssist(intent)) window.decorView.post { listen() }
    }
    override fun onStart() {
        super.onStart()
        model.attachSpotifyLauncher {
            val launch = packageManager.getLaunchIntentForPackage("com.spotify.music")
                ?: error("Spotify não instalado. Instale o aplicativo para abri-lo pelo Orbi.")
            try { startActivity(launch) }
            catch (_: Exception) { error("Não foi possível abrir o Spotify. Volte ao Orbi e tente novamente.") }
        }
    }
    override fun onStop() {
        model.attachSpotifyLauncher(null)
        super.onStop()
    }
    override fun onResume() {
        super.onResume()
        model.browserReturned()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.data != null && intent.action == Intent.ACTION_VIEW) handleSpotify(intent)
        else if (isAssist(intent)) listen()
    }
    private fun handleSpotify(intent: Intent) {
        intent.data?.let(model::spotifyCallback)
        intent.data = null // Do not reprocess an authorization code after recreation.
        voiceError = null
        section = AppSection.SETTINGS
        integrations = true
    }
    private fun openBrowser(uri: Uri) { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    private fun isAssist(intent: Intent) = intent.action == Intent.ACTION_ASSIST || intent.action == "android.intent.action.VOICE_ASSIST"
    private fun sendText(text: String) {
        if (model.busy || listening || text.isBlank()) return
        voiceError = null
        model.send(text)
    }
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
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("section", section.name); outState.putBoolean("integrations", integrations); super.onSaveInstanceState(outState) }

}
