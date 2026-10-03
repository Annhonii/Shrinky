package com.davexh.shrinky.engine

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

/**
 * The photo is shown whole (fit) in the view, with a centred frame on top (aspect = width:height).
 * The frame is resized by dragging its corners; the photo is moved with one finger and zoomed with two.
 * [sourceRect] turns whatever is inside the frame back into source-pixel coordinates.
 */
class CropState {
    var image by mutableStateOf<Bitmap?>(null)
    var view by mutableStateOf(IntSize.Zero)
    var aspect by mutableFloatStateOf(0f)
    /** Photo zoom on top of "fit". Never below [minZoom], which keeps the photo covering the frame. */
    var zoom by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    /** Frame width as a fraction of the biggest frame that fits the view. */
    var frameFrac by mutableFloatStateOf(START_FRAC)

    private val maxFrameW: Float
        get() {
            if (aspect <= 0f || view.width == 0 || view.height == 0) return 0f
            return minOf(view.width * 0.92f, view.height * 0.92f * aspect)
        }

    /** Frame size in view pixels, centered in the view. Zero when no valid size was entered. */
    val frame: Size
        get() {
            val mw = maxFrameW
            if (mw <= 0f) return Size.Zero
            val w = mw * frameFrac
            return Size(w, w / aspect)
        }

    /** View pixels per source pixel when the whole photo just fits. */
    private val fit: Float
        get() {
            val b = image ?: return 1f
            if (view.width == 0 || view.height == 0) return 1f
            return minOf(view.width.toFloat() / b.width, view.height.toFloat() / b.height)
        }

    private val minZoom: Float
        get() {
            val b = image ?: return 1f
            val f = frame
            if (f == Size.Zero) return 1f
            val base = fit
            return maxOf(1f, f.width / (b.width * base), f.height / (b.height * base))
        }

    val effectiveZoom: Float get() = maxOf(zoom, minZoom)

    /** View pixels per source pixel. */
    val scale: Float get() = fit * (if (frame == Size.Zero) 1f else effectiveZoom)

    private fun clampOffset(o: Offset): Offset {
        val b = image ?: return Offset.Zero
        val f = frame
        if (f == Size.Zero) return Offset.Zero
        val s = scale
        val mx = ((b.width * s - f.width) / 2f).coerceAtLeast(0f)
        val my = ((b.height * s - f.height) / 2f).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    /** One finger pans, two fingers zoom around [centroid]. */
    fun transform(centroid: Offset, pan: Offset, zoomChange: Float) {
        if (frame == Size.Zero) return
        val old = effectiveZoom
        val next = (old * zoomChange).coerceIn(minZoom, maxOf(minZoom, MAX_ZOOM))
        val r = next / old
        val c = centroid - Offset(view.width / 2f, view.height / 2f)
        zoom = next
        offset = clampOffset(c - (c - offset) * r + pan)
    }

    // ---- frame resizing ----

    /** If [p] is within [radius] of a frame corner, returns how far the finger is from that exact corner. */
    fun cornerGrab(p: Offset, radius: Float): Offset? {
        val f = frame
        if (f == Size.Zero) return null
        val cx = view.width / 2f
        val cy = view.height / 2f
        var best: Offset? = null
        var bestD = radius
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
            val corner = Offset(cx + sx * f.width / 2f, cy + sy * f.height / 2f)
            val d = (p - corner).getDistance()
            if (d <= bestD) { bestD = d; best = p - corner }
        }
        return best
    }

    /** Resizes the frame (keeping its aspect, centred) so a corner follows [p]. */
    fun resizeTo(p: Offset) {
        val mw = maxFrameW
        if (mw <= 0f) return
        val dx = abs(p.x - view.width / 2f)
        val dy = abs(p.y - view.height / 2f)
        val w = (2f * dx + 2f * dy * aspect) / 2f
        zoom = effectiveZoom                      // lock in the current zoom before the frame changes
        frameFrac = (w / mw).coerceIn(MIN_FRAC, 1f)
        zoom = effectiveZoom                      // a bigger frame may need the photo zoomed to cover it
        offset = clampOffset(offset)
    }

    fun reset() { zoom = 1f; offset = Offset.Zero; frameFrac = START_FRAC }

    fun setAspect(w: Int, h: Int) {
        val a = if (w > 0 && h > 0) w.toFloat() / h else 0f
        if (a != aspect) { aspect = a; reset() }
    }

    fun sourceRect(): RectF? {
        val b = image ?: return null
        val f = frame
        if (f == Size.Zero) return null
        val s = scale
        val w = f.width / s
        val h = f.height / s
        val o = clampOffset(offset)
        val left = ((b.width * s / 2f - o.x - f.width / 2f) / s).coerceIn(0f, (b.width - w).coerceAtLeast(0f))
        val top = ((b.height * s / 2f - o.y - f.height / 2f) / s).coerceIn(0f, (b.height - h).coerceAtLeast(0f))
        return RectF(left, top, left + w, top + h)
    }

    private companion object {
        const val START_FRAC = 0.85f
        const val MIN_FRAC = 0.2f
        const val MAX_ZOOM = 8f
    }
}
