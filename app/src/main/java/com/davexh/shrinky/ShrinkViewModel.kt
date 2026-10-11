package com.davexh.shrinky

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.isActive
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.davexh.shrinky.engine.AudioCodec
import com.davexh.shrinky.engine.AudioEngine
import com.davexh.shrinky.engine.AudioOut
import com.davexh.shrinky.engine.CropState
import com.davexh.shrinky.engine.Engine
import com.davexh.shrinky.engine.Images
import com.davexh.shrinky.engine.Kind
import com.davexh.shrinky.engine.OutFormat
import com.davexh.shrinky.engine.PdfMaker
import com.davexh.shrinky.engine.Saver
import com.davexh.shrinky.engine.Shrunk
import com.davexh.shrinky.engine.ToolJob
import com.davexh.shrinky.engine.ToolRequest
import com.davexh.shrinky.engine.VideoCodec
import com.davexh.shrinky.engine.VideoEngine
import com.davexh.shrinky.engine.VideoJob
import com.davexh.shrinky.engine.VideoRequest
import com.davexh.shrinky.engine.queryMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** File name + "was it saved" state shared by every tool. */
class SaveState {
    var name by mutableStateOf("")
    var savedAs by mutableStateOf<String?>(null)
    fun onName(v: String) { name = v; savedAs = null }
    fun reset(defaultName: String) { name = defaultName; savedAs = null }
}

abstract class BaseVm(app: Application) : AndroidViewModel(app) {
    protected val cr: ContentResolver get() = getApplication<Application>().contentResolver

    var busy by mutableStateOf(false); protected set
    var failure by mutableStateOf<String?>(null); protected set
    val save = SaveState()

    protected fun work(block: () -> Unit) {
        busy = true; failure = null
        viewModelScope.launch(Dispatchers.Default) {
            try { block() } catch (e: Throwable) { failure = e.message ?: "Something went wrong." } finally { busy = false }
        }
    }

    protected fun writeFile(ext: String, mime: String, bytes: ByteArray) {
        val name = Saver.fileName(save.name, ext)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Saver.save(cr, name, mime, bytes)
                save.savedAs = Saver.label + "/" + name
            } catch (e: Throwable) {
                failure = "Couldn't save: ${e.message}"
            }
        }
    }

    protected fun writeFile(ext: String, mime: String, file: java.io.File) {
        val name = Saver.fileName(save.name, ext)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Saver.save(cr, name, mime, file)
                save.savedAs = Saver.label + "/" + name
            } catch (e: Throwable) {
                failure = "Couldn't save: ${e.message}"
            }
        }
    }
}

// ---------------------------------------------------------------- Shrink

data class Source(val uri: Uri, val name: String, val bytes: Long, val kind: Kind, val mime: String)

class ShrinkVm(app: Application) : BaseVm(app) {
    var source by mutableStateOf<Source?>(null); private set
    var targetText by mutableStateOf(""); private set
    var unitMb by mutableStateOf(false); private set
    var format by mutableStateOf(OutFormat.JPG); private set
    var maxHeight by mutableIntStateOf(0); private set            // video only: 0 = auto, else 1080 / 720 / 480
    var videoCodec by mutableStateOf(VideoCodec.H264); private set  // video only
    var audioCodec by mutableStateOf(AudioCodec.ORIGINAL); private set
    var audioKbps by mutableIntStateOf(128); private set             // video only: bitrate of the re-encoded audio track
    var audioOut by mutableStateOf(AudioOut.AAC); private set        // audio files: output format
    var channels by mutableIntStateOf(0); private set                // audio files: 0 auto, 1 mono, 2 stereo

    /** Results belong to the background jobs, so they survive the app being left or recreated. */
    val result: Shrunk?
        get() = if (source?.kind == Kind.VIDEO) VideoJob.result else ToolJob.result

    // "Smallest possible" hint shown under the target field.
    var floorBytes by mutableStateOf<Long?>(null); private set
    var floorLabel by mutableStateOf("Smallest possible"); private set
    var floorTappable by mutableStateOf(true); private set
    var floorBusy by mutableStateOf(false); private set
    var losslessBytes by mutableStateOf<Long?>(null); private set
    var losslessLabel by mutableStateOf(""); private set          // contains "{size}", filled in by the UI in the chosen unit
    var losslessTappable by mutableStateOf(false); private set
    private var losslessSetsPng = false
    private var floorJob: kotlinx.coroutines.Job? = null

    val targetBytes: Long
        get() = ((targetText.toDoubleOrNull() ?: 0.0) * if (unitMb) 1_048_576.0 else 1024.0).toLong()

    init {
        // Coming back to a running or finished video job (e.g. the activity was recreated): restore what it was about.
        VideoJob.request?.let { r ->
            source = r.source; targetText = r.targetText; unitMb = r.unitMb
            maxHeight = r.maxHeight; videoCodec = r.video; audioCodec = r.audio; audioKbps = r.audioKbps
        } ?: ToolJob.request?.let { r ->
            source = r.source; targetText = r.targetText; unitMb = r.unitMb
            format = r.format; audioOut = r.audioOut; channels = r.channels
        }
        if (source != null) refreshFloor()
        viewModelScope.launch { snapshotFlow { VideoJob.running || ToolJob.running }.collect { busy = it } }
        viewModelScope.launch {
            snapshotFlow { VideoJob.failure ?: ToolJob.failure }.collect { f ->
                if (f != null) { failure = f; VideoJob.failure = null; ToolJob.failure = null }
            }
        }
        viewModelScope.launch {
            snapshotFlow { result }.collect { r ->
                val s = source
                if (r != null && s != null && save.name.isEmpty()) save.reset(s.name.substringBeforeLast('.') + "_shrunk")
            }
        }
    }

    private fun stale() {
        VideoJob.clear()
        ToolJob.clear()
        failure = null; save.savedAs = null
    }

    fun onTargetText(v: String) { targetText = v.filter { it.isDigit() || it == '.' }.take(7); stale() }
    fun onUnit(mb: Boolean) { unitMb = mb; stale() }
    fun onFormat(f: OutFormat) { format = f; stale(); if (source?.kind == Kind.IMAGE) refreshFloor() }
    fun onMaxHeight(h: Int) { maxHeight = h; stale() }
    fun onVideoCodec(c: VideoCodec) { videoCodec = c; stale(); if (source?.kind == Kind.VIDEO) refreshFloor() }
    fun onAudioCodec(c: AudioCodec) { audioCodec = c; stale(); if (source?.kind == Kind.VIDEO) refreshFloor() }
    fun onAudioKbps(k: Int) { audioKbps = k; stale(); if (source?.kind == Kind.VIDEO) refreshFloor() }
    fun onAudioOut(o: AudioOut) { audioOut = o; stale(); if (source?.kind == Kind.AUDIO) refreshFloor() }

    /** Works out the smallest file, and the best-quality size, for the current file and settings, off the main thread. */
    private fun refreshFloor() {
        floorJob?.cancel()
        val s = source
        if (s == null) { floorBytes = null; losslessBytes = null; floorBusy = false; return }
        val app = getApplication<Application>()
        val f = format; val ac = audioCodec; val kb = audioKbps; val out = audioOut; val vc = videoCodec
        floorBusy = true; floorBytes = null; losslessBytes = null
        floorJob = viewModelScope.launch(Dispatchers.IO) {
            var minB: Long? = null
            var minLabel = "Smallest possible"
            var minTap = true
            var llB: Long? = null
            var llLabel = ""
            var llTap = false
            var llPng = false
            val orig = s.bytes
            try {
                when (s.kind) {
                    Kind.IMAGE -> {
                        minB = Engine.smallestImage(cr, s.uri, f)
                        llB = runCatching { Engine.losslessImage(cr, s.uri) }.getOrNull()
                        llB?.let { b ->
                            if (orig > 0 && b >= orig) llLabel = "Without quality loss: {size} as PNG, larger than the original."
                            else { llLabel = "Without quality loss (PNG): about {size}."; llTap = true; llPng = true }
                        }
                    }
                    Kind.PDF -> {
                        minB = Engine.smallestPdf(cr, s.uri)
                        llB = runCatching { Engine.bestQualityPdf(cr, s.uri) }.getOrNull()
                        llB?.let { b ->
                            if (orig > 0 && b >= orig) llLabel = "Best quality: {size}, larger than the original. PDFs are re-rendered, so never pixel-perfect."
                            else { llLabel = "Best quality (PDFs are re-rendered, so not pixel-perfect): about {size}."; llTap = true }
                        }
                    }
                    Kind.VIDEO -> {
                        minB = VideoEngine.smallest(app, s.uri, ac, kb)
                        llB = runCatching { VideoEngine.nearLossless(app, s.uri, vc, ac, kb) }.getOrNull()
                        llB?.let { b ->
                            if (orig > 0 && b >= orig) llLabel = "Visually lossless needs about {size}, more than the original, so there's little to gain."
                            else { llLabel = "Visually lossless (estimate): about {size}."; llTap = true }
                        }
                    }
                    Kind.AUDIO -> {
                        // Lossless formats have no size knob, so show what a lossy one could do instead.
                        val lossy = if (out.lossless) (if (AudioOut.OPUS in AudioOut.available) AudioOut.OPUS else AudioOut.AAC) else out
                        if (out.lossless) { minLabel = "Smallest with ${lossy.label}"; minTap = false }
                        minB = AudioEngine.smallest(app, s.uri, lossy)
                        llB = runCatching { AudioEngine.losslessEstimate(app, s.uri) }.getOrNull()
                        llB?.let { llLabel = "Lossless (FLAC, estimate): about {size}." }
                    }
                }
            } catch (e: Exception) {
                minB = null
            }
            if (!isActive) return@launch
            floorBytes = minB; floorLabel = minLabel; floorTappable = minTap
            losslessBytes = llB; losslessLabel = llLabel; losslessTappable = llTap; losslessSetsPng = llPng
            floorBusy = false
        }
    }

    private fun fillTarget(bytes: Long) {
        val padded = bytes * 1.03
        targetText = if (unitMb) {
            (kotlin.math.ceil(padded / 1_048_576.0 * 10) / 10).toString()
        } else {
            maxOf(5L, kotlin.math.ceil(padded / 1024.0).toLong()).toString()
        }
    }

    /** Fills the target field with the smallest possible size (plus a little margin so rounding never undershoots). */
    fun useFloor() {
        val fb = floorBytes ?: return
        fillTarget(fb)
        stale()
    }

    /** Fills the target with the no-quality-loss size. For photos that also switches the output to PNG, which is lossless. */
    fun useLossless() {
        val lb = losslessBytes ?: return
        val png = losslessSetsPng
        if (png) format = OutFormat.PNG
        fillTarget(lb)
        stale()
        if (png) refreshFloor()
    }
    fun onChannels(c: Int) { channels = c; stale() }

    fun pick(uri: Uri?) {
        uri ?: return
        if (VideoJob.running || ToolJob.running) { failure = "Something is still compressing. Wait for it, or cancel it from the notification."; return }
        viewModelScope.launch(Dispatchers.IO) {
            val mime = cr.getType(uri).orEmpty()
            val kind = when {
                mime == "application/pdf" -> Kind.PDF
                mime.startsWith("image/") -> Kind.IMAGE
                mime.startsWith("video/") -> Kind.VIDEO
                mime.startsWith("audio/") || mime == "application/ogg" -> Kind.AUDIO
                else -> null
            }
            if (kind == null) { failure = "Only photos, videos, audio and PDFs are supported."; return@launch }
            VideoJob.clear()
            ToolJob.clear()
            VideoEngine.clearCache(getApplication())
            val (name, size) = queryMeta(cr, uri)
            source = Source(uri, name, size, kind, mime)
            format = when (mime) {
                "image/png" -> OutFormat.PNG
                "image/webp" -> OutFormat.WEBP
                else -> OutFormat.JPG
            }
            failure = null
            save.reset("")
            refreshFloor()
        }
    }

    fun shrink() {
        val s = source ?: return
        val t = targetBytes
        if (t < 5 * 1024) { failure = "Target must be at least 5 KB."; return }
        if (VideoJob.running || ToolJob.running) return
        save.reset("")
        if (s.kind == Kind.VIDEO) { shrinkVideo(s, t); return }

        val app = getApplication<Application>()
        val f = format
        val out = audioOut
        val ch = channels
        val resolver = app.contentResolver
        val (label, task) = when (s.kind) {
            Kind.AUDIO -> "Compressing audio" to { AudioEngine.compress(app, s.uri, t, out, ch) }
            Kind.PDF -> "Compressing PDF" to { Engine.compress(resolver, s.uri, s.kind, s.mime, s.bytes, t, f) }
            else -> "Compressing photo" to { Engine.compress(resolver, s.uri, s.kind, s.mime, s.bytes, t, f) }
        }
        failure = null
        VideoEngine.clearCache(app)
        ToolJob.begin(ToolRequest(s, targetText, unitMb, f, out, ch), label, task)
        startService(app)
    }

    private fun startService(app: Application) {
        try {
            app.startForegroundService(Intent(app, CompressService::class.java))
        } catch (e: Throwable) {
            val msg = "Couldn't start compression: ${e.message}"
            VideoJob.fail(msg); ToolJob.fail(msg)
        }
    }

    private fun shrinkVideo(s: Source, target: Long) {
        if (VideoJob.running) return
        if (s.bytes in 1..target) { failure = "This video is already under your target size."; return }
        failure = null
        val app = getApplication<Application>()
        VideoEngine.clearCache(app)
        VideoJob.begin(VideoRequest(s, target, maxHeight, videoCodec, audioCodec, audioKbps, targetText, unitMb))
        startService(app)
    }

    fun saveResult() {
        val r = result ?: return
        val f = r.file
        if (f != null) writeFile(r.ext, r.mime, f) else writeFile(r.ext, r.mime, r.bytes)
    }
}

// ---------------------------------------------------------------- Crop

class CropSource(val name: String, val origW: Int, val origH: Int)

class CropResult(val bytes: ByteArray, val ext: String, val mime: String, val w: Int, val h: Int, val preview: Bitmap)

/** Ratio presets for the crop tool (width : height). */
val CROP_RATIOS = listOf(1 to 1, 4 to 5, 3 to 4, 2 to 3, 9 to 16, 4 to 3, 3 to 2, 16 to 9)

class CropVm(app: Application) : BaseVm(app) {
    val editor = CropState()
    var src by mutableStateOf<CropSource?>(null); private set
    var wText by mutableStateOf(""); private set
    var hText by mutableStateOf(""); private set
    var mode by mutableIntStateOf(0); private set      // 0 = exact size, 1 = aspect ratio
    var ratio by mutableIntStateOf(0); private set       // preset chip matching the typed ratio, -1 = none
    var customW by mutableStateOf("1"); private set      // the ratio fields are the single source of truth
    var customH by mutableStateOf("1"); private set
    var format by mutableStateOf(OutFormat.JPG); private set
    var result by mutableStateOf<CropResult?>(null); private set

    private fun ratioPair(): Pair<Int, Int> = (customW.toIntOrNull() ?: 0) to (customH.toIntOrNull() ?: 0)

    val ratioValid: Boolean
        get() { val (a, b) = ratioPair(); return a > 0 && b > 0 }

    private fun syncRatioChip() {
        ratio = CROP_RATIOS.indexOfFirst { it.first.toString() == customW && it.second.toString() == customH }
    }

    private fun refreshAspect() {
        result = null; failure = null; save.savedAs = null
        if (mode == 0) editor.setAspect(wText.toIntOrNull() ?: 0, hText.toIntOrNull() ?: 0)
        else { val (a, b) = ratioPair(); editor.setAspect(a, b) }
    }

    fun onW(v: String) { wText = v.filter { it.isDigit() }.take(4); refreshAspect() }
    fun onH(v: String) { hText = v.filter { it.isDigit() }.take(4); refreshAspect() }
    fun onMode(m: Int) { mode = m; refreshAspect() }
    fun onRatio(i: Int) {
        ratio = i
        customW = CROP_RATIOS[i].first.toString()
        customH = CROP_RATIOS[i].second.toString()
        refreshAspect()
    }
    fun onCustomW(v: String) { customW = v.filter { it.isDigit() }.take(3); syncRatioChip(); refreshAspect() }
    fun onCustomH(v: String) { customH = v.filter { it.isDigit() }.take(3); syncRatioChip(); refreshAspect() }
    fun onFormat(f: OutFormat) { format = f; result = null; failure = null; save.savedAs = null }

    fun pick(uri: Uri?) {
        uri ?: return
        work {
            val mime = cr.getType(uri).orEmpty()
            if (!mime.startsWith("image/")) error("Pick a photo.")
            val d = Images.decode(cr, uri, 3072)
            val (name, _) = queryMeta(cr, uri)
            editor.image = d.bitmap
            editor.reset()
            format = when (mime) {
                "image/png" -> OutFormat.PNG
                "image/webp" -> OutFormat.WEBP
                else -> OutFormat.JPG
            }
            src = CropSource(name, d.origW, d.origH)
            refreshAspect()
        }
    }

    fun crop() {
        val bmp = editor.image ?: return
        val rect = editor.sourceRect()
        if (rect == null) {
            failure = if (mode == 0) "Enter a width and height first." else "Enter a ratio first."
            return
        }
        // Size mode: exactly what was typed. Ratio mode: the framed area at its own resolution.
        val size: Pair<Int, Int> =
            if (mode == 0) (wText.toIntOrNull() ?: 0) to (hText.toIntOrNull() ?: 0)
            else rect.width().roundToInt().coerceAtLeast(1) to rect.height().roundToInt().coerceAtLeast(1)
        val (w, h) = size
        if (w <= 0 || h <= 0) { failure = "Enter a width and height first."; return }
        if (w.toLong() * h > 25_000_000L) { failure = "Output is too large. Keep it under 25 megapixels."; return }
        val f = format
        val base = src?.name?.substringBeforeLast('.') ?: "crop"
        work {
            val out = Images.crop(bmp, rect, w, h, f)
            val bytes = Images.encode(out, f, 92)
            result = CropResult(bytes, f.ext, f.mime, w, h, Images.thumb(out))
            save.reset(base + "_" + w + "x" + h)
        }
    }

    fun saveResult() {
        val r = result ?: return
        writeFile(r.ext, r.mime, r.bytes)
    }
}

// ---------------------------------------------------------------- Images to PDF

class PageItem(val id: Long, val uri: Uri, val name: String, val thumb: Bitmap)

class PdfResult(val bytes: ByteArray, val pages: Int)

class PdfVm(app: Application) : BaseVm(app) {
    val pages = mutableStateListOf<PageItem>()
    var a4 by mutableStateOf(true); private set
    var result by mutableStateOf<PdfResult?>(null); private set
    private var nextId = 0L

    private fun stale() { result = null; failure = null; save.savedAs = null }

    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        busy = true
        viewModelScope.launch(Dispatchers.IO) {
            val items = uris.mapNotNull { u ->
                runCatching { PageItem(nextId++, u, queryMeta(cr, u).first, Images.decode(cr, u, 200).bitmap) }.getOrNull()
            }
            withContext(Dispatchers.Main) { pages.addAll(items); stale(); busy = false }
        }
    }

    fun move(i: Int, d: Int) {
        val j = i + d
        if (i !in pages.indices || j !in pages.indices) return
        val t = pages[i]; pages[i] = pages[j]; pages[j] = t
        stale()
    }

    /** Moves the page at [from] so it ends up at index [to] (used by long-press drag). */
    fun reorder(from: Int, to: Int) {
        if (from !in pages.indices || to !in pages.indices || from == to) return
        pages.add(to, pages.removeAt(from))
        stale()
    }

    fun remove(i: Int) { if (i in pages.indices) { pages.removeAt(i); stale() } }

    fun onA4(v: Boolean) { a4 = v; stale() }

    fun make() {
        val uris = pages.map { it.uri }
        if (uris.isEmpty()) return
        val a = a4
        work {
            result = PdfResult(PdfMaker.make(cr, uris, a), uris.size)
            save.reset("pages")
        }
    }

    fun saveResult() {
        val r = result ?: return
        writeFile("pdf", "application/pdf", r.bytes)
    }
}
