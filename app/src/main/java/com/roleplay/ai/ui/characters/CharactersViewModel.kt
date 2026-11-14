package com.roleplay.ai.ui.characters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.roleplay.ai.RoleplayApp
import com.roleplay.ai.data.db.CharacterEntity
import com.roleplay.ai.data.prefs.SettingsStore
import com.roleplay.ai.data.repo.CharacterDraft
import com.roleplay.ai.data.repo.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CharactersViewModel(
    private val repo: ChatRepository,
    private val store: SettingsStore
) : ViewModel() {

    private val _generating = MutableStateFlow(false)
    /** True while "Generate with AI" is waiting for the model. */
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    /** Expands a short idea into a full character card. Exactly one callback is invoked. */
    fun generateCharacter(idea: String, onDraft: (CharacterDraft) -> Unit, onError: (String) -> Unit) {
        if (_generating.value || idea.isBlank()) return
        _generating.value = true
        viewModelScope.launch {
            try {
                onDraft(repo.generateCharacter(idea, store.settings.first()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: "Request failed")
            } finally {
                _generating.value = false
            }
        }
    }


    val characters: StateFlow<List<CharacterEntity>> = repo.observeCharacters()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadCharacter(id: Long, onLoaded: (CharacterEntity?) -> Unit) {
        viewModelScope.launch { onLoaded(repo.getCharacter(id)) }
    }

    fun save(character: CharacterEntity, onSaved: (Long) -> Unit = {}) {
        viewModelScope.launch { onSaved(repo.saveCharacter(character)) }
    }

    fun delete(character: CharacterEntity) {
        viewModelScope.launch { repo.deleteCharacter(character) }
    }

    fun startChat(character: CharacterEntity, onReady: (Long) -> Unit) {
        viewModelScope.launch { onReady(repo.createSession(character)) }
    }

    companion object {
        fun factory(app: RoleplayApp) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CharactersViewModel(app.repository, app.settingsStore) as T
        }
    }
}
