package com.davexh.shrinky

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
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
    private var localResult by mutableStateOf<Shrunk?>(null)
    var targetText by mutableStateOf(""); private set
    var unitMb by mutableStateOf(false); private set
    var format by mutableStateOf(OutFormat.JPG); private set
    var maxHeight by mutableIntStateOf(0); private set            // video only: 0 = auto, else 1080 / 720 / 480
    var videoCodec by mutableStateOf(VideoCodec.H264); private set  // video only
    var audioCodec by mutableStateOf(AudioCodec.ORIGINAL); private set
    var audioKbps by mutableIntStateOf(128); private set             // video only: bitrate of the re-encoded audio track
    var audioOut by mutableStateOf(AudioOut.AAC); private set        // audio files: output format
    var channels by mutableIntStateOf(0); private set                // audio files: 0 auto, 1 mono, 2 stereo

    /** Images and PDFs are computed here; a video result belongs to VideoJob so it survives the app being left. */
    val result: Shrunk?
        get() = if (source?.kind == Kind.VIDEO) VideoJob.result else localResult

    val targetBytes: Long
        get() = ((targetText.toDoubleOrNull() ?: 0.0) * if (unitMb) 1_048_576.0 else 1024.0).toLong()

    init {
        // Coming back to a running or finished video job (e.g. the activity was recreated): restore what it was about.
        VideoJob.request?.let { r ->
            source = r.source; targetText = r.targetText; unitMb = r.unitMb
            maxHeight = r.maxHeight; videoCodec = r.video; audioCodec = r.audio; audioKbps = r.audioKbps
        }
        viewModelScope.launch { snapshotFlow { VideoJob.running }.collect { busy = it } }
        viewModelScope.launch {
            snapshotFlow { VideoJob.failure }.collect { f ->
                if (f != null) { failure = f; VideoJob.failure = null }
            }
        }
        viewModelScope.launch {
            snapshotFlow { VideoJob.result }.collect { r ->
                val s = source
                if (r != null && s != null && s.kind == Kind.VIDEO && save.name.isEmpty()) {
                    save.reset(s.name.substringBeforeLast('.') + "_shrunk")
                }
            }
        }
    }

    private fun stale() {
        localResult = null
        if (!VideoJob.running) VideoJob.clear()
        failure = null; save.savedAs = null
    }

    fun onTargetText(v: String) { targetText = v.filter { it.isDigit() || it == '.' }.take(7); stale() }
    fun onUnit(mb: Boolean) { unitMb = mb; stale() }
    fun onFormat(f: OutFormat) { format = f; stale() }
    fun onMaxHeight(h: Int) { maxHeight = h; stale() }
    fun onVideoCodec(c: VideoCodec) { videoCodec = c; stale() }
    fun onAudioCodec(c: AudioCodec) { audioCodec = c; stale() }
    fun onAudioKbps(k: Int) { audioKbps = k; stale() }
    fun onAudioOut(o: AudioOut) { audioOut = o; stale() }
    fun onChannels(c: Int) { channels = c; stale() }

    fun pick(uri: Uri?) {
        uri ?: return
        if (VideoJob.running) { failure = "A video is still compressing. Cancel it from the notification first."; return }
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
            VideoEngine.clearCache(getApplication())
            val (name, size) = queryMeta(cr, uri)
            source = Source(uri, name, size, kind, mime)
            format = when (mime) {
                "image/png" -> OutFormat.PNG
                "image/webp" -> OutFormat.WEBP
                else -> OutFormat.JPG
            }
            localResult = null
            failure = null
            save.reset("")
        }
    }

    fun shrink() {
        val s = source ?: return
        val t = targetBytes
        if (t < 5 * 1024) { failure = "Target must be at least 5 KB."; return }
        val f = format
        localResult = null
        save.reset("")
        if (s.kind == Kind.VIDEO) { shrinkVideo(s, t); return }
        if (s.kind == Kind.AUDIO) {
            val out = audioOut
            val ch = channels
            val app = getApplication<Application>()
            work {
                localResult = AudioEngine.compress(app, s.uri, t, out, ch)
                save.reset(s.name.substringBeforeLast('.') + "_shrunk")
            }
            return
        }
        work {
            localResult = Engine.compress(cr, s.uri, s.kind, s.mime, s.bytes, t, f)
            save.reset(s.name.substringBeforeLast('.') + "_shrunk")
        }
    }

    private fun shrinkVideo(s: Source, target: Long) {
        if (VideoJob.running) return
        if (s.bytes in 1..target) { failure = "This video is already under your target size."; return }
        failure = null
        val app = getApplication<Application>()
        VideoEngine.clearCache(app)
        VideoJob.begin(VideoRequest(s, target, maxHeight, videoCodec, audioCodec, audioKbps, targetText, unitMb))
        try {
            app.startForegroundService(Intent(app, CompressService::class.java))
        } catch (e: Throwable) {
            VideoJob.fail("Couldn't start compression: ${e.message}")
        }
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
