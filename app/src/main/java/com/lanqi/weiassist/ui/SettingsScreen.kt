package com.lanqi.weiassist.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.lanqi.weiassist.agent.DshSetup
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanqi.weiassist.AppViewModel
import com.lanqi.weiassist.BuildConfig
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.AgentState

/** The Settings tab: what the assistant may use on this phone, and the app itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(vm: AppViewModel, s: AgentState) {
    val ctx = LocalContext.current
    val bar = TopAppBarDefaults.pinnedScrollBehavior()
    fun open(intent: Intent) = runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    // DeepSeek Harness's setup screen is open (it replaces this tab, like a provider's form).
    var dshSetup by rememberSaveable { mutableStateOf(false) }
    // Likewise Linux commands' (Termux) setup screen.
    var termuxSetup by rememberSaveable { mutableStateOf(false) }
    val termux = vm.termux
    val setup = vm.dshSetup
    if (dshSetup && termux != null && setup != null) {
        DshSetupScreen(vm, termux, setup) { dshSetup = false }
        return
    }
    if (termuxSetup && termux != null) {
        TermuxSetupScreen(termux) { termuxSetup = false }
        return
    }
    Scaffold(
        modifier = Modifier.nestedScroll(bar.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_settings)) },
                actions = { ScanQrButton() },
                scrollBehavior = bar,
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            val accessibilityOn by vm.accessibilityOn.collectAsStateWithLifecycle()
            SectionHeader(stringResource(R.string.section_phone))
            Group {
                GroupRow(
                    title = stringResource(R.string.a11y_service), icon = R.drawable.ic_phone,
                    description = stringResource(if (accessibilityOn) R.string.a11y_on else R.string.a11y_off),
                    descriptionColor = if (accessibilityOn) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    onClick = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    trailing = { AppIcon(R.drawable.ic_open_in_new, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                GroupDivider()
                GroupRow(
                    title = stringResource(R.string.show_live_screens), description = stringResource(R.string.show_live_screens_hint),
                    onClick = { vm.showPreviews(!vm.showPreviews) },
                    trailing = { Switch(checked = vm.showPreviews, onCheckedChange = vm::showPreviews) },
                )
                GroupDivider()
                GroupRow(
                    title = stringResource(R.string.web_fetch_local), description = stringResource(R.string.web_fetch_local_hint),
                    onClick = { vm.webFetchLocal(!vm.webFetchLocal) },
                    trailing = { Switch(checked = vm.webFetchLocal, onCheckedChange = vm::webFetchLocal) },
                )
            }

            termux?.let { t -> LinuxSection(t) { termuxSetup = true } }

            EngineSection(vm, s) { dshSetup = true }

            SectionHeader(stringResource(R.string.gateway_title))
            Group { Column(Modifier.padding(GroupContentPadding)) { GatewaySection() } }

            SectionHeader(stringResource(R.string.shell_title))
            Group { Column(Modifier.padding(GroupContentPadding)) { ShellSection() } }

            SectionHeader(stringResource(R.string.telegram_title))
            Group { Column(Modifier.padding(GroupContentPadding)) { TelegramSection(vm) } }

            SectionHeader(stringResource(R.string.voice_title))
            Group { Column(Modifier.padding(GroupContentPadding)) { VoiceSection(vm.voice) } }

            // Per-app language is a system screen on Android 13+.
            if (Build.VERSION.SDK_INT >= 33) {
                SectionHeader(stringResource(R.string.section_general))
                Group {
                    GroupRow(
                        title = stringResource(R.string.language), description = stringResource(R.string.language_hint),
                        onClick = { open(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", ctx.packageName, null))) },
                        trailing = { AppIcon(R.drawable.ic_open_in_new, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }

            if (BuildConfig.DEV_TOOLS) {
                SectionHeader(stringResource(R.string.section_developer))
                Group {
                    GroupRow(
                        title = stringResource(R.string.dev_mock), onClick = { vm.setMock(!vm.useMock) },
                        trailing = { Switch(checked = vm.useMock, onCheckedChange = vm::setMock) },
                    )
                }
            }

            SectionHeader(stringResource(R.string.section_about))
            Group {
                GroupRow(title = stringResource(R.string.app_name), description = stringResource(R.string.version, BuildConfig.VERSION_NAME))
                GroupDivider()
                GroupRow(title = stringResource(R.string.runtime), description = stringResource(R.string.pi_runtime_info, s.piVersion.orEmpty()))
            }
        }
    }
}

/**
 * The AI engine new sessions use: each installed one with whether it can run now (DeepSeek Harness needs its setup in
 * Termux), and the default to pick. A session keeps the engine it started with.
 */
@Composable
private fun EngineSection(vm: AppViewModel, s: AgentState, openDshSetup: () -> Unit) {
    val engines = vm.engines
    if (engines.size < 2) return
    val default by vm.defaultEngine.collectAsStateWithLifecycle()
    // Why each engine can't run now (null: it can); absent while checking.
    val problems = remember { mutableStateMapOf<String, String?>() }
    // DeepSeek Harness: why, in the user's words; checked again while its setup runs (an automatic update after an
    // app update), so the row turns ready by itself.
    var dsh by remember { mutableStateOf<DshSetup.Readiness?>(null) }
    LaunchedEffect(engines) {
        while (true) {
            engines.forEach { e -> problems[e.id] = vm.engineProblem(e.id) }
            if (engines.any { it.id == DSH }) dsh = vm.dshReadiness()
            // Set up by another version of the app: update it now (the app does on start too, but Termux may not
            // have answered then), and follow it here.
            if (dsh == DshSetup.Readiness.Outdated && vm.updateDshIfOutdated()) dsh = DshSetup.Readiness.Updating
            if (dsh != DshSetup.Readiness.Updating) break
            delay(5_000)
        }
    }
    SectionHeader(stringResource(R.string.section_engine))
    Group {
        engines.forEachIndexed { i, e ->
            if (i > 0) GroupDivider()
            val checked = problems.containsKey(e.id)
            val problem = problems[e.id]
            val usable = checked && problem == null
            GroupRow(
                title = e.name,
                description = when {
                    e.id == "pi" -> stringResource(R.string.engine_pi_info, s.piVersion.orEmpty())
                    !checked -> stringResource(R.string.engine_checking)
                    problem != null && e.id == DSH -> when (dsh) {
                        DshSetup.Readiness.Outdated -> stringResource(R.string.engine_dsh_outdated)
                        DshSetup.Readiness.Updating -> stringResource(R.string.engine_dsh_updating)
                        is DshSetup.Readiness.Termux -> stringResource(R.string.engine_dsh_termux)
                        else -> stringResource(R.string.engine_dsh_not_ready)
                    }
                    problem != null -> problem
                    else -> stringResource(R.string.engine_ready)
                },
                descriptionColor = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                // DeepSeek Harness not ready: its setup.
                onClick = when {
                    usable || default == e.id -> ({ vm.selectCore(e.id) })
                    checked && e.id == DSH -> openDshSetup
                    else -> null
                },
                // The row takes the tap. A RadioButton with an onClick is wider (its touch target), so mixing them misaligns.
                trailing = { RadioButton(selected = default == e.id, onClick = null, enabled = usable || default == e.id) },
            )
        }
    }
    if (engines.any { it.id == DSH }) {
        Group(Modifier.padding(top = 8.dp)) {
            GroupRow(
                title = stringResource(R.string.dsh_setup_title), description = stringResource(R.string.dsh_setup_row_hint),
                icon = R.drawable.ic_terminal, onClick = openDshSetup,
                trailing = { AppIcon(R.drawable.ic_chevron_right, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
    }
    Text(
        stringResource(R.string.engine_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
    )
}

private const val DSH = "dsh"

/**
 * Linux commands (Termux), above the AI engine: whether they are set up, and the way to their setup. Until they are,
 * a note says what the assistant can't do without them.
 */
@Composable
private fun LinuxSection(termux: com.lanqi.weiassist.agent.Termux, openSetup: () -> Unit) {
    val view = rememberTermuxView(termux)
    val local = view.local
    val inspection by termux.lastInspection.collectAsStateWithLifecycle()
    // Ask Termux once (later the setup screen keeps the answer up to date); only when commands can be sent at all.
    LaunchedEffect(local.sendable) { if (local.sendable && termux.lastInspection.value == null) termux.inspect() }
    val ready = local.sendable && inspection?.ready == true
    SectionHeader(stringResource(R.string.termux_title))
    Group {
        GroupRow(
            title = stringResource(R.string.termux_setup_row), icon = R.drawable.ic_terminal,
            description = when {
                !local.installed -> stringResource(R.string.termux_missing)
                !local.runnable -> stringResource(R.string.termux_play_build)
                !local.permitted -> stringResource(R.string.termux_no_permission)
                ready -> stringResource(R.string.termux_row_ready)
                else -> stringResource(R.string.termux_row_allowed)
            },
            descriptionColor = if (local.sendable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            onClick = openSetup,
            trailing = { AppIcon(R.drawable.ic_chevron_right, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
    }
    // Not set up (known: Termux missing or not allowed, or its check failed); nothing while it is being checked.
    if (!local.sendable || inspection?.ready == false) Text(
        stringResource(R.string.termux_limited_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
    )
}
