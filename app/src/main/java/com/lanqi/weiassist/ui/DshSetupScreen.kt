package com.lanqi.weiassist.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanqi.weiassist.AppViewModel
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.DshSetup
import com.lanqi.weiassist.agent.DshSetup.StepState
import com.lanqi.weiassist.agent.Termux
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Sets DeepSeek Harness up from the app: Termux itself (the right build, the permission, commands allowed), then the
 * setup script, run in Termux ([DshSetup]), with each of its steps and its output as it goes. The setup goes on in
 * Termux when the user leaves; coming back shows where it stands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DshSetupScreen(vm: AppViewModel, termux: Termux, setup: DshSetup, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    fun open(intent: Intent) = runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    // Re-read Termux's state when the user comes back (from installing Termux, the permission screen, or Termux).
    var seen by remember { mutableIntStateOf(0) }
    var resumed by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) { seen++; resumed = true; onPauseOrDispose { resumed = false } }
    val installed = remember(seen) { termux.installed() }
    val runnable = remember(seen) { installed && termux.runnable() }
    val permitted = remember(seen) { termux.permitted() }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> seen++; denied = !granted }

    val launching by setup.launching.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<DshSetup.Status?>(null) }
    // While the setup runs (and this screen is in front), follow it.
    LaunchedEffect(seen, resumed, launching, runnable, permitted) {
        while (resumed && runnable && permitted) {
            val s = setup.status()
            status = s
            if (!launching && !s.running) break
            delay(3_000)
        }
    }
    val termuxOk = runnable && permitted
    val answers = termuxOk && status != null && status?.termuxProblem == null
    val running = launching || status?.running == true
    val default by vm.defaultEngine.collectAsStateWithLifecycle()
    var mirrors by rememberSaveable { mutableStateOf(Locale.getDefault().let { it.language == "zh" && it.country != "TW" && it.country != "HK" }) }
    var showLog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dsh_setup_title)) },
                navigationIcon = { IconButton(onClick = onClose) { AppIcon(R.drawable.ic_arrow_back, stringResource(R.string.back)) } },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Text(
                stringResource(R.string.dsh_setup_intro), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )

            SectionHeader(stringResource(R.string.dsh_section_termux))
            Group {
                Step(
                    stringResource(R.string.dsh_step_termux_app),
                    if (runnable) StepState.Done else StepState.Failed.takeIf { installed } ?: StepState.Pending,
                    detail = when {
                        !installed -> stringResource(R.string.termux_missing)
                        !runnable -> stringResource(R.string.termux_play_build)
                        else -> null
                    },
                ) {
                    if (!runnable) FilledTonalButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse(TERMUX_DOWNLOAD))) }) { Text(stringResource(R.string.termux_download)) }
                }
                GroupDivider()
                Step(stringResource(R.string.dsh_step_permission), if (permitted) StepState.Done else StepState.Pending) {
                    if (runnable && !permitted) FilledTonalButton(onClick = {
                        if (denied) open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                        else ask.launch(Termux.PERMISSION)
                    }) { Text(stringResource(R.string.termux_allow)) }
                }
                GroupDivider()
                val problem = status?.termuxProblem
                Step(
                    stringResource(R.string.dsh_step_commands),
                    when {
                        answers -> StepState.Done
                        termuxOk && status == null -> StepState.Running // asking Termux
                        termuxOk && problem != null -> StepState.Failed
                        else -> StepState.Pending
                    },
                    detail = if (termuxOk && problem != null) stringResource(R.string.dsh_step_commands_hint) else null,
                ) {
                    if (termuxOk && problem != null) {
                        var copied by remember { mutableStateOf(false) }
                        SelectionContainer { Text(ALLOW_COMMAND, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                        Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = {
                                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Termux", ALLOW_COMMAND))
                                copied = true
                            }) { Text(stringResource(if (copied) R.string.dsh_copied else R.string.dsh_copy_command)) }
                            TextButton(onClick = { ctx.packageManager.getLaunchIntentForPackage(Termux.PACKAGE)?.let(::open) }) {
                                Text(stringResource(R.string.dsh_open_termux))
                            }
                            TextButton(onClick = { status = null; seen++ }) { Text(stringResource(R.string.termux_check)) }
                        }
                    }
                }
            }

            SectionHeader(stringResource(R.string.dsh_section_install))
            Group {
                val steps = status?.steps.orEmpty()
                val names = mapOf(
                    DshSetup.Step.Termux to R.string.dsh_step_packages, DshSetup.Step.Ubuntu to R.string.dsh_step_ubuntu,
                    DshSetup.Step.Node to R.string.dsh_step_node, DshSetup.Step.Dsh to R.string.dsh_step_dsh,
                    DshSetup.Step.Profile to R.string.dsh_step_profile, DshSetup.Step.Check to R.string.dsh_step_check,
                )
                DshSetup.Step.entries.forEachIndexed { i, step ->
                    if (i > 0) GroupDivider()
                    // Just started: the script hasn't said anything yet, but it is on its way to the first step.
                    val state = steps[step]?.takeIf { !(launching && i == 0 && it == StepState.Pending) } ?: if (launching && i == 0) StepState.Running else StepState.Pending
                    Step(stringResource(names.getValue(step)), state)
                }
            }

            val s = status
            Column(Modifier.padding(horizontal = 4.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    running -> Text(stringResource(R.string.dsh_setup_running), style = MaterialTheme.typography.bodyMedium)
                    s?.current == true -> Text(stringResource(R.string.dsh_setup_done), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    s?.failedCode != null -> Text(stringResource(R.string.dsh_setup_failed, s.failedCode!!), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    s?.outdated == true -> Text(stringResource(R.string.dsh_setup_outdated), style = MaterialTheme.typography.bodyMedium)
                }
                if (answers && !running && s?.current != true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.dsh_mirrors), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.dsh_mirrors_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = mirrors, onCheckedChange = { mirrors = it })
                    }
                    Button(onClick = { setup.start(mirrors) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(when {
                            s?.failedCode != null -> R.string.dsh_setup_retry
                            s?.outdated == true -> R.string.dsh_setup_update
                            else -> R.string.dsh_setup_start
                        }))
                    }
                }
                if (s?.current == true && !running) {
                    if (default == ENGINE_ID) Text(stringResource(R.string.dsh_in_use), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else Button(onClick = { vm.selectCore(ENGINE_ID); onClose() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dsh_use)) }
                    TextButton(onClick = { setup.start(mirrors) }) { Text(stringResource(R.string.dsh_setup_again)) }
                }
                if (!s?.log.isNullOrBlank()) {
                    TextButton(onClick = { showLog = !showLog }) { Text(stringResource(if (showLog) R.string.dsh_hide_log else R.string.dsh_show_log)) }
                    if (showLog) {
                        Group {
                            SelectionContainer {
                                Text(
                                    s!!.log.lines().takeLast(LOG_LINES).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                    softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(GroupContentPadding),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One step: what it is, whether it's done, and what to do about it ([actions]). */
@Composable
private fun Step(title: String, state: StepState, detail: String? = null, actions: @Composable () -> Unit = {}) {
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

private const val ENGINE_ID = "dsh"
private const val LOG_LINES = 60
private const val TERMUX_DOWNLOAD = "https://github.com/termux/termux-app/releases/latest"
/** Lets other apps (this one) run commands in Termux. */
private const val ALLOW_COMMAND = "mkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings"
