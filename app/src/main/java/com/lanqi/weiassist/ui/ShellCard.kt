package com.lanqi.weiassist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanqi.weiassist.R
import com.lanqi.weiassist.shell.PairingService
import com.lanqi.weiassist.shell.ShellBackend
import com.lanqi.weiassist.shell.ShellState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The built-in shell backend in Settings: lets the agent give each task its own virtual display.
 * Set up once (pair with Wireless debugging); afterwards it starts by itself while Wireless debugging is on.
 */
@Composable
fun ShellSection() {
    val ctx = LocalContext.current
    val backend = remember { ShellBackend.get(ctx) }
    val state by backend.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var test by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.shell_title), style = MaterialTheme.typography.titleSmall)
        val (status, detail) = when (val s = state) {
            ShellState.Unsupported -> stringResource(R.string.shell_unsupported) to null
            ShellState.NotPaired -> stringResource(R.string.shell_not_paired) to stringResource(R.string.shell_why)
            is ShellState.Stopped -> stringResource(R.string.shell_stopped) to s.hint
            ShellState.Starting -> stringResource(R.string.shell_starting) to null
            is ShellState.Running -> stringResource(R.string.shell_running, if (s.uid == 0) "root" else if (s.uid == 2000) "shell" else s.uid.toString()) to null
        }
        Text(status, style = MaterialTheme.typography.bodyMedium)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                ShellState.NotPaired -> OutlinedButton(onClick = { PairingService.begin(ctx) }) { Text(stringResource(R.string.shell_set_up)) }
                is ShellState.Stopped -> {
                    OutlinedButton(onClick = { scope.launch { backend.start() } }) { Text(stringResource(R.string.shell_start)) }
                    TextButton(onClick = { backend.unpair() }) { Text(stringResource(R.string.shell_unpair)) }
                }
                is ShellState.Running -> {
                    OutlinedButton(onClick = {
                        scope.launch {
                            test = withContext(Dispatchers.IO) {
                                runCatching { backend.service()?.exec("id; getprop ro.build.version.release", 5_000)?.getString("out")?.trim() }
                                    .getOrElse { it.toString() }
                            }
                        }
                    }) { Text(stringResource(R.string.shell_test)) }
                    TextButton(onClick = { backend.stop() }) { Text(stringResource(R.string.shell_stop)) }
                }
                else -> Unit
            }
        }
        test?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
    }
}
