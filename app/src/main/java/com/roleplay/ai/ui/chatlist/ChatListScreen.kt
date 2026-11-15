package com.roleplay.ai.ui.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roleplay.ai.RoleplayApp
import com.roleplay.ai.data.db.CharacterEntity
import com.roleplay.ai.data.db.isGroup
import com.roleplay.ai.data.db.isWriting
import com.roleplay.ai.ui.characters.CharacterAvatar
import com.roleplay.ai.ui.characters.avatarFile
import com.roleplay.ai.ui.common.ConfirmDeleteDialog
import com.roleplay.ai.ui.common.RenameDialog
import com.roleplay.ai.ui.common.rememberExportActions
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    app: RoleplayApp,
    onOpenChat: (Long) -> Unit,
    onOpenWriting: (Long) -> Unit,
    onGoToCharacters: () -> Unit,
    onGoToSettings: () -> Unit
) {
    val viewModel: ChatListViewModel = viewModel(factory = ChatListViewModel.factory(app))
    val items by viewModel.items.collectAsStateWithLifecycle()
    val needsSetup by viewModel.needsSetup.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ChatListItem?>(null) }
    var deleting by remember { mutableStateOf<ChatListItem?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val export = rememberExportActions(app) { message -> scope.launch { snackbarHostState.showSnackbar(message) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Chats") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showPicker = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New chat")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (needsSetup) SetupBanner(onGoToSettings)
            if (items.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("No chats yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Create a character and start a roleplay, or tap + to open a writing document.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    TextButton(onClick = onGoToCharacters, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Go to characters")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { it.session.id }) { item ->
                        ChatListRow(
                            item = item,
                            imageStore = app.repository.images,
                            onClick = {
                                if (item.session.isWriting) onOpenWriting(item.session.id) else onOpenChat(item.session.id)
                            },
                            onTogglePin = { viewModel.setPinned(item.session.id, !item.session.pinned) },
                            onRename = { renaming = item },
                            onSaveAsFile = { export.saveAsFile(item.session.id) },
                            onShare = { export.share(item.session.id) },
                            onDelete = { deleting = item }
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        CharacterPickerDialog(
            app = app,
            onDismiss = { showPicker = false },
            onStart = { characters ->
                showPicker = false
                viewModel.startChat(characters, onOpenChat)
            },
            onNewDocument = {
                showPicker = false
                viewModel.startWriting(onOpenWriting)
            },
            onGoToCharacters = {
                showPicker = false
                onGoToCharacters()
            }
        )
    }

    renaming?.let { item ->
        RenameDialog(
            title = if (item.session.isWriting) "Rename document" else "Rename chat",
            initial = item.session.title,
            onSave = {
                viewModel.rename(item.session.id, it)
                renaming = null
            },
            onDismiss = { renaming = null }
        )
    }

    deleting?.let { item ->
        val what = if (item.session.isWriting) "document" else "chat"
        ConfirmDeleteDialog(
            title = "Delete this $what?",
            text = "\"${item.session.title}\" and everything in it will be deleted. This cannot be undone.",
            onConfirm = {
                viewModel.deleteSession(item.session.id)
                deleting = null
            },
            onDismiss = { deleting = null }
        )
    }
}

/** Shown until an API key is saved, so a new user knows the first step. */
@Composable
private fun SetupBanner(onGoToSettings: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Connect your AI first", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Add your API key in Settings so characters can reply.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onGoToSettings) { Text("Open Settings") }
        }
    }
}

@Composable
private fun ChatListRow(
    item: ChatListItem,
    imageStore: com.roleplay.ai.data.files.ImageStore,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onSaveAsFile: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                item.session.isWriting -> SessionBadge(Icons.Filled.EditNote)
                item.session.isGroup -> SessionBadge(Icons.Filled.Groups)
                else -> CharacterAvatar(
                    name = item.character?.name ?: "?",
                    color = item.character?.avatarColor,
                    image = imageStore.avatarFile(item.character)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.session.pinned) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(14.dp)
                        )
                    }
                    Text(
                        item.session.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (item.preview.isNotBlank()) {
                    Text(
                        item.preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val kind = when {
                    item.session.isWriting -> "Document · "
                    item.session.isGroup -> "Group · "
                    else -> ""
                }
                Text(
                    kind + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(item.session.updatedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options for ${item.session.title}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (item.session.pinned) "Unpin" else "Pin to top") },
                        leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                        onClick = { menuOpen = false; onTogglePin() }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("Save as text file") },
                        leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                        onClick = { menuOpen = false; onSaveAsFile() }
                    )
                    DropdownMenuItem(
                        text = { Text("Share as text") },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        onClick = { menuOpen = false; onShare() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionBadge(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** Starts something new: a writing document, a chat with one character, or a group chat. */
@Composable
fun CharacterPickerDialog(
    app: RoleplayApp,
    onDismiss: () -> Unit,
    onStart: (List<CharacterEntity>) -> Unit,
    onNewDocument: () -> Unit,
    onGoToCharacters: () -> Unit
) {
    val charactersViewModel: com.roleplay.ai.ui.characters.CharactersViewModel =
        viewModel(factory = com.roleplay.ai.ui.characters.CharactersViewModel.factory(app))
    val characters by charactersViewModel.characters.collectAsStateWithLifecycle()
    var selectedIds by remember { mutableStateOf(listOf<Long>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start something new") },
        text = {
            Column {
                OutlinedButton(onClick = onNewDocument, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.EditNote, contentDescription = null)
                    Text("New writing document", modifier = Modifier.padding(start = 8.dp))
                }
                if (characters.isEmpty()) {
                    Text("No characters yet.", modifier = Modifier.padding(top = 16.dp))
                    TextButton(onClick = onGoToCharacters) { Text("Create one") }
                } else {
                    Text(
                        "Or chat: pick one character, or two or more for a group chat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(characters, key = { it.id }) { character ->
                            val checked = character.id in selectedIds
                            val toggle = {
                                selectedIds = if (checked) selectedIds - character.id else selectedIds + character.id
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = toggle)
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = { toggle() })
                                CharacterAvatar(
                                    name = character.name,
                                    color = character.avatarColor,
                                    size = 32.dp,
                                    image = app.repository.images.avatarFile(character)
                                )
                                Column(modifier = Modifier.padding(start = 12.dp)) {
                                    Text(character.name, style = MaterialTheme.typography.bodyLarge)
                                    if (character.tagline.isNotBlank()) {
                                        Text(
                                            character.tagline,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Keep the order the characters were picked in.
                    val byId = characters.associateBy { it.id }
                    onStart(selectedIds.mapNotNull { byId[it] })
                },
                enabled = selectedIds.isNotEmpty()
            ) {
                Text(if (selectedIds.size > 1) "Start group chat (${selectedIds.size})" else "Start chat")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
