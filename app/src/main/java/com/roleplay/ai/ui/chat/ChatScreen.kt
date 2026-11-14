package com.roleplay.ai.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Groups
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roleplay.ai.RoleplayApp
import com.roleplay.ai.data.db.MessageEntity
import com.roleplay.ai.data.db.imageList
import com.roleplay.ai.data.db.variantList
import com.roleplay.ai.data.repo.ReplyKind
import com.roleplay.ai.domain.TextJoin
import com.roleplay.ai.ui.characters.CharacterAvatar
import com.roleplay.ai.ui.characters.avatarFile
import com.roleplay.ai.ui.common.NoteDialog
import com.roleplay.ai.ui.common.NoteStrip
import com.roleplay.ai.ui.common.FileImage
import com.roleplay.ai.ui.common.roleplayText
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    app: RoleplayApp,
    sessionId: Long,
    onBack: () -> Unit,
    onOpenMemories: () -> Unit,
    onOpenLorebook: () -> Unit
) {
    val viewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(app, sessionId))
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val generation by viewModel.generation.collectAsStateWithLifecycle()
    val pendingImages by viewModel.pendingImages.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val tokens by viewModel.tokens.collectAsStateWithLifecycle()
    var editingNote by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var input by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MessageEntity?>(null) }
    var pinning by remember { mutableStateOf<MessageEntity?>(null) }

    val generating = generation != null
    val isGroup = characters.size > 1
    val character = characters.firstOrNull()
    val speaker = if (isGroup) "" else character?.name ?: "AI"
    val lastMessage = messages.lastOrNull()
    val showsNewBubble = generation?.kind == ReplyKind.NEW

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 6)
    ) { uris -> viewModel.attachImages(uris) }

    LaunchedEffect(messages.size, generation?.text, lastMessage?.content) {
        val count = messages.size + if (showsNewBubble) 1 else 0
        // A huge offset pins the view to the bottom of the last item while it grows.
        if (count > 0) listState.scrollToItem(count - 1, Int.MAX_VALUE / 2)
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
                        if (isGroup) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Groups,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        } else {
                            CharacterAvatar(
                                name = character?.name ?: "…",
                                color = character?.avatarColor,
                                size = 32.dp,
                                image = app.repository.images.avatarFile(character)
                            )
                        }
                        Column(modifier = Modifier.padding(start = 10.dp)) {
                            Text(
                                if (isGroup) session?.title ?: "Group chat" else character?.name ?: "Chat",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val subtitle = if (isGroup) "Group chat · ${characters.size} characters" else character?.tagline.orEmpty()
                            if (subtitle.isNotBlank()) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
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
                    IconButton(onClick = { editingNote = true }) {
                        Icon(Icons.AutoMirrored.Filled.StickyNote2, contentDescription = "Chat note")
                    }
                    IconButton(onClick = onOpenLorebook) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Lorebook")
                    }
                    IconButton(onClick = onOpenMemories) {
                        Icon(Icons.Filled.Save, contentDescription = "Memories")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val background = settings.chatBackground.takeIf { it.isNotBlank() }?.let { viewModel.imageFile(it) }
            val hasBackground = background != null && background.isFile
            if (background != null && hasBackground) {
                FileImage(file = background, modifier = Modifier.fillMaxSize(), maxSidePx = 1600)
                // A light scrim keeps text readable without hiding the picture.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = BACKGROUND_SCRIM))
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
            ) {
                session?.note?.takeIf { it.isNotBlank() }?.let { note ->
                    NoteStrip(note = note, onClick = { editingNote = true })
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        val isLast = message.id == lastMessage?.id
                        val target = generation?.takeIf { it.targetId == message.id }
                        val idleLast = isLast && !generating
                        val lastReply = idleLast && message.role == "assistant"
                        MessageBubble(
                            message = message,
                            text = when (target?.kind) {
                                ReplyKind.REGENERATE -> target.text
                                ReplyKind.CONTINUE -> TextJoin.join(message.content, target.text)
                                else -> message.content
                            },
                            speaker = speaker,
                            streaming = target != null,
                            translucent = hasBackground,
                            imageFile = viewModel::imageFile,
                            onEdit = if (generating) null else ({ editing = message }),
                            onDelete = if (generating) null else ({ viewModel.deleteMessage(message) }),
                            onPin = { pinning = message },
                            onRegenerate = if (lastReply && messages.size > 1) viewModel::regenerate else null,
                            onContinue = if (lastReply) viewModel::continueReply else null,
                            onGenerateReply = if (idleLast && message.role == "user") viewModel::generateReply else null,
                            onSwipe = if (lastReply) ({ delta -> viewModel.swipe(message, delta) }) else null
                        )
                    }
                    if (showsNewBubble) {
                        item(key = "streaming") {
                            MessageBubble(
                                message = MessageEntity(sessionId = sessionId, role = "assistant", content = ""),
                                text = generation?.text.orEmpty(),
                                speaker = speaker,
                                streaming = true,
                                translucent = hasBackground,
                                imageFile = viewModel::imageFile
                            )
                        }
                    }
                }

                Surface(
                    tonalElevation = 2.dp,
                    color = if (hasBackground) MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                    else MaterialTheme.colorScheme.surface
                ) {
                    Column {
                        tokens?.let { usage ->
                            Text(
                                tokenLine(usage),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, top = 6.dp)
                            )
                        }
                        if (pendingImages.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(start = 12.dp, end = 12.dp, top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                pendingImages.forEach { name ->
                                    Box {
                                        FileImage(
                                            file = viewModel.imageFile(name),
                                            modifier = Modifier
                                                .size(72.dp)
                                                .clip(RoundedCornerShape(8.dp)),
                                            maxSidePx = 256,
                                            contentDescription = "Attached photo"
                                        )
                                        IconButton(
                                            onClick = { viewModel.removePendingImage(name) },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(24.dp)
                                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), CircleShape)
                                        ) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "Remove photo",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            IconButton(
                                onClick = {
                                    photoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                enabled = !generating
                            ) {
                                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Attach photos")
                            }
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
                                    enabled = input.isNotBlank() || pendingImages.isNotEmpty()
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                                }
                            }
                        }
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

    if (editingNote) {
        NoteDialog(
            initial = session?.note.orEmpty(),
            forDocument = false,
            onDismiss = { editingNote = false },
            onSave = {
                viewModel.saveNote(it)
                editingNote = false
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
    text: String,
    speaker: String,
    streaming: Boolean,
    imageFile: (String) -> File,
    translucent: Boolean = false,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onPin: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onContinue: (() -> Unit)? = null,
    onGenerateReply: (() -> Unit)? = null,
    onSwipe: ((Int) -> Unit)? = null
) {
    val isUser = message.role == "user"
    var menuOpen by remember { mutableStateOf(false) }
    val variants = remember(message.variants, message.content) { message.variantList() }
    val canSwipe = onSwipe != null && variants.size > 1
    val swipe by rememberUpdatedState(onSwipe)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = (if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant).copy(alpha = if (translucent) 0.88f else 1f)
            ),
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .then(
                    if (canSwipe) {
                        Modifier.pointerInput(message.id) {
                            val threshold = 56.dp.toPx()
                            var dragged = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { dragged = 0f },
                                onDragCancel = { dragged = 0f },
                                onDragEnd = {
                                    // Swipe left for the next variant, right for the previous one.
                                    if (dragged <= -threshold) swipe?.invoke(1)
                                    else if (dragged >= threshold) swipe?.invoke(-1)
                                    dragged = 0f
                                },
                                onHorizontalDrag = { _, amount -> dragged += amount }
                            )
                        }
                    } else Modifier
                )
                .combinedClickable(onClick = {}, onLongClick = { if (!streaming) menuOpen = true })
        ) {
            Box {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val label = when {
                        isUser -> ""
                        streaming && speaker.isBlank() -> "typing…"
                        streaming -> "$speaker (typing…)"
                        else -> speaker
                    }
                    if (label.isNotBlank()) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    message.imageList().forEach { name ->
                        FileImage(
                            file = imageFile(name),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                            contentDescription = "Photo"
                        )
                    }
                    val shown = if (streaming && text.isBlank()) "…" else text
                    if (shown.isNotBlank()) {
                        Text(roleplayText(shown), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    onEdit?.let {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = { menuOpen = false; it() }
                        )
                    }
                    if (message.content.isNotBlank()) {
                        onPin?.let {
                            DropdownMenuItem(
                                text = { Text("Save as memory") },
                                leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                                onClick = { menuOpen = false; it() }
                            )
                        }
                    }
                    onRegenerate?.let {
                        DropdownMenuItem(
                            text = { Text("Regenerate") },
                            leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                            onClick = { menuOpen = false; it() }
                        )
                    }
                    onContinue?.let {
                        DropdownMenuItem(
                            text = { Text("Continue") },
                            leadingIcon = { Icon(Icons.Filled.FastForward, contentDescription = null) },
                            onClick = { menuOpen = false; it() }
                        )
                    }
                    onGenerateReply?.let {
                        DropdownMenuItem(
                            text = { Text("Generate reply") },
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

        if (onRegenerate != null || onContinue != null || canSwipe) {
            Row(
                modifier = Modifier.fillMaxWidth(0.85f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (canSwipe) {
                    IconButton(onClick = { onSwipe?.invoke(-1) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous reply")
                    }
                    Text(
                        "${message.variantIndex.coerceIn(0, variants.lastIndex) + 1}/${variants.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = { onSwipe?.invoke(1) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.ChevronRight, contentDescription = "Next reply")
                    }
                }
                Box(modifier = Modifier.weight(1f))
                onRegenerate?.let {
                    IconButton(onClick = it, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Regenerate",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                onContinue?.let {
                    IconButton(onClick = it, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.FastForward,
                            contentDescription = "Continue",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
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

private const val BACKGROUND_SCRIM = 0.35f

private fun tokenLine(usage: TokenUsage): String {
    val format = java.text.NumberFormat.getIntegerInstance()
    val sent = (if (usage.exact) "" else "≈") + format.format(usage.sent) + " tokens sent"
    val received = usage.received?.let { " · " + format.format(it) + " received" }.orEmpty()
    val note = if (usage.exact) " (last reply)" else " (estimate, last reply)"
    return sent + received + note
}
