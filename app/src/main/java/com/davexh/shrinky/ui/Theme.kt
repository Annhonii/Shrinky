package com.davexh.shrinky.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.davexh.shrinky.R

/**
 * Colour roles follow the Music app's theme (surface / surfaceContainer* / onSurface...),
 * flattened to the handful this app needs.
 */
@Immutable
class Palette(
    val bg: Color,          // surface: the page
    val card: Color,        // surfaceContainerLowest: cards and floating pills
    val stroke: Color,      // outlineVariant: hairlines
    val text: Color,        // onSurface
    val mute: Color,        // onSurfaceVariant
    val dotOff: Color,
    val dotOn: Color,
    val primary: Color,     // Nothing red
    val onPrimary: Color,
    val accent: Color,
    val field: Color,       // surfaceContainer: inputs, segmented track
    val fieldStroke: Color,
    val disabled: Color,
    val high: Color,        // surfaceContainerHigh: ripple, pressed
    val inverse: Color,     // inverseSurface: selected segment / chip
    val onInverse: Color,
)

private val Red = Color(0xFFD71921)

val DarkPalette = Palette(
    bg = Color(0xFF000000), card = Color(0xFF1C1C1C), stroke = Color(0xFF323232),
    text = Color(0xFFFFFFFF), mute = Color(0xFFADADAD),
    dotOff = Color(0xFF323232), dotOn = Color(0xFFFFFFFF),
    primary = Red, onPrimary = Color(0xFFFFFFFF), accent = Red,
    field = Color(0xFF292929), fieldStroke = Color(0xFF292929), disabled = Color(0xFF323232),
    high = Color(0xFF323232), inverse = Color(0xFFFFFFFF), onInverse = Color(0xFF000000),
)

val LightPalette = Palette(
    bg = Color(0xFFF2F2F2), card = Color(0xFFFFFFFF), stroke = Color(0xFFD9D9D9),
    text = Color(0xFF000000), mute = Color(0xFF606060),
    dotOff = Color(0xFFD9D9D9), dotOn = Color(0xFF000000),
    primary = Red, onPrimary = Color(0xFFFFFFFF), accent = Red,
    field = Color(0xFFEBEBEB), fieldStroke = Color(0xFFEBEBEB), disabled = Color(0xFFD9D9D9),
    high = Color(0xFFD9D9D9), inverse = Color(0xFF000000), onInverse = Color(0xFFFFFFFF),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** Geist, the same typeface the Music app ships with. */
val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
)

object Type {
    val headline = TextStyle(fontFamily = Geist, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium)
    val input = TextStyle(fontFamily = Geist, fontSize = 22.sp, lineHeight = 28.sp)
    val title = TextStyle(fontFamily = Geist, fontSize = 16.sp, lineHeight = 22.sp)
    val button = TextStyle(fontFamily = Geist, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val label = TextStyle(fontFamily = Geist, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontFamily = Geist, fontSize = 14.sp, lineHeight = 20.sp)
    val small = TextStyle(fontFamily = Geist, fontSize = 12.sp, lineHeight = 16.sp)
    val tiny = TextStyle(fontFamily = Geist, fontSize = 10.sp, lineHeight = 14.sp)
    /** Section headings: small, medium weight, wide tracking. Pass the text in upper case. */
    val em = TextStyle(fontFamily = Geist, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
}

/** Tiny replacement for Material's Text, so the app doesn't need the Material library. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalPalette.current.text,
    style: TextStyle = Type.body,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    BasicText(text, modifier, style.copy(color = color, textAlign = textAlign), overflow = overflow, maxLines = maxLines)
}

@Composable
fun ShrinkyTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides p) {
        Box(Modifier.fillMaxSize().background(p.bg)) { content() }
    }
}
