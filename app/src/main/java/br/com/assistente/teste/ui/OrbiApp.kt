package br.com.assistente.teste.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import br.com.assistente.teste.ChatModel
import br.com.assistente.teste.R
import br.com.assistente.teste.integrations.IntegrationsDialog
import kotlinx.coroutines.launch

enum class AppSection(val title: String) { HOME("Início"), LISTS("Listas"), LOG("Log"), SETTINGS("Configurações") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrbiApp(model: ChatModel, section: AppSection, navigate: (AppSection) -> Unit,
    listening: Boolean, voiceError: String?, listen: () -> Unit, sendText: (String) -> Unit, openBrowser: (Uri) -> Unit,
    integrations: Boolean, showIntegrations: (Boolean) -> Unit) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var api by rememberSaveable { mutableStateOf(false) }
    var composing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB7F58A), background = Color(0xFF10161B), surface = Color(0xFF10161B))) {
        BackHandler(drawer.isOpen || section != AppSection.HOME) {
            if (drawer.isOpen) scope.launch { drawer.close() } else navigate(AppSection.HOME)
        }
        ModalNavigationDrawer(drawerState = drawer, drawerContent = {
            ModalDrawerSheet {
                Text("Orbi", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(24.dp))
                AppSection.entries.forEach { destination ->
                    NavigationDrawerItem(label = { Text(destination.title) }, selected = section == destination,
                        modifier = Modifier.padding(horizontal = 12.dp), onClick = {
                            navigate(destination)
                            scope.launch { drawer.close() }
                        })
                }
            }
        }) {
            Scaffold(topBar = {
                TopAppBar(title = { Text(if (section == AppSection.HOME) "Orbi" else section.title) },
                    navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) {
                        Icon(Icons.Default.Menu, contentDescription = "Abrir menu de navegação")
                    } })
            }) { padding ->
                when (section) {
                    AppSection.HOME -> Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 24.dp)) {
                        val chatScroll = rememberLazyListState()
                        LaunchedEffect(model.messages.size, model.answer) {
                            if (model.messages.isNotEmpty()) chatScroll.animateScrollToItem(model.messages.lastIndex)
                        }
                        LazyColumn(Modifier.weight(1f), state = chatScroll,
                            contentPadding = PaddingValues(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            items(model.messages) { message ->
                                SelectionContainer {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(if (message.role == "user") "Você" else "Orbi",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(message.text, style = MaterialTheme.typography.bodyLarge,
                                            color = if (message.role == "user") MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                            if (model.messages.isEmpty()) item { Text(model.answer) }
                            voiceError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                            if (!model.busy && (model.question.isNotBlank() || voiceError != null)) {
                                item { TextButton(onClick = { clipboard.setText(AnnotatedString(voiceError ?: model.answer)) }) { Text("Copiar resposta") } }
                            }
                        }
                        if (model.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = listen, enabled = !model.busy && !listening,
                                modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                                Text(if (model.busy) "Aguardando resposta…" else if (listening) "Ouvindo…" else "Falar")
                            }
                            IconButton(onClick = { composing = true }, enabled = !model.busy && !listening) {
                                Icon(painterResource(R.drawable.ic_keyboard), contentDescription = "Escrever mensagem",
                                    tint = if (model.busy || listening) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    AppSection.LISTS -> ListsScreen(model, Modifier.padding(padding))
                    AppSection.LOG -> LogScreen(model, Modifier.padding(padding))
                    AppSection.SETTINGS -> SettingsScreen(model, Modifier.padding(padding), { api = true }, { showIntegrations(true) })
                }
            }
        }
        val pendingDeletion by model.pendingListDeletion.collectAsState()
        pendingDeletion?.let { request ->
            AlertDialog(onDismissRequest = { if (!model.listsBusy) model.cancelListDeletion(request.id) },
                title = { Text("Excluir lista?") },
                text = { Text("Excluir “${request.name}” e seus ${request.itemCount} itens? Essa ação não pode ser desfeita.") },
                confirmButton = { TextButton(enabled = !model.listsBusy, onClick = { model.confirmListDeletion(request.id) }) { Text("Excluir lista") } },
                dismissButton = { TextButton(enabled = !model.listsBusy, onClick = { model.cancelListDeletion(request.id) }) { Text("Cancelar") } })
        }
        model.listsError?.let { message ->
            AlertDialog(onDismissRequest = model::dismissListsError, title = { Text("Listas") }, text = { Text(message) },
                confirmButton = { TextButton(onClick = model::dismissListsError) { Text("OK") } })
        }
        if (composing) {
            val focus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            val submit = {
                val text = draft.trim()
                if (text.isNotEmpty() && !model.busy && !listening) {
                    keyboard?.hide()
                    sendText(text)
                    draft = ""
                    composing = false
                }
            }
            AlertDialog(onDismissRequest = { composing = false },
                title = { Text("Escrever mensagem") },
                text = {
                    OutlinedTextField(value = draft, onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        placeholder = { Text("O que você quer pedir ao Orbi?") },
                        minLines = 2, maxLines = 5, enabled = !model.busy && !listening,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }))
                },
                confirmButton = { TextButton(onClick = { submit() }, enabled = draft.isNotBlank() && !model.busy && !listening) { Text("Enviar") } },
                dismissButton = { TextButton(onClick = { composing = false }) { Text("Cancelar") } })
            LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
        }
        if (api) ApiDialog(model) { api = false }
        if (integrations) IntegrationsDialog(model, openBrowser) { showIntegrations(false) }
    }
}

@Composable
private fun SettingsScreen(model: ChatModel, modifier: Modifier, configure: () -> Unit, integrations: () -> Unit) {
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Assistente", style = MaterialTheme.typography.titleMedium)
        Text("${model.llmConfig.provider} · ${model.llmConfig.model}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = configure, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("Configurar API e modelo") }
        Spacer(Modifier.height(12.dp))
        Text("Conexões", style = MaterialTheme.typography.titleMedium)
        Text(if (model.spotifyConnected) "Spotify conectado" else "Spotify não conectado", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = integrations, modifier = Modifier.fillMaxWidth()) { Text("Gerenciar integrações") }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Ferramentas", style = MaterialTheme.typography.titleMedium)
        Text("Selecione as ferramentas para novas conversas. A conversa atual mantém sua seleção.",
            style = MaterialTheme.typography.bodySmall)
        model.toolCatalog.forEach { tool ->
            val name = tool.getString("name")
            val checked = model.selectedTools?.contains(name) != false
            Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox,
                onValueChange = { model.selectTool(name, it) }).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = null)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Text(tool.optString("description"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        OutlinedButton(onClick = { model.newConversation() }, enabled = !model.busy,
            modifier = Modifier.fillMaxWidth()) { Text("Iniciar nova conversa") }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        TextButton(onClick = { diagnostics = !diagnostics }) { Text(if (diagnostics) "Ocultar diagnóstico" else "Diagnóstico") }
        if (diagnostics) {
            Text("O relatório inclui sua pergunta, resposta e dados consultados. Revise antes de compartilhar.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { clipboard.setText(AnnotatedString(model.debugReport())) }) { Text("Copiar diagnóstico") }
            SelectionContainer { Text(model.debugReport(), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
