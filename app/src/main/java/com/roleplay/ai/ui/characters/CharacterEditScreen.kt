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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roleplay.ai.RoleplayApp
import com.roleplay.ai.data.db.CharacterEntity

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
                                        createdAt = createdAt ?: System.currentTimeMillis()
                                    )
                                )
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

            Text("Avatar color", style = MaterialTheme.typography.labelLarge)
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
