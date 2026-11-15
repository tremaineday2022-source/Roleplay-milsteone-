package com.roleplay.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roleplay.ai.ui.AppViewModel
import com.roleplay.ai.ui.RoleplayNav
import com.roleplay.ai.ui.common.LocalReadingScale
import com.roleplay.ai.ui.theme.RoleplayTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels { AppViewModel.factory(application as RoleplayApp) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            RoleplayTheme(themeMode = settings.themeMode) {
                CompositionLocalProvider(LocalReadingScale provides settings.textScale) {
                    RoleplayNav(app = application as RoleplayApp)
                }
            }
        }
    }
}
