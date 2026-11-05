package com.lanqi.weiassist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanqi.weiassist.AppViewModel
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.AgentState

/** The AI providers tab: which provider and account the assistant uses, and its model and thinking level. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersTab(vm: AppViewModel, s: AgentState) {
    val bar = TopAppBarDefaults.pinnedScrollBehavior()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    // A provider not signed in with several sign-in methods: which one (asked in a dialog).
    var connecting by rememberSaveable { mutableStateOf<String?>(null) }
    // The custom-provider form: "" = adding one, else the id being edited.
    var customForm by rememberSaveable { mutableStateOf<String?>(null) }
    val customs by vm.customProviders.collectAsStateWithLifecycle()
    customForm?.let { id ->
        // Home's scaffold leaves the insets to each tab (contentWindowInsets = 0): keep clear of the status bar.
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp)) {
            CustomProviderScreen(vm, customs.firstOrNull { it.id == id }) { customForm = null }
        }
        return
    }
    Scaffold(
        modifier = Modifier.nestedScroll(bar.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_providers)) }, scrollBehavior = bar) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            val provider = s.provider
            SectionHeader(stringResource(R.string.section_provider))
            Group {
                s.providers.forEachIndexed { i, p ->
                    if (i > 0) GroupDivider()
                    val how = when (p.credential) {
                        "oauth" -> stringResource(if (p.id == "xai") R.string.auth_subscription_xai else R.string.auth_subscription)
                        "api_key" -> stringResource(R.string.auth_api_key)
                        else -> null
                    }
                    val active = p.id == s.providerId && how != null
                    GroupRow(
                        title = p.name, icon = R.drawable.ic_sparkle,
                        description = when {
                            how == null -> stringResource(R.string.provider_not_connected)
                            active -> stringResource(R.string.provider_via, how)
                            else -> stringResource(R.string.provider_use, how)
                        },
                        onClick = when {
                            active -> null
                            how != null -> { { vm.selectProvider(p.id) } }
                            p.custom -> { { customForm = p.id } }
                            p.methods.size == 1 -> { { vm.signIn(p.id, p.methods.first()) } }
                            else -> { { connecting = p.id } }
                        },
                        trailing = {
                            if (p.custom) IconButton(onClick = { customForm = p.id }) {
                                AppIcon(R.drawable.ic_edit, stringResource(R.string.custom_edit_title), Modifier.size(20.dp))
                            }
                            if (active) Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                                AppIcon(R.drawable.ic_check_circle, stringResource(R.string.provider_in_use), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        },
                    )
                }
                GroupDivider()
                GroupRow(
                    title = stringResource(R.string.custom_add_title), icon = R.drawable.ic_add,
                    description = stringResource(R.string.custom_add_row), onClick = { customForm = "" },
                )
                GroupDivider()
                GroupRow(
                    title = stringResource(R.string.sign_out_of, provider?.name ?: "…"), icon = R.drawable.ic_logout,
                    description = stringResource(R.string.switch_method_hint), onClick = { confirmSignOut = true },
                )
            }
            s.signIn.error?.let {
                Text(stringResource(R.string.last_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp))
            }

            SectionHeader(stringResource(R.string.section_model))
            Group {
                Column(Modifier.padding(GroupContentPadding)) {
                    ModelPicker(
                        models = provider?.models.orEmpty(), selected = s.model, thinkingLevel = s.thinkingLevel,
                        onModel = vm::selectModel, onThinking = vm::selectThinking, subscription = provider?.id == "openai" && provider.credential == "oauth",
                    )
                }
            }
            Text(
                stringResource(R.string.providers_footnote), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp),
            )
        }
    }
    s.providers.firstOrNull { it.id == connecting }?.let { p ->
        AlertDialog(
            onDismissRequest = { connecting = null },
            title = { Text(stringResource(R.string.connect_provider, p.name)) },
            text = {
                Column {
                    p.methods.sortedByDescending { it.subscription }.forEach { m ->
                        TextButton(onClick = { connecting = null; vm.signIn(p.id, m) }) { Text(methodText(p, m).first) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { connecting = null }) { Text(stringResource(R.string.cancel)) } },
        )
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
