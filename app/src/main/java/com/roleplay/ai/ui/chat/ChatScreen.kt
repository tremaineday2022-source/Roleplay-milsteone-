package com.roleplay.ai.ui.chat

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roleplay.ai.RoleplayApp
import com.roleplay.ai.data.db.MessageEntity
import com.roleplay.ai.ui.characters.CharacterAvatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    app: RoleplayApp,
    sessionId: Long,
    onBack: () -> Unit,
    onOpenMemories: () -> Unit
) {
    val viewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(app, sessionId))
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val character by viewModel.character.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val generating by viewModel.generating.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var input by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MessageEntity?>(null) }
    var pinning by remember { mutableStateOf<MessageEntity?>(null) }

    LaunchedEffect(messages.size, streamingText) {
        if (messages.isNotEmpty() || streamingText.isNotEmpty()) {
            listState.animateScrollToItem((messages.size + if (streamingText.isNotEmpty()) 1 else 0).coerceAtLeast(0))
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CharacterAvatar(name = character?.name ?: "…", color = character?.avatarColor, size = 32.dp)
                        Column(modifier = Modifier.padding(start = 10.dp)) {
                            Text(character?.name ?: "Chat", style = MaterialTheme.typography.titleMedium)
                            if (character?.tagline?.isNotBlank() == true) {
                                Text(
                                    character!!.tagline,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenMemories) {
                        Icon(Icons.Filled.Save, contentDescription = "Memories")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        characterName = character?.name ?: "AI",
                        onEdit = { editing = message },
                        onDelete = { viewModel.deleteMessage(message) },
                        onPin = { pinning = message },
                        onRegenerate = if (message == messages.lastOrNull { it.role == "assistant" }) {
                            { viewModel.regenerate() }
                        } else null
                    )
                }
                if (generating && streamingText.isNotEmpty()) {
                    items(listOf("__streaming__")) { _ ->
                        MessageBubble(
                            message = MessageEntity(sessionId = sessionId, role = "assistant", content = streamingText),
                            characterName = character?.name ?: "AI",
                            streaming = true
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message…") },
                    maxLines = 5
                )
                if (generating) {
                    IconButton(onClick = { viewModel.stopGeneration() }) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop")
                    }
                } else {
                    IconButton(
                        onClick = {
                            viewModel.send(input)
                            input = ""
                        },
                        enabled = input.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }

    editing?.let { message ->
        EditMessageDialog(
            initial = message.content,
            onDismiss = { editing = null },
            onSave = {
                viewModel.updateMessage(message, it)
                editing = null
            }
        )
    }

    pinning?.let { message ->
        AlertDialog(
            onDismissRequest = { pinning = null },
            title = { Text("Save as memory") },
            text = { Text("Keep this message as a long-term memory for this chat?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.pinMemory(message.content)
                    pinning = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { pinning = null }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageEntity,
    characterName: String,
    streaming: Boolean = false,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onPin: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null
) {
    val isUser = message.role == "user"
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
        ) {
            Box {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (!isUser) {
                        Text(
                            if (streaming) "$characterName (typing…)" else characterName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(message.content, style = MaterialTheme.typography.bodyMedium)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    onEdit?.let {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = { menuOpen = false; it() }
                        )
                    }
                    onPin?.let {
                        DropdownMenuItem(
                            text = { Text("Save as memory") },
                            leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                            onClick = { menuOpen = false; it() }
                        )
                    }
                    onRegenerate?.let {
                        DropdownMenuItem(
                            text = { Text("Regenerate") },
                            leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                            onClick = { menuOpen = false; it() }
                        )
                    }
                    onDelete?.let {
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menuOpen = false; it() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditMessageDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit message") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 10
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
