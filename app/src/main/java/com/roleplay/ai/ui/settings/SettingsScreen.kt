package com.roleplay.ai.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.roleplay.ai.RoleplayApp
import com.roleplay.ai.data.prefs.AppSettings
import com.roleplay.ai.data.prefs.ThemeMode
import com.roleplay.ai.ui.AppViewModel
import com.roleplay.ai.ui.ConnectionState
import com.roleplay.ai.ui.common.FileImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class ProviderPreset(val name: String, val baseUrl: String, val model: String)

private val Presets = listOf(
    ProviderPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    ProviderPreset("OpenRouter", "https://openrouter.ai/api/v1", "openrouter/auto"),
    ProviderPreset("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    ProviderPreset("Mistral", "https://api.mistral.ai/v1", "mistral-large-latest"),
    ProviderPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    ProviderPreset("Ollama (local)", "http://localhost:11434/v1", "llama3.1"),
    ProviderPreset("LM Studio (local)", "http://localhost:1234/v1", "local-model")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: RoleplayApp, onOpenLorebook: () -> Unit) {
    val viewModel: AppViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = AppViewModel.factory(app)
    )
    // The on-screen copy, which reflects typing immediately (see AppViewModel.draft).
    val settings = viewModel.draft
    val loaded = viewModel.loaded
    var showKey by remember { mutableStateOf(false) }

    fun update(transform: (AppSettings) -> AppSettings) = viewModel.update(transform)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<Uri?>(null) }

    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val previous = settings.chatBackground
            val name = app.repository.images.importImage(uri, maxSide = 2048)
            if (name == null) {
                snackbarHostState.showSnackbar("That image could not be read.")
            } else {
                update { it.copy(chatBackground = name) }
                if (previous.isNotBlank()) app.repository.images.delete(listOf(previous))
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val message = try {
                withContext(Dispatchers.IO) {
                    val json = app.backupManager.export()
                    val stream = context.contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Could not open the file for writing.")
                    stream.use { it.write(json.toByteArray()) }
                }
                "Backup saved."
            } catch (e: Exception) {
                "Export failed: ${e.message ?: "unknown error"}"
            } finally {
                busy = false
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importUri = uri
    }

    fun runImport(uri: Uri, replace: Boolean) {
        scope.launch {
            busy = true
            val message = try {
                val result = withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: throw IOException("Could not open the file.")
                    app.backupManager.import(text, replace).also {
                        if (replace) app.repository.sweepOrphanImages(keep = setOf(settings.chatBackground))
                    }
                }
                "Imported ${result.characters} characters, ${result.sessions} chats and documents, " +
                    "${result.messages} messages, ${result.memories} memories, ${result.lorebook} lorebook entries."
            } catch (e: Exception) {
                "Import failed: ${e.message ?: "unknown error"}"
            } finally {
                busy = false
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsHeader("API connection")
            Text(
                "Any OpenAI-compatible endpoint works: set the base URL, key, and model. " +
                    "For local servers (Ollama, LM Studio) leave the key empty.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Quick presets", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Presets.forEach { preset ->
                    androidx.compose.material3.FilterChip(
                        selected = settings.baseUrl == preset.baseUrl,
                        onClick = {
                            update { it.copy(baseUrl = preset.baseUrl, model = preset.model) }
                        },
                        label = { Text(preset.name) }
                    )
                }
            }

            val shortenedKey = "..." in settings.apiKey || "…" in settings.apiKey
            OutlinedTextField(
                value = settings.baseUrl,
                onValueChange = { v -> update { it.copy(baseUrl = v) } },
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth(),
                enabled = loaded,
                singleLine = true
            )
            OutlinedTextField(
                value = settings.apiKey,
                onValueChange = { v -> update { it.copy(apiKey = v) } },
                label = { Text("API key") },
                modifier = Modifier.fillMaxWidth(),
                enabled = loaded,
                singleLine = true,
                isError = shortenedKey,
                supportingText = if (shortenedKey) {
                    {
                        Text(
                            "This looks like a shortened key (it contains \"...\"). Providers show " +
                                "the full key only once, when it is created. Paste the full key."
                        )
                    }
                } else null,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showKey) "Hide key" else "Show key"
                        )
                    }
                }
            )
            OutlinedTextField(
                value = settings.model,
                onValueChange = { v -> update { it.copy(model = v) } },
                label = { Text("Model") },
                modifier = Modifier.fillMaxWidth(),
                enabled = loaded,
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val testing = viewModel.connection == ConnectionState.Testing
                Button(onClick = viewModel::testConnection, enabled = loaded && !testing) {
                    Text("Test connection")
                }
                if (testing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            when (val state = viewModel.connection) {
                is ConnectionState.Connected -> Text(
                    "Connected. ${state.model} answered, so chats will work.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                is ConnectionState.Failed -> Text(
                    "Not connected. ${state.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                ConnectionState.Testing -> Text("Testing…", style = MaterialTheme.typography.bodySmall)
                ConnectionState.Idle -> Text(
                    "Everything here saves by itself as you type, so there is no Connect button. " +
                        "Tap Test connection to check that your key works.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Temperature", modifier = Modifier.weight(1f))
                Text(
                    String.format("%.1f", settings.temperature),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Slider(
                value = settings.temperature,
                onValueChange = { v -> update { it.copy(temperature = v) } },
                valueRange = 0f..2f,
                steps = 19
            )
            NumberField(
                label = "Max response tokens",
                value = settings.maxTokens,
                onChange = { v -> update { it.copy(maxTokens = v) } }
            )

            SettingsHeader("Appearance")
            Text("Theme", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.themeMode == mode,
                        onClick = { update { it.copy(themeMode = mode) } },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size)
                    ) {
                        Text(
                            when (mode) {
                                ThemeMode.SYSTEM -> "System"
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK -> "Dark"
                            }
                        )
                    }
                }
            }

            Text("Chat background", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val hasBackground = settings.chatBackground.isNotBlank()
                if (hasBackground) {
                    FileImage(
                        file = app.repository.images.file(settings.chatBackground),
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        maxSidePx = 256,
                        contentDescription = "Current chat background"
                    )
                }
                OutlinedButton(onClick = {
                    backgroundPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }) { Text(if (hasBackground) "Change image" else "Choose image") }
                if (hasBackground) {
                    TextButton(onClick = {
                        val previous = settings.chatBackground
                        update { it.copy(chatBackground = "") }
                        app.repository.images.delete(listOf(previous))
                    }) { Text("Remove") }
                }
            }
            Text(
                "Shown dimmed behind the messages in every chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SettingsHeader("Custom prompt instructions")
            OutlinedTextField(
                value = settings.globalInstructions,
                onValueChange = { v -> update { it.copy(globalInstructions = v) } },
                label = { Text("Global instructions (applied to every chat)") },
                modifier = Modifier.fillMaxWidth(),
                enabled = loaded,
                minLines = 4,
                maxLines = 10,
                placeholder = {
                    Text("e.g. writing style, formatting rules, content boundaries, narration perspective…")
                }
            )

            SettingsHeader("Lorebook")
            Text(
                "World info entries with trigger keywords. An entry is added to the prompt only while " +
                    "one of its keywords appears in the recent messages or the document.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onOpenLorebook) { Text("Manage lorebook") }

            SettingsHeader("Memory extension")
            SettingsSwitch(
                title = "Auto-save new facts as memories",
                checked = settings.autoMemory,
                onChange = { v -> update { it.copy(autoMemory = v) } }
            )
            Text(
                "After each reply, one extra background request picks out up to 3 new lasting facts " +
                    "and adds them to that chat's Memories. Facts already saved are skipped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SettingsSwitch(
                title = "Auto-summarize long conversations",
                checked = settings.autoSummary,
                onChange = { v -> update { it.copy(autoSummary = v) } }
            )
            if (settings.autoSummary) {
                Text(
                    "Older messages are folded into a rolling summary so the story keeps context " +
                        "without growing the prompt forever.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                NumberField(
                    label = "Summarize after this many new messages",
                    value = settings.summaryThreshold,
                    onChange = { v -> update { it.copy(summaryThreshold = v.coerceAtLeast(4)) } }
                )
            }
            NumberField(
                label = "Recent messages kept in full",
                value = settings.contextMessages,
                onChange = { v -> update { it.copy(contextMessages = v.coerceAtLeast(2)) } }
            )

            SettingsSwitch(
                title = "Stream responses",
                checked = settings.streaming,
                onChange = { v -> update { it.copy(streaming = v) } }
            )

            SettingsHeader("Backup & restore")
            Text(
                "Saves characters, chats, documents, messages, memories and the lorebook to one JSON " +
                    "file. Settings, the API key and attached photos are not included.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        exportLauncher.launch("roleplay-ai-backup-$date.json")
                    },
                    enabled = !busy
                ) { Text("Export") }
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    },
                    enabled = !busy
                ) { Text("Import") }
                if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }

    importUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { importUri = null },
            title = { Text("Import backup") },
            text = {
                Text(
                    "Add the backup next to what is already in the app, or replace everything? " +
                        "Replacing permanently deletes your current characters, chats, documents, " +
                        "memories and lorebook first."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    importUri = null
                    runImport(uri, replace = false)
                }) { Text("Add to existing") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { importUri = null }) { Text("Cancel") }
                    TextButton(onClick = {
                        importUri = null
                        runImport(uri, replace = true)
                    }) { Text("Replace all", color = MaterialTheme.colorScheme.error) }
                }
            }
        )
    }
}

@Composable
private fun SettingsHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SettingsSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter { it.isDigit() }
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}
