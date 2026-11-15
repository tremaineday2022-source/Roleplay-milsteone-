package com.roleplay.ai.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val temperature: Float = 0.9f,
    val maxTokens: Int = 1024,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val globalInstructions: String = "",
    val autoSummary: Boolean = true,
    val summaryThreshold: Int = 24,
    val contextMessages: Int = 30,
    val streaming: Boolean = true,
    /** After each reply, ask the model for new durable facts and save them as memories. */
    val autoMemory: Boolean = true,
    /** File name (in the app image folder) of the custom chat background; blank for none. */
    val chatBackground: String = "",
    /** Writing mode sends at most this many of the latest words; 0 sends the whole document. */
    val writingContextWords: Int = 0,
    /** Size of message and document text, as a multiple of the normal size. */
    val textScale: Float = 1f
) {
    companion object {
        const val MIN_TEXT_SCALE = 0.8f
        const val MAX_TEXT_SCALE = 1.6f
    }
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL = stringPreferencesKey("model")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val THEME_MODE = intPreferencesKey("theme_mode")
        val GLOBAL_INSTRUCTIONS = stringPreferencesKey("global_instructions")
        val AUTO_SUMMARY = booleanPreferencesKey("auto_summary")
        val SUMMARY_THRESHOLD = intPreferencesKey("summary_threshold")
        val CONTEXT_MESSAGES = intPreferencesKey("context_messages")
        val STREAMING = booleanPreferencesKey("streaming")
        val AUTO_MEMORY = booleanPreferencesKey("auto_memory")
        val CHAT_BACKGROUND = stringPreferencesKey("chat_background")
        val WRITING_CONTEXT_WORDS = intPreferencesKey("writing_context_words")
        val TEXT_SCALE = floatPreferencesKey("text_scale")
    }

    private fun read(p: Preferences) = AppSettings(
        baseUrl = p[Keys.BASE_URL] ?: AppSettings().baseUrl,
        apiKey = p[Keys.API_KEY] ?: "",
        model = p[Keys.MODEL] ?: AppSettings().model,
        temperature = p[Keys.TEMPERATURE] ?: AppSettings().temperature,
        maxTokens = p[Keys.MAX_TOKENS] ?: AppSettings().maxTokens,
        themeMode = ThemeMode.entries.getOrElse(p[Keys.THEME_MODE] ?: 0) { ThemeMode.SYSTEM },
        globalInstructions = p[Keys.GLOBAL_INSTRUCTIONS] ?: "",
        autoSummary = p[Keys.AUTO_SUMMARY] ?: true,
        summaryThreshold = p[Keys.SUMMARY_THRESHOLD] ?: 24,
        contextMessages = p[Keys.CONTEXT_MESSAGES] ?: 30,
        streaming = p[Keys.STREAMING] ?: true,
        autoMemory = p[Keys.AUTO_MEMORY] ?: true,
        chatBackground = p[Keys.CHAT_BACKGROUND] ?: "",
        writingContextWords = p[Keys.WRITING_CONTEXT_WORDS] ?: 0,
        textScale = (p[Keys.TEXT_SCALE] ?: 1f).coerceIn(AppSettings.MIN_TEXT_SCALE, AppSettings.MAX_TEXT_SCALE)
    )

    val settings: Flow<AppSettings> = context.dataStore.data.map { read(it) }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val current = read(p)
            val next = transform(current)
            p[Keys.BASE_URL] = next.baseUrl.trim().trimEnd('/')
            p[Keys.API_KEY] = next.apiKey.trim()
            p[Keys.MODEL] = next.model.trim()
            p[Keys.TEMPERATURE] = next.temperature
            p[Keys.MAX_TOKENS] = next.maxTokens
            p[Keys.THEME_MODE] = next.themeMode.ordinal
            p[Keys.GLOBAL_INSTRUCTIONS] = next.globalInstructions
            p[Keys.AUTO_SUMMARY] = next.autoSummary
            p[Keys.SUMMARY_THRESHOLD] = next.summaryThreshold
            p[Keys.CONTEXT_MESSAGES] = next.contextMessages
            p[Keys.STREAMING] = next.streaming
            p[Keys.AUTO_MEMORY] = next.autoMemory
            p[Keys.CHAT_BACKGROUND] = next.chatBackground
            p[Keys.WRITING_CONTEXT_WORDS] = next.writingContextWords.coerceAtLeast(0)
            p[Keys.TEXT_SCALE] = next.textScale.coerceIn(AppSettings.MIN_TEXT_SCALE, AppSettings.MAX_TEXT_SCALE)
        }
    }
}
