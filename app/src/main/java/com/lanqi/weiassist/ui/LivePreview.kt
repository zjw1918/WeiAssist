package com.lanqi.weiassist.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanqi.weiassist.AppViewModel
import com.lanqi.weiassist.agent.PreviewHub
import com.lanqi.weiassist.agent.DisplayScreenPool

/**
 * A session's virtual display, live. Frames flow only while this is on screen and the app is visible
 * (collectAsStateWithLifecycle); the picture is the display's GPU buffer, scaled by the GPU.
 */
@Composable
fun LivePreview(vm: AppViewModel, display: Int, purpose: PreviewHub.Purpose, modifier: Modifier = Modifier, label: String? = null) {
    val frames = remember(display, purpose) { vm.frames(display, purpose) }
    val frame by frames.collectAsStateWithLifecycle(null)
    val shape = RoundedCornerShape(if (purpose == PreviewHub.Purpose.Page) 12.dp else 8.dp)
    Box(
        modifier
            .aspectRatio(DisplayScreenPool.WIDTH.toFloat() / DisplayScreenPool.HEIGHT)
            .clip(shape)
            .background(Color(0xFF202124))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = frame
        if (bmp != null && !bmp.isRecycled) {
            Image(bmp.asImageBitmap(), contentDescription = label, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Text("…", color = Color.White)
        }
    }
}
