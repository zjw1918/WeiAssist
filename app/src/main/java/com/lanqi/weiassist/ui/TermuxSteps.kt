package com.lanqi.weiassist.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.Termux
import kotlinx.coroutines.launch

/** A setup step's state, as its icon shows it. */
enum class StepState { Pending, Running, Done, Failed }

/** What the setup screens know about Termux on this phone, without asking Termux itself (see [TermuxSteps]). */
class TermuxLocal(
    val installed: Boolean, val runnable: Boolean, val permitted: Boolean, val unrestricted: Boolean,
    /** Termux may read the phone's shared storage (its own storage permission; termux-setup-storage asks for it). */
    val storage: Boolean = false,
) {
    /** Commands can be sent (whether Termux accepts them is another matter). */
    val sendable get() = runnable && permitted
}

/**
 * Termux's state on this phone, read again each time the screen comes back (from installing Termux, the permission
 * screen, Termux's app settings, or Termux), and when [TermuxView.refresh] is called. [TermuxView.seen] counts the reads.
 */
class TermuxView(val seen: Int, val local: TermuxLocal, val refresh: () -> Unit)

@Composable
fun rememberTermuxView(termux: Termux): TermuxView {
    val ctx = LocalContext.current
    var seen by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { seen++; onPauseOrDispose { } }
    val local = remember(seen) {
        val installed = termux.installed()
        TermuxLocal(
            installed = installed, runnable = installed && termux.runnable(), permitted = termux.permitted(),
            unrestricted = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(Termux.PACKAGE),
            storage = installed && ctx.packageManager.checkPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE, Termux.PACKAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    return TermuxView(seen, local) { seen++ }
}

/**
 * The Termux part of a setup: the right build, the permission, no battery restrictions, and Termux accepting this
 * app's commands. [problem]: why Termux didn't run a command (null: it did, or not asked yet); [asking]: a command is
 * on its way. [onPermission] re-reads the state after the permission dialog; [onRecheck] asks Termux again.
 */
@Composable
fun TermuxSteps(termux: Termux, local: TermuxLocal, problem: String?, asking: Boolean, onPermission: () -> Unit, onRecheck: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun open(intent: Intent) = runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    // Once denied, Android may not ask again: then the button opens this app's permission settings.
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> denied = !granted; onPermission() }

    SectionHeader(stringResource(R.string.termux_section))
    Group {
        Step(
            stringResource(R.string.termux_step_app),
            if (local.runnable) StepState.Done else if (local.installed) StepState.Failed else StepState.Pending,
            detail = when {
                !local.installed -> stringResource(R.string.termux_missing)
                !local.runnable -> stringResource(R.string.termux_play_build)
                else -> null
            },
        ) {
            if (!local.runnable) {
                FilledTonalButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse(TERMUX_DOWNLOAD))) }) { Text(stringResource(R.string.termux_download)) }
                // GitHub is slow or blocked in some places (mainland China): F-Droid's build from a mirror there.
                var resolving by remember { mutableStateOf(false) }
                TextButton(enabled = !resolving, onClick = {
                    resolving = true
                    scope.launch { open(Intent(Intent.ACTION_VIEW, Uri.parse(termux.mirrorApkUrl()))); resolving = false }
                }) { Text(stringResource(R.string.termux_download_mirror)) }
                Text(stringResource(R.string.termux_download_mirror_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        GroupDivider()
        Step(stringResource(R.string.termux_step_permission), if (local.permitted) StepState.Done else StepState.Pending) {
            if (local.runnable && !local.permitted) FilledTonalButton(onClick = {
                if (denied) open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                else ask.launch(Termux.PERMISSION)
            }) { Text(stringResource(R.string.termux_allow)) }
        }
        GroupDivider()
        // Not needed to run a command, but without it some phones (HyperOS, ColorOS) freeze Termux in the background.
        Step(
            stringResource(R.string.termux_step_battery),
            if (local.unrestricted) StepState.Done else if (local.installed) StepState.Failed else StepState.Pending,
            detail = if (local.installed && !local.unrestricted) stringResource(R.string.termux_step_battery_hint) else null,
        ) {
            if (local.installed && !local.unrestricted) FilledTonalButton(onClick = {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", Termux.PACKAGE, null)))
            }) { Text(stringResource(R.string.termux_app_settings)) }
        }
        GroupDivider()
        val failed = local.sendable && problem != null && !asking
        Step(
            stringResource(R.string.termux_step_commands),
            when {
                !local.sendable -> StepState.Pending
                asking -> StepState.Running
                problem != null -> StepState.Failed
                else -> StepState.Done
            },
            detail = if (failed) stringResource(R.string.termux_step_commands_hint) else null,
        ) {
            if (failed) {
                var copied by remember { mutableStateOf(false) }
                SelectionContainer { Text(ALLOW_COMMAND, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                Text(problem!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Termux", ALLOW_COMMAND))
                        copied = true
                    }) { Text(stringResource(if (copied) R.string.termux_copied else R.string.termux_copy_command)) }
                    TextButton(onClick = { ctx.packageManager.getLaunchIntentForPackage(Termux.PACKAGE)?.let(::open) }) {
                        Text(stringResource(R.string.termux_open))
                    }
                    TextButton(onClick = onRecheck) { Text(stringResource(R.string.termux_check)) }
                }
            }
        }
    }
}

/** One step: what it is, whether it's done, and what to do about it ([actions]). */
@Composable
fun Step(title: String, state: StepState, detail: String? = null, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 2.dp).size(22.dp), contentAlignment = Alignment.Center) {
            when (state) {
                StepState.Done -> AppIcon(R.drawable.ic_check_circle, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                StepState.Failed -> AppIcon(R.drawable.ic_cancel, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.error)
                StepState.Running -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                StepState.Pending -> Box(Modifier.size(18.dp).border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (state == StepState.Pending) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            actions()
        }
    }
}

/** A command's output behind "Show details": its last [lines] lines, scrollable sideways. */
@Composable
fun DetailsLog(text: String, lines: Int = 60) {
    if (text.isBlank()) return
    var show by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { show = !show }) { Text(stringResource(if (show) R.string.hide_details else R.string.show_details)) }
    if (show) Group {
        SelectionContainer {
            Text(
                text.lines().takeLast(lines).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(GroupContentPadding),
            )
        }
    }
}

private const val TERMUX_DOWNLOAD = "https://github.com/termux/termux-app/releases/latest"
/** Lets other apps (this one) run commands in Termux. */
private const val ALLOW_COMMAND = "mkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings"

/**
 * Optional: Termux sees the phone's shared storage (Downloads, photos), so commands can work on the user's files. Not
 * needed for the app's own file transfers. An app installed from a downloaded file is under Android's "restricted
 * settings" first, which can block the permission until the user allows them in its app info.
 */
@Composable
fun StorageStep(local: TermuxLocal) {
    val ctx = LocalContext.current
    fun open(intent: Intent) = runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    Step(
        stringResource(R.string.termux_step_storage),
        if (local.storage) StepState.Done else StepState.Pending,
        detail = if (local.installed && !local.storage) stringResource(R.string.termux_step_storage_hint) else null,
    ) {
        if (local.installed && !local.storage) {
            var copied by remember { mutableStateOf(false) }
            SelectionContainer { Text(STORAGE_COMMAND, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = {
                    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", Termux.PACKAGE, null)))
                }) { Text(stringResource(R.string.termux_app_info)) }
                TextButton(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Termux", STORAGE_COMMAND))
                    copied = true
                    ctx.packageManager.getLaunchIntentForPackage(Termux.PACKAGE)?.let(::open)
                }) { Text(stringResource(if (copied) R.string.termux_copied else R.string.termux_copy_open)) }
            }
        }
    }
}

private const val STORAGE_COMMAND = "termux-setup-storage"
