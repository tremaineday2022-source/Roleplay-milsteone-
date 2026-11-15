package com.roleplay.ai.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.roleplay.ai.RoleplayApp
import com.roleplay.ai.data.api.Usage
import com.roleplay.ai.data.db.CharacterEntity
import com.roleplay.ai.data.db.JsonList
import com.roleplay.ai.data.db.MemoryEntity
import com.roleplay.ai.data.db.MessageEntity
import com.roleplay.ai.data.db.SessionEntity
import com.roleplay.ai.data.prefs.AppSettings
import com.roleplay.ai.data.prefs.SettingsStore
import com.roleplay.ai.data.repo.ChatRepository
import com.roleplay.ai.data.repo.ReplyKind
import com.roleplay.ai.domain.TokenEstimate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tokens of the last request: an estimate until the provider reports the real counts. */
data class TokenUsage(
    val sent: Int,
    val received: Int?,
    val exact: Boolean,
    /** The model that answered, when the character overrides the app default. */
    val model: String? = null
)

/** A reply being generated: what it is for, which message it targets, and the text so far. */
data class Generation(val kind: ReplyKind, val targetId: Long?, val text: String)

class ChatViewModel(
    private val repo: ChatRepository,
    private val store: SettingsStore,
    private val sessionId: Long
) : ViewModel() {

    val messages: StateFlow<List<MessageEntity>> = repo.observeMessages(sessionId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val session: StateFlow<SessionEntity?> = repo.observeSession(sessionId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val settings: StateFlow<AppSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _characters = MutableStateFlow<List<CharacterEntity>>(emptyList())
    val characters: StateFlow<List<CharacterEntity>> = _characters.asStateFlow()

    private val _generation = MutableStateFlow<Generation?>(null)
    val generation: StateFlow<Generation?> = _generation.asStateFlow()

    private val _pendingImages = MutableStateFlow<List<String>>(emptyList())
    /** Photos attached to the message being written, already copied into app storage. */
    val pendingImages: StateFlow<List<String>> = _pendingImages.asStateFlow()

    private val _tokens = MutableStateFlow<TokenUsage?>(null)
    val tokens: StateFlow<TokenUsage?> = _tokens.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var streamJob: Job? = null

    init {
        viewModelScope.launch {
            val loaded = repo.getSession(sessionId) ?: return@launch
            _characters.value = repo.charactersFor(loaded)
        }
    }

    fun imageFile(name: String) = repo.images.file(name)

    fun attachImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val names = repo.importImages(uris)
            if (names.size < uris.size) _error.value = "Some images could not be read."
            _pendingImages.update { it + names }
        }
    }

    fun removePendingImage(name: String) {
        _pendingImages.update { it - name }
        repo.images.delete(listOf(name))
    }

    fun send(text: String) {
        val trimmed = text.trim()
        val attached = _pendingImages.value
        if ((trimmed.isEmpty() && attached.isEmpty()) || _generation.value != null) return
        _pendingImages.value = emptyList()
        viewModelScope.launch {
            repo.insertMessage(
                MessageEntity(
                    sessionId = sessionId,
                    role = "user",
                    content = trimmed,
                    images = JsonList.encode(attached)
                )
            )
            repo.touchSession(sessionId)
            generate(ReplyKind.NEW)
        }
    }

    /** Asks for a reply to the conversation as it stands (e.g. after a failed request). */
    fun generateReply() = generate(ReplyKind.NEW)

    /** Generates another variant of the last reply; earlier variants stay available to swipe to. */
    fun regenerate() = generate(ReplyKind.REGENERATE)

    /** Extends the last reply in place. */
    fun continueReply() = generate(ReplyKind.CONTINUE)

    fun swipe(message: MessageEntity, delta: Int) {
        if (_generation.value != null) return
        viewModelScope.launch { repo.swipeVariant(message.id, delta) }
    }

    fun stopGeneration() {
        streamJob?.cancel()
    }

    fun dismissError() { _error.value = null }

    fun updateMessage(message: MessageEntity, newContent: String) {
        viewModelScope.launch { repo.editMessage(message, newContent) }
    }

    fun deleteMessage(message: MessageEntity) {
        viewModelScope.launch { repo.deleteMessage(message) }
    }

    fun rename(title: String) {
        val trimmed = title.trim().ifEmpty { return }
        viewModelScope.launch { repo.updateSessionTitle(sessionId, trimmed) }
    }

    /** Copies the chat up to [message] into a new chat and hands back its id. */
    fun branchFrom(message: MessageEntity, onReady: (Long) -> Unit) {
        viewModelScope.launch {
            val branchId = repo.branchSession(sessionId, message.id)
            if (branchId != null) onReady(branchId) else _error.value = "That message could not be branched."
        }
    }

    fun saveNote(note: String) {
        viewModelScope.launch { repo.updateNote(sessionId, note) }
    }

    fun pinMemory(content: String) {
        viewModelScope.launch {
            repo.saveMemory(MemoryEntity(sessionId = sessionId, content = content.trim()))
        }
    }

    private fun generate(kind: ReplyKind) {
        if (_generation.value != null) return
        // Claim the slot synchronously so a double tap cannot start two requests.
        _generation.value = Generation(kind, null, "")
        streamJob = viewModelScope.launch {
            val builder = StringBuilder()
            var targetId: Long? = null
            var finished = false
            var current: AppSettings? = null
            try {
                val loadedSettings = store.settings.first()
                current = loadedSettings
                repo.keyError(loadedSettings)?.let {
                    _error.value = it
                    return@launch
                }
                val session = repo.getSession(sessionId) ?: return@launch
                // Read from the database, not the UI state, so a just-sent message is never missed.
                val all = repo.getMessages(sessionId)
                val last = all.lastOrNull()
                val history = when (kind) {
                    ReplyKind.NEW -> all
                    ReplyKind.REGENERATE -> {
                        if (last?.role != "assistant" || all.size < 2) return@launch
                        targetId = last.id
                        all.dropLast(1)
                    }
                    ReplyKind.CONTINUE -> {
                        if (last?.role != "assistant") return@launch
                        targetId = last.id
                        all
                    }
                }
                if (history.isEmpty()) return@launch
                _generation.value = Generation(kind, targetId, "")
                _error.value = null
                val request = repo.chatRequest(session, history, loadedSettings, continueLast = kind == ReplyKind.CONTINUE)
                val config = repo.apiConfigFor(loadedSettings, repo.charactersFor(session))
                val overriddenModel = config.model.takeIf { it != loadedSettings.model }
                _tokens.value = TokenUsage(TokenEstimate.request(request), null, exact = false, model = overriddenModel)
                val onUsage: (Usage) -> Unit = { usage ->
                    _tokens.value = TokenUsage(usage.promptTokens, usage.completionTokens, exact = true, model = overriddenModel)
                }
                repo.stream(config, request, onUsage).collect { delta ->
                    builder.append(delta)
                    _generation.value = Generation(kind, targetId, builder.toString())
                }
                finished = true
                if (builder.isBlank()) _error.value = "The model returned an empty response."
            } catch (e: CancellationException) {
                // Stopped by the user (or the screen closed): whatever arrived so far is kept below.
            } catch (e: Exception) {
                _error.value = e.message ?: "Request failed"
            } finally {
                withContext(NonCancellable) {
                    val text = builder.toString()
                    if (text.isNotBlank()) repo.commitReply(sessionId, kind, targetId, text)
                    _generation.value = null
                    val used = current
                    if (finished && text.isNotBlank() && used != null) {
                        // Alternative takes of the same turn are not mined for memories again.
                        repo.afterReply(sessionId, used, extractMemories = kind != ReplyKind.REGENERATE)
                    }
                }
            }
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
