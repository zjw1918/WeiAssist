package com.lanqi.weiassist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.ModelInfo

/** Pi's ThinkingLevel values in plain words. */
private val LEVEL_LABEL = mapOf(
    "off" to R.string.level_off, "minimal" to R.string.level_minimal, "low" to R.string.level_low, "medium" to R.string.level_medium,
    "high" to R.string.level_high, "xhigh" to R.string.level_xhigh, "max" to R.string.level_max,
)

@Composable
private fun ModelInfo.detail(): String {
    val ctx = if (contextWindow > 0) " · " + stringResource(R.string.ctx_window, contextWindow / 1000) else ""
    val img = if (images) " · " + stringResource(R.string.reads_images) else ""
    return id + ctx + img
}

/** Model list and thinking levels both come from Pi: only what this sign-in can use, only what the model supports. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelPicker(
    models: List<ModelInfo>, selected: ModelInfo?, thinkingLevel: String,
    onModel: (String) -> Unit, onThinking: (String) -> Unit, subscription: Boolean,
) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
            OutlinedTextField(
                value = selected?.name ?: stringResource(R.string.choose_model), onValueChange = {}, readOnly = true,
                label = { Text(stringResource(if (subscription) R.string.models_subscription else R.string.models_count, models.size)) },
                supportingText = { selected?.let { Text(it.detail()) } },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                models.forEach { m ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(m.name)
                                Text(m.detail(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = { onModel(m.id); open = false },
                    )
                }
            }
        }
        val levels = selected?.thinkingLevels.orEmpty()
        if (levels.size > 1) {
            Text(stringResource(R.string.thinking), style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                levels.forEach { lv ->
                    FilterChip(selected = lv == thinkingLevel, onClick = { onThinking(lv) }, label = { Text(LEVEL_LABEL[lv]?.let { stringResource(it) } ?: lv) })
                }
            }
            Text(stringResource(R.string.thinking_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
