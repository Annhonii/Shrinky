@file:OptIn(ExperimentalFoundationApi::class)

package com.davexh.shrinky.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.davexh.shrinky.engine.Saver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------- formatting ----------

fun formatSize(b: Long): String =
    if (b >= 1_048_576) String.format(Locale.US, "%.1f MB", b / 1_048_576.0)
    else "${(b / 1024).coerceAtLeast(1)} KB"

/** Size in the unit the user picked: whole KB, or MB with two decimals. */
fun formatSizeIn(b: Long, mb: Boolean): String =
    if (mb) String.format(Locale.US, "%.2f MB", b / 1_048_576.0)
    else "${(b / 1024.0).roundToInt()} KB"

fun delta(before: Double, after: Double): String {
    if (before <= 0) return ""
    val d = ((after - before) / before * 100).roundToInt()
    return when {
        d > 0 -> "+$d%"
        d < 0 -> "\u2212${-d}%"
        else -> "no change"
    }
}

/** Keeps the last non-null value so exit animations still have something to draw. */
class Holder<T>(var value: T? = null)

@Composable
fun <T : Any> rememberLast(value: T?): T? {
    val h = remember { Holder<T>() }
    if (value != null) h.value = value
    return h.value
}

// ---------- motion ----------

/**
 * Space the floating nav pill takes at the bottom (pill + padding + system inset).
 * Screens keep their own floating action pill, and their scrolling content, clear of it.
 */
val LocalBottomReserve = compositionLocalOf { 72.dp }

/**
 * Press feedback like the Music app: an Android-style ripple that grows from the touch point,
 * plus an optional dim (opacity 0.5) for plain text taps. Clips to [shape] so the ripple stays inside.
 */
@Composable
fun Modifier.tap(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(16.dp),
    dim: Boolean = false,
    ripple: Color? = null,
    action: () -> Unit,
): Modifier {
    val p = LocalPalette.current
    val current by rememberUpdatedState(action)
    val scope = rememberCoroutineScope()
    val grow = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var pressed by remember { mutableStateOf(false) }
    val opacity by animateFloatAsState(if (pressed && dim) 0.5f else 1f, tween(90), label = "press")
    val rippleColor = ripple ?: p.high
    return this
        .graphicsLayer { alpha = opacity }
        .semantics(mergeDescendants = true) { role = Role.Button; onClick { current(); true } }
        .clip(shape)
        .drawWithContent {
            drawContent()
            if (glow.value > 0f) {
                drawCircle(
                    rippleColor.copy(alpha = rippleColor.alpha * 0.6f * glow.value),
                    radius = size.maxDimension * grow.value,
                    center = origin,
                )
            }
        }
        .pointerInput(enabled) {
            if (enabled) {
                detectTapGestures(
                    onPress = { o ->
                        origin = o
                        pressed = true
                        scope.launch {
                            glow.snapTo(1f)
                            grow.snapTo(0f)
                            grow.animateTo(1.2f, tween(320, easing = FastOutSlowInEasing))
                        }
                        tryAwaitRelease()
                        pressed = false
                        scope.launch { glow.animateTo(0f, tween(260)) }
                    },
                    onTap = { current() },
                )
            }
        }
}

/** Long names scroll sideways instead of being cut off. */
fun Modifier.marquee(): Modifier = basicMarquee(iterations = 3)

/** A thin scrollbar that fades in while scrolling and lingers a moment after. Apply BEFORE verticalScroll. */
@Composable
fun Modifier.scrollbar(state: ScrollState): Modifier {
    val p = LocalPalette.current
    val scrolling = state.isScrollInProgress
    val visible by animateFloatAsState(
        if (scrolling) 1f else 0f,
        tween(durationMillis = if (scrolling) 120 else 400, delayMillis = if (scrolling) 0 else 700),
        label = "scrollbar",
    )
    return drawWithContent {
        drawContent()
        val max = state.maxValue
        if (visible > 0.01f && max > 0) {
            val view = size.height
            val h = (view * view / (view + max)).coerceAtLeast(28.dp.toPx())
            val y = (view - h) * state.value / max
            val w = 3.dp.toPx()
            drawRoundRect(
                p.mute.copy(alpha = 0.55f * visible),
                Offset(size.width - w - 3.dp.toPx(), y),
                Size(w, h),
                CornerRadius(w / 2f),
            )
        }
    }
}

/** Smooth expand + fade for sections that come and go inside the card. */
@Composable
fun Reveal(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(250)) + expandVertically(tween(320, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(150)) + shrinkVertically(tween(250, easing = FastOutSlowInEasing)),
    ) { Column(content = content) }
}

// ---------- layout ----------

/** One card per tool. Sections inside are separated by Rule(). */
@Composable
fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(p.card, shape)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (title != null) Text(title.uppercase(), color = p.mute, style = Type.em)
        content()
    }
}

@Composable
fun Rule() {
    val p = LocalPalette.current
    Box(Modifier.padding(vertical = 16.dp).fillMaxWidth().height(1.dp).background(p.stroke))
}

/** Scrolling body. The action pill is the very last thing on the page; the nav pill floats over the bottom. */
@Composable
fun ToolScaffold(
    scrollTo: Any?,
    bar: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reserve = LocalBottomReserve.current
    val scroll = rememberScrollState()
    LaunchedEffect(scrollTo) {
        if (scrollTo != null) {
            delay(340) // let the result section finish expanding
            scroll.animateScrollTo(scroll.maxValue, tween(450, easing = FastOutSlowInEasing))
        }
    }
    Column(
        Modifier.fillMaxSize()
            .scrollbar(scroll)
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = reserve + 24.dp),
    ) {
        content()
        Spacer(Modifier.height(16.dp))
        bar()
    }
}

private enum class Bar { Busy, Saved, Save, Primary }

/** One primary action that follows the flow: do the thing, then Save, then Saved. A floating pill. */
@Composable
fun ActionBar(
    busy: Boolean,
    hasResult: Boolean,
    savedAs: String?,
    primary: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    onSave: () -> Unit,
) {
    val p = LocalPalette.current
    val state = when {
        busy -> Bar.Busy
        hasResult && savedAs != null -> Bar.Saved
        hasResult -> Bar.Save
        else -> Bar.Primary
    }
    val filled = state == Bar.Primary || state == Bar.Save
    val container by animateColorAsState(if (filled) p.primary else p.card, tween(250), label = "bar-bg")
    val fade by animateFloatAsState(if (state == Bar.Primary && !primaryEnabled) 0.25f else 1f, tween(200), label = "bar-fade")
    val shape = CircleShape

    Box(
        Modifier.fillMaxWidth().height(56.dp)
            .graphicsLayer { alpha = fade }
            .clip(shape)
            .background(container),
    ) {
        AnimatedContent(
            targetState = state,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                (slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(220))) togetherWith
                    (slideOutVertically(tween(320, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(150)))
            },
            label = "action-bar",
        ) { s ->
            when (s) {
                Bar.Busy -> BarLabel("Working...", p.mute)
                Bar.Saved -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Saved", color = p.text, style = Type.button)
                    Text(
                        savedAs.orEmpty(), color = p.mute, style = Type.tiny,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Bar.Save -> BarLabel("Save", p.onPrimary, Modifier.tap(true, CircleShape, ripple = Color(0x33000000)) { onSave() })
                Bar.Primary -> BarLabel(
                    primary, p.onPrimary,
                    Modifier.tap(primaryEnabled, CircleShape, ripple = Color(0x33000000)) { onPrimary() },
                )
            }
        }
        AnimatedVisibility(
            visible = state == Bar.Busy,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(150)),
        ) { BusyLine() }
    }
}

@Composable
private fun BarLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = color, style = Type.button, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The thin red line along the bottom of the pill (like the mini-player's progress), sweeping while busy. */
@Composable
private fun BusyLine() {
    val p = LocalPalette.current
    val t = rememberInfiniteTransition(label = "busy")
    val x by t.animateFloat(-0.35f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing)), label = "sweep")
    Canvas(Modifier.padding(horizontal = 8.dp).fillMaxWidth().height(2.dp)) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(p.high, Offset.Zero, size, r)
        clipRect {
            drawRoundRect(p.primary, Offset(size.width * x, 0f), Size(size.width * 0.35f, size.height), r)
        }
    }
}

@Composable
fun StatusSection(busy: Boolean, failure: String?, label: String = "Working") {
    val p = LocalPalette.current
    Reveal(busy || failure != null) {
        Rule()
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                WorkingDots()
                Text(label, color = p.mute)
            }
        } else if (failure != null) {
            Text(failure, color = p.accent)
        }
    }
}

@Composable
fun SaveSection(name: String, onName: (String) -> Unit, ext: String, pickFolder: () -> Unit) {
    Section("Save as") {
        Field(name, onName, "File name", suffix = ".$ext", style = Type.title)
        PickerRow(Saver.label, "Change", pickFolder)
    }
}

// ---------- the memorable element: a dot grid that fills to show size ----------

@Composable
fun DotMeter(fraction: Float, cols: Int = 24, rows: Int = 7) {
    val p = LocalPalette.current
    Canvas(Modifier.fillMaxWidth().aspectRatio(cols.toFloat() / rows)) {
        val cell = size.width / cols
        val total = cols * rows
        val filled = (fraction * total).roundToInt().coerceIn(0, total)
        for (i in 0 until total) {
            val c = i / rows
            val r = i % rows
            val color = when {
                i == filled - 1 -> p.accent
                i < filled -> p.dotOn
                else -> p.dotOff
            }
            drawCircle(color, radius = cell * 0.27f, center = Offset(cell * (c + 0.5f), cell * (r + 0.5f)))
        }
    }
}

// ---------- controls ----------

enum class PillStyle { Filled, Outlined }

/** Rounded-xl button: filled in the primary red, or outlined. Disabled drops to 25% like the Music app. */
@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: PillStyle = PillStyle.Filled,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val filled = style == PillStyle.Filled
    val shape = RoundedCornerShape(24.dp)
    val fade by animateFloatAsState(if (enabled) 1f else 0.25f, tween(200), label = "pill-fade")
    Box(
        modifier.fillMaxWidth().height(48.dp)
            .graphicsLayer { alpha = fade }
            .tap(enabled, shape, ripple = if (filled) Color(0x33000000) else null) { onClick() }
            .then(if (filled) Modifier.background(p.primary, shape) else Modifier.border(1.dp, p.stroke, shape))
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = if (filled) p.onPrimary else p.text, style = Type.button,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Segmented control. [position] is fractional (0f..n-1), so the thumb can follow a finger.
 * Drag anywhere on the bar to slide the thumb.
 */
@Composable
fun SlidingSegments(
    labels: List<String>,
    position: Float,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    onTap: (Int) -> Unit,
    onDrag: ((Float) -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    var widthPx by remember { mutableIntStateOf(0) }
    val padPx = with(density) { 4.dp.toPx() }
    val n = labels.size
    val segPx = if (widthPx > 0) (widthPx - 2 * padPx) / n else 0f
    val segDp = with(density) { segPx.toDp() }
    val thumbX = (position * segPx).roundToInt()
    val totalPx = (segPx * n).roundToInt()

    Box(
        modifier.fillMaxWidth().height(height)
            .clip(CircleShape)
            .background(p.field)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(n, segPx) {
                detectTapGestures { o ->
                    if (segPx > 0f) onTap(((o.x - padPx) / segPx).toInt().coerceIn(0, n - 1))
                }
            }
            .then(
                if (onDrag != null) {
                    Modifier.pointerInput(n, segPx) {
                        detectHorizontalDragGestures(
                            onDragEnd = { onRelease?.invoke() },
                            onDragCancel = { onRelease?.invoke() },
                        ) { change, amount ->
                            change.consume()
                            if (segPx > 0f) onDrag(amount / segPx)
                        }
                    }
                } else Modifier,
            ),
    ) {
        Row(Modifier.fillMaxSize().padding(4.dp)) {
            labels.forEach { l ->
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text(l, color = p.text, style = Type.label, maxLines = 1)
                }
            }
        }
        // The thumb, with a second copy of the labels (inverted) clipped inside it.
        Box(
            Modifier.padding(4.dp).fillMaxHeight().width(segDp)
                .offset { IntOffset(thumbX, 0) }
                .clip(CircleShape)
                .background(p.inverse),
        ) {
            Row(
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(Constraints.fixed(totalPx, constraints.maxHeight))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-thumbX, 0) }
                },
            ) {
                labels.forEach { l ->
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text(l, color = p.onInverse, style = Type.label, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Segmented picker: tap a segment, or grab the thumb and slide it. The thumb glides over 250ms. */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    val glide = tween<Float>(250, easing = FastOutSlowInEasing)
    val anim = remember { Animatable(selected.toFloat()) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val last = options.size - 1
    LaunchedEffect(selected) { anim.animateTo(selected.toFloat(), glide) }

    SlidingSegments(
        labels = options,
        position = drag ?: anim.value,
        modifier = modifier,
        onTap = { onSelect(it) },
        onDrag = { f -> drag = ((drag ?: anim.value) + f).coerceIn(0f, last.toFloat()) },
        onRelease = {
            val d = drag
            if (d != null) {
                val idx = d.roundToInt().coerceIn(0, last)
                scope.launch { anim.snapTo(d); drag = null; anim.animateTo(idx.toFloat(), glide) }
                if (idx != selected) onSelect(idx)
            }
        },
    )
}

/**
 * The floating bottom nav, like the Music app's: a white pill, the active label in red.
 * A soft thumb slides behind the labels and follows [position] while you swipe the pages.
 */
@Composable
fun NavPill(
    labels: List<String>,
    position: Float,
    modifier: Modifier = Modifier,
    onTap: (Int) -> Unit,
    onDrag: (Float) -> Unit,
    onRelease: () -> Unit,
) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    var widthPx by remember { mutableIntStateOf(0) }
    val padPx = with(density) { 4.dp.toPx() }
    val n = labels.size
    val segPx = if (widthPx > 0) (widthPx - 2 * padPx) / n else 0f
    val segDp = with(density) { segPx.toDp() }
    val shape = CircleShape

    Box(
        modifier.fillMaxWidth().height(56.dp)
            .shadow(6.dp, shape, clip = false, ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
            .clip(shape)
            .background(p.card)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(n, segPx) {
                detectTapGestures { o ->
                    if (segPx > 0f) onTap(((o.x - padPx) / segPx).toInt().coerceIn(0, n - 1))
                }
            }
            .pointerInput(n, segPx) {
                detectHorizontalDragGestures(
                    onDragEnd = { onRelease() },
                    onDragCancel = { onRelease() },
                ) { change, amount ->
                    change.consume()
                    if (segPx > 0f) onDrag(amount / segPx)
                }
            },
    ) {
        Box(
            Modifier.padding(4.dp).fillMaxHeight().width(segDp)
                .offset { IntOffset((position * segPx).roundToInt(), 0) }
                .clip(shape)
                .background(p.field),
        )
        Row(Modifier.fillMaxSize().padding(4.dp)) {
            labels.forEachIndexed { i, l ->
                val near = (1f - abs(position - i)).coerceIn(0f, 1f)
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text(l, color = lerp(p.text, p.primary, near), style = Type.title, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun ChipRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, l ->
            val sel = i == selected
            val bg by animateColorAsState(if (sel) p.inverse else p.field, tween(200), label = "chip-bg")
            val fg by animateColorAsState(if (sel) p.onInverse else p.text, tween(200), label = "chip-fg")
            Box(
                Modifier.tap(shape = CircleShape) { onSelect(i) }
                    .background(bg, CircleShape)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) { Text(l, color = fg, style = Type.label) }
        }
    }
}

/** A filled well (no outline); a red ring eases in on focus. */
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    style: TextStyle = Type.input,
    suffix: String? = null,
) {
    val p = LocalPalette.current
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "ring")
    val shape = RoundedCornerShape(16.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style.copy(color = p.text),
        cursorBrush = SolidColor(p.accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth()
                    .background(p.field, shape)
                    .then(
                        if (ring > 0.01f) Modifier.border((2f * ring).dp, p.accent.copy(alpha = ring), shape)
                        else Modifier,
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = p.mute, style = style, maxLines = 1)
                    inner()
                }
                if (suffix != null) Text(suffix, color = p.mute, style = style, maxLines = 1)
            }
        },
    )
}

@Composable
fun PickerRow(text: String, action: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth()
            .tap(shape = shape) { onClick() }
            .background(p.field, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(12.dp))
        Text(action, color = p.primary, style = Type.label)
    }
}

@Composable
fun IconTap(label: String, enabled: Boolean = true, action: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.size(40.dp).tap(enabled, CircleShape, dim = true) { action() }, contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) p.text else p.dotOff, style = Type.title)
    }
}

/** A thin cross, drawn rather than typed, so it stays crisp at any size. */
@Composable
fun CloseIcon(color: Color, modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier) {
        val inset = size.minDimension * 0.25f
        val far = size.minDimension - inset
        val w = 2.dp.toPx()
        drawLine(color, Offset(inset, inset), Offset(far, far), w, StrokeCap.Round)
        drawLine(color, Offset(far, inset), Offset(inset, far), w, StrokeCap.Round)
    }
}

@Composable
fun WorkingDots() {
    val p = LocalPalette.current
    val t = rememberInfiniteTransition(label = "working")
    val pos by t.animateFloat(0f, 5f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "pos")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(5) { i ->
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (pos.toInt() == i) p.accent else p.dotOff))
        }
    }
}

// ---------- results ----------

@Composable
fun StatRow(label: String, from: String, to: String? = null, delta: String? = null) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = p.mute, style = Type.small)
            if (!delta.isNullOrEmpty()) Text(delta, color = p.accent, style = Type.label)
        }
        Text(if (to == null) from else "$from  \u2192  $to", style = Type.title)
    }
}

/** Drag to reveal Before (left) against After (right). */
@Composable
fun CompareView(before: ImageBitmap, after: ImageBitmap) {
    val p = LocalPalette.current
    var frac by remember { mutableFloatStateOf(0.5f) }
    Box(
        Modifier.fillMaxWidth()
            .aspectRatio((before.width.toFloat() / before.height).coerceIn(0.6f, 1.8f))
            .clip(RoundedCornerShape(16.dp))
            .background(p.field)
            .pointerInput(Unit) { detectTapGestures { frac = (it.x / size.width).coerceIn(0f, 1f) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { frac = (it.x / size.width).coerceIn(0f, 1f) },
                ) { change, _ ->
                    change.consume()
                    frac = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            },
    ) {
        Image(after, "After", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Image(
            before, "Before",
            Modifier.fillMaxSize().drawWithContent {
                clipRect(right = size.width * frac) { this@drawWithContent.drawContent() }
            },
            contentScale = ContentScale.Crop,
        )
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width * frac
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            drawCircle(Color.White, 15.dp.toPx(), Offset(x, size.height / 2))
            drawCircle(p.accent, 4.dp.toPx(), Offset(x, size.height / 2))
        }
        Tag("Before", Modifier.align(Alignment.TopStart).padding(10.dp))
        Tag("After", Modifier.align(Alignment.TopEnd).padding(10.dp))
    }
}

@Composable
private fun Tag(text: String, modifier: Modifier) {
    Text(
        text,
        modifier.clip(CircleShape).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp),
        color = Color.White, style = Type.small,
    )
}
