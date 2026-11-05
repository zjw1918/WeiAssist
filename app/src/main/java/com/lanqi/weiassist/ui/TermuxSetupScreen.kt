package com.lanqi.weiassist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.Termux

/**
 * Sets Linux commands up (the bash tool, for every engine): Termux itself ([TermuxSteps]), then its basic tools
 * (curl, tmux), installed from here, and a check that Termux reaches the internet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermuxSetupScreen(termux: Termux, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val view = rememberTermuxView(termux)
    val local = view.local
    val installing by termux.installing.collectAsStateWithLifecycle()
    val installed by termux.installOutput.collectAsStateWithLifecycle()
    var inspection by remember { mutableStateOf<Termux.Inspection?>(null) }
    // Ask again whenever the screen comes back, a step was fixed, or an install ended.
    var asks by remember { mutableIntStateOf(0) }
    LaunchedEffect(view.seen, asks, installing, local.sendable) {
        if (!local.sendable || installing) return@LaunchedEffect
        inspection = null
        inspection = termux.inspect()
    }
    val i = inspection
    val answers = local.sendable && i != null && i.problem == null

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.termux_title)) },
                navigationIcon = { IconButton(onClick = onClose) { AppIcon(R.drawable.ic_arrow_back, stringResource(R.string.back)) } },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Text(
                stringResource(R.string.termux_setup_intro), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )

            TermuxSteps(
                termux, local, problem = i?.problem, asking = local.sendable && i == null && !installing,
                onPermission = view.refresh, onRecheck = { asks++ },
            )

            SectionHeader(stringResource(R.string.termux_section_tools))
            Group {
                Step(
                    stringResource(R.string.termux_step_tools),
                    when {
                        installing -> StepState.Running
                        !answers -> StepState.Pending
                        i!!.tools -> StepState.Done
                        else -> StepState.Failed
                    },
                    detail = when {
                        installing -> stringResource(R.string.termux_installing)
                        answers && !i!!.tools -> stringResource(R.string.termux_step_tools_hint)
                        else -> null
                    },
                ) {
                    if (answers && !i!!.tools && !installing) FilledTonalButton(onClick = termux::installTools) { Text(stringResource(R.string.termux_install)) }
                }
                GroupDivider()
                Step(
                    stringResource(R.string.termux_step_network),
                    when {
                        !answers || !i!!.curl -> StepState.Pending
                        i.network != null -> StepState.Done
                        else -> StepState.Failed
                    },
                    detail = if (answers && i!!.curl && i.network == null) stringResource(R.string.termux_step_network_hint) else null,
                )
            }

            Column(Modifier.padding(horizontal = 4.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    i?.ready == true && local.unrestricted -> Text(stringResource(R.string.termux_ready_all), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    i?.ready == true -> Text(stringResource(R.string.termux_ready_restricted), style = MaterialTheme.typography.bodyMedium)
                    installed?.first == false && !installing -> Text(stringResource(R.string.termux_install_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                if (local.sendable && !installing) TextButton(onClick = { asks++ }) { Text(stringResource(R.string.termux_check_again)) }
                DetailsLog(installed?.second.orEmpty())
            }
        }
    }
}
