package com.lanqi.weiassist.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.lanqi.weiassist.shell.IFrameListener
import com.lanqi.weiassist.shell.ShellBackend
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Live pictures of the sessions' virtual displays, for the screens that show them.
 *
 * - **No copies:** the helper sends the display's own GPU buffers; each becomes a hardware Bitmap that
 *   Compose draws scaled on the GPU. The two newest are kept (shown, and maybe still being drawn); older
 *   ones go back to the helper at once ([IShellService.frameDone]).
 * - **Adaptive rate:** the fastest rate any viewer of a display asks for ([Purpose]), lowered in battery
 *   saver and when the phone runs warm, never more than the display changes (a still screen sends nothing).
 * - **Nothing when unseen:** a display is watched only while some flow from [frames] is collected; the UI
 *   collects only while it is on screen and the app is visible (collectAsStateWithLifecycle).
 *
 * Main thread, except Binder callbacks (posted to it).
 */
class PreviewHub(context: Context, private val shell: ShellBackend) {
    /** What a preview is for: the rate it would like at best. */
    enum class Purpose(val fps: Int) { Page(12), Thumbnail(3) }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    /** Binder calls to the helper, off the main thread, in order. */
    private val calls = Executors.newSingleThreadExecutor { r -> Thread(r, "previews").apply { isDaemon = true } }
    private val power = app.getSystemService(PowerManager::class.java)

    private class Viewer(val purpose: Purpose)

    private inner class Feed(val display: Int) {
        val frame = MutableStateFlow<Bitmap?>(null)
        val viewers = HashSet<Viewer>()
        /** Frames we hold: (seq, bitmap), oldest first. */
        val held = ArrayDeque<Pair<Int, Bitmap>>()
        var fps = 0
    }

    private val feeds = HashMap<Int, Feed>()

    private val listener = object : IFrameListener.Stub() {
        override fun onFrame(displayId: Int, buffer: HardwareBuffer, seq: Int) {
            // The Bitmap holds its own reference to the buffer: ours can be closed now.
            // The helper sends frames on Android 10+ only (GPU-sampleable buffers).
            val bmp = if (Build.VERSION.SDK_INT >= 29) runCatching { Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)) }.getOrNull() else null
            buffer.close()
            main.post { onFrame(displayId, seq, bmp) }
        }
    }

    init {
        // Rates follow battery saver and the phone's temperature.
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = updateRates()
        }, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        if (Build.VERSION.SDK_INT >= 29) power.addThermalStatusListener(app.mainExecutor) { updateRates() }
    }

    /** The display's live picture while collected (null until the first frame). */
    fun frames(display: Int, purpose: Purpose): Flow<Bitmap?> = callbackFlow {
        val viewer = Viewer(purpose)
        val feed = feeds.getOrPut(display) { Feed(display) }
        feed.viewers += viewer
        updateRate(feed)
        val job = launch { feed.frame.collect { send(it) } }
        awaitClose {
            job.cancel()
            main.post {
                feed.viewers -= viewer
                if (feed.viewers.isEmpty()) stop(feed) else updateRate(feed)
            }
        }
    }

    private fun onFrame(display: Int, seq: Int, bmp: Bitmap?) {
        val feed = feeds[display]
        if (feed == null || feed.viewers.isEmpty() || bmp == null) {
            bmp?.recycle()
            call { it.frameDone(display, listener, seq) }
            return
        }
        feed.held.addLast(seq to bmp)
        feed.frame.value = bmp
        // Keep the shown frame and the one before it (it may still be in the frame being drawn).
        var done = -1
        while (feed.held.size > 2) feed.held.removeFirst().let { (s, b) -> b.recycle(); done = s }
        if (done >= 0) call { it.frameDone(display, listener, done) }
    }

    private fun stop(feed: Feed) {
        feeds.remove(feed.display)
        call { it.unwatch(feed.display, listener) }
        feed.frame.value = null
        // The helper reclaims what it sent (unwatch); our wrappers can go.
        feed.held.forEach { it.second.recycle() }
        feed.held.clear()
    }

    private fun updateRates() = feeds.values.forEach(::updateRate)

    private fun updateRate(feed: Feed) {
        val want = feed.viewers.maxOfOrNull { it.purpose.fps } ?: return
        val fps = adapt(want)
        if (fps == feed.fps) return
        feed.fps = fps
        Log.d(TAG, "display ${feed.display}: $fps fps")
        call { it.watch(feed.display, listener, fps) }
    }

    /** Lower the rate when the phone saves battery or runs warm (heat makes Android throttle everything). */
    private fun adapt(fps: Int): Int {
        var f = fps.toFloat()
        if (power.isPowerSaveMode) f /= 2
        if (Build.VERSION.SDK_INT >= 29) when {
            power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> f = 1f
            power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> f /= 2
        }
        return f.toInt().coerceAtLeast(1)
    }

    private fun call(f: (com.lanqi.weiassist.shell.IShellService) -> Unit) {
        val s = shell.service() ?: return
        calls.execute { runCatching { f(s) }.onFailure { Log.w(TAG, "preview call failed: $it") } }
    }

    /** The helper restarted: watched displays are gone; viewers see their sessions get new ones. */
    fun reset() {
        feeds.values.toList().forEach { it.held.forEach { h -> h.second.recycle() }; it.held.clear(); it.frame.value = null; it.fps = 0 }
    }

    private companion object { const val TAG = "Previews" }
}
