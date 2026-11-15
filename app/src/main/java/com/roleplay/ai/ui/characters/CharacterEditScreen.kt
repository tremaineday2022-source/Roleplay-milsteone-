package com.roleplay.ai.ui.characters

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roleplay.ai.RoleplayApp
import com.roleplay.ai.data.db.CharacterEntity
import kotlinx.coroutines.launch
import java.util.Locale

private val Palette = listOf(
    0xFF6750A4, 0xFF7D5260, 0xFF00639B, 0xFF00696E, 0xFF4C662B,
    0xFF984061, 0xFF795369, 0xFF006A60, 0xFF35618F, 0xFF815600
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterEditScreen(
    app: RoleplayApp,
    characterId: Long,
    onBack: () -> Unit
) {
    val viewModel: CharactersViewModel = viewModel(factory = CharactersViewModel.factory(app))
    var name by remember { mutableStateOf("") }
    var tagline by remember { mutableStateOf("") }
    var persona by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var exampleDialogue by remember { mutableStateOf("") }
    var greeting by remember { mutableStateOf("") }
    var avatarColor by remember { mutableStateOf(Palette.first()) }
    var loaded by remember { mutableStateOf(false) }
    // Kept from the stored card so editing does not reset them.
    var isFavorite by remember { mutableStateOf(false) }
    var createdAt by remember { mutableStateOf<Long?>(null) }
    // Optional per-character model settings; blank / off means "use the app's Settings".
    var model by remember { mutableStateOf("") }
    var customTemperature by remember { mutableStateOf(false) }
    var temperature by remember { mutableStateOf(0.9f) }
    // The picture saved on the character, and the one shown in the editor (may be unsaved).
    var savedPicture by remember { mutableStateOf("") }
    var picture by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val pictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val name = app.repository.images.importImage(uri, maxSide = 512)
            if (name != null) {
                // Discard an earlier unsaved pick; the saved one is only replaced on Save.
                if (picture.isNotBlank() && picture != savedPicture) app.repository.images.delete(listOf(picture))
                picture = name
            }
        }
    }
    val generating by viewModel.generating.collectAsStateWithLifecycle()
    var showGenerate by remember { mutableStateOf(false) }
    var generateError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(characterId) {
        if (characterId > 0) {
            viewModel.loadCharacter(characterId) { c ->
                if (c != null) {
                    name = c.name
                    tagline = c.tagline
                    persona = c.persona
                    description = c.description
                    exampleDialogue = c.exampleDialogue
                    greeting = c.greeting
                    avatarColor = c.avatarColor
                    isFavorite = c.isFavorite
                    model = c.model
                    customTemperature = c.temperature != null
                    temperature = c.temperature ?: 0.9f
                    savedPicture = c.avatarImage
                    picture = c.avatarImage
                    createdAt = c.createdAt
                }
                loaded = true
            }
        } else {
            loaded = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (characterId > 0) "Edit character" else "New character") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (name.isNotBlank()) {
                                viewModel.save(
                                    CharacterEntity(
                                        id = characterId,
                                        name = name.trim(),
                                        tagline = tagline.trim(),
                                        avatarColor = avatarColor,
                                        persona = persona,
                                        description = description,
                                        exampleDialogue = exampleDialogue,
                                        greeting = greeting,
                                        isFavorite = isFavorite,
                                        createdAt = createdAt ?: System.currentTimeMillis(),
                                        avatarImage = picture,
                                        model = model.trim(),
                                        temperature = if (customTemperature) temperature else null
                                    )
                                )
                                if (savedPicture.isNotBlank() && savedPicture != picture) {
                                    app.repository.images.delete(listOf(savedPicture))
                                }
                                onBack()
                            }
                        },
                        enabled = name.isNotBlank() && loaded
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    generateError = null
                    showGenerate = true
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = loaded
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                Text("Generate with AI", modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = tagline,
                onValueChange = { tagline = it },
                label = { Text("Tagline (short summary)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Text("Picture", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CharacterAvatar(
                    name = name.ifBlank { "?" },
                    color = avatarColor,
                    size = 56.dp,
                    image = picture.takeIf { app.repository.images.exists(it) }?.let { app.repository.images.file(it) }
                )
                OutlinedButton(
                    onClick = {
                        pictureLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    enabled = loaded
                ) { Text(if (picture.isBlank()) "Add picture" else "Change picture") }
                if (picture.isNotBlank()) {
                    TextButton(onClick = {
                        if (picture != savedPicture) app.repository.images.delete(listOf(picture))
                        picture = ""
                    }) { Text("Remove") }
                }
            }

            Text("Avatar color (shown when there is no picture)", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Palette.forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .clickable { avatarColor = c },
                        contentAlignment = Alignment.Center
                    ) {
                        if (c == avatarColor) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = persona,
                onValueChange = { persona = it },
                label = { Text("Persona — who they are, how they speak and behave") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 10
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description & scenario — world, setting, situation") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 10
            )
            OutlinedTextField(
                value = exampleDialogue,
                onValueChange = { exampleDialogue = it },
                label = { Text("Example dialogue (optional style reference)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8
            )
            OutlinedTextField(
                value = greeting,
                onValueChange = { greeting = it },
                label = { Text("First message / greeting (optional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 6
            )

            Text("AI model for this character (optional)", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model name") },
                placeholder = { Text("Empty = the model from Settings") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Text(
                "Used in one-on-one chats with this character. Group chats always use the model " +
                    "from Settings. Type the name exactly as your provider lists it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Custom creativity (temperature)", modifier = Modifier.weight(1f))
                Switch(checked = customTemperature, onCheckedChange = { customTemperature = it })
            }
            if (customTemperature) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Temperature", modifier = Modifier.weight(1f))
                    Text(String.format(Locale.US, "%.1f", temperature))
                }
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..2f,
                    steps = 19
                )
            }
        }
    }

    if (showGenerate) {
        var idea by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!generating) showGenerate = false },
            title = { Text("Generate with AI") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Describe the character in a sentence or two. The fields below are filled in " +
                            "for you to review and edit; nothing is saved until you tap the check mark.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = idea,
                        onValueChange = { idea = it },
                        label = { Text("Short description") },
                        placeholder = { Text("e.g. a sarcastic ship AI who secretly cares about her crew") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        enabled = !generating
                    )
                    if (generating) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Writing the card…", modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                    generateError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        generateError = null
                        viewModel.generateCharacter(
                            idea = idea,
                            onDraft = { draft ->
                                if (draft.name.isNotBlank()) name = draft.name
                                tagline = draft.tagline
                                persona = draft.persona
                                description = draft.description
                                exampleDialogue = draft.exampleDialogue
                                greeting = draft.greeting
                                showGenerate = false
                            },
                            onError = { generateError = it }
                        )
                    },
                    enabled = idea.isNotBlank() && !generating
                ) { Text("Generate") }
            },
            dismissButton = {
                TextButton(onClick = { showGenerate = false }, enabled = !generating) { Text("Cancel") }
            }
        )
    }
}
