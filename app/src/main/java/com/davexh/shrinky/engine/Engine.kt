package com.davexh.shrinky.engine

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlin.math.roundToInt

enum class Kind { IMAGE, PDF, VIDEO }

enum class OutFormat(val label: String, val mime: String, val ext: String) {
    JPG("JPG", "image/jpeg", "jpg"),
    PNG("PNG", "image/png", "png"),
    WEBP("WebP", "image/webp", "webp"),
}

class Shrunk(
    val bytes: ByteArray,
    val mime: String,
    val ext: String,
    val hitTarget: Boolean,
    val unchanged: Boolean = false,
    val origW: Int = 0, val origH: Int = 0,
    val newW: Int = 0, val newH: Int = 0,
    val pages: Int = 0, val dpi: Int = 0,
    val before: Bitmap? = null,
    val after: Bitmap? = null,
    val beforeZoom: Bitmap? = null,
    val afterZoom: Bitmap? = null,
    /** Videos are too big to hold in memory: the result lives in a cache file and [bytes] stays empty. */
    val file: java.io.File? = null,
    val codecInfo: String? = null,   // e.g. "H.265, AAC"
    val notice: String? = null,      // e.g. "Your phone can't encode AV1, so H.264 was used."
) {
    val size: Long get() = file?.length() ?: bytes.size.toLong()
}

object Engine {
    private const val MIN_Q = 40
    private const val MAX_Q = 95
    private const val MAX_SIDE = 4096

    // (render scale, JPEG quality), biggest file first. Binary-searched for the first that fits.
    private val PDF_LADDER = listOf(
        2.0f to 85, 1.6f to 75, 1.3f to 65, 1.0f to 60, 1.0f to 45,
        0.8f to 40, 0.6f to 35, 0.45f to 30, 0.3f to 25,
    )

    fun compress(
        cr: ContentResolver, uri: Uri, kind: Kind, mime: String,
        originalSize: Long, target: Long, format: OutFormat,
    ): Shrunk {
        val sameFormat = kind == Kind.PDF || mime == format.mime ||
            (format == OutFormat.JPG && mime == "image/jpg")
        if (sameFormat && originalSize in 1..target) {
            val bytes = cr.openInputStream(uri)!!.use { it.readBytes() }
            return Shrunk(bytes, mime, if (kind == Kind.PDF) "pdf" else format.ext, hitTarget = true, unchanged = true)
        }
        return when (kind) {
            Kind.IMAGE -> compressImage(cr, uri, target, format)
            Kind.PDF -> compressPdf(cr, uri, target)
            Kind.VIDEO -> error("Videos are compressed by VideoEngine.")
        }
    }

    // ---------- Images ----------

    private fun compressImage(cr: ContentResolver, uri: Uri, target: Long, fmt: OutFormat): Shrunk {
        val d = Images.decode(cr, uri, MAX_SIDE)
        val base = if (fmt == OutFormat.JPG) Images.flatten(d.bitmap) else d.bitmap

        fun done(bytes: ByteArray, bmp: Bitmap, hit: Boolean): Shrunk {
            val decodedAfter = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val zoom = decodedAfter?.let { Images.zoomPair(base, it) }
            return Shrunk(
                bytes, fmt.mime, fmt.ext, hit,
                origW = d.origW, origH = d.origH, newW = bmp.width, newH = bmp.height,
                before = Images.thumb(base),
                after = decodedAfter?.let { Images.thumb(it) },
                beforeZoom = zoom?.first,
                afterZoom = zoom?.second,
            )
        }

        if (fmt == OutFormat.PNG) {
            // PNG is lossless: the only lever is resolution, so binary-search the scale.
            val full = Images.encode(base, fmt, 100)
            if (full.size <= target) return done(full, base, true)
            var lo = 0.04f; var hi = 1f
            var bestBytes: ByteArray? = null; var bestBmp: Bitmap? = null
            repeat(9) {
                val mid = (lo + hi) / 2
                val b = Images.scaled(base, mid)
                val bytes = Images.encode(b, fmt, 100)
                if (bytes.size <= target) { bestBytes = bytes; bestBmp = b; lo = mid } else hi = mid
            }
            val bb = bestBytes
            if (bb != null) return done(bb, bestBmp!!, true)
            val b = Images.scaled(base, 0.04f)
            val bytes = Images.encode(b, fmt, 100)
            return done(bytes, b, bytes.size <= target)
        }

        // JPG / WebP: search quality first, shrink resolution only if quality alone can't reach the target.
        var scale = 1f
        var smallest: ByteArray? = null
        var smallestBmp: Bitmap = base
        for (attempt in 0 until 16) {
            val bmp = Images.scaled(base, scale)
            val atMin = Images.encode(bmp, fmt, MIN_Q)
            if (atMin.size <= target) {
                var lo = MIN_Q; var hi = MAX_Q; var best = atMin
                while (lo < hi) {
                    val mid = (lo + hi + 1) / 2
                    val b = Images.encode(bmp, fmt, mid)
                    if (b.size <= target) { lo = mid; best = b } else hi = mid - 1
                }
                return done(best, bmp, true)
            }
            smallest = atMin
            smallestBmp = bmp
            scale *= 0.85f
        }
        return done(smallest!!, smallestBmp, false)
    }

    // ---------- PDF ----------

    private fun compressPdf(cr: ContentResolver, uri: Uri, target: Long): Shrunk {
        val pfd = cr.openFileDescriptor(uri, "r") ?: error("Can't open this PDF.")
        pfd.use {
            PdfRenderer(it).use { renderer ->
                val cache = HashMap<Int, ByteArray>()
                fun build(step: Int): ByteArray = cache.getOrPut(step) {
                    val (scale, q) = PDF_LADDER[step]
                    val pages = (0 until renderer.pageCount).map { i ->
                        renderer.openPage(i).use { p ->
                            val w = (p.width * scale).roundToInt().coerceAtLeast(1)
                            val h = (p.height * scale).roundToInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val jpg = Images.encode(bmp, OutFormat.JPG, q)
                            bmp.recycle()
                            JpegPage(jpg, w, h, p.width.toFloat(), p.height.toFloat())
                        }
                    }
                    PdfWriter.write(pages)
                }

                var lo = 0; var hi = PDF_LADDER.lastIndex
                var best: ByteArray? = null; var bestStep = PDF_LADDER.lastIndex
                while (lo <= hi) {
                    val mid = (lo + hi) / 2
                    val out = build(mid)
                    if (out.size <= target) { best = out; bestStep = mid; hi = mid - 1 } else lo = mid + 1
                }
                return Shrunk(
                    best ?: build(PDF_LADDER.lastIndex), "application/pdf", "pdf", best != null,
                    pages = renderer.pageCount,
                    dpi = (72 * PDF_LADDER[bestStep].first).roundToInt(),
                )
            }
        }
    }
}
