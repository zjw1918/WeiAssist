package com.lanqi.weiassist.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.AnswerDraft
import com.lanqi.weiassist.agent.QuestionOption

/**
 * What the agent asks the user, above the message box: one question at a time. A choice answers it (several are
 * picked, then Next); words typed in the message box answer it too; Skip leaves it unanswered. A plan to review
 * shows the plan itself.
 */
@Composable
fun QuestionCard(draft: AnswerDraft, onStep: ((AnswerDraft) -> AnswerDraft) -> Unit, modifier: Modifier = Modifier) {
    val q = draft.current ?: return
    val total = draft.ask.questions.size
    val last = draft.index == total - 1
    Surface(
        modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_help, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                val head = when (q.intent?.kind) {
                    "plan-review" -> stringResource(R.string.question_plan)
                    "approval" -> stringResource(R.string.question_approval)
                    else -> q.header.ifBlank { stringResource(R.string.question_title) }
                }
                val count = if (total > 1) " · ${draft.index + 1}/$total" else ""
                Text(head + count, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { onStep { it.skip() } }) { Text(stringResource(R.string.question_skip)) }
            }
            Text(q.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.padding(end = 6.dp, bottom = 6.dp))
            if (q.detail.isNotBlank()) {
                Surface(
                    Modifier.fillMaxWidth().padding(end = 6.dp, bottom = 8.dp).heightIn(max = 260.dp), shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    MarkdownText(q.detail, Modifier.verticalScroll(rememberScrollState()).padding(10.dp))
                }
            }
            Column(Modifier.padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                q.options.forEach { o ->
                    val primary = q.intent != null && o.label == q.intent!!.approve
                    OptionRow(o, multi = q.multi, picked = o.label in draft.picked, primary = primary) { onStep { it.choose(o.label) } }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (q.options.isEmpty()) R.string.question_type_answer else R.string.question_or_type),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                if (q.multi) TextButton(onClick = { onStep { it.confirm() } }, enabled = draft.picked.isNotEmpty()) {
                    Text(stringResource(if (last) R.string.question_send else R.string.question_next))
                }
            }
        }
    }
}

@Composable
private fun OptionRow(o: QuestionOption, multi: Boolean, picked: Boolean, primary: Boolean, onClick: () -> Unit) {
    if (primary) {
        FilledTonalButton(onClick, Modifier.fillMaxWidth()) { Text(o.label) }
        return
    }
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = if (picked) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = if (multi) 2.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (multi) Checkbox(checked = picked, onCheckedChange = { onClick() })
            Column(Modifier.weight(1f)) {
                Text(o.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (o.description.isNotBlank()) Text(o.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
