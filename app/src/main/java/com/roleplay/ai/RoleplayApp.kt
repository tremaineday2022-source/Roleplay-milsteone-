package com.roleplay.ai

import android.app.Application
import com.roleplay.ai.data.db.AppDatabase
import com.roleplay.ai.data.prefs.SettingsStore
import com.roleplay.ai.data.repo.ChatRepository

class RoleplayApp : Application() {

    lateinit var repository: ChatRepository
        private set
    lateinit var settingsStore: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        repository = ChatRepository(AppDatabase.get(this))
    }
}
