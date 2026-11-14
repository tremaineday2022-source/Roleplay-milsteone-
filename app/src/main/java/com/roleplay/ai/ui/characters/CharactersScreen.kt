package com.roleplay.ai.ui.characters

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roleplay.ai.RoleplayApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharactersScreen(
    app: RoleplayApp,
    onEditCharacter: (Long) -> Unit,
    onStartChat: (Long) -> Unit
) {
    val viewModel: CharactersViewModel = viewModel(factory = CharactersViewModel.factory(app))
    val characters by viewModel.characters.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Characters") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEditCharacter(0L) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New character") }
            )
        }
    ) { padding ->
        if (characters.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("No characters yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Define a persona, a scenario, and custom instructions for each roleplay.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(characters, key = { it.id }) { character ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEditCharacter(character.id) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CharacterAvatar(name = character.name, color = character.avatarColor)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp)
                            ) {
                                Text(character.name, style = MaterialTheme.typography.titleSmall)
                                if (character.tagline.isNotBlank()) {
                                    Text(
                                        character.tagline,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(onClick = {
                                viewModel.startChat(character, onStartChat)
                            }) {
                                Icon(Icons.Filled.Chat, contentDescription = "Start chat")
                            }
                            IconButton(onClick = { onEditCharacter(character.id) }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit")
                            }
                            IconButton(onClick = { viewModel.delete(character) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
        }
    }
}
