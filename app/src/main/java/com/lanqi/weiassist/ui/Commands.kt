package com.lanqi.weiassist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.sp
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.ChatItem
import com.lanqi.weiassist.core.CoreCommand

/**
 * A slash command the user ran: the line as typed, on the user's side; under it, what the core said of it. Both are
 * selectable like the other messages: long-press to select, then copy.
 */
@Composable
fun CommandMessage(item: ChatItem.Command, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = 48.dp), horizontalAlignment = Alignment.End) {
        SelectionContainer {
            Text(
                item.line, style = CodeStyle.copy(fontSize = 14.sp, lineHeight = 20.sp), color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.widthIn(max = 320.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
            when (item.ok) {
                null -> CircularProgressIndicator(Modifier.padding(top = 2.dp).size(14.dp), strokeWidth = 2.dp)
                true -> AppIcon(R.drawable.ic_check_circle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                false -> AppIcon(R.drawable.ic_cancel, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            }
            if (item.result.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                SelectionContainer(Modifier.weight(1f)) {
                    Text(
                        item.result, style = MaterialTheme.typography.bodyMedium,
                        color = if (item.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The commands whose names start with what is typed (`/pl` → /plan), above the message box while only a command's
 * name is typed. Tapping one puts it in the box, followed by a space when it takes input.
 */
@Composable
fun CommandSuggestions(typed: String, commands: List<CoreCommand>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val prefix = Regex("""^/([a-z0-9_-]*)$""").find(typed)?.groupValues?.get(1) ?: return
    val shown = commands.filter { it.name.startsWith(prefix) && "/${it.name}" != typed }
    if (shown.isEmpty()) return
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            shown.forEach { c ->
                Column(
                    Modifier.fillMaxWidth().clickable { onPick("/${c.name}" + if (c.hint.isNotEmpty()) " " else "") }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("/${c.name}", style = CodeStyle, fontWeight = FontWeight.Medium)
                        if (c.hint.isNotEmpty()) Text("  ${c.hint}", style = CodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    if (c.description.isNotEmpty()) Text(c.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
