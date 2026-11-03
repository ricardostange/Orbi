package br.com.assistente.teste.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import br.com.assistente.teste.ChatModel
import br.com.assistente.teste.integrations.IntegrationsDialog
import kotlinx.coroutines.launch

enum class AppSection(val title: String) { HOME("Início"), LISTS("Listas"), SETTINGS("Configurações") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrbiApp(model: ChatModel, section: AppSection, navigate: (AppSection) -> Unit,
    listening: Boolean, voiceError: String?, listen: () -> Unit, openBrowser: (Uri) -> Unit,
    integrations: Boolean, showIntegrations: (Boolean) -> Unit) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var api by rememberSaveable { mutableStateOf(false) }
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
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Spacer(Modifier.height(12.dp))
                            SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                if (model.question.isNotBlank()) Text(model.question, style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary)
                                Text(voiceError ?: model.answer, style = MaterialTheme.typography.bodyLarge)
                            } }
                            if (!model.busy && (model.question.isNotBlank() || voiceError != null)) {
                                TextButton(onClick = { clipboard.setText(AnnotatedString(voiceError ?: model.answer)) }) { Text("Copiar resposta") }
                            }
                        }
                        if (model.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Button(onClick = listen, enabled = !model.busy && !listening,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp).heightIn(min = 56.dp)) {
                            Text(if (model.busy) "Aguardando resposta…" else if (listening) "Ouvindo…" else "Falar")
                        }
                    }
                    AppSection.LISTS -> ListsScreen(model, Modifier.padding(padding))
                    AppSection.SETTINGS -> SettingsScreen(model, Modifier.padding(padding), { api = true }, { showIntegrations(true) })
                }
            }
        }
        val pendingDeletion by model.pendingListDeletion.collectAsState()
        pendingDeletion?.let { request ->
            AlertDialog(onDismissRequest = { model.cancelListDeletion(request.id) },
                title = { Text("Excluir lista?") },
                text = { Text("Excluir “${request.name}” e seus ${request.itemCount} itens? Essa ação não pode ser desfeita.") },
                confirmButton = { TextButton(enabled = !model.listsBusy, onClick = { model.confirmListDeletion(request.id) }) { Text("Excluir lista") } },
                dismissButton = { TextButton(onClick = { model.cancelListDeletion(request.id) }) { Text("Cancelar") } })
        }
        model.listsError?.let { message ->
            AlertDialog(onDismissRequest = model::dismissListsError, title = { Text("Listas") }, text = { Text(message) },
                confirmButton = { TextButton(onClick = model::dismissListsError) { Text("OK") } })
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
        TextButton(onClick = { diagnostics = !diagnostics }) { Text(if (diagnostics) "Ocultar diagnóstico" else "Diagnóstico") }
        if (diagnostics) {
            Text("O relatório inclui sua pergunta, resposta e dados consultados. Revise antes de compartilhar.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { clipboard.setText(AnnotatedString(model.debugReport())) }) { Text("Copiar diagnóstico") }
            SelectionContainer { Text(model.debugReport(), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
