package com.roleplay.ai.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roleplay.ai.RoleplayApp
import com.roleplay.ai.data.prefs.AppSettings
import com.roleplay.ai.data.prefs.ThemeMode
import com.roleplay.ai.ui.AppViewModel

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
fun SettingsScreen(app: RoleplayApp) {
    val viewModel: AppViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = AppViewModel.factory(app)
    )
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var showKey by remember { mutableStateOf(false) }

    fun update(transform: (AppSettings) -> AppSettings) = viewModel.update(transform)

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
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

            OutlinedTextField(
                value = settings.baseUrl,
                onValueChange = { v -> update { it.copy(baseUrl = v) } },
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = settings.apiKey,
                onValueChange = { v -> update { it.copy(apiKey = v) } },
                label = { Text("API key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
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
                singleLine = true
            )

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

            SettingsHeader("Custom prompt instructions")
            OutlinedTextField(
                value = settings.globalInstructions,
                onValueChange = { v -> update { it.copy(globalInstructions = v) } },
                label = { Text("Global instructions (applied to every chat)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 10,
                placeholder = {
                    Text("e.g. writing style, formatting rules, content boundaries, narration perspective…")
                }
            )

            SettingsHeader("Memory extension")
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
        }
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
