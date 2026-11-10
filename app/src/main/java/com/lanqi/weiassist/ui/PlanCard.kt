package com.lanqi.weiassist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.PlanItem
import com.lanqi.weiassist.agent.PlanItem.Status

/** The agent's plan above the message box: how far along, and the step it is on; a tap shows every step. */
@Composable
fun PlanCard(session: String, plan: List<PlanItem>, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    var open by rememberSaveable(session) { mutableStateOf(false) }
    val done = plan.count { it.status == Status.Completed }
    val now = plan.firstOrNull { it.status == Status.InProgress } ?: plan.firstOrNull { it.status == Status.Pending }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surfaceContainer)
            .clickable { open = !open }.padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_check_circle, null, Modifier.size(16.dp), tint = c.primary)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.plan_progress, done, plan.size), style = MaterialTheme.typography.labelLarge, color = c.onSurface)
            if (!open && now != null) {
                Spacer(Modifier.width(10.dp))
                Text(now.activeForm.ifEmpty { now.content }, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            } else Spacer(Modifier.weight(1f))
            AppIcon(R.drawable.ic_chevron_right, null, Modifier.size(18.dp).rotate(if (open) 90f else 0f), tint = c.onSurfaceVariant)
        }
        if (open) Column(Modifier.padding(top = 8.dp)) {
            plan.forEach { item -> PlanRow(item) }
        }
    }
}

@Composable
private fun PlanRow(item: PlanItem) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            when (item.status) {
                Status.Completed -> AppIcon(R.drawable.ic_check_circle, stringResource(R.string.plan_done), Modifier.size(16.dp), tint = c.primary)
                Status.InProgress -> Box(Modifier.size(12.dp).clip(CircleShape).background(c.primary))
                Status.Pending -> Box(Modifier.size(12.dp).border(1.5.dp, c.outline, CircleShape))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (item.status == Status.InProgress) item.activeForm.ifEmpty { item.content } else item.content,
            style = MaterialTheme.typography.bodyMedium,
            color = if (item.status == Status.Completed) c.onSurfaceVariant else c.onSurface,
            fontWeight = if (item.status == Status.InProgress) FontWeight.Medium else null,
            textDecoration = if (item.status == Status.Completed) TextDecoration.LineThrough else null,
        )
    }
}
