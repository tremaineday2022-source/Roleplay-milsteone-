package com.roleplay.ai.ui.lorebook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roleplay.ai.RoleplayApp
import com.roleplay.ai.data.db.LorebookEntity
import com.roleplay.ai.data.db.isWriting
import com.roleplay.ai.data.repo.ChatRepository
import com.roleplay.ai.ui.common.ConfirmDeleteDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * [sessionId] is the chat or document the lorebook was opened from, or 0 when it was opened
 * from Settings (shared entries only).
 */
class LorebookViewModel(private val repo: ChatRepository, private val sessionId: Long) : ViewModel() {

    val entries: StateFlow<List<LorebookEntity>> = repo.observeLorebook(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isDocument = MutableStateFlow(false)
    val isDocument: StateFlow<Boolean> = _isDocument.asStateFlow()

    init {
        if (sessionId > 0) viewModelScope.launch {
            _isDocument.value = repo.getSession(sessionId)?.isWriting == true
        }
    }

    fun save(entry: LorebookEntity) {
        viewModelScope.launch { repo.saveLoreEntry(entry) }
    }

    fun delete(entry: LorebookEntity) {
        viewModelScope.launch { repo.deleteLoreEntry(entry) }
    }

    companion object {
        fun factory(app: RoleplayApp, sessionId: Long) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LorebookViewModel(app.repository, sessionId) as T
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LorebookScreen(app: RoleplayApp, sessionId: Long, onBack: () -> Unit) {
    val viewModel: LorebookViewModel = viewModel(factory = LorebookViewModel.factory(app, sessionId))
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val isDocument by viewModel.isDocument.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<LorebookEntity?>(null) }
    var deleting by remember { mutableStateOf<LorebookEntity?>(null) }

    val inSession = sessionId > 0
    val here = if (isDocument) "This document" else "This chat"
    val everywhere = "All chats"
    // Tab 0 = this chat's own entries, tab 1 = entries shared by everything.
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val own = entries.filter { it.sessionId == sessionId && inSession }
    val shared = entries.filter { it.sessionId == 0L }
    val showingOwn = inSession && tab == 0
    val visible = if (showingOwn) own else shared

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lorebook") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editing = LorebookEntity(
                        name = "", keywords = "", content = "",
                        sessionId = if (showingOwn) sessionId else 0
                    )
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add entry") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (inSession) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("$here (${own.size})") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("$everywhere (${shared.size})") })
                }
            }
            Text(
                when {
                    showingOwn -> "World info used only in ${here.lowercase()}, on top of the shared entries. "
                    else -> "World info shared by every chat and document. "
                } + "An entry is added to the prompt only while one of its keywords appears in the " +
                    "recent messages or the document.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (visible.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("No entries yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tap \"Add entry\" to write a piece of lore, such as a place, a rule of the " +
                            "world or a character's history, and the words that should bring it up.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visible, key = { it.id }) { entry ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editing = entry }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = entry.enabled,
                                    onCheckedChange = { viewModel.save(entry.copy(enabled = it)) }
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = 8.dp)
                                ) {
                                    Text(
                                        entry.name.ifBlank { "Untitled entry" },
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "Keywords: ${entry.keywords.ifBlank { "none (never triggers)" }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        entry.content,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { deleting = entry }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete entry")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { entry ->
        var name by remember(entry) { mutableStateOf(entry.name) }
        var keywords by remember(entry) { mutableStateOf(entry.keywords) }
        var content by remember(entry) { mutableStateOf(entry.content) }
        var onlyHere by remember(entry) { mutableStateOf(entry.sessionId != 0L) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (entry.id == 0L) "New lorebook entry" else "Edit lorebook entry") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = keywords,
                        onValueChange = { keywords = it },
                        label = { Text("Trigger keywords, separated by commas") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("Content added to the prompt") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 10
                    )
                    if (inSession) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Only ${here.lowercase()}", modifier = Modifier.weight(1f))
                            Switch(checked = onlyHere, onCheckedChange = { onlyHere = it })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.save(
                            entry.copy(
                                name = name.trim(),
                                keywords = keywords.trim(),
                                content = content.trim(),
                                sessionId = if (inSession && onlyHere) sessionId else 0
                            )
                        )
                        editing = null
                    },
                    enabled = content.isNotBlank() && keywords.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Cancel") }
            }
        )
    }

    deleting?.let { entry ->
        ConfirmDeleteDialog(
            title = "Delete this entry?",
            text = "\"${entry.name.ifBlank { "Untitled entry" }}\" will be deleted. This cannot be undone.",
            onConfirm = {
                viewModel.delete(entry)
                deleting = null
            },
            onDismiss = { deleting = null }
        )
    }
}
