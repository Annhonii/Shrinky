package com.davexh.shrinky.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.davexh.shrinky.ShrinkVm
import com.davexh.shrinky.engine.AudioCodec
import com.davexh.shrinky.engine.AudioOut
import com.davexh.shrinky.engine.Kind
import com.davexh.shrinky.engine.OutFormat
import com.davexh.shrinky.engine.Shrunk
import com.davexh.shrinky.engine.VideoCodec

/** The compress tool: everything lives in one card. */
@Composable
fun ShrinkScreen(vm: ShrinkVm, pickFolder: () -> Unit) {
    val p = LocalPalette.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.pick(it) }
    val ctx = LocalContext.current
    val src = vm.source
    val result = vm.result
    // Android 13+ needs a runtime OK to show the progress notification. Compression runs either way.
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.shrink() }
    val onShrink = {
        if (src?.kind == Kind.VIDEO && Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.shrink()
    }
    val shown = rememberLast(result)
    val target = vm.targetBytes

    val fillTo = result?.size ?: target
    val fraction = if (src != null && src.bytes > 0) fillTo.toFloat() / src.bytes else 0f
    val meter by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(dampingRatio = 0.6f, stiffness = 150f), label = "meter")

    ToolScaffold(
        scrollTo = result,
        bar = {
            ActionBar(
                busy = vm.busy, hasResult = result != null, savedAs = vm.save.savedAs,
                primary = "Shrink", primaryEnabled = src != null && target >= 5 * 1024,
                onPrimary = onShrink, onSave = vm::saveResult,
            )
        },
    ) {
        SectionCard {
            DotMeter(meter)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Readout("Original", src?.let { formatSizeIn(it.bytes, vm.unitMb) } ?: "--")
                if (result != null) {
                    Readout("Result", formatSizeIn(result.size, vm.unitMb), alignEnd = true, warn = !result.hitTarget)
                } else {
                    Readout("Target", if (vm.targetText.isNotEmpty()) vm.targetText + " " + (if (vm.unitMb) "MB" else "KB") else "--", alignEnd = true)
                }
            }

            Rule()
            Section("File") {
                if (src != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(src.name, Modifier.marquee(), style = Type.title, maxLines = 1)
                        Text((when (src.kind) { Kind.PDF -> "PDF"; Kind.VIDEO -> "Video"; Kind.AUDIO -> "Audio"; Kind.IMAGE -> "Photo" }) + ", " + formatSizeIn(src.bytes, vm.unitMb), color = p.mute)
                    }
                }
                PillButton(
                    if (src == null) "Choose file" else "Change file",
                    style = if (src == null) PillStyle.Filled else PillStyle.Outlined,
                ) { picker.launch(arrayOf("image/*", "video/*", "audio/*", "application/ogg", "application/pdf")) }
            }

            Rule()
            Section("Target size") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(vm.targetText, vm::onTargetText, "0", Modifier.weight(1f), keyboard = KeyboardType.Decimal, suffix = if (vm.unitMb) "MB" else "KB")
                    Segmented(listOf("KB", "MB"), if (vm.unitMb) 1 else 0, Modifier.width(112.dp)) { vm.onUnit(it == 1) }
                }
            }

            Reveal(src?.kind == Kind.IMAGE) {
                Rule()
                Section("Output format") {
                    Segmented(OutFormat.entries.map { it.label }, vm.format.ordinal) { vm.onFormat(OutFormat.entries[it]) }
                }
            }

            Reveal(src?.kind == Kind.AUDIO) {
                Rule()
                Section("Output format") {
                    val formats = remember { AudioOut.available }
                    Segmented(formats.map { it.label }, formats.indexOf(vm.audioOut).coerceAtLeast(0)) { vm.onAudioOut(formats[it]) }
                    Text(vm.audioOut.hint, color = p.mute)
                }
                Rule()
                Section("Channels") {
                    Segmented(listOf("Auto", "Mono", "Stereo"), vm.channels) { vm.onChannels(it) }
                }
            }

            Reveal(src?.kind == Kind.VIDEO) {
                Rule()
                Section("Video codec") {
                    val codecs = remember { VideoCodec.available }
                    Segmented(codecs.map { it.label }, codecs.indexOf(vm.videoCodec).coerceAtLeast(0)) { vm.onVideoCodec(codecs[it]) }
                }
                Rule()
                Section("Audio") {
                    val audio = AudioCodec.entries
                    Segmented(audio.map { it.label }, vm.audioCodec.ordinal) { vm.onAudioCodec(audio[it]) }
                }
                Reveal(vm.audioCodec == AudioCodec.AAC || vm.audioCodec == AudioCodec.OPUS) {
                    Rule()
                    Section("Audio bitrate") {
                        val steps = listOf(64, 96, 128, 192)
                        Segmented(steps.map { "${it}k" }, steps.indexOf(vm.audioKbps).coerceAtLeast(0)) { vm.onAudioKbps(steps[it]) }
                    }
                }
                Rule()
                Section("Max resolution") {
                    val steps = listOf(0, 1080, 720, 480)
                    Segmented(listOf("Auto", "1080p", "720p", "480p"), steps.indexOf(vm.maxHeight).coerceAtLeast(0)) { vm.onMaxHeight(steps[it]) }
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

    val file = r.file
    if (file != null && src != null) {
        Rule()
        Section(if (src.kind == Kind.AUDIO) "Play audio" else "Play video") {
            var open by remember(r) { mutableStateOf(false) }
            var which by remember(r) { mutableIntStateOf(0) }
            if (!open) {
                PillButton(if (src.kind == Kind.AUDIO) "Play compressed audio" else "Play compressed video", style = PillStyle.Outlined) { open = true }
            } else {
                Segmented(listOf("Compressed", "Original"), which) { which = it }
                val uri = if (which == 0) Uri.fromFile(file) else src.uri
                val isAudio = src.kind == Kind.AUDIO
                val aspect = when {
                    isAudio -> 2.5f
                    which == 0 -> r.newW.toFloat() / r.newH.coerceAtLeast(1)
                    else -> r.origW.toFloat() / r.origH.coerceAtLeast(1)
                }
                key(which) { VideoPlayer(uri, aspect, audioOnly = isAudio) }
            }
        }
    }

    Rule()
    Section("What changed") {
        when {
            r.unchanged -> Text("The file is already under your " + (if (vm.targetText.isNotEmpty()) vm.targetText + " " else "") + (if (vm.unitMb) "MB" else "KB") + " target, so it was left as is.", color = p.mute)
            !r.hitTarget -> Text("This is as small as it gets without breaking the file. Try a bigger target.", color = p.accent)
        }
        if (src != null) {
            StatRow(
                "File size", formatSizeIn(src.bytes, vm.unitMb), formatSizeIn(r.size, vm.unitMb),
                delta(src.bytes.toDouble(), r.size.toDouble()),
            )
        }
        if (r.origW > 0) {
            val d = delta(r.origW.toDouble() * r.origH, r.newW.toDouble() * r.newH)
            StatRow(
                "Resolution", "${r.origW} \u00d7 ${r.origH}", "${r.newW} \u00d7 ${r.newH}",
                if (d == "no change" || d.isEmpty()) d else "$d pixels",
            )
        }
        r.codecInfo?.let { StatRow("Codec", it) }
        r.notice?.let { Text(it, color = p.accent) }
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
