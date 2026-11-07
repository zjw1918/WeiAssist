package com.lanqi.weiassist.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanqi.weiassist.R
import com.lanqi.weiassist.magpie.MagpieHost

/**
 * The AI gateway (magpie) in Settings: on or off, where this phone's apps and the network's devices reach it, and
 * its console (providers, sign-ins, routing, usage, gateway keys), opened in the browser.
 */
@Composable
fun GatewaySection() {
    val ctx = LocalContext.current
    val host = remember { MagpieHost.get(ctx) }
    val state by host.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state == MagpieHost.State.Unavailable) {
            Text(stringResource(R.string.gateway_unavailable), style = MaterialTheme.typography.bodyLarge)
            return@Column
        }
        val on = state != MagpieHost.State.Off
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val status = when (val s = state) {
                    MagpieHost.State.Starting -> stringResource(R.string.gateway_starting)
                    MagpieHost.State.Running -> stringResource(R.string.gateway_running)
                    is MagpieHost.State.Failed -> stringResource(R.string.gateway_failed, s.message)
                    else -> stringResource(R.string.gateway_off)
                }
                Text(status, style = MaterialTheme.typography.bodyLarge)
            }
            Switch(checked = on, onCheckedChange = host::setEnabled)
        }
        Text(stringResource(R.string.gateway_why), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state == MagpieHost.State.Running) {
            val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.gateway_this_phone, host.localBaseUrl), style = mono)
                    val lan = host.lanBaseUrl()
                    Text(
                        if (lan != null) stringResource(R.string.gateway_network, lan) else stringResource(R.string.gateway_no_network),
                        style = mono,
                    )
                }
            }
            FilledTonalButton(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(host.consoleUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text(stringResource(R.string.gateway_console)) }
        }
    }
}
