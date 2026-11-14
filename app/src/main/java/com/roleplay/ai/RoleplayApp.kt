package com.roleplay.ai

import android.app.Application
import com.roleplay.ai.data.backup.BackupManager
import com.roleplay.ai.data.db.AppDatabase
import com.roleplay.ai.data.files.ImageStore
import com.roleplay.ai.data.prefs.SettingsStore
import com.roleplay.ai.data.repo.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class RoleplayApp : Application() {

    lateinit var repository: ChatRepository
        private set
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var backupManager: BackupManager
        private set

    override fun onCreate() {
        super.onCreate()
        val database = AppDatabase.get(this)
        val images = ImageStore(this)
        settingsStore = SettingsStore(this)
        repository = ChatRepository(database, images)
        backupManager = BackupManager(database, images)

        // Clear out photos that were attached but never sent, or whose message is gone.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                repository.sweepOrphanImages(keep = setOf(settingsStore.settings.first().chatBackground))
            }
        }
    }
}
