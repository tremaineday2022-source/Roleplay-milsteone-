package com.roleplay.ai.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.roleplay.ai.RoleplayApp
import com.roleplay.ai.data.api.OpenAiException
import com.roleplay.ai.data.prefs.AppSettings
import com.roleplay.ai.data.prefs.SettingsStore
import com.roleplay.ai.data.repo.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Result of the "Test connection" button. */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Testing : ConnectionState
    data class Connected(val model: String) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

class AppViewModel(
    private val store: SettingsStore,
    private val repo: ChatRepository
) : ViewModel() {

    /** The saved settings, as other screens see them. */
    val settings: StateFlow<AppSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /**
     * The settings as shown on the Settings screen. Text fields must read their own edits back
     * immediately: routing every keystroke through the saved copy (which is written to disk in
     * the background) made letters arrive late and scrambled what was typed.
     */
    var draft by mutableStateOf(AppSettings())
        private set

    /** False until the saved settings have been read; the fields stay disabled until then. */
    var loaded by mutableStateOf(false)
        private set

    var connection by mutableStateOf<ConnectionState>(ConnectionState.Idle)
        private set

    init {
        viewModelScope.launch {
            draft = store.settings.first()
            loaded = true
        }
    }

    /** Shows the change at once and saves it in the background. Saves are applied in order. */
    fun update(transform: (AppSettings) -> AppSettings) {
        draft = transform(draft)
        connection = ConnectionState.Idle
        viewModelScope.launch { store.update(transform) }
    }

    /** Sends one tiny request with the settings on screen and reports whether it worked. */
    fun testConnection() {
        if (connection == ConnectionState.Testing) return
        val current = draft.copy(
            baseUrl = draft.baseUrl.trim().trimEnd('/'),
            apiKey = draft.apiKey.trim(),
            model = draft.model.trim()
        )
        connection = ConnectionState.Testing
        viewModelScope.launch {
            connection = try {
                repo.testConnection(current)
                ConnectionState.Connected(current.model)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ConnectionState.Failed(explain(e))
            }
        }
    }

    private fun explain(e: Exception): String {
        val detail = e.message ?: "Unknown error"
        val hint = when ((e as? OpenAiException)?.code) {
            401, 403 -> "The API key was rejected. Check that you pasted the whole key."
            402 -> "The provider says this account has no credit."
            404 -> "The base URL or model name was not found."
            429 -> "The provider is rate limiting this key. Wait a moment and try again."
            null -> if (e is OpenAiException) null else "Could not reach the server. Check the base URL and your internet connection."
            else -> null
        }
        return if (hint == null) detail else "$hint ($detail)"
    }

    companion object {
        fun factory(app: RoleplayApp) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(app.settingsStore, app.repository) as T
        }
    }
}
