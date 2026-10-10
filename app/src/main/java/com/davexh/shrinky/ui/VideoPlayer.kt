package com.davexh.shrinky.ui

import android.net.Uri
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

/**
 * A small in-app player built on the system VideoView (no extra library).
 * Tap the picture to play or pause, tap or drag the bar to seek. Pauses when the app leaves the screen.
 */
@Composable
fun VideoPlayer(uri: Uri, aspect: Float, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val viewRef = remember(uri) { arrayOfNulls<VideoView>(1) }
    var ready by remember(uri) { mutableStateOf(false) }
    var playing by remember(uri) { mutableStateOf(true) }
    var progress by remember(uri) { mutableFloatStateOf(0f) }

    DisposableEffect(uri) {
        val lifecycle = (ctx as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) playing = false }
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
            viewRef[0]?.stopPlayback()
            viewRef[0] = null
        }
    }

    // Keeps the VideoView in step with `playing` and moves the bar while it plays.
    LaunchedEffect(playing, ready) {
        val v = viewRef[0] ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        if (playing) v.start() else { v.pause(); if (v.currentPosition == 0) v.seekTo(1) }
        while (playing) {
            val d = v.duration
            progress = if (d > 0) v.currentPosition.toFloat() / d else 0f
            delay(200)
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.4f, 2.5f)).background(Color.Black)) {
            AndroidView(
                factory = { c ->
                    VideoView(c).apply {
                        setVideoURI(uri)
                        setOnPreparedListener { mp -> mp.isLooping = true; ready = true }
                        setOnCompletionListener { playing = false }
                        setOnErrorListener { _, _, _ -> playing = false; true }
                        viewRef[0] = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Sits on top so taps always reach Compose, not the VideoView.
            Box(
                Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) {
                    if (ready) playing = !playing
                },
                contentAlignment = Alignment.Center,
            ) {
                if (!playing && ready) {
                    Canvas(Modifier.size(56.dp)) {
                        drawCircle(Color.Black.copy(alpha = 0.5f))
                        val s = size.minDimension
                        val tri = Path().apply {
                            moveTo(s * 0.40f, s * 0.30f)
                            lineTo(s * 0.72f, s * 0.50f)
                            lineTo(s * 0.40f, s * 0.70f)
                            close()
                        }
                        drawPath(tri, Color.White)
                    }
                }
            }
        }

        fun seek(frac: Float) {
            val v = viewRef[0] ?: return
            val f = frac.coerceIn(0f, 1f)
            if (v.duration > 0) v.seekTo((f * v.duration).toInt())
            progress = f
        }
        Canvas(
            Modifier.fillMaxWidth().height(28.dp)
                .pointerInput(uri) { detectTapGestures { seek(it.x / size.width) } }
                .pointerInput(uri) { detectHorizontalDragGestures { change, _ -> seek(change.position.x / size.width) } },
        ) {
            val h = 4.dp.toPx()
            val y = (size.height - h) / 2f
            val r = CornerRadius(h / 2f)
            drawRoundRect(p.high, Offset(0f, y), Size(size.width, h), r)
            drawRoundRect(p.primary, Offset(0f, y), Size(size.width * progress, h), r)
            drawCircle(p.primary, 7.dp.toPx(), Offset((size.width * progress).coerceIn(7.dp.toPx(), size.width - 7.dp.toPx()), size.height / 2f))
        }
    }
}
