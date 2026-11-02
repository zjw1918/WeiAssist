package com.lanqi.phoneagent.ui

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
import androidx.compose.ui.unit.dp
import com.lanqi.phoneagent.ModelInfo

/** Pi's ThinkingLevel values in plain words. */
private val LEVEL_LABEL = mapOf(
    "off" to "不思考", "minimal" to "最少", "low" to "低", "medium" to "中", "high" to "高", "xhigh" to "很高", "max" to "最高",
)

private fun ModelInfo.detail() = buildString {
    append(id)
    if (contextWindow > 0) append(" · ${contextWindow / 1000}K 上下文")
    if (images) append(" · 能看图")
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
                value = selected?.name ?: "选择模型", onValueChange = {}, readOnly = true,
                label = { Text(if (subscription) "模型（ChatGPT 订阅可用的 ${models.size} 个）" else "模型（${models.size} 个）") },
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
            Text("思考程度", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                levels.forEach { lv ->
                    FilterChip(selected = lv == thinkingLevel, onClick = { onThinking(lv) }, label = { Text(LEVEL_LABEL[lv] ?: lv) })
                }
            }
            Text("想得越多，回答越慢、用量越多。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
