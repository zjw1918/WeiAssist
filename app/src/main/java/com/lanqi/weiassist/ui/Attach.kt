package com.lanqi.weiassist.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** The "+" by a message box: attach photos (the system photo picker) or files (images, PDFs, text). */
@Composable
fun AttachButton(onPicked: (List<Uri>) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { if (it.isNotEmpty()) onPicked(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) onPicked(it) }
    Box {
        IconButton(onClick = { menu = true }) { AppIcon(R.drawable.ic_add, stringResource(R.string.attach)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.attach_photos)) }, onClick = {
                menu = false
                photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.attach_files)) }, onClick = {
                menu = false
                files.launch(arrayOf("*/*"))
            })
        }
    }
}

private const val MAX_PICK = 10

/** Attachments waiting to be sent, above the message box; each can be removed. */
@Composable
fun AttachmentStrip(list: List<Attachment>, onRemove: (Attachment) -> Unit, modifier: Modifier = Modifier) {
    if (list.isEmpty()) return
    var viewing by remember { mutableStateOf<Attachment?>(null) }
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        list.forEach { a ->
            Box {
                AttachmentTile(a, 64.dp) { viewing = a }
                // The remove button sits on the tile's corner.
                Surface(
                    onClick = { onRemove(a) }, shape = CircleShape, color = MaterialTheme.colorScheme.inverseSurface,
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(22.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        AppIcon(R.drawable.ic_cancel, stringResource(R.string.attach_remove), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.inverseOnSurface)
                    }
                }
            }
        }
    }
    viewing?.let { ImageViewer(it) { viewing = null } }
}

/** A sent message's attachments, above its text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageAttachments(list: List<Attachment>, modifier: Modifier = Modifier) {
    if (list.isEmpty()) return
    var viewing by remember { mutableStateOf<Attachment?>(null) }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        list.forEach { a -> AttachmentTile(a, if (a.kind == Attachment.Kind.Image) 120.dp else 64.dp) { viewing = a } }
    }
    viewing?.let { ImageViewer(it) { viewing = null } }
}

/** An image as a thumbnail (tap: [onOpen], to view it large); another file as its type and name. */
@Composable
private fun AttachmentTile(a: Attachment, size: Dp, onOpen: () -> Unit = {}) {
    val shape = MaterialTheme.shapes.medium
    if (a.kind == Attachment.Kind.Image) {
        val bmp = rememberThumbnail(a.path, size)
        Box(Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable(onClick = onOpen)) {
            bmp?.let { Image(it, a.name, Modifier.size(size), contentScale = ContentScale.Crop) }
        }
    } else {
        Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(width = 150.dp, height = 64.dp)) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (a.kind == Attachment.Kind.Pdf) "PDF" else a.name.substringAfterLast('.', "").uppercase().ifEmpty { "TXT" },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                )
                Text(a.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp))
            }
        }
    }
}

/**
 * An attached image over the whole screen: pinch or double-tap to zoom, drag to move it when zoomed,
 * tap or Back to close.
 */
@Composable
private fun ImageViewer(a: Attachment, onClose: () -> Unit) {
    val screen = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.let { max(it.width, it.height).toDp() }
    }
    val bmp = rememberThumbnail(a.path, screen)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onClose() },
                        // Zoom in toward the tapped point; a second double tap fits the image again.
                        onDoubleTap = { p ->
                            if (scale > 1f) { scale = 1f; offset = Offset.Zero }
                            else {
                                scale = 2.5f
                                val maxX = (scale - 1f) * size.width / 2f
                                val maxY = (scale - 1f) * size.height / 2f
                                offset = Offset(
                                    ((size.width / 2f - p.x) * (scale - 1f)).coerceIn(-maxX, maxX),
                                    ((size.height / 2f - p.y) * (scale - 1f)).coerceIn(-maxY, maxY),
                                )
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        // Keep the image over the screen: it may move as far as it is larger than the screen.
                        val maxX = (scale - 1f) * size.width / 2f
                        val maxY = (scale - 1f) * size.height / 2f
                        offset = if (scale == 1f) Offset.Zero
                        else Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (bmp == null) CircularProgressIndicator(color = Color.White)
            else Image(
                bmp, a.name, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
            Text(
                a.name, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp),
            )
        }
    }
}

/** A small bitmap of an attached image, decoded off the main thread. */
@Composable
private fun rememberThumbnail(path: String, size: Dp): ImageBitmap? {
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val bmp by produceState<ImageBitmap?>(null, path, px) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bmp
}
