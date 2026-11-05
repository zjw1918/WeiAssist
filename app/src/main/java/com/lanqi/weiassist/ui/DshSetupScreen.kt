package com.lanqi.weiassist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanqi.weiassist.AppViewModel
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.DshSetup
import com.lanqi.weiassist.agent.Termux
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Sets DeepSeek Harness up from the app: Termux itself ([TermuxSteps]), then the setup script, run in Termux
 * ([DshSetup]), with each of its steps and its output as it goes. The setup goes on in Termux when the user leaves;
 * coming back shows where it stands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DshSetupScreen(vm: AppViewModel, termux: Termux, setup: DshSetup, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val view = rememberTermuxView(termux)
    val local = view.local
    var resumed by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) { resumed = true; onPauseOrDispose { resumed = false } }

    val launching by setup.launching.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<DshSetup.Status?>(null) }
    // While the setup runs (and this screen is in front), follow it.
    LaunchedEffect(view.seen, resumed, launching, local.sendable) {
        while (resumed && local.sendable) {
            val s = setup.status()
            status = s
            if (!launching && !s.running) break
            delay(3_000)
        }
    }
    val answers = local.sendable && status != null && status?.termuxProblem == null
    val running = launching || status?.running == true
    val default by vm.defaultEngine.collectAsStateWithLifecycle()
    var mirrors by rememberSaveable { mutableStateOf(Locale.getDefault().let { it.language == "zh" && it.country != "TW" && it.country != "HK" }) }

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

            TermuxSteps(
                local, problem = status?.termuxProblem, asking = local.sendable && status == null,
                onPermission = view.refresh, onRecheck = { status = null; view.refresh() },
            )

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
                    val reported = steps[step]?.let { StepState.valueOf(it.name) } ?: StepState.Pending
                    // Just started: the script hasn't said anything yet, but it is on its way to the first step.
                    val state = if (launching && i == 0 && reported == StepState.Pending) StepState.Running else reported
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
                DetailsLog(s?.log.orEmpty())
            }
        }
    }
}

private const val ENGINE_ID = "dsh"
