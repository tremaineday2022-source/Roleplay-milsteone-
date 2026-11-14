package com.roleplay.ai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.roleplay.ai.RoleplayApp
import com.roleplay.ai.data.db.CharacterEntity
import com.roleplay.ai.data.db.MemoryEntity
import com.roleplay.ai.data.db.MessageEntity
import com.roleplay.ai.data.prefs.AppSettings
import com.roleplay.ai.data.repo.ChatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(
    private val repo: ChatRepository,
    private val store: com.roleplay.ai.data.prefs.SettingsStore,
    private val sessionId: Long
) : ViewModel() {

    val messages: StateFlow<List<MessageEntity>> = repo.observeMessages(sessionId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val memories: StateFlow<List<MemoryEntity>> = repo.observeMemories(sessionId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val settings: StateFlow<AppSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _character = MutableStateFlow<CharacterEntity?>(null)
    val character: StateFlow<CharacterEntity?> = _character.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var streamJob: Job? = null

    init {
        viewModelScope.launch {
            val session = repo.getSession(sessionId) ?: return@launch
            _character.value = repo.getCharacter(session.characterId)
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _generating.value) return
        viewModelScope.launch {
            repo.insertMessage(MessageEntity(sessionId = sessionId, role = "user", content = trimmed))
            repo.touchSession(sessionId)
            generateReply()
        }
    }

    fun regenerate() {
        if (_generating.value) return
        val lastAssistant = messages.value.lastOrNull { it.role == "assistant" } ?: return
        viewModelScope.launch {
            repo.deleteMessage(lastAssistant)
            generateReply()
        }
    }

    fun stopGeneration() {
        streamJob?.cancel()
        val partial = _streamingText.value
        if (partial.isNotBlank()) {
            viewModelScope.launch {
                repo.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = partial))
                repo.touchSession(sessionId)
            }
        }
        _streamingText.value = ""
        _generating.value = false
    }

    fun dismissError() { _error.value = null }

    fun updateMessage(message: MessageEntity, newContent: String) {
        viewModelScope.launch { repo.updateMessage(message.copy(content = newContent)) }
    }

    fun deleteMessage(message: MessageEntity) {
        viewModelScope.launch { repo.deleteMessage(message) }
    }

    fun pinMemory(content: String) {
        viewModelScope.launch {
            repo.saveMemory(MemoryEntity(sessionId = sessionId, content = content.trim()))
        }
    }

    fun deleteMemory(memory: MemoryEntity) {
        viewModelScope.launch { repo.deleteMemory(memory) }
    }

    private suspend fun generateReply() {
        val character = _character.value ?: return
        val current = settings.value
        val session = repo.getSession(sessionId) ?: return
        if (current.apiKey.isBlank() && !current.baseUrl.contains("localhost") &&
            !current.baseUrl.contains("127.0.0.1")
        ) {
            _error.value = "No API key configured. Add one in Settings."
            return
        }

        _generating.value = true
        _streamingText.value = ""
        _error.value = null
        streamJob = viewModelScope.launch {
            try {
                val systemPrompt = repo.buildPrompt(character, session, memories.value, current)
                val history = repo.historyMessages(messages.value, systemPrompt, current)
                val builder = StringBuilder()
                repo.stream(repo.toApiConfig(current), history).collect { delta ->
                    builder.append(delta)
                    _streamingText.value = builder.toString()
                }
                val full = builder.toString()
                if (full.isNotBlank()) {
                    repo.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = full))
                    repo.touchSession(sessionId)
                } else {
                    _error.value = "The model returned an empty response."
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Request failed"
            } finally {
                _streamingText.value = ""
                _generating.value = false
            }
        }
        streamJob?.invokeOnCompletion {
            viewModelScope.launch { repo.maybeSummarize(session, current) }
        }
    }

    companion object {
        fun factory(app: RoleplayApp, sessionId: Long) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(app.repository, app.settingsStore, sessionId) as T
        }
    }
}
