package com.roleplay.ai.ui.writing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.roleplay.ai.RoleplayApp
import com.roleplay.ai.data.prefs.AppSettings
import com.roleplay.ai.data.prefs.SettingsStore
import com.roleplay.ai.data.repo.ChatRepository
import com.roleplay.ai.domain.TextJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class WritingViewModel(
    private val repo: ChatRepository,
    private val store: SettingsStore,
    private val sessionId: Long
) : ViewModel() {

    /** The document being edited. Compose state, so typing never round-trips through a flow. */
    var document by mutableStateOf(TextFieldValue(""))
        private set

    var title by mutableStateOf("")
        private set

    var note by mutableStateOf("")
        private set

    /** Saved settings, for the "what gets sent" line under the document. */
    val settings: StateFlow<AppSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    var loaded by mutableStateOf(false)
        private set

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _saved = MutableStateFlow(true)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var streamJob: Job? = null
    private var lastSaved = ""

    init {
        viewModelScope.launch {
            val session = repo.getSession(sessionId)
            if (session != null) {
                title = session.title
                note = session.note
                lastSaved = session.document
                document = TextFieldValue(session.document, TextRange(session.document.length))
            }
            loaded = true
            // Autosave shortly after the text stops changing.
            snapshotFlow { document.text }
                .distinctUntilChanged()
                .drop(1)
                .debounce(AUTOSAVE_DELAY_MS)
                .collect { save(it) }
        }
    }

    fun onDocumentChange(value: TextFieldValue) {
        if (_generating.value || !loaded) return
        if (value.text != document.text) _saved.value = false
        document = value
    }

    fun saveNote(newNote: String) {
        note = newNote.trim()
        viewModelScope.launch { repo.updateNote(sessionId, note) }
    }

    fun rename(newTitle: String) {
        val trimmed = newTitle.trim().ifEmpty { return }
        title = trimmed
        viewModelScope.launch { repo.updateSessionTitle(sessionId, trimmed) }
    }

    /** Sends the entire document and appends what the model writes next. */
    fun continueWriting() {
        if (_generating.value || !loaded || document.text.isBlank()) return
        _generating.value = true
        streamJob = viewModelScope.launch {
            val base = document.text
            val builder = StringBuilder()
            try {
                val settings = store.settings.first()
                repo.keyError(settings)?.let {
                    _error.value = it
                    return@launch
                }
                _error.value = null
                save(base)
                val request = repo.writingRequest(sessionId, base, settings)
                repo.stream(repo.toApiConfig(settings), request).collect { delta ->
                    builder.append(delta)
                    val text = TextJoin.join(base, builder.toString())
                    document = TextFieldValue(text, TextRange(text.length))
                }
                if (builder.isBlank()) _error.value = "The model returned an empty response."
            } catch (e: CancellationException) {
                // Stopped: the text written so far stays in the document.
            } catch (e: Exception) {
                _error.value = e.message ?: "Request failed"
            } finally {
                _generating.value = false
                if (document.text != lastSaved) {
                    _saved.value = false
                    lastSaved = document.text
                    repo.saveDocumentInBackground(sessionId, document.text).invokeOnCompletion { _saved.value = true }
                }
            }
        }
    }

    fun stop() {
        streamJob?.cancel()
    }

    fun dismissError() { _error.value = null }

    /** Saves what is on screen right now, then runs [then] (used before exporting). */
    fun saveNow(then: () -> Unit) {
        viewModelScope.launch {
            save(document.text)
            then()
        }
    }

    private suspend fun save(text: String) {
        if (text == lastSaved) {
            _saved.value = true
            return
        }
        repo.saveDocument(sessionId, text)
        lastSaved = text
        if (document.text == text) _saved.value = true
    }

    override fun onCleared() {
        // Leaving the screen inside the autosave delay must not lose the last edits.
        if (loaded && document.text != lastSaved) repo.saveDocumentInBackground(sessionId, document.text)
    }

    companion object {
        const val AUTOSAVE_DELAY_MS = 700L

        fun factory(app: RoleplayApp, sessionId: Long) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WritingViewModel(app.repository, app.settingsStore, sessionId) as T
        }
    }
}
