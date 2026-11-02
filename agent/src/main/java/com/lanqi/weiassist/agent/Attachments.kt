package com.lanqi.weiassist.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** A file the user attached to a message, kept in the app's own storage at [path]. */
data class Attachment(val name: String, val mime: String, val path: String, val size: Long) {
    val kind: Kind get() = when {
        mime.startsWith("image/") -> Kind.Image
        mime == "application/pdf" -> Kind.Pdf
        else -> Kind.Text
    }

    enum class Kind { Image, Pdf, Text }

    fun toJson(): JSONObject = JSONObject().put("name", name).put("mime", mime).put("path", path).put("size", size)

    companion object {
        fun fromJson(o: JSONObject) = Attachment(o.getString("name"), o.getString("mime"), o.getString("path"), o.optLong("size"))
        fun listFrom(a: JSONArray?): List<Attachment> = if (a == null) emptyList() else (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
    }
}

/**
 * Attachments: picking (copied into a staging folder, images scaled down), keeping (moved into the session's
 * folder when sent), and turning into what the model reads (Pi content blocks): images as images, text files
 * inline, PDFs as page images. Other binary files are refused when picked.
 */
object Attachments {
    /** Longest image side sent to the model: enough to read a screenshot or a document photo. */
    private const val IMAGE_MAX_SIDE = 1600
    private const val PDF_MAX_PAGES = 8
    private const val PDF_PAGE_WIDTH = 1240
    /** Text files are inlined up to this many characters. */
    private const val TEXT_MAX_CHARS = 100_000
    private const val PICK_MAX_BYTES = 30L * 1024 * 1024

    class Unsupported(val name: String) : Exception("unsupported file: $name")
    class TooLarge(val name: String) : Exception("too large: $name")

    private fun staging(context: Context) = File(context.cacheDir, "attachments").apply { mkdirs() }

    /** Copy a picked file into the staging folder, ready to send. Throws [Unsupported] / [TooLarge]. */
    suspend fun import(context: Context, uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        var name = "file"
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > PICK_MAX_BYTES) throw TooLarge(name)
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = cr.getType(uri)?.takeIf { it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val dir = staging(context)
        val id = UUID.randomUUID().toString().take(8)
        when {
            mime.startsWith("image/") -> {
                val bmp = decodeScaled(context, uri) ?: throw Unsupported(name)
                val out = File(dir, "$id.jpg")
                out.outputStream().use { flatten(bmp).compress(Bitmap.CompressFormat.JPEG, 85, it) }
                Attachment(name, "image/jpeg", out.path, out.length())
            }
            mime == "application/pdf" -> {
                val out = File(dir, "$id.pdf")
                copy(context, uri, out, name)
                Attachment(name, mime, out.path, out.length())
            }
            else -> {
                val out = File(dir, "$id.${ext.ifEmpty { "txt" }}")
                copy(context, uri, out, name)
                // Text if it says so or reads as text (code files often come as octet-stream).
                if (!looksLikeText(out)) { out.delete(); throw Unsupported(name) }
                Attachment(name, if (mime.startsWith("text/")) mime else "text/plain", out.path, out.length())
            }
        }
    }

    /** Move staged attachments into [dir] (the session's folder): they live as long as the session. */
    fun keep(list: List<Attachment>, dir: File): List<Attachment> {
        if (list.isEmpty()) return list
        dir.mkdirs()
        return list.map { a ->
            val src = File(a.path)
            if (src.parentFile == dir || !src.exists()) return@map a
            val dst = File(dir, src.name)
            if (!src.renameTo(dst)) { src.copyTo(dst, overwrite = true); src.delete() }
            a.copy(path = dst.path)
        }
    }

    fun discard(list: List<Attachment>) = list.forEach { File(it.path).delete() }

    /** Files picked but never sent before the app's process ended: nothing refers to them any more. */
    fun clearStaging(context: Context) { staging(context).listFiles()?.forEach { it.delete() } }

    /**
     * Pi content blocks for these attachments: images as `image` blocks, PDF pages as images (with a note),
     * text files inline in a text block. Each text block starts with "<attachment " (pi-host tells them from
     * the typed text by that).
     */
    suspend fun content(list: List<Attachment>): JSONArray = withContext(Dispatchers.IO) {
        val out = JSONArray()
        for (a in list) {
            val f = File(a.path)
            if (!f.exists()) {
                out.put(text("<attachment name=\"${a.name}\">(the file is no longer on the phone)</attachment>"))
                continue
            }
            when (a.kind) {
                Attachment.Kind.Image -> {
                    out.put(text("<attachment name=\"${a.name}\" type=\"image\"/>"))
                    out.put(image(f.readBytes(), a.mime))
                }
                Attachment.Kind.Pdf -> runCatching { pdfPages(f) }.fold(
                    { (pages, total) ->
                        val shown = if (total > pages.size) "pages 1-${pages.size} of $total, as images" else "$total page(s), as images"
                        out.put(text("<attachment name=\"${a.name}\" type=\"pdf\">$shown</attachment>"))
                        pages.forEach { out.put(image(it, "image/jpeg")) }
                    },
                    { out.put(text("<attachment name=\"${a.name}\" type=\"pdf\">(could not be opened: ${it.message})</attachment>")) },
                )
                Attachment.Kind.Text -> {
                    val all = f.readText()
                    val body = if (all.length > TEXT_MAX_CHARS) all.take(TEXT_MAX_CHARS) + "\n…(cut: ${all.length} characters in all)" else all
                    out.put(text("<attachment name=\"${a.name}\">\n$body\n</attachment>"))
                }
            }
        }
        out
    }

    private fun text(t: String) = JSONObject().put("type", "text").put("text", t)
    private fun image(bytes: ByteArray, mime: String) =
        JSONObject().put("type", "image").put("mimeType", mime).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))

    private fun copy(context: Context, uri: Uri, out: File, name: String) {
        val input = context.contentResolver.openInputStream(uri) ?: throw Unsupported(name)
        input.use { i -> out.outputStream().use { o -> if (i.copyTo(o) > PICK_MAX_BYTES) { out.delete(); throw TooLarge(name) } } }
    }

    private fun decodeScaled(context: Context, uri: Uri): Bitmap? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= IMAGE_MAX_SIDE) sample *= 2
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val side = max(bmp.width, bmp.height)
        if (side <= IMAGE_MAX_SIDE) return bmp
        val k = IMAGE_MAX_SIDE.toFloat() / side
        return Bitmap.createScaledBitmap(bmp, (bmp.width * k).roundToInt(), (bmp.height * k).roundToInt(), true)
    }

    /** JPEG has no transparency: put transparent images on white, not black. */
    private fun flatten(b: Bitmap): Bitmap {
        if (!b.hasAlpha()) return b
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(b, 0f, 0f, null) }
        return out
    }

    /** The first pages as JPEG, and the page count. */
    private fun pdfPages(f: File): Pair<List<ByteArray>, Int> =
        PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            val pages = (0 until minOf(r.pageCount, PDF_MAX_PAGES)).map { i ->
                r.openPage(i).use { p ->
                    val w = PDF_PAGE_WIDTH
                    val h = (p.height.toFloat() * w / p.width).roundToInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
                }
            }
            pages to r.pageCount
        }

    /** No NUL bytes and valid UTF-8 in the first 8 KB. */
    private fun looksLikeText(f: File): Boolean {
        val head = f.inputStream().use { s -> ByteArray(8192).let { b -> val n = s.read(b); if (n <= 0) ByteArray(0) else b.copyOf(n) } }
        if (head.any { it == 0.toByte() }) return false
        val dec = Charsets.UTF_8.newDecoder()
        // A multi-byte character may be cut at the 8 KB boundary: allow a malformed tail.
        return runCatching { dec.decode(java.nio.ByteBuffer.wrap(head)); true }.getOrElse {
            runCatching { dec.reset().decode(java.nio.ByteBuffer.wrap(head, 0, max(0, head.size - 3))); true }.getOrDefault(false)
        }
    }
}
