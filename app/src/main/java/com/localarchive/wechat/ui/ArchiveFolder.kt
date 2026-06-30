package com.localarchive.wechat.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.localarchive.wechat.data.repository.ArchiveRepository
import kotlinx.coroutines.launch

/** State + action for the one-time SAF archive-folder grant. */
data class ArchiveFolderState(val hasFolder: Boolean, val pick: () -> Unit)

/**
 * Wires the system folder picker (ACTION_OPEN_DOCUMENT_TREE): on grant it takes
 * a persistable read/write permission and stores the tree URI as the single
 * archive location. Returns whether a folder is set + a launch action.
 */
@Composable
fun rememberArchiveFolder(repository: ArchiveRepository): ArchiveFolderState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Assume granted while loading so the banner / auto-prompt doesn't flash.
    val hasFolder by produceState(initialValue = true, repository) {
        value = repository.hasArchiveFolder()
        repository.observeArchiveFolder().collect { value = it != null }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            scope.launch { repository.setArchiveFolder(uri) }
        }
    }
    return ArchiveFolderState(hasFolder = hasFolder, pick = { launcher.launch(null) })
}
