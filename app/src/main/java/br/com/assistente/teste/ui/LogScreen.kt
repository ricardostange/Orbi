package br.com.assistente.teste.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.assistente.teste.ChatModel
import java.text.DateFormat
import java.util.Date

@Composable
fun LogScreen(model: ChatModel, modifier: Modifier = Modifier) {
    val logs by model.logs.collectAsState()
    val error by model.logError.collectAsState()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val date = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Últimos 50 comandos", style = MaterialTheme.typography.titleLarge)
            Text("${logs.size}/50 · salvos neste aparelho. Abra um comando para ver as etapas. Conteúdos muito longos são abreviados.",
                style = MaterialTheme.typography.bodySmall)
            Text("Os registros incluem suas mensagens e dados consultados. Revise antes de compartilhar.", style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = logs.isNotEmpty() && !model.busy, onClick = { confirmClear = true }) { Text("Limpar log") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (logs.isEmpty()) item { Text("Nenhum comando registrado. Faça um pedido ao Orbi para acompanhar o fluxo aqui.") }
        items(logs, key = { it.id }) { entry ->
            val expanded = selected == entry.id
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(date.format(Date(entry.time)) + " · " + entry.status,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (entry.status == "Erro") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    SelectionContainer { Text(entry.command, style = MaterialTheme.typography.titleMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { selected = if (expanded) null else entry.id }) { Text(if (expanded) "Recolher" else "Ver etapas") }
                        TextButton(onClick = { clipboard.setText(AnnotatedString(entry.report())) }) { Text("Copiar comando") }
                    }
                }
            }
        }
    }
    selected?.let { id -> logs.find { it.id == id }?.let { entry ->
        AlertDialog(onDismissRequest = { selected = null },
            title = { Text("Etapas do comando") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { SelectionContainer { Text(entry.command, style = MaterialTheme.typography.titleMedium) } }
                    item { Text(entry.status, color = MaterialTheme.colorScheme.primary) }
                    items(entry.events) { event ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(date.format(Date(event.time)) + " · " + event.title, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                            SelectionContainer { Text(event.detail, style = MaterialTheme.typography.bodySmall) }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(entry.report())) }) { Text("Copiar log") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Fechar") } })
    } }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Limpar log?") },
        text = { Text("Os comandos registrados serão removidos deste aparelho.") },
        confirmButton = { TextButton(enabled = !model.busy, onClick = { model.clearLogs(); selected = null; confirmClear = false }) { Text("Limpar") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } })
}
