package com.davexh.shrinky.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.davexh.shrinky.ShrinkVm
import com.davexh.shrinky.engine.Kind
import com.davexh.shrinky.engine.OutFormat
import com.davexh.shrinky.engine.Shrunk

/** The compress tool: everything lives in one card. */
@Composable
fun ShrinkScreen(vm: ShrinkVm, pickFolder: () -> Unit) {
    val p = LocalPalette.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.pick(it) }
    val src = vm.source
    val result = vm.result
    val shown = rememberLast(result)
    val target = vm.targetBytes

    val fillTo = result?.bytes?.size?.toLong() ?: target
    val fraction = if (src != null && src.bytes > 0) fillTo.toFloat() / src.bytes else 0f
    val meter by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(dampingRatio = 0.6f, stiffness = 150f), label = "meter")

    ToolScaffold(
        scrollTo = result,
        bar = {
            ActionBar(
                busy = vm.busy, hasResult = result != null, savedAs = vm.save.savedAs,
                primary = "Shrink", primaryEnabled = src != null && target >= 5 * 1024,
                onPrimary = vm::shrink, onSave = vm::saveResult,
            )
        },
    ) {
        SectionCard {
            DotMeter(meter)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Readout("Original", src?.let { formatSize(it.bytes) } ?: "--")
                if (result != null) {
                    Readout("Result", formatSize(result.bytes.size.toLong()), alignEnd = true, warn = !result.hitTarget)
                } else {
                    Readout("Target", if (target > 0) formatSize(target) else "--", alignEnd = true)
                }
            }

            Rule()
            Section("File") {
                if (src != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(src.name, Modifier.marquee(), style = Type.title, maxLines = 1)
                        Text((if (src.kind == Kind.PDF) "PDF" else "Photo") + ", " + formatSize(src.bytes), color = p.mute)
                    }
                }
                PillButton(
                    if (src == null) "Choose file" else "Change file",
                    style = if (src == null) PillStyle.Filled else PillStyle.Outlined,
                ) { picker.launch(arrayOf("image/*", "application/pdf")) }
            }

            Rule()
            Section("Target size") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(vm.targetText, vm::onTargetText, "0", Modifier.weight(1f), keyboard = KeyboardType.Decimal)
                    Segmented(listOf("KB", "MB"), if (vm.unitMb) 1 else 0, Modifier.width(112.dp)) { vm.onUnit(it == 1) }
                }
            }

            Reveal(src?.kind == Kind.IMAGE) {
                Rule()
                Section("Output format") {
                    Segmented(OutFormat.entries.map { it.label }, vm.format.ordinal) { vm.onFormat(OutFormat.entries[it]) }
                }
            }

            StatusSection(vm.busy, vm.failure)

            Reveal(result != null) {
                if (shown != null) ResultBlock(vm, shown, pickFolder)
            }
        }
    }
}

@Composable
private fun ColumnScope.ResultBlock(vm: ShrinkVm, r: Shrunk, pickFolder: () -> Unit) {
    val p = LocalPalette.current
    val src = vm.source
    var view by rememberSaveable { mutableIntStateOf(0) }

    val hasZoom = r.beforeZoom != null && r.afterZoom != null
    val useZoom = view == 1 && hasZoom
    val b = if (useZoom) r.beforeZoom else r.before
    val a = if (useZoom) r.afterZoom else r.after

    if (b != null && a != null) {
        Rule()
        Section("Preview") {
            if (hasZoom) Segmented(listOf("Fit", "100%"), view) { view = it }
            CompareView(remember(b) { b.asImageBitmap() }, remember(a) { a.asImageBitmap() })
        }
    }

    Rule()
    Section("What changed") {
        when {
            r.unchanged -> Text("Already under your target, so the file was left as is.", color = p.mute)
            !r.hitTarget -> Text("This is as small as it gets without breaking the file. Try a bigger target.", color = p.accent)
        }
        if (src != null) {
            StatRow(
                "File size", formatSize(src.bytes), formatSize(r.bytes.size.toLong()),
                delta(src.bytes.toDouble(), r.bytes.size.toDouble()),
            )
        }
        if (r.origW > 0) {
            val d = delta(r.origW.toDouble() * r.origH, r.newW.toDouble() * r.newH)
            StatRow(
                "Resolution", "${r.origW} \u00d7 ${r.origH}", "${r.newW} \u00d7 ${r.newH}",
                if (d == "no change" || d.isEmpty()) d else "$d pixels",
            )
        }
        if (r.pages > 0) {
            StatRow("Pages", "${r.pages}, saved as images")
            StatRow("Page quality", "${r.dpi} dpi")
        }
    }

    Rule()
    SaveSection(vm.save.name, vm.save::onName, r.ext, pickFolder)
}

@Composable
private fun Readout(label: String, value: String, alignEnd: Boolean = false, warn: Boolean = false) {
    val p = LocalPalette.current
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(label, color = p.mute)
        Text(value, color = if (warn) p.accent else p.text, style = Type.headline)
    }
}
