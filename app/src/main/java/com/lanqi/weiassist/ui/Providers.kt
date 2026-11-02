package com.lanqi.weiassist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lanqi.weiassist.AppViewModel
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.AgentState

/** The AI providers tab: which provider and account the assistant uses, and its model and thinking level. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersTab(vm: AppViewModel, s: AgentState) {
    val bar = TopAppBarDefaults.pinnedScrollBehavior()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.nestedScroll(bar.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_providers)) }, scrollBehavior = bar) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            val provider = s.provider
            val how = when (provider?.credential) {
                "oauth" -> stringResource(R.string.auth_subscription)
                "api_key" -> stringResource(R.string.auth_api_key)
                else -> null
            }
            SectionHeader(stringResource(R.string.section_provider))
            Group {
                GroupRow(
                    title = provider?.name ?: "…", icon = R.drawable.ic_sparkle,
                    description = how?.let { stringResource(R.string.provider_via, it) },
                    trailing = {
                        Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                            AppIcon(R.drawable.ic_check_circle, stringResource(R.string.provider_connected), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    },
                )
                GroupDivider()
                GroupRow(
                    title = stringResource(R.string.sign_out), icon = R.drawable.ic_logout,
                    description = stringResource(R.string.switch_method_hint), onClick = { confirmSignOut = true },
                )
            }

            SectionHeader(stringResource(R.string.section_model))
            Group {
                Column(Modifier.padding(GroupContentPadding)) {
                    ModelPicker(
                        models = provider?.models.orEmpty(), selected = s.model, thinkingLevel = s.thinkingLevel,
                        onModel = vm::selectModel, onThinking = vm::selectThinking, subscription = provider?.credential == "oauth",
                    )
                }
            }
            Text(
                stringResource(R.string.providers_footnote), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp),
            )
        }
    }
    // Signing out deletes the saved credential, so ask first.
    if (confirmSignOut) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text(stringResource(R.string.sign_out_title)) },
        text = { Text(stringResource(R.string.sign_out_text)) },
        confirmButton = { TextButton(onClick = { confirmSignOut = false; vm.signOut() }) { Text(stringResource(R.string.sign_out_confirm), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
