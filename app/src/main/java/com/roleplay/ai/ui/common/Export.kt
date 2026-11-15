package com.roleplay.ai.ui.common

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.roleplay.ai.RoleplayApp
import com.roleplay.ai.domain.ExportedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Saving a chat or document as a text file, or sharing it to another app. */
class ExportActions(val saveAsFile: (sessionId: Long) -> Unit, val share: (sessionId: Long) -> Unit)

/** Above this size the text is shared as an attached file; intents cannot carry very long text. */
private const val MAX_INLINE_SHARE_CHARS = 50_000

/**
 * The share intent for [export]: plain text for normal chats (accepted by any app that takes
 * text), or a .txt attachment for very long ones.
 */
fun buildShareIntent(context: Context, export: ExportedText): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, export.title)
    }
    if (export.text.length <= MAX_INLINE_SHARE_CHARS) {
        send.putExtra(Intent.EXTRA_TEXT, export.text)
    } else {
        val dir = File(context.cacheDir, "exports").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val file = File(dir, export.fileName).apply { writeText(export.text) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        send.putExtra(Intent.EXTRA_STREAM, uri)
        send.clipData = ClipData.newRawUri(export.title, uri)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, "Share \"${export.title}\"")
}

@Composable
fun rememberExportActions(app: RoleplayApp, onMessage: (String) -> Unit): ExportActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ExportedText?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val export = pending
        pending = null
        if (uri != null && export != null) scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(export.text.toByteArray()) }
                }.isSuccess
            }
            onMessage(if (saved) "Saved \"${export.fileName}\"." else "The file could not be saved.")
        }
    }

    return remember(app) {
        ExportActions(
            saveAsFile = { sessionId ->
                scope.launch {
                    val export = app.repository.exportText(sessionId)
                    if (export == null) onMessage("Nothing to export.") else {
                        pending = export
                        saveLauncher.launch(export.fileName)
                    }
                }
            },
            share = { sessionId ->
                scope.launch {
                    val export = app.repository.exportText(sessionId)
                    if (export == null) onMessage("Nothing to export.") else {
                        runCatching {
                            val intent = withContext(Dispatchers.IO) { buildShareIntent(context, export) }
                            context.startActivity(intent)
                        }.onFailure { onMessage("No app available to share with.") }
                    }
                }
            }
        )
    }
}
