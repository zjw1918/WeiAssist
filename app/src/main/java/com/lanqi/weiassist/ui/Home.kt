package com.lanqi.weiassist.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanqi.weiassist.AppViewModel
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.AgentState

enum class Tab(@StringRes val label: Int, @DrawableRes val icon: Int) {
    Sessions(R.string.tab_sessions, R.drawable.ic_forum),
    Providers(R.string.tab_providers, R.drawable.ic_sparkle),
    Settings(R.string.tab_settings, R.drawable.ic_settings),
}

/**
 * The signed-in app: three tabs (Sessions, AI providers, Settings) on a bottom bar. An open session
 * takes the whole screen, as a chat does; Back returns to the list. The tab is the caller's, so it
 * survives a sign-in started from the AI providers tab (which replaces this screen meanwhile).
 */
@Composable
fun HomeScreen(vm: AppViewModel, s: AgentState, tab: Tab, onTab: (Tab) -> Unit) {
    // Kept here so the list keeps its place while a session is open.
    val sessionList = rememberLazyListState()
    val openId by vm.openId.collectAsStateWithLifecycle()
    if (openId != null) {
        val session by vm.session.collectAsStateWithLifecycle()
        SessionScreen(vm, session)
        return
    }
    BackHandler(enabled = tab != Tab.Sessions) { onTab(Tab.Sessions) }
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val running = sessions.count { it.status.active && !it.supervisor }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { onTab(t) },
                        label = { Text(stringResource(t.label)) },
                        icon = {
                            BadgedBox(badge = { if (t == Tab.Sessions && running > 0) Badge { Text("$running") } }) {
                                AppIcon(t.icon, null)
                            }
                        },
                    )
                }
            }
        },
    ) { inner ->
        // The bar's height is consumed, so the tabs' keyboard padding (imePadding) doesn't count it twice.
        Box(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)) {
            when (tab) {
                Tab.Sessions -> SessionsTab(vm, sessionList)
                Tab.Providers -> ProvidersTab(vm, s)
                Tab.Settings -> SettingsTab(vm, s)
            }
        }
    }
}
