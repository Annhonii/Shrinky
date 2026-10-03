package com.davexh.shrinky.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.davexh.shrinky.engine.CropState
import kotlin.math.roundToInt

/**
 * The photo under a frame. Drag a corner to make the frame bigger or smaller (its ratio stays fixed),
 * drag the photo to move it, pinch to zoom. Whatever is inside the frame is what gets cropped.
 */
@Composable
fun CropEditor(state: CropState) {
    val bmp = state.image ?: return
    val p = LocalPalette.current
    val img = remember(bmp) { bmp.asImageBitmap() }

    Canvas(
        Modifier.fillMaxWidth().aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(p.field)
            .onSizeChanged { state.view = it }
            .pointerInput(bmp) {
                val grabRadius = 40.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val grab = state.cornerGrab(down.position, grabRadius)
                    if (grab != null) {
                        // Corner handle: resize the frame.
                        drag(down.id) { change ->
                            change.consume()
                            state.resizeTo(change.position - grab)
                        }
                    } else {
                        // Anywhere else: move and zoom the photo.
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f || pan != Offset.Zero) {
                                state.transform(event.calculateCentroid(useCurrent = false), pan, zoom)
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
            },
    ) {
        val s = state.scale
        val f = state.frame
        val iw = bmp.width * s
        val ih = bmp.height * s
        val o = if (f == Size.Zero) Offset.Zero else state.offset
        val topLeft = Offset(size.width / 2f - iw / 2f, size.height / 2f - ih / 2f) + o
        drawImage(
            img,
            dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
            dstSize = IntSize(iw.roundToInt().coerceAtLeast(1), ih.roundToInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.Medium,
        )
        if (f != Size.Zero) {
            val fl = (size.width - f.width) / 2f
            val ft = (size.height - f.height) / 2f
            val scrim = Color(0xB3000000)
            drawRect(scrim, Offset.Zero, Size(size.width, ft))
            drawRect(scrim, Offset(0f, ft + f.height), Size(size.width, size.height - ft - f.height))
            drawRect(scrim, Offset(0f, ft), Size(fl, f.height))
            drawRect(scrim, Offset(fl + f.width, ft), Size(size.width - fl - f.width, f.height))
            val line = Color(0x66FFFFFF)
            val hair = 1.dp.toPx()
            for (i in 1..2) {
                val x = fl + f.width * i / 3f
                val y = ft + f.height * i / 3f
                drawLine(line, Offset(x, ft), Offset(x, ft + f.height), hair)
                drawLine(line, Offset(fl, y), Offset(fl + f.width, y), hair)
            }
            drawRect(Color.White, Offset(fl, ft), Size(f.width, f.height), style = Stroke(1.5.dp.toPx()))

            // Corner handles: thick L shapes, so it's clear they can be dragged.
            val arm = minOf(22.dp.toPx(), f.width / 3f, f.height / 3f)
            val thick = 4.dp.toPx()
            val r = fl + f.width
            val b = ft + f.height
            fun corner(cx: Float, cy: Float, dx: Float, dy: Float) {
                drawLine(Color.White, Offset(cx - thick / 2f * dx, cy), Offset(cx + arm * dx, cy), thick, StrokeCap.Round)
                drawLine(Color.White, Offset(cx, cy - thick / 2f * dy), Offset(cx, cy + arm * dy), thick, StrokeCap.Round)
            }
            corner(fl, ft, 1f, 1f)
            corner(r, ft, -1f, 1f)
            corner(fl, b, 1f, -1f)
            corner(r, b, -1f, -1f)
        }
    }
}
