package br.com.assistente.teste.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextDecoration
import br.com.assistente.teste.ChatModel

@Composable
fun ListsScreen(model: ChatModel, modifier: Modifier = Modifier) {
    val lists by model.lists.collectAsState()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = lists.firstOrNull { it.id == selectedId }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var itemText by rememberSaveable(selectedId) { mutableStateOf("") }
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }
    val removeItem = selected?.items?.firstOrNull { it.id == removeId }
    BackHandler(selectedId != null || editing || removeId != null) {
        when { removeId != null -> removeId = null; editing -> editing = false; else -> selectedId = null }
    }
    LaunchedEffect(lists, model.listsReady) {
        if (model.listsReady && selectedId != null && selected == null) selectedId = null
    }
    Column(modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!model.listsReady) {
            Text("Carregando listas…")
            if (model.listsBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            else TextButton(onClick = { model.loadLists() }) { Text("Tentar novamente") }
        } else if (selected == null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Suas listas", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(enabled = !model.listsBusy, onClick = { editingId = null; name = ""; editing = true }) {
                    Icon(Icons.Default.Add, "Criar lista")
                }
            }
            if (lists.isEmpty()) Text("Nenhuma lista ainda. Toque em + para criar, por exemplo, Compras.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f)) {
                items(lists, key = { it.id }) { list ->
                    ListItem(headlineContent = { Text(list.name) }, supportingContent = {
                        Text("${list.items.count { it.completed }} de ${list.items.size} concluídos")
                    }, modifier = Modifier.clickable { selectedId = list.id })
                    HorizontalDivider()
                }
            }
        } else {
            TextButton(onClick = { selectedId = null }) { Text("Voltar às listas") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(selected.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(enabled = !model.listsBusy, onClick = { editingId = selected.id; name = selected.name; editing = true }) {
                    Icon(Icons.Default.Edit, "Renomear lista ${selected.name}")
                }
                IconButton(enabled = !model.listsBusy, onClick = { model.requestListDeletion(selected.id) }) {
                    Icon(Icons.Default.Delete, "Excluir lista ${selected.name}")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                fun add() { if (itemText.isNotBlank() && !model.listsBusy) model.addListItem(selected.id, itemText) { itemText = "" } }
                OutlinedTextField(value = itemText, onValueChange = { itemText = it }, label = { Text("Novo item") },
                    enabled = !model.listsBusy, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }))
                IconButton(enabled = itemText.isNotBlank() && !model.listsBusy, onClick = { add() }) {
                    Icon(Icons.Default.Add, "Adicionar item")
                }
            }
            if (selected.items.isEmpty()) Text("Esta lista está vazia. Adicione seu primeiro item.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f)) {
                items(selected.items, key = { it.id }) { item ->
                    ListItem(headlineContent = {
                        Text(item.text,
                            textDecoration = if (item.completed) TextDecoration.LineThrough else TextDecoration.None,
                            color = if (item.completed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.onSurface)
                    },
                        leadingContent = {
                            Checkbox(checked = item.completed, enabled = !model.listsBusy,
                                onCheckedChange = { model.completeListItem(selected.id, item.id, it) },
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Concluído: ${item.text}" })
                        }, trailingContent = {
                            IconButton(enabled = !model.listsBusy, onClick = { removeId = item.id }) { Icon(Icons.Default.Delete, "Remover ${item.text}") }
                        }, modifier = Modifier.fillMaxWidth())
                    HorizontalDivider()
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (editing) {
        AlertDialog(onDismissRequest = { if (!model.listsBusy) editing = false },
            title = { Text(if (editingId == null) "Nova lista" else "Renomear lista") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, enabled = !model.listsBusy, label = { Text("Nome da lista") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && !model.listsBusy, onClick = {
                    val id = editingId
                    if (id == null) model.createList(name) { editing = false }
                    else model.renameList(id, name) { editing = false }
                }) { Text("Salvar") }
            }, dismissButton = { TextButton(enabled = !model.listsBusy, onClick = { editing = false }) { Text("Cancelar") } })
    }
    if (removeItem != null) {
        AlertDialog(onDismissRequest = { removeId = null }, title = { Text("Remover item?") }, text = { Text("Remover “${removeItem.text}” de “${selected.name}”?") },
            confirmButton = { TextButton(enabled = !model.listsBusy, onClick = { model.removeListItem(selected.id, removeItem.id); removeId = null }) { Text("Remover") } },
            dismissButton = { TextButton(onClick = { removeId = null }) { Text("Cancelar") } })
    }
}
