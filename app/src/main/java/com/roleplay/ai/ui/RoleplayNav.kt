package com.roleplay.ai.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.roleplay.ai.RoleplayApp
import com.roleplay.ai.ui.characters.CharacterEditScreen
import com.roleplay.ai.ui.characters.CharactersScreen
import com.roleplay.ai.ui.chat.ChatScreen
import com.roleplay.ai.ui.chatlist.ChatListScreen
import com.roleplay.ai.ui.memory.MemoryScreen
import com.roleplay.ai.ui.settings.SettingsScreen

object Routes {
    const val CHATS = "chats"
    const val CHARACTERS = "characters"
    const val SETTINGS = "settings"
    const val CHAT = "chat/{sessionId}"
    const val CHARACTER_EDIT = "character/edit?characterId={characterId}"
    const val MEMORY = "memory/{sessionId}"

    fun chat(sessionId: Long) = "chat/$sessionId"
    fun characterEdit(characterId: Long = 0L) = "character/edit?characterId=$characterId"
    fun memory(sessionId: Long) = "memory/$sessionId"
}

@Composable
fun RoleplayNav(app: RoleplayApp) {
    val navController: NavHostController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val showBottomBar = route in listOf(Routes.CHATS, Routes.CHARACTERS, Routes.SETTINGS)

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = route == Routes.CHATS,
                        onClick = { navController.navigateTab(Routes.CHATS) },
                        icon = { Icon(Icons.Filled.Forum, contentDescription = null) },
                        label = { Text("Chats") }
                    )
                    NavigationBarItem(
                        selected = route == Routes.CHARACTERS,
                        onClick = { navController.navigateTab(Routes.CHARACTERS) },
                        icon = { Icon(Icons.Filled.TheaterComedy, contentDescription = null) },
                        label = { Text("Characters") }
                    )
                    NavigationBarItem(
                        selected = route == Routes.SETTINGS,
                        onClick = { navController.navigateTab(Routes.SETTINGS) },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Settings") }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.CHATS,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.CHATS) {
                ChatListScreen(
                    app = app,
                    onOpenChat = { navController.navigate(Routes.chat(it)) },
                    onGoToCharacters = { navController.navigateTab(Routes.CHARACTERS) }
                )
            }
            composable(Routes.CHARACTERS) {
                CharactersScreen(
                    app = app,
                    onEditCharacter = { navController.navigate(Routes.characterEdit(it)) },
                    onStartChat = { characterId ->
                        navController.navigate(Routes.chat(characterId))
                    }
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(app = app)
            }
            composable(
                Routes.CHAT,
                arguments = listOf(navArgument("sessionId") { type = NavType.LongType })
            ) { entry ->
                val sessionId = entry.arguments?.getLong("sessionId") ?: 0L
                ChatScreen(
                    app = app,
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                    onOpenMemories = { navController.navigate(Routes.memory(sessionId)) }
                )
            }
            composable(
                Routes.CHARACTER_EDIT,
                arguments = listOf(navArgument("characterId") {
                    type = NavType.LongType
                    defaultValue = 0L
                })
            ) { entry ->
                CharacterEditScreen(
                    app = app,
                    characterId = entry.arguments?.getLong("characterId") ?: 0L,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                Routes.MEMORY,
                arguments = listOf(navArgument("sessionId") { type = NavType.LongType })
            ) { entry ->
                MemoryScreen(
                    app = app,
                    sessionId = entry.arguments?.getLong("sessionId") ?: 0L,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
