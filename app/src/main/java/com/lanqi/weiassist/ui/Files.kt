package com.lanqi.weiassist.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.Attachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What tapping a link to a file in an answer does (a path in the session's Termux folder, as a model writes it):
 * provided by the session's page; null elsewhere, where such a link stays plain text.
 */
val LocalFileLinks = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Whether an answer's link is to a file (a path), not a web page or another app's link (mailto:, tel:…). */
fun isFileLink(link: String): Boolean {
    val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(link)?.groupValues?.get(1)?.lowercase()
    return (scheme == null || scheme == "file") && link.isNotBlank() && !link.startsWith("#")
}

/** Open [file] (in the app's files) with the app the user picks for its type. */
fun openFile(context: Context, file: Attachment) {
    val f = File(file.path)
    if (!f.exists()) { Toast.makeText(context, R.string.file_missing, Toast.LENGTH_SHORT).show(); return }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f, file.name)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(view)
    } catch (_: ActivityNotFoundException) {
        val type = file.name.substringAfterLast('.', "").uppercase().ifEmpty { file.mime }
        Toast.makeText(context, context.getString(R.string.file_no_app, type), Toast.LENGTH_LONG).show()
    }
}

/** A file the agent gave the user: its type, name, size and caption. Tap: open it; Save…: put a copy where the user picks. */
@Composable
fun FileCard(file: Attachment, caption: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(file.mime)) { uri ->
        if (uri != null) saveCopy(ctx, file, uri)
    }
    Surface(
        shape = MaterialTheme.shapes.medium, color = c.surfaceContainerHigh,
        modifier = modifier.widthIn(max = 360.dp).clickable { openFile(ctx, file) },
    ) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Text(
                    file.name.substringAfterLast('.', "").uppercase().take(4).ifEmpty { "FILE" },
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = c.primary,
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    android.text.format.Formatter.formatShortFileSize(ctx, file.size) + if (caption.isNotBlank()) " · $caption" else "",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { save.launch(file.name) }) { Text(stringResource(R.string.file_save)) }
        }
    }
}

/** Saves outlive the card that started them (the user may leave the page). */
private val saves: CoroutineScope = MainScope()

/** Copy [file] to [dest] (a document the user created), off the main thread; says how it went. */
private fun saveCopy(context: Context, file: Attachment, dest: android.net.Uri) {
    val app = context.applicationContext
    saves.launch {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                File(file.path).inputStream().use { input ->
                    checkNotNull(app.contentResolver.openOutputStream(dest)) { "no output" }.use { input.copyTo(it) }
                }
            }
        }
        r.onSuccess { Toast.makeText(app, R.string.file_saved, Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(app, app.getString(R.string.file_save_failed, it.message ?: it.javaClass.simpleName), Toast.LENGTH_LONG).show() }
    }
}
