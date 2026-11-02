package com.lanqi.weiassist.shell

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.ByteArrayOutputStream

/**
 * Virtual displays and input, inside the shell helper (shell may create displays that other apps launch
 * on, and inject input to any display). One display per phone session; the app asks for them over Binder.
 *
 * Each display renders into an ImageReader owned here. Only the newest frame is kept, plus frames sent to
 * watchers that the app still shows (at most [MAX_IN_FLIGHT] each); a frame's buffer is reused once
 * nobody holds it. Screenshots come from the newest frame; previews get the GPU buffers themselves.
 */
// Runs as the shell user, where hidden APIs and display flags are available (as scrcpy uses them).
@android.annotation.SuppressLint("PrivateApi", "DiscouragedPrivateApi", "WrongConstant", "BlockedPrivateApi", "SoonBlockedPrivateApi")
internal class DisplayHost(
    /** A Context the framework accepts from the shell user; made on the main thread (see ShellServer.main). */
    private val context: Context,
) {
    private val thread = HandlerThread("displays").apply { start() }
    private val handler = Handler(thread.looper)
    /** Displays by id. All frame state below is touched on [handler]'s thread only. */
    private val displays = java.util.concurrent.ConcurrentHashMap<Int, Entry>()

    /** One frame from a display, held by "latest" and by every watcher it was sent to; closed when nobody holds it. */
    private class Frame(val image: Image, val seq: Int) {
        var refs = 1
        fun unref() { if (--refs == 0) image.close() }
    }

    /** Someone watching a display: frames at most every [intervalMs], at most [MAX_IN_FLIGHT] not yet done. */
    private inner class Watch(val display: Int, val listener: IFrameListener, var intervalMs: Long) : IBinder.DeathRecipient {
        val sent = ArrayDeque<Frame>()
        var lastSeq = -1
        var nextAt = 0L
        var scheduled = false
        override fun binderDied() { handler.post { stopWatch(this) } }
    }

    private inner class Entry(val display: VirtualDisplay, val reader: ImageReader) {
        var latest: Frame? = null
        var seq = 0
        var active = true
        val watches = HashMap<IBinder, Watch>()
        val lock = Any() // guards latest for screenshots taken on binder threads
    }

    fun create(name: String, width: Int, height: Int, dpi: Int): Int {
        // CPU-readable for screenshots, GPU-sampled for previews (the app draws the buffer as is).
        val reader = if (Build.VERSION.SDK_INT >= 29) ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES,
            HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        else ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES) // no live previews before Android 10
        val dm = DisplayManager::class.java.getDeclaredConstructor(Context::class.java).newInstance(context)
        // Public so other apps may be launched on it, its own content only (no mirroring), touchable, and its
        // apps closed when it goes.
        val base = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            FLAG_SUPPORTS_TOUCH or FLAG_DESTROY_CONTENT_ON_REMOVAL
        // Trusted where shell may make it so (ADD_TRUSTED_DISPLAY, Android 13+): on an untrusted display Android
        // refuses an app's own launches (unless the activity allows embedding, which few do) and puts them on the
        // phone's screen instead (ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay). With a focus of its
        // own (14+), so taps on it never take focus from the user's screen; and no vibration for our taps.
        val trusted = when {
            Build.VERSION.SDK_INT >= 34 -> base or FLAG_TRUSTED or FLAG_TOUCH_FEEDBACK_DISABLED or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
            Build.VERSION.SDK_INT >= 33 -> base or FLAG_TRUSTED or FLAG_TOUCH_FEEDBACK_DISABLED
            else -> base // shell can't make trusted displays on Android 12
        }
        val vd = (if (trusted != base) runCatching { dm.createVirtualDisplay("weiassist-$name", width, height, dpi, reader.surface, trusted) }
            .onFailure { android.util.Log.w("WeiAssistShell", "trusted display refused, using an untrusted one: $it") }.getOrNull() else null)
            ?: dm.createVirtualDisplay("weiassist-$name", width, height, dpi, reader.surface, base)
            ?: error("createVirtualDisplay returned null")
        val e = Entry(vd, reader)
        val id = vd.display.displayId
        android.util.Log.i("WeiAssistShell", "display $id: flags ${Integer.toHexString(vd.display.flags)}")
        reader.setOnImageAvailableListener({ r -> onImage(id, e, r) }, handler)
        displays[id] = e
        return id
    }

    private fun onImage(id: Int, e: Entry, r: ImageReader) {
        // All buffers held (by watchers that are slow to finish): skip; the newest frame comes next time.
        val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        val f = Frame(img, ++e.seq)
        synchronized(e.lock) { e.latest?.unref(); e.latest = f }
        e.watches.values.toList().forEach(::schedule)
    }

    fun release(id: Int) {
        val e = displays.remove(id) ?: return
        runOnHandler {
            e.watches.values.toList().forEach(::stopWatch)
            e.display.release()
            synchronized(e.lock) { e.latest?.unref(); e.latest = null }
            e.reader.close()
        }
    }

    fun releaseAll() = displays.keys.toList().forEach(::release)

    fun has(id: Int) = displays.containsKey(id)

    fun screenshot(id: Int, maxWidth: Int, quality: Int): ByteArray {
        val e = displays[id] ?: return ByteArray(0)
        val full = synchronized(e.lock) {
            val img = e.latest?.image ?: return ByteArray(0)
            val plane = img.planes[0]
            val rowPixels = plane.rowStride / plane.pixelStride
            val bmp = Bitmap.createBitmap(rowPixels, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer.rewind())
            if (rowPixels != img.width) Bitmap.createBitmap(bmp, 0, 0, img.width, img.height).also { bmp.recycle() } else bmp
        }
        val scale = minOf(1f, maxWidth.toFloat() / full.width)
        val out = if (scale < 1f) Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true).also { full.recycle() } else full
        return ByteArrayOutputStream().use { b -> out.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 95), b); out.recycle(); b.toByteArray() }
    }

    // ---- rendering on/off -----------------------------------------------------------------------------

    /** Off disconnects the display's output: its apps stop drawing (and costing power); the last frame stays. */
    fun setActive(id: Int, active: Boolean) = runOnHandler {
        val e = displays[id] ?: return@runOnHandler
        if (e.active == active) return@runOnHandler
        e.active = active
        e.display.surface = if (active) e.reader.surface else null
    }

    // ---- live previews --------------------------------------------------------------------------------

    fun watch(id: Int, listener: IFrameListener, maxFps: Int) = runOnHandler {
        if (Build.VERSION.SDK_INT < 29) return@runOnHandler // buffers aren't GPU-sampleable there
        val e = displays[id] ?: return@runOnHandler
        val key = listener.asBinder()
        val interval = 1000L / maxFps.coerceIn(1, 30)
        val w = e.watches[key] ?: Watch(id, listener, interval).also { w ->
            runCatching { key.linkToDeath(w, 0) }
            e.watches[key] = w
        }
        w.intervalMs = interval
        schedule(w) // the latest frame at once
    }

    fun unwatch(id: Int, listener: IFrameListener) = runOnHandler {
        displays[id]?.watches?.get(listener.asBinder())?.let(::stopWatch)
    }

    fun frameDone(id: Int, listener: IFrameListener, seq: Int) = runOnHandler {
        val w = displays[id]?.watches?.get(listener.asBinder()) ?: return@runOnHandler
        while (w.sent.isNotEmpty() && w.sent.first().seq <= seq) w.sent.removeFirst().unref()
        schedule(w) // there may be room for a waiting frame now
    }

    private fun stopWatch(w: Watch) {
        val e = displays[w.display]
        e?.watches?.remove(w.listener.asBinder())
        runCatching { w.listener.asBinder().unlinkToDeath(w, 0) }
        while (w.sent.isNotEmpty()) w.sent.removeFirst().unref()
    }

    /** Send [w] the latest frame when it is new, its interval has passed and it has room; else try again later. */
    @android.annotation.TargetApi(29) // only watches exist, and they need Android 10 (see watch)
    private fun schedule(w: Watch) {
        val e = displays[w.display] ?: return
        if (w.scheduled || e.watches[w.listener.asBinder()] !== w) return
        val f = e.latest ?: return
        if (f.seq == w.lastSeq || w.sent.size >= MAX_IN_FLIGHT) return // nothing new, or the app is behind (frameDone reschedules)
        val wait = w.nextAt - SystemClock.uptimeMillis()
        if (wait > 0) {
            w.scheduled = true
            handler.postDelayed({ w.scheduled = false; schedule(w) }, wait)
            return
        }
        val buffer = f.image.hardwareBuffer ?: return
        f.refs++
        w.sent.addLast(f)
        w.lastSeq = f.seq
        w.nextAt = SystemClock.uptimeMillis() + w.intervalMs
        try {
            w.listener.onFrame(w.display, buffer, f.seq)
        } catch (e: RemoteException) {
            stopWatch(w)
        } finally {
            buffer.close() // our handle only; the app has its own
        }
    }

    private fun runOnHandler(f: () -> Unit) { if (Looper.myLooper() == thread.looper) f() else handler.post(f) }

    // ---- input ----------------------------------------------------------------------------------------

    private val inputManager by lazy {
        Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!
    }
    private val injectMethod by lazy {
        inputManager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }
    private val setDisplayId by lazy { InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) }

    private fun inject(event: InputEvent, display: Int): Boolean {
        setDisplayId.invoke(event, display)
        return injectMethod.invoke(inputManager, event, INJECT_WAIT_FOR_FINISH) as Boolean
    }

    private fun touch(display: Int, action: Int, down: Long, x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(down, now, action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        return try { inject(e, display) } finally { e.recycle() }
    }

    fun tap(display: Int, x: Float, y: Float, durationMs: Long): Boolean {
        val down = SystemClock.uptimeMillis()
        if (!touch(display, MotionEvent.ACTION_DOWN, down, x, y)) return false
        SystemClock.sleep(durationMs.coerceIn(40, 3_000))
        return touch(display, MotionEvent.ACTION_UP, down, x, y)
    }

    fun swipe(display: Int, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val down = SystemClock.uptimeMillis()
        if (!touch(display, MotionEvent.ACTION_DOWN, down, x1, y1)) return false
        val steps = (durationMs.coerceIn(100, 3_000) / 16).toInt().coerceAtLeast(4)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            SystemClock.sleep(16)
            touch(display, MotionEvent.ACTION_MOVE, down, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t)
        }
        return touch(display, MotionEvent.ACTION_UP, down, x2, y2)
    }

    fun key(display: Int, keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
        return inject(down, display) && inject(up, display)
    }

    companion object {
        /** The system context as the shell package (as scrcpy does). Must run on a Looper thread. */
        fun shellContext(): Context {
            val at = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null)
            val sys = at.javaClass.getMethod("getSystemContext").invoke(at) as Context
            return object : ContextWrapper(sys) {
                override fun getPackageName() = ShellServer.SHELL_PACKAGE
                override fun getOpPackageName() = ShellServer.SHELL_PACKAGE
            }
        }

        // Hidden DisplayManager flags (stable since Android 10): touch input, and close the apps on removal.
        /** Frames a watcher may hold (shown, previous, in flight); the reader's buffers: those, the latest, and the producer's. */
        private const val MAX_IN_FLIGHT = 3
        private const val MAX_IMAGES = 6
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val FLAG_OWN_FOCUS = 1 shl 14
        private const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16
        /** InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH */
        private const val INJECT_WAIT_FOR_FINISH = 2
    }
}
