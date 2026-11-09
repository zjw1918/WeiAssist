package com.lanqi.weiassist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.VoiceLanguage
import com.lanqi.weiassist.agent.VoiceServer
import com.lanqi.weiassist.agent.VoiceServer.Health
import com.lanqi.weiassist.agent.VoiceSettings
import kotlinx.coroutines.delay

/** Voice input in Settings: the address of the user's speech-to-text server and the language they speak. */
@Composable
fun VoiceSection(voice: VoiceSettings) {
    val saved by voice.url.collectAsStateWithLifecycle()
    val language by voice.language.collectAsStateWithLifecycle()
    var field by rememberSaveable(saved) { mutableStateOf(saved.orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    var health by remember { mutableStateOf<Health?>(null) }
    // Saving the same address again is a new test.
    var tests by remember { mutableIntStateOf(0) }

    LaunchedEffect(saved, tests) {
        health = null
        val url = saved ?: return@LaunchedEffect
        // A server still loading its model becomes ready by itself.
        do {
            val now = VoiceServer.health(url)
            health = now
            if (now == Health.Loading) delay(3_000)
        } while (now == Health.Loading)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            when {
                invalid -> stringResource(R.string.voice_invalid)
                saved == null -> stringResource(R.string.voice_off)
                else -> when (health) {
                    null -> stringResource(R.string.gateway_checking)
                    is Health.Ready -> stringResource(R.string.voice_ready)
                    Health.Loading -> stringResource(R.string.voice_loading)
                    Health.Other -> stringResource(R.string.voice_other)
                    Health.Unreachable -> stringResource(R.string.voice_unreachable)
                }
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (invalid || health == Health.Other || health == Health.Unreachable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(stringResource(R.string.voice_why), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            field, { field = it; invalid = false }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.voice_address)) },
            placeholder = { Text("10.0.0.5:${VoiceServer.DEFAULT_PORT}") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = MaterialTheme.shapes.medium,
        )
        FilledTonalButton(onClick = {
            if (field.isBlank()) {
                voice.setUrl(null)
                invalid = false
            } else {
                val url = VoiceServer.normalizeUrl(field)
                invalid = url == null
                if (url != null) {
                    field = url
                    voice.setUrl(url)
                    tests++
                }
            }
        }) { Text(stringResource(R.string.voice_save_test)) }
        Text(stringResource(R.string.voice_language), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VoiceLanguage.entries.forEach { option ->
                FilterChip(
                    selected = option == language, onClick = { voice.setLanguage(option) },
                    label = {
                        Text(
                            stringResource(
                                when (option) {
                                    VoiceLanguage.Auto -> R.string.voice_language_auto
                                    VoiceLanguage.Chinese -> R.string.voice_language_zh
                                    VoiceLanguage.English -> R.string.voice_language_en
                                },
                            ),
                        )
                    },
                )
            }
        }
    }
}
