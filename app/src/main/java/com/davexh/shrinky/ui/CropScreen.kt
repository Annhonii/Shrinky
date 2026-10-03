package com.davexh.shrinky.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.davexh.shrinky.CROP_RATIOS
import com.davexh.shrinky.CropResult
import com.davexh.shrinky.CropVm
import com.davexh.shrinky.engine.OutFormat

/** The crop tool: exact size or aspect ratio, then move/zoom the photo under the frame. */
@Composable
fun CropScreen(vm: CropVm, pickFolder: () -> Unit) {
    val p = LocalPalette.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.pick(it) }
    val src = vm.src
    val result = vm.result
    val shown = rememberLast(result)
    val w = vm.wText.toIntOrNull() ?: 0
    val h = vm.hText.toIntOrNull() ?: 0

    ToolScaffold(
        scrollTo = result,
        bar = {
            ActionBar(
                busy = vm.busy, hasResult = result != null, savedAs = vm.save.savedAs,
                primary = "Crop", primaryEnabled = src != null && (if (vm.mode == 1) vm.ratioValid else (w > 0 && h > 0)),
                onPrimary = vm::crop, onSave = vm::saveResult,
            )
        },
    ) {
        SectionCard {
            Section("Photo") {
                if (src != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(src.name, Modifier.marquee(), style = Type.title, maxLines = 1)
                        Text("${src.origW} \u00d7 ${src.origH}", color = p.mute)
                    }
                }
                PillButton(
                    if (src == null) "Choose photo" else "Change photo",
                    style = if (src == null) PillStyle.Filled else PillStyle.Outlined,
                ) { picker.launch(arrayOf("image/*")) }
            }

            Rule()
            Section("Crop") {
                Segmented(listOf("Size", "Ratio"), vm.mode) { vm.onMode(it) }
                AnimatedContent(
                    targetState = vm.mode,
                    transitionSpec = { fadeIn(spring(stiffness = 300f)) togetherWith fadeOut(spring(stiffness = 500f)) },
                    label = "crop-mode",
                ) { m ->
                    if (m == 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Field(vm.wText, vm::onW, "Width", Modifier.weight(1f), keyboard = KeyboardType.Number, style = Type.title)
                            Text("\u00d7", color = p.mute, style = Type.title)
                            Field(vm.hText, vm::onH, "Height", Modifier.weight(1f), keyboard = KeyboardType.Number, style = Type.title)
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Custom ratio first; the chips below just fill it in.
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Field(vm.customW, vm::onCustomW, "Width", Modifier.weight(1f), keyboard = KeyboardType.Number, style = Type.title)
                                Text(":", color = p.mute, style = Type.title)
                                Field(vm.customH, vm::onCustomH, "Height", Modifier.weight(1f), keyboard = KeyboardType.Number, style = Type.title)
                            }
                            ChipRow(CROP_RATIOS.map { "${it.first}:${it.second}" }, vm.ratio) { vm.onRatio(it) }
                        }
                    }
                }
            }

            Reveal(src != null) {
                Rule()
                Section("Select area") { CropEditor(vm.editor) }
                Rule()
                Section("Output format") {
                    Segmented(OutFormat.entries.map { it.label }, vm.format.ordinal) { vm.onFormat(OutFormat.entries[it]) }
                }
            }

            StatusSection(vm.busy, vm.failure)

            Reveal(result != null) {
                if (shown != null) CropResultBlock(vm, shown, pickFolder)
            }
        }
    }
}

@Composable
private fun ColumnScope.CropResultBlock(vm: CropVm, r: CropResult, pickFolder: () -> Unit) {
    val src = vm.src
    Rule()
    Section("Result") {
        Image(
            remember(r.preview) { r.preview.asImageBitmap() }, "Cropped photo",
            Modifier.fillMaxWidth()
                .aspectRatio((r.w.toFloat() / r.h).coerceIn(0.3f, 3f))
                .clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Fit,
        )
        if (src != null) StatRow("Resolution", "${src.origW} \u00d7 ${src.origH}", "${r.w} \u00d7 ${r.h}")
        StatRow("File size", formatSize(r.bytes.size.toLong()))
    }
    Rule()
    SaveSection(vm.save.name, vm.save::onName, r.ext, pickFolder)
}
