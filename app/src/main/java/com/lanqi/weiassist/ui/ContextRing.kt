package com.lanqi.weiassist.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.RunStats
import java.util.Locale

/** At this share of the window the ring warns: Pi compacts the conversation at 70% (pi-host compact.ts). */
private const val WARN_AT = 0.7f

/**
 * How full the session's context is, as a ring: the tokens the latest model call carried against the
 * model's window. Tap for the numbers ("89,000 of 1,000,000 tokens") and how much of the last run's input came from the provider's cache ([last]).
 * Still, no animation: it changes once per model call.
 */
@Composable
fun ContextRing(tokens: Long, window: Int, last: RunStats?, modifier: Modifier = Modifier, hint: Int = R.string.context_hint) {
    if (tokens <= 0 || window <= 0) return
    val c = MaterialTheme.colorScheme
    val share = (tokens.toFloat() / window).coerceIn(0f, 1f)
    val color = if (share >= WARN_AT) c.error else c.primary
    val track = c.surfaceContainerHighest
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        // Just the ring (a 40dp touch target); the numbers are in its popup.
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = stringResource(R.string.context_title)) { open = true }
                .semantics { contentDescription = "${compactCount(tokens)}/${compactCount(window.toLong())}" },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(16.dp)) {
                val w = 2.5.dp.toPx()
                val stroke = Stroke(w, cap = StrokeCap.Round)
                val inset = Offset(w / 2, w / 2)
                val box = Size(size.width - w, size.height - w)
                drawArc(track, 0f, 360f, false, inset, box, style = Stroke(w))
                // A sliver at least, so a fresh conversation still shows it counts.
                drawArc(color, -90f, (360f * share).coerceAtLeast(6f), false, inset, box, style = stroke)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.context_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(4.dp))
                Text(
                    stringResource(R.string.context_used, grouped(tokens), grouped(window.toLong()), percent(share)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                last?.cacheHitPercent?.let { hit ->
                    Spacer(Modifier.size(4.dp))
                    Text(
                        stringResource(R.string.context_cache, hit),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.size(6.dp))
                Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
    }
}

/** "9%", or "<1%" for a context that has something in it but under a percent. */
private fun percent(share: Float) = if (share > 0f && share < 0.01f) "<1%" else "${(share * 100).toInt()}%"

private fun grouped(n: Long) = String.format(Locale.getDefault(), "%,d", n)
