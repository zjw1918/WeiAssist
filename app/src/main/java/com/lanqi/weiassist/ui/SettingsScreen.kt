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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    Scaffold(
        modifier = Modifier.nestedScroll(bar.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_settings)) }, scrollBehavior = bar) },
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
            }

            SectionHeader(stringResource(R.string.shell_title))
            Group { Column(Modifier.padding(GroupContentPadding)) { ShellSection() } }

            SectionHeader(stringResource(R.string.telegram_title))
            Group { Column(Modifier.padding(GroupContentPadding)) { TelegramSection(vm) } }

            vm.termux?.let { termux ->
                SectionHeader(stringResource(R.string.termux_title))
                Group { Column(Modifier.padding(GroupContentPadding)) { TermuxSection(termux) } }
            }

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

            if (BuildConfig.DEBUG) {
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
