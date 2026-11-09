package com.lanqi.weiassist.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lanqi.weiassist.R
import java.util.concurrent.Executors

/** A page of its own for scanning a QR code, opened from Settings. */
class ScanActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { ScanScreen(onClose = ::finish) } }
    }
}

/** The way to [ScanActivity]: an icon for the top right corner of a page. */
@Composable
fun ScanQrButton() {
    val ctx = LocalContext.current
    IconButton(onClick = { ctx.startActivity(Intent(ctx, ScanActivity::class.java)) }) {
        AppIcon(R.drawable.ic_qr_scan, stringResource(R.string.scan_qr))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanScreen(onClose: () -> Unit) {
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (result == null) R.string.scan_qr else R.string.scan_result_title)) },
                navigationIcon = { IconButton(onClick = onClose) { AppIcon(R.drawable.ic_arrow_back, stringResource(R.string.back)) } },
            )
        },
    ) { inner ->
        val page = Modifier.fillMaxSize().padding(inner)
        result?.let { ScanResult(it, page) { result = null } } ?: CameraPage(page) { result = it }
    }
}

/** The text can be copied whole with the button, or any part of it by selecting. */
@Composable
private fun ScanResult(text: String, modifier: Modifier, onScanAgain: () -> Unit) {
    val ctx = LocalContext.current
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Scrolls by itself, so that the buttons stay in reach however long the text is.
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Group {
                SelectionContainer(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(
                stringResource(R.string.scan_select_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
        Button(onClick = { copyText(ctx, text) }, Modifier.fillMaxWidth()) {
            AppIcon(R.drawable.ic_copy, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.copy))
        }
        OutlinedButton(onClick = onScanAgain, Modifier.fillMaxWidth()) { Text(stringResource(R.string.scan_again)) }
    }
}

/** Asks for the camera if need be, then shows it. Coming back from the system's app settings counts. */
@Composable
private fun CameraPage(modifier: Modifier, onDecoded: (String) -> Unit) {
    val ctx = LocalContext.current
    fun allowed() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(allowed()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; denied = !it }
    LifecycleResumeEffect(Unit) { granted = allowed(); onPauseOrDispose {} }
    LaunchedEffect(Unit) {
        if (!granted && !asked) { asked = true; ask.launch(Manifest.permission.CAMERA) }
    }
    when {
        granted -> CameraView(modifier, onDecoded)
        denied -> Column(modifier.padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text(stringResource(R.string.scan_camera_needed), textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 16.dp))
            Button(onClick = {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))) }
            }) { Text(stringResource(R.string.scan_open_app_settings)) }
        }
    }
}

@Composable
private fun CameraView(modifier: Modifier, onDecoded: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val decoded by rememberUpdatedState(onDecoded)
    val view = remember { PreviewView(ctx) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(owner) {
        val main = ContextCompat.getMainExecutor(ctx)
        val worker = Executors.newSingleThreadExecutor()
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // The default, 640×480, misses small codes.
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                ).build(),
            )
            .build()
        var luma = ByteArray(0)
        analysis.setAnalyzer(worker) { image ->
            if (luma.size != image.width * image.height) luma = ByteArray(image.width * image.height)
            val text = image.use { copyLuma(it, luma); QrDecoder.decode(luma, it.width, it.height) }
            if (text != null) {
                analysis.clearAnalyzer()
                main.execute { decoded(text) }
            }
        }
        var gone = false
        var bound: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            if (gone) return@addListener
            runCatching {
                val provider = future.get()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                bound = provider
            }.onFailure { failed = true }
        }, main)
        onDispose {
            gone = true
            bound?.unbind(preview, analysis)
            analysis.clearAnalyzer()
            worker.shutdown()
        }
    }
    if (failed) {
        Box(modifier.padding(24.dp), Alignment.Center) { Text(stringResource(R.string.scan_camera_failed), textAlign = TextAlign.Center) }
        return
    }
    Box(modifier.background(Color.Black), Alignment.Center) {
        AndroidView({ view }, Modifier.fillMaxSize())
        Box(Modifier.size(248.dp).border(2.dp, Color.White, RoundedCornerShape(16.dp)))
        Text(
            stringResource(R.string.scan_hint), color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** Packs the frame's brightness plane into [out]; the camera may pad each row. */
private fun copyLuma(image: ImageProxy, out: ByteArray) {
    val plane = image.planes[0]
    val buffer = plane.buffer
    for (row in 0 until image.height) {
        buffer.position(row * plane.rowStride)
        buffer.get(out, row * image.width, image.width)
    }
}
