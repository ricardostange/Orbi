package br.com.assistente.teste.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import br.com.assistente.teste.ChatModel

@Composable
fun ApiDialog(model: ChatModel, close: () -> Unit) {
    val current = model.llmConfig
    var key by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf(current.provider) }
    var endpoint by remember { mutableStateOf(current.endpoint) }
    var name by remember { mutableStateOf(current.model) }
    var temperature by remember { mutableStateOf(current.temperature.toString()) }
    var tokens by remember { mutableStateOf(current.maxTokens.toString()) }
    var connect by remember { mutableStateOf(current.connectTimeoutSeconds.toString()) }
    var read by remember { mutableStateOf(current.readTimeoutSeconds.toString()) }
    var call by remember { mutableStateOf(current.callTimeoutSeconds.toString()) }
    var extras by remember { mutableStateOf(current.extraBody) }
    var advanced by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = close, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("API do assistente") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (model.keys.hasKey()) "Chave salva. Deixe em branco para manter." else "Informe a chave de API do seu provedor.")
                Text("Mensagens e resultados das ferramentas serão enviados ao endpoint configurado. Pode haver cobrança de uso.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(key, { key = it }, label = { Text("Chave de API") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Ocultar opções avançadas" else "Provedor, modelo e parâmetros") }
                if (advanced) {
                    OutlinedTextField(provider, { provider = it }, label = { Text("Nome do provedor") }, singleLine = true)
                    OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Endpoint HTTPS · chat/completions") }, singleLine = true)
                    OutlinedTextField(name, { name = it }, label = { Text("Modelo") }, singleLine = true)
                    OutlinedTextField(temperature, { temperature = it }, label = { Text("Temperatura (0–2)") }, singleLine = true)
                    OutlinedTextField(tokens, { tokens = it }, label = { Text("Máximo de tokens") }, singleLine = true)
                    OutlinedTextField(connect, { connect = it }, label = { Text("Timeout de conexão (s)") }, singleLine = true)
                    OutlinedTextField(read, { read = it }, label = { Text("Timeout de leitura (s)") }, singleLine = true)
                    OutlinedTextField(call, { call = it }, label = { Text("Timeout total (s)") }, singleLine = true)
                    OutlinedTextField(extras, { extras = it }, label = { Text("Parâmetros extras JSON") })
                    Text("Ao trocar o endpoint, forneça a chave desse provedor novamente.", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(enabled = !model.busy, onClick = {
            try {
                val config = current.copy(provider = provider.trim(), endpoint = endpoint.trim(), model = name.trim(),
                    temperature = temperature.toDouble(), maxTokens = tokens.toInt(), connectTimeoutSeconds = connect.toLong(),
                    readTimeoutSeconds = read.toLong(), callTimeoutSeconds = call.toLong(), extraBody = extras)
                require(config.endpoint == current.endpoint || key.isNotBlank())
                if (key.isNotBlank()) model.keys.save(key.trim())
                model.configureLlm(config)
                key = ""
                close()
            } catch (_: Exception) { error = "Não foi possível salvar. Confira os parâmetros; ao trocar o endpoint, informe uma nova chave." }
        }) { Text("Salvar") } }, dismissButton = { Row {
            if (model.keys.hasKey()) TextButton(enabled = !model.busy, onClick = {
                try { model.keys.remove(); close() } catch (_: Exception) { error = "Não foi possível remover a chave." }
            }) { Text("Remover chave") }
            TextButton(onClick = close) { Text("Cancelar") }
        } })
}
