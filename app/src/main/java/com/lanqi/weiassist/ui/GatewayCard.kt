package com.lanqi.weiassist.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.GatewayLink
import com.lanqi.weiassist.agent.ProviderSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The AI gateway in Settings. It is a separate app, WeiAssist Gateway (magpie: providers, subscriptions, routing and
 * format translation for this phone and its network), so WeiAssist stays small. This card says whether it is
 * installed and serving, and opens it or its download page.
 */
@Composable
fun GatewaySection() {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var gw by remember { mutableStateOf(GatewayStatus.Checking) }
    // Checked while Settings is in front: the gateway may be turned on or off in its own app meanwhile.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                gw = withContext(Dispatchers.IO) { GatewayStatus.check(ctx) }
                delay(3_000)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(
                when (gw) {
                    GatewayStatus.Checking -> R.string.gateway_checking
                    GatewayStatus.NotInstalled -> R.string.gateway_not_installed
                    GatewayStatus.Off -> R.string.gateway_off
                    GatewayStatus.Serving -> R.string.gateway_running
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(stringResource(R.string.gateway_why), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (gw == GatewayStatus.Serving) {
            val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.gateway_this_phone, GatewayStatus.LOCAL), style = mono)
                    val lan = remember { lanAddress() }
                    Text(
                        if (lan != null) stringResource(R.string.gateway_network, "http://$lan:${GatewayStatus.PORT}")
                        else stringResource(R.string.gateway_no_network),
                        style = mono,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (gw) {
                GatewayStatus.NotInstalled -> FilledTonalButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GatewayStatus.DOWNLOAD)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text(stringResource(R.string.gateway_get)) }
                GatewayStatus.Off, GatewayStatus.Serving -> FilledTonalButton(onClick = {
                    ctx.packageManager.getLaunchIntentForPackage(GatewayStatus.PACKAGE)?.let {
                        runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }) { Text(stringResource(R.string.gateway_open)) }
                GatewayStatus.Checking -> Unit
            }
        }
    }
}

enum class GatewayStatus {
    Checking, NotInstalled, Off, Serving;

    companion object {
        const val PACKAGE = GatewayLink.PACKAGE
        const val PORT = GatewayLink.PORT
        const val LOCAL = GatewayLink.ORIGIN
        const val DOWNLOAD = GatewayLink.DOWNLOAD

        /** Installed (it is in the manifest's queries), and whether it answers on loopback, where it needs no key. */
        fun check(ctx: Context): GatewayStatus = when (GatewayLink.status(ctx)) {
            GatewayLink.Status.NotInstalled -> NotInstalled
            GatewayLink.Status.Off -> Off
            GatewayLink.Status.Serving -> Serving
        }
    }
}

/** This phone's IPv4 address on a local network, preferring Wi-Fi; null if it has none. */
private fun lanAddress(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
}.getOrNull()

/** The gateway's status, checked every few seconds while the screen is in front (it is turned on and off in its own app). */
@Composable
fun rememberGatewayStatus(): GatewayStatus {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var gw by remember { mutableStateOf(GatewayStatus.Checking) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                gw = withContext(Dispatchers.IO) { GatewayStatus.check(ctx) }
                delay(3_000)
            }
        }
    }
    return gw
}

/** Open the Gateway app, or its download page when it isn't installed. */
fun openGateway(ctx: Context) {
    val launch = ctx.packageManager.getLaunchIntentForPackage(GatewayStatus.PACKAGE)
        ?: Intent(Intent.ACTION_VIEW, Uri.parse(GatewayStatus.DOWNLOAD))
    runCatching { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Where the assistant's AI providers come from (top of the Providers tab): WeiAssist's own, or the Gateway app, which
 * then carries every engine's requests. Only the user switches; the gateway must be serving models to be chosen.
 */
@Composable
fun ProviderSourcePicker(vm: com.lanqi.weiassist.AppViewModel, source: ProviderSource) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val gw = rememberGatewayStatus()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val check: @Composable (Boolean) -> Unit = { on ->
        if (on) androidx.compose.foundation.layout.Box(
            Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center,
        ) {
            AppIcon(R.drawable.ic_check_circle, stringResource(R.string.provider_in_use), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
    Group {
        GroupRow(
            title = stringResource(R.string.source_weiassist), icon = R.drawable.ic_sparkle,
            description = stringResource(R.string.source_weiassist_desc),
            onClick = if (source == ProviderSource.WeiAssist || busy) null else { { error = null; scope.launch { vm.selectSource(ProviderSource.WeiAssist) } } },
            trailing = { check(source == ProviderSource.WeiAssist) },
        )
        GroupDivider()
        val on = source == ProviderSource.Gateway
        GroupRow(
            title = stringResource(R.string.source_gateway), icon = R.drawable.ic_sparkle,
            description = error ?: stringResource(
                when {
                    gw == GatewayStatus.NotInstalled -> R.string.source_gateway_get
                    gw == GatewayStatus.Off -> if (on) R.string.source_gateway_stopped else R.string.source_gateway_off
                    else -> R.string.source_gateway_desc
                },
            ),
            descriptionColor = if (error != null || on && gw == GatewayStatus.Off) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = {
                when {
                    gw == GatewayStatus.NotInstalled || gw == GatewayStatus.Off || on -> openGateway(ctx)
                    gw == GatewayStatus.Serving && !busy -> {
                        busy = true
                        error = null
                        scope.launch {
                            error = runCatching { vm.selectSource(ProviderSource.Gateway) }.getOrElse { it.message ?: it.javaClass.simpleName }
                            busy = false
                        }
                    }
                    else -> Unit
                }
            },
            trailing = {
                if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else check(on)
            },
        )
    }
    // In use: its models as it serves them now (sign-ins added or removed in its console), each time this tab opens.
    val refresh = source == ProviderSource.Gateway && gw == GatewayStatus.Serving
    LaunchedEffect(refresh) { if (refresh) runCatching { vm.refreshGateway() } }
}
