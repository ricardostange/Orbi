package br.com.assistente.teste.integrations

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import br.com.assistente.teste.ChatModel
import br.com.assistente.teste.integrations.spotify.SpotifyAuth

@Composable
fun IntegrationsDialog(model: ChatModel, open: (Uri) -> Unit, close: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var advanced by remember { mutableStateOf(false) }
    var clientId by remember(model.spotifyClientId) { mutableStateOf(model.spotifyClientId) }
    var browserError by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = close,
        title = { Text("Integrações") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Spotify", style = MaterialTheme.typography.titleLarge)
                Text(if (model.spotifyConnected) "Conta conectada" else "Conta não conectada")
                Text(model.integrationStatus)
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Ocultar configuração" else "Configuração do Spotify") }
                if (advanced) {
                    Text("No painel do app correspondente ao Client ID abaixo, habilite Web API e cadastre exatamente este Redirect URI:")
                    Text(SpotifyAuth.REDIRECT_URI)
                    TextButton(onClick = { clipboard.setText(AnnotatedString(SpotifyAuth.REDIRECT_URI)) }) { Text("Copiar Redirect URI") }
                    TextButton(onClick = {
                        runCatching { open(Uri.parse("https://developer.spotify.com/dashboard")) }
                            .onFailure { browserError = "Não foi possível abrir o navegador." }
                    }) { Text("Abrir painel Spotify") }
                    OutlinedTextField(clientId, { clientId = it }, label = { Text("Client ID (não é o Client Secret)") }, singleLine = true)
                }
                browserError?.let { Text(it) }
                Text("A autorização ocorre no navegador. Tokens ficam criptografados no aparelho. Ao usar a conversa, dados consultados do Spotify são enviados ao DeepSeek para responder ao seu pedido.")
                Text("Reprodução exige Premium e um dispositivo ativo. No modo de desenvolvimento, autorize sua conta no painel; limites e recursos dependem do Spotify.")
                Button(onClick = { model.connectSpotify(clientId, open) }, enabled = !model.busy && clientId.isNotBlank()) {
                    Text(if (model.spotifyConnected) "Reconectar Spotify" else "Conectar Spotify")
                }
                if (model.spotifyConnected) {
                    TextButton(onClick = model::testSpotify, enabled = !model.busy) { Text("Testar conexão") }
                    TextButton(onClick = {
                        runCatching { open(Uri.parse("spotify:")) }.recoverCatching { open(Uri.parse("https://open.spotify.com")) }
                            .onFailure { browserError = "Não foi possível abrir o Spotify." }
                    }) { Text("Abrir Spotify") }
                    Row {
                        TextButton(onClick = { model.spotifyControl("play") }, enabled = !model.busy) { Text("Retomar") }
                        TextButton(onClick = { model.spotifyControl("pause") }, enabled = !model.busy) { Text("Pausar") }
                        TextButton(onClick = { model.spotifyControl("next") }, enabled = !model.busy) { Text("Próxima") }
                    }
                }
                TextButton(onClick = { model.disconnectSpotify() }, enabled = !model.busy) { Text("Desconectar / limpar credenciais") }
                Text("Por voz: ‘toque [música]’, ‘volume em 30%’, ‘quais dispositivos estão disponíveis?’, ‘crie uma playlist chamada Viagem’.")
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Fechar") } })
}
