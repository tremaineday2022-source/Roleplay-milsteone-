package com.roleplay.ai.ui.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.roleplay.ai.RoleplayApp
import com.roleplay.ai.data.db.CharacterEntity
import com.roleplay.ai.data.db.SessionEntity
import com.roleplay.ai.data.db.isWriting
import com.roleplay.ai.data.prefs.SettingsStore
import com.roleplay.ai.data.repo.ChatRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatListItem(
    val session: SessionEntity,
    val character: CharacterEntity?,
    /** One line of the latest message, or of the document. */
    val preview: String = ""
)

class ChatListViewModel(private val repo: ChatRepository, store: SettingsStore) : ViewModel() {

    val items: StateFlow<List<ChatListItem>> =
        combine(repo.observeSessions(), repo.observeCharacters(), repo.observeLastMessages()) { sessions, characters, last ->
            val byId = characters.associateBy { it.id }
            val lastBySession = last.associate { it.sessionId to it.content }
            sessions.map { session ->
                val preview = if (session.isWriting) previewOf(session.document, fromEnd = true)
                else previewOf(lastBySession[session.id].orEmpty())
                ChatListItem(session, byId[session.characterId], preview)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** True while no API key is saved for a remote provider, so the list can point to Settings. */
    val needsSetup: StateFlow<Boolean> = store.settings
        .map { repo.keyError(it) != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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

    fun setPinned(sessionId: Long, pinned: Boolean) {
        viewModelScope.launch { repo.setPinned(sessionId, pinned) }
    }

    fun rename(sessionId: Long, title: String) {
        val trimmed = title.trim().ifEmpty { return }
        viewModelScope.launch { repo.updateSessionTitle(sessionId, trimmed) }
    }

    companion object {
        private const val PREVIEW_LENGTH = 120

        /**
         * One line of [text] without the * marks used for bold and actions: the start of a message, or the end of a
         * document (where the latest writing is) when [fromEnd] is set.
         */
        fun previewOf(text: String, fromEnd: Boolean = false): String {
            val window = if (fromEnd) text.takeLast(PREVIEW_LENGTH * 4) else text.take(PREVIEW_LENGTH * 4)
            val line = window.replace("*", "").split('\n', '\r', '\t', ' ').filter { it.isNotEmpty() }.joinToString(" ")
            return when {
                line.length <= PREVIEW_LENGTH -> line
                fromEnd -> "…" + line.takeLast(PREVIEW_LENGTH)
                else -> line.take(PREVIEW_LENGTH) + "…"
            }
        }

        fun factory(app: RoleplayApp) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatListViewModel(app.repository, app.settingsStore) as T
        }
    }
}
