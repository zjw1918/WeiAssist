package com.lanqi.weiassist.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.lanqi.weiassist.AppViewModel
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.FileTools.Level
import com.lanqi.weiassist.agent.StorageAccess

/** Settings → File access: what the file tools may reach, and the storage permission the last two choices need. */
@Composable
fun FileAccessSection(vm: AppViewModel) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(StorageAccess.granted(ctx)) }
    // Back from Android's "All files access" screen: look again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = StorageAccess.granted(ctx) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = StorageAccess.granted(ctx) }
    fun allow() {
        if (StorageAccess.usesSystemScreen) runCatching { ctx.startActivity(StorageAccess.settingsIntent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        else ask.launch(StorageAccess.legacyPermissions)
    }
    fun pick(l: Level) {
        vm.fileAccess(l)
        if (l >= Level.StorageRead && !StorageAccess.granted(ctx)) allow()
    }

    SectionHeader(stringResource(R.string.file_access))
    Group {
        val choices = listOf(
            Level.Off to (R.string.file_access_off to R.string.file_access_off_hint),
            Level.Session to (R.string.file_access_session to R.string.file_access_session_hint),
            Level.StorageRead to (R.string.file_access_storage_read to R.string.file_access_storage_read_hint),
            Level.StorageWrite to (R.string.file_access_storage_write to R.string.file_access_storage_write_hint),
        )
        choices.forEachIndexed { i, (level, text) ->
            if (i > 0) GroupDivider()
            GroupRow(
                title = stringResource(text.first), description = stringResource(text.second),
                onClick = { pick(level) },
                trailing = { RadioButton(selected = vm.fileAccess == level, onClick = null) },
            )
        }
        if (vm.fileAccess >= Level.StorageRead && !granted) {
            GroupDivider()
            GroupRow(
                title = stringResource(R.string.file_access_grant), description = stringResource(R.string.file_access_grant_hint),
                descriptionColor = MaterialTheme.colorScheme.error,
                onClick = ::allow,
                trailing = { AppIcon(R.drawable.ic_open_in_new, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
    }
}
