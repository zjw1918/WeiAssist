package com.lanqi.weiassist.shell

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
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
 * Each display renders into an ImageReader owned here; only the newest frame is kept (the older one is
 * released at once), so a display holds at most three buffers. Screenshots are taken from that frame.
 */
// Runs as the shell user, where hidden APIs and display flags are available (as scrcpy uses them).
@android.annotation.SuppressLint("PrivateApi", "DiscouragedPrivateApi", "WrongConstant", "BlockedPrivateApi", "SoonBlockedPrivateApi")
internal class DisplayHost(
    /** A Context the framework accepts from the shell user; made on the main thread (see ShellServer.main). */
    private val context: Context,
) {
    private val thread = HandlerThread("displays").apply { start() }
    private val handler = Handler(thread.looper)
    private val displays = HashMap<Int, Entry>()

    private class Entry(val display: VirtualDisplay, val reader: ImageReader) {
        @Volatile var latest: Image? = null
        val lock = Any()
    }


    @Synchronized fun create(name: String, width: Int, height: Int, dpi: Int): Int {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val dm = DisplayManager::class.java.getDeclaredConstructor(Context::class.java).newInstance(context)
        // Untrusted (shell can't make trusted ones on Android 12), public so other apps may be launched on it,
        // its own content only (no mirroring), touchable, and its apps closed when it goes.
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            FLAG_SUPPORTS_TOUCH or FLAG_DESTROY_CONTENT_ON_REMOVAL
        val vd = dm.createVirtualDisplay("weiassist-$name", width, height, dpi, reader.surface, flags)
            ?: error("createVirtualDisplay returned null")
        val e = Entry(vd, reader)
        reader.setOnImageAvailableListener({ r ->
            val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            synchronized(e.lock) { e.latest?.close(); e.latest = img }
        }, handler)
        val id = vd.display.displayId
        displays[id] = e
        return id
    }

    @Synchronized fun release(id: Int) {
        val e = displays.remove(id) ?: return
        e.display.release()
        synchronized(e.lock) { e.latest?.close(); e.latest = null }
        e.reader.close()
    }

    @Synchronized fun releaseAll() = displays.keys.toList().forEach(::release)

    fun has(id: Int) = synchronized(this) { id in displays }

    fun screenshot(id: Int, maxWidth: Int, quality: Int): ByteArray {
        val e = synchronized(this) { displays[id] } ?: return ByteArray(0)
        val full = synchronized(e.lock) {
            val img = e.latest ?: return ByteArray(0)
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
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        /** InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH */
        private const val INJECT_WAIT_FOR_FINISH = 2
    }
}
