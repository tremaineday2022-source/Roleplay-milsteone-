package com.roleplay.ai.ui.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.roleplay.ai.RoleplayApp
import com.roleplay.ai.data.db.CharacterEntity
import com.roleplay.ai.data.db.SessionEntity
import com.roleplay.ai.data.repo.ChatRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatListItem(
    val session: SessionEntity,
    val character: CharacterEntity?
)

class ChatListViewModel(private val repo: ChatRepository) : ViewModel() {

    val items: StateFlow<List<ChatListItem>> =
        combine(repo.observeSessions(), repo.observeCharacters()) { sessions, characters ->
            val byId = characters.associateBy { it.id }
            sessions.map { ChatListItem(it, byId[it.characterId]) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** One character starts a normal chat; several start a group chat. */
    fun startChat(characters: List<CharacterEntity>, onReady: (Long) -> Unit) {
        if (characters.isEmpty()) return
        viewModelScope.launch {
            onReady(repo.createSession(characters))
        }
    }

    fun startWriting(onReady: (Long) -> Unit) {
        viewModelScope.launch {
            onReady(repo.createWritingSession())
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch { repo.deleteSession(sessionId) }
    }

    companion object {
        fun factory(app: RoleplayApp) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatListViewModel(app.repository) as T
        }
    }
}
