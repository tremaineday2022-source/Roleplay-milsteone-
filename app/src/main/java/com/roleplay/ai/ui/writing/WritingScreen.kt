package com.roleplay.ai.ui.writing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roleplay.ai.RoleplayApp
import com.roleplay.ai.domain.DocumentWindow
import com.roleplay.ai.domain.TokenEstimate
import com.roleplay.ai.ui.common.NoteDialog
import com.roleplay.ai.ui.common.NoteStrip
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WritingScreen(
    app: RoleplayApp,
    sessionId: Long,
    onBack: () -> Unit,
    onOpenMemories: () -> Unit,
    onOpenLorebook: () -> Unit
) {
    val viewModel: WritingViewModel = viewModel(factory = WritingViewModel.factory(app, sessionId))
    val generating by viewModel.generating.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf(false) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    val text = viewModel.document.text
    val words = remember(text) { text.split(Regex("\\s+")).count { it.isNotEmpty() } }
    val limit = settings.writingContextWords
    val sentTokens = remember(text, limit) { TokenEstimate.text(DocumentWindow.tail(text, limit)) }
    val sendLine = remember(sentTokens, words, limit) {
        val tokens = NumberFormat.getIntegerInstance().format(sentTokens)
        if (limit in 1 until words) {
            "Continue sends the last ${NumberFormat.getIntegerInstance().format(limit)} words (≈$tokens tokens)."
        } else {
            "Continue sends the whole document (≈$tokens tokens)."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { renaming = true }) {
                        Text(
                            viewModel.title.ifBlank { "Untitled document" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            when {
                                generating -> "Writing…"
                                saved -> "$words words · Saved"
                                else -> "$words words · Saving…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { editingNote = true }) {
                        Icon(Icons.AutoMirrored.Filled.StickyNote2, contentDescription = "Document note")
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            if (viewModel.note.isNotBlank()) NoteStrip(note = viewModel.note, onClick = { editingNote = true })
            TextField(
                value = viewModel.document,
                onValueChange = viewModel::onDocumentChange,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                readOnly = generating || !viewModel.loaded,
                placeholder = { Text("Start writing. Continue sends the whole document and adds what comes next.") },
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                )
            )
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        sendLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    )
                    if (generating) {
                        OutlinedButton(onClick = viewModel::stop) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Text("Stop", modifier = Modifier.padding(start = 6.dp))
                        }
                    } else {
                        Button(onClick = viewModel::continueWriting, enabled = text.isNotBlank()) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                            Text("Continue", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }

    if (editingNote) {
        NoteDialog(
            initial = viewModel.note,
            forDocument = true,
            onDismiss = { editingNote = false },
            onSave = {
                viewModel.saveNote(it)
                editingNote = false
            }
        )
    }

    if (renaming) {
        var name by remember { mutableStateOf(viewModel.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename document") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.rename(name)
                        renaming = false
                    },
                    enabled = name.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) { Text("Cancel") }
            }
        )
    }
}
