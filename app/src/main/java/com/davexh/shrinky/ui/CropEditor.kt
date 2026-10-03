package com.davexh.shrinky.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.davexh.shrinky.engine.CropState
import kotlin.math.roundToInt

/** Fixed frame over a photo you drag (move) and pinch (zoom). Whatever is inside the frame is what gets cropped. */
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
                detectTransformGestures { centroid, pan, zoom, _ -> state.transform(centroid, pan, zoom) }
            },
    ) {
        val s = state.scale
        val f = state.frame
        val iw = bmp.width * s
        val ih = bmp.height * s
        val topLeft = Offset(size.width / 2f - iw / 2f, size.height / 2f - ih / 2f) + state.offset
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
            drawRect(Color.White, Offset(fl, ft), Size(f.width, f.height), style = Stroke(2.dp.toPx()))
        }
    }
}
