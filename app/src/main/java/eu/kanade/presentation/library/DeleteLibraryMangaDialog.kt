package eu.kanade.presentation.library

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun DeleteLibraryMangaDialog(
    containsLocalManga: Boolean,
    canDeleteRemote: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: (Boolean, Boolean, Boolean) -> Unit,
) {
    var fromLibrary by remember { mutableStateOf(false) }
    var downloadedChapters by remember { mutableStateOf(false) }
    var remoteFolder by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                enabled = fromLibrary || downloadedChapters,
                onClick = {
                    onDismissRequest()
                    onConfirm(fromLibrary, downloadedChapters, remoteFolder && fromLibrary && canDeleteRemote)
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.action_remove))
        },
        text = {
            Column {
                LabeledCheckbox(
                    label = stringResource(MR.strings.manga_from_library),
                    checked = fromLibrary,
                    onCheckedChange = {
                        fromLibrary = it
                        if (!it) remoteFolder = false
                    },
                )
                if (!containsLocalManga) {
                    LabeledCheckbox(
                        label = stringResource(MR.strings.downloaded_chapters),
                        checked = downloadedChapters,
                        onCheckedChange = { downloadedChapters = it },
                    )
                }
                if (canDeleteRemote) {
                    LabeledCheckbox(
                        label = stringResource(MR.strings.remote_sync_delete_folder),
                        checked = remoteFolder,
                        onCheckedChange = {
                            remoteFolder = it
                            if (it) fromLibrary = true
                        },
                    )
                }
            }
        },
    )
}
