package com.davexh.shrinky.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.davexh.shrinky.PageItem
import com.davexh.shrinky.PdfResult
import com.davexh.shrinky.PdfVm
import com.davexh.shrinky.engine.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val ROW_H = 64.dp
private val ROW_GAP = 8.dp

/** Photos to PDF: one photo per page, in the order listed. Long-press a row to drag it, tap a photo to preview. */
@Composable
fun PdfScreen(vm: PdfVm, pickFolder: () -> Unit) {
    val p = LocalPalette.current
    val add = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.add(it) }
    val result = vm.result
    val shown = rememberLast(result)
    var preview by remember { mutableStateOf<PageItem?>(null) }

    ToolScaffold(
        scrollTo = result,
        bar = {
            ActionBar(
                busy = vm.busy, hasResult = result != null, savedAs = vm.save.savedAs,
                primary = "Make PDF", primaryEnabled = vm.pages.isNotEmpty(),
                onPrimary = vm::make, onSave = vm::saveResult,
            )
        },
    ) {
        SectionCard {
            Section("Pages") {
                PageList(vm, onOpen = { preview = it })
                if (vm.pages.isNotEmpty()) {
                    Text(
                        if (vm.pages.size > 1) "Tap a photo to preview \u00b7 long press a row, then drag to reorder"
                        else "Tap a photo to preview",
                        color = p.mute, style = Type.small,
                    )
                }
                PillButton(
                    if (vm.pages.isEmpty()) "Add photos" else "Add more",
                    style = if (vm.pages.isEmpty()) PillStyle.Filled else PillStyle.Outlined,
                ) { add.launch(arrayOf("image/*")) }
            }

            Rule()
            Section("Page size") {
                Segmented(listOf("A4", "Photo size"), if (vm.a4) 0 else 1) { vm.onA4(it == 0) }
            }

            StatusSection(vm.busy, vm.failure)

            Reveal(result != null) {
                if (shown != null) PdfResultBlock(vm, shown, pickFolder)
            }
        }
    }

    preview?.let { item ->
        val n = vm.pages.indexOfFirst { it.id == item.id }
        if (n < 0) preview = null
        else PhotoPreview(item, n + 1, vm.pages.size) { preview = null }
    }
}

/**
 * Fixed-height rows placed by hand (not a Lazy list, since the screen already scrolls).
 * Long press a row, then drag up/down; the other rows slide out of the way.
 */
@Composable
private fun PageList(vm: PdfVm, onOpen: (PageItem) -> Unit) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val step = with(density) { (ROW_H + ROW_GAP).toPx() }
    val n = vm.pages.size

    var dragId by remember { mutableStateOf<Long?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) } // top edge of the dragged row, in px

    if (n == 0) return
    Box(Modifier.fillMaxWidth().height(with(density) { (n * step - ROW_GAP.toPx()).coerceAtLeast(0f).toDp() })) {
        vm.pages.forEachIndexed { i, item ->
            key(item.id) {
                val appear = remember { Animatable(0f) }
                LaunchedEffect(Unit) { appear.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
                val dragging = dragId == item.id
                val settled by animateIntAsState((i * step).roundToInt(), spring(dampingRatio = 1f, stiffness = 420f), label = "row")
                val y = if (dragging) dragY.roundToInt() else settled

                PageRow(
                    item = item,
                    number = i + 1,
                    dragging = dragging,
                    appear = appear.value,
                    onOpen = { onOpen(item) },
                    onRemove = { vm.remove(vm.pages.indexOfFirst { it.id == item.id }) },
                    modifier = Modifier
                        .offset { IntOffset(0, y) }
                        .zIndex(if (dragging) 1f else 0f)
                        .pointerInput(item.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    dragY = vm.pages.indexOfFirst { it.id == item.id } * step
                                    dragId = item.id
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    val count = vm.pages.size
                                    dragY = (dragY + amount.y).coerceIn(0f, (count - 1) * step)
                                    val to = ((dragY + step / 2f) / step).toInt().coerceIn(0, count - 1)
                                    val from = vm.pages.indexOfFirst { it.id == item.id }
                                    if (from >= 0 && to != from) vm.reorder(from, to)
                                },
                                onDragEnd = { scope.launch { settle(vm, item.id, step, { dragY }, { dragY = it }); dragId = null } },
                                onDragCancel = { scope.launch { settle(vm, item.id, step, { dragY }, { dragY = it }); dragId = null } },
                            )
                        },
                )
            }
        }
    }
}

/** Glides the dropped row into its final slot before it hands back to the normal layout. */
private suspend fun settle(vm: PdfVm, id: Long, step: Float, get: () -> Float, set: (Float) -> Unit) {
    val target = vm.pages.indexOfFirst { it.id == id } * step
    animate(get(), target, animationSpec = spring(dampingRatio = 1f, stiffness = 500f)) { v, _ -> set(v) }
}

@Composable
private fun PageRow(
    item: PageItem,
    number: Int,
    dragging: Boolean,
    appear: Float,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    val lift by animateDpAsState(if (dragging) 10.dp else 0.dp, tween(150), label = "lift")
    val grow by animateFloatAsState(if (dragging) 1.03f else 1f, tween(150), label = "lift-scale")
    Row(
        modifier
            .fillMaxWidth()
            .height(ROW_H)
            .graphicsLayer {
                val k = grow * (0.96f + 0.04f * appear)
                scaleX = k; scaleY = k
                alpha = appear
            }
            .shadow(lift, shape, clip = false, ambientColor = Color(0x44000000), spotColor = Color(0x44000000))
            .clip(shape)
            .background(if (dragging) p.high else p.field, shape)
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            remember(item.thumb) { item.thumb.asImageBitmap() }, "Preview",
            Modifier.size(48.dp).tap(shape = RoundedCornerShape(12.dp)) { onOpen() },
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.weight(1f)) {
            Text(item.name, Modifier.marquee(), maxLines = 1, style = Type.title)
            Text("Page $number", color = p.mute, style = Type.small)
        }
        Box(Modifier.size(40.dp).tap(shape = CircleShape, dim = true) { onRemove() }, contentAlignment = Alignment.Center) {
            CloseIcon(p.text)
        }
        DragGrip()
    }
}

/** Two columns of three dots: the "you can drag me" hint. */
@Composable
private fun DragGrip() {
    val p = LocalPalette.current
    Canvas(Modifier.padding(end = 12.dp).size(width = 14.dp, height = 20.dp)) {
        val r = 1.8.dp.toPx()
        for (c in 0..1) for (rw in 0..2) {
            drawCircle(p.mute, r, Offset(size.width * (0.25f + 0.5f * c), size.height * (0.15f + 0.35f * rw)))
        }
    }
}

/** Full-screen photo viewer: pinch to zoom, drag to pan, double-tap to zoom in/out. */
@Composable
private fun PhotoPreview(item: PageItem, number: Int, total: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val image by produceState(item.thumb.asImageBitmap(), item.id) {
        val full: ImageBitmap? = withContext(Dispatchers.IO) {
            runCatching { Images.decode(ctx.contentResolver, item.uri, 2000).bitmap.asImageBitmap() }.getOrNull()
        }
        if (full != null) value = full
    }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(260, easing = FastOutSlowInEasing)) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val mx = box.width * (s - 1f) / 2f
        val my = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xF2000000))) {
            Image(
                image, null,
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { box = it }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1f) { scale = 1f; pan = Offset.Zero } else scale = 2.5f
                        })
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, panChange, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            pan = clamp(pan + panChange * scale, scale)
                        }
                    }
                    .graphicsLayer {
                        val k = scale * (0.94f + 0.06f * enter.value)
                        scaleX = k; scaleY = k
                        translationX = pan.x; translationY = pan.y
                        alpha = enter.value
                    },
                contentScale = ContentScale.Fit,
            )
            Row(
                Modifier.fillMaxWidth().systemBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        item.name, color = Color.White,
                        style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text("Page $number of $total", color = Color(0xFFB0B0B0), style = Type.small)
                }
                Box(
                    Modifier.size(44.dp).tap(shape = CircleShape) { onClose() }
                        .background(Color(0x66000000), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { CloseIcon(Color.White, Modifier.size(24.dp)) }
            }
        }
    }
}

@Composable
private fun ColumnScope.PdfResultBlock(vm: PdfVm, r: PdfResult, pickFolder: () -> Unit) {
    Rule()
    Section("Result") {
        StatRow("Pages", "${r.pages}")
        StatRow("File size", formatSize(r.bytes.size.toLong()))
    }
    Rule()
    SaveSection(vm.save.name, vm.save::onName, "pdf", pickFolder)
}
