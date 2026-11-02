package com.lanqi.weiassist.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.Termux
import kotlinx.coroutines.launch

/**
 * Termux in Settings: the agent's bash tool runs there. Shows what is missing (install, the right build,
 * permission) with the button that fixes it, and a check that commands really run.
 */
@Composable
fun TermuxSection(termux: Termux) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Re-read the state when the user comes back (from installing Termux, or from the permission screen).
    var seen by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { seen++; onPauseOrDispose { } }
    val installed = remember(seen) { termux.installed() }
    val runnable = remember(seen) { installed && termux.runnable() }
    val permitted = remember(seen) { termux.permitted() }
    var check by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var checking by remember { mutableStateOf(false) }
    // Once denied, Android may not ask again: then the button opens this app's permission settings.
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> seen++; denied = !granted }
    fun open(intent: Intent) = runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val status = when {
            !installed -> stringResource(R.string.termux_missing)
            !runnable -> stringResource(R.string.termux_play_build)
            !permitted -> stringResource(R.string.termux_no_permission)
            else -> stringResource(R.string.termux_installed)
        }
        Text(status, style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.termux_why), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                !installed || !runnable -> FilledTonalButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD))) }) { Text(stringResource(R.string.termux_download)) }
                !permitted -> FilledTonalButton(onClick = {
                    if (denied) open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                    else ask.launch(Termux.PERMISSION)
                }) { Text(stringResource(R.string.termux_allow)) }
                else -> FilledTonalButton(enabled = !checking, onClick = {
                    checking = true
                    scope.launch { check = termux.check(); checking = false }
                }) { Text(stringResource(if (checking) R.string.termux_checking else R.string.termux_check)) }
            }
            if (installed) TextButton(onClick = {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", Termux.PACKAGE, null)))
            }) { Text(stringResource(R.string.termux_app_settings)) }
        }
        if (installed && runnable) Text(stringResource(R.string.termux_background), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        check?.let { (ok, text) ->
            Text(
                stringResource(if (ok) R.string.termux_ready else R.string.termux_not_ready), style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }
}

private const val DOWNLOAD = "https://github.com/termux/termux-app/releases/latest"
