package com.lanqi.weiassist.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.lanqi.weiassist.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

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
        const val PACKAGE = "com.lanqi.weiassist.gateway"
        const val PORT = 3425
        const val LOCAL = "http://127.0.0.1:$PORT"
        const val DOWNLOAD = "https://github.com/zjw1918/WeiAssist/releases/latest"

        /** Installed (it is in the manifest's queries), and whether it answers on loopback, where it needs no key. */
        fun check(ctx: Context): GatewayStatus {
            val installed = runCatching { ctx.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess
            if (!installed) return NotInstalled
            return if (answers()) Serving else Off
        }

        private fun answers(): Boolean = runCatching {
            val c = URL("$LOCAL/v1/models").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 1_000
                c.readTimeout = 2_000
                c.responseCode in 200..299
            } finally {
                c.disconnect()
            }
        }.getOrDefault(false)
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
