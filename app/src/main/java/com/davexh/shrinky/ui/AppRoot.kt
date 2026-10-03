package com.davexh.shrinky.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.davexh.shrinky.CropVm
import com.davexh.shrinky.PdfVm
import com.davexh.shrinky.ShrinkVm
import com.davexh.shrinky.engine.Saver
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRoot(shrink: ShrinkVm, crop: CropVm, pdf: PdfVm) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(pageCount = { 3 })
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        Saver.setFolder(ctx, it)
    }
    val pickFolder = { folderPicker.launch(null) }

    // Pages glide: no bounce, like the Music app's screen changes.
    val glide = spring<Float>(dampingRatio = 1f, stiffness = 350f)

    // The nav pill slides away when the keyboard opens; screens keep their action pill clear of it.
    val imeOpen = WindowInsets.ime.getBottom(density) > 0
    val navInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val reserve by animateDpAsState(
        if (imeOpen) 8.dp else 72.dp + navInset,
        tween(300, easing = FastOutSlowInEasing), label = "reserve",
    )
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }

    CompositionLocalProvider(LocalBottomReserve provides reserve) {
        Box(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Shrinky", style = Type.headline)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(9.dp).clip(CircleShape).background(p.accent))
                }

                HorizontalPager(
                    state = pager,
                    modifier = Modifier.weight(1f),
                    beyondViewportPageCount = 2,
                    flingBehavior = PagerDefaults.flingBehavior(
                        state = pager,
                        snapAnimationSpec = spring(dampingRatio = 1f, stiffness = 380f),
                    ),
                    key = { it },
                ) { page ->
                    when (page) {
                        0 -> ShrinkScreen(shrink, pickFolder)
                        1 -> CropScreen(crop, pickFolder)
                        else -> PdfScreen(pdf, pickFolder)
                    }
                }
            }

            // Slides up from the bottom on launch and ducks out of the keyboard's way.
            AnimatedVisibility(
                visible = started && !imeOpen,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(450, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(300)),
                exit = slideOutVertically(tween(250, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(200)),
            ) {
                NavPill(
                    labels = listOf("Shrink", "Crop", "PDF"),
                    position = pager.currentPage + pager.currentPageOffsetFraction,
                    modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    onTap = { i -> scope.launch { pager.animateScrollToPage(i, animationSpec = glide) } },
                    onDrag = { f ->
                        val size = pager.layoutInfo.pageSize
                        if (size > 0) pager.dispatchRawDelta(f * size)
                    },
                    onRelease = {
                        val target = (pager.currentPage + pager.currentPageOffsetFraction).roundToInt().coerceIn(0, 2)
                        scope.launch { pager.animateScrollToPage(target, animationSpec = glide) }
                    },
                )
            }
        }
    }
}
