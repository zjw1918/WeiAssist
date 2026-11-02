package com.lanqi.weiassist.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.SessionStatus

/** One of the app's vector icons (res/drawable/ic_*.xml), tinted like Material icons. */
@Composable
fun AppIcon(@DrawableRes id: Int, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = androidx.compose.material3.LocalContentColor.current) =
    Icon(painterResource(id), contentDescription, modifier, tint)

/** The small label above a group of rows. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, top = 20.dp, bottom = 8.dp).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/** Rows that belong together, on one rounded surface, separated by hairlines. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(content = content)
    }
}

@Composable
fun GroupDivider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

/** A settings-style row: optional icon, title and description, something on the end. */
@Composable
fun GroupRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null,
    descriptionColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            AppIcon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = descriptionColor) }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** Padding for free content (text, buttons) inside a [Group]. */
val GroupContentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)

/** A session's status as a dot and a word; nothing while it is idle. */
@Composable
fun StatusBadge(status: SessionStatus, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val (label, color) = when (status) {
        SessionStatus.Running -> stringResource(R.string.status_running) to c.primary
        SessionStatus.Queued -> stringResource(R.string.status_queued) to c.onSurfaceVariant
        SessionStatus.Paused -> stringResource(R.string.status_paused) to c.tertiary
        SessionStatus.Interrupted -> stringResource(R.string.status_interrupted) to c.error
        SessionStatus.Idle -> return
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // A still dot, not a spinner: an endless animation would redraw the whole page 60 times a second
        // for as long as a task runs (measured: most of the app's CPU while working).
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The message box used throughout: a rounded field with a one-tap clear button while it has text,
 * an optional extra action (voice), and a round send button.
 */
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    sendEnabled: Boolean = value.isNotBlank(),
    maxLines: Int = 5,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    extra: (@Composable () -> Unit)? = null,
    /** With these, a "+" before the box attaches files, shown above it until sent. */
    attachments: List<com.lanqi.weiassist.agent.Attachment> = emptyList(),
    onAttach: ((List<android.net.Uri>) -> Unit)? = null,
    onRemoveAttachment: (com.lanqi.weiassist.agent.Attachment) -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
    AttachmentStrip(attachments, onRemoveAttachment, Modifier.padding(start = 4.dp, bottom = 8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(if (onAttach != null) 2.dp else 8.dp)) {
        if (onAttach != null) Box(Modifier.padding(bottom = 4.dp)) { AttachButton(onAttach) }
        TextField(
            value, onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            maxLines = maxLines,
            shape = MaterialTheme.shapes.extraLarge,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = containerColor, unfocusedContainerColor = containerColor, disabledContainerColor = containerColor,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
            ),
            trailingIcon = if (value.isEmpty() && extra == null) null else {
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (value.isNotEmpty()) ClearButton { onValueChange("") } else extra?.invoke()
                    }
                }
            },
        )
        FilledIconButton(
            onClick = onSend, enabled = sendEnabled, modifier = Modifier.padding(bottom = 4.dp).size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(),
        ) { AppIcon(R.drawable.ic_arrow_upward, stringResource(R.string.send)) }
    }
    }
}

/** Empties a text field in one tap. */
@Composable
fun ClearButton(onClear: () -> Unit) {
    IconButton(onClick = onClear) {
        AppIcon(R.drawable.ic_cancel, stringResource(R.string.clear), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
