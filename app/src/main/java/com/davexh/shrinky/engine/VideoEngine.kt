package com.davexh.shrinky.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlin.math.sqrt

class VideoInfo(val durationMs: Long, val width: Int, val height: Int, val hasAudio: Boolean, val frame: Bitmap?)

class VideoOut(
    val file: File,
    val hitTarget: Boolean,
    val origW: Int, val origH: Int,
    val newW: Int, val newH: Int,
    val before: Bitmap?, val after: Bitmap?,
    val videoMime: String?, val audioMime: String?,
)

/**
 * Video compression on top of Media3 Transformer (the phone's own encoders, nothing bundled).
 * Target size is turned into a bitrate budget; if the encoder overshoots, it retries with a corrected bitrate.
 */
object VideoEngine {
    private const val MIN_VIDEO_BPS = 100_000
    private const val ASSUMED_FPS = 30
    private const val SAFETY = 0.93           // container overhead + rate-control slop
    private const val MAX_TRIES = 3

    fun probe(ctx: Context, uri: Uri): VideoInfo {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            fun key(k: Int) = r.extractMetadata(k)
            val rot = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            var w = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }   // upright size, what the user sees
            val dur = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val audio = key(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val frame = r.getFrameAtTime(dur * 1000 / 2, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            return VideoInfo(dur, w, h, audio, frame)
        } finally {
            runCatching { r.release() }
        }
    }

    /** [onProgress] gets (pass number, 0..1 within that pass): a retry pass starts again from 0. */
    suspend fun compress(ctx: Context, req: VideoRequest, onProgress: (Int, Float) -> Unit): VideoOut {
        val uri = req.source.uri
        val target = req.target
        val info = withContext(Dispatchers.IO) { probe(ctx, uri) }
        if (info.durationMs <= 0 || info.width <= 0 || info.height <= 0) error("Can't read this video.")

        val seconds = info.durationMs / 1000.0
        val audioBps = if (info.hasAudio) req.audioBps else 0
        var videoBps = ((target * 8 * SAFETY) / seconds).toLong() - audioBps
        if (videoBps < MIN_VIDEO_BPS) error("Target is too small for a video this long. Try a bigger target.")

        var best: File? = null
        var bestSize = Long.MAX_VALUE
        var bestUsed: ExportResult? = null
        var hit = false
        var finalH = info.height

        for (attempt in 1..MAX_TRIES) {
            // Resolution follows the bitrate: fewer bits per second means fewer pixels, never upscaling.
            val pixels = videoBps / (req.video.bpp * ASSUMED_FPS)
            val aspect = info.width.toDouble() / info.height
            var h = sqrt(pixels / aspect).roundToInt().coerceAtLeast(240)
            if (req.maxHeight > 0) h = minOf(h, req.maxHeight)
            h = minOf(h, info.height) and 1.inv()           // even number, as encoders want
            finalH = h

            val effects = if (h < info.height) listOf<Effect>(Presentation.createForHeight(h)) else emptyList()
            val tmp = File(ctx.cacheDir, "shrinky_try$attempt.mp4")
            val used = runTransformer(ctx, uri, tmp, videoBps.toInt(), effects, req, info.hasAudio) {
                onProgress(attempt, it)
            }

            val size = tmp.length()
            if (size < bestSize || size <= target && bestSize > target) {
                best?.delete(); best = tmp; bestSize = size; bestUsed = used
            } else tmp.delete()

            if (size <= target) { hit = true; break }
            // Overshot: scale the bitrate by how far off we were, with a little extra margin.
            videoBps = (videoBps * (target.toDouble() / size) * 0.94).toLong()
            if (videoBps < MIN_VIDEO_BPS) break
        }

        val chosen = best ?: error("Compression failed.")
        val out = File(ctx.cacheDir, "shrinky_video.mp4")
        withContext(Dispatchers.IO) {
            out.delete()
            if (!chosen.renameTo(out)) { chosen.copyTo(out, overwrite = true); chosen.delete() }
        }

        val after = withContext(Dispatchers.IO) { runCatching { probe(ctx, Uri.fromFile(out)) }.getOrNull() }
        val newW = after?.width?.takeIf { it > 0 } ?: (finalH * info.width.toDouble() / info.height).roundToInt()
        val newH = after?.height?.takeIf { it > 0 } ?: finalH
        return VideoOut(
            out, hit, info.width, info.height, newW, newH,
            before = info.frame?.let { Images.thumb(it) },
            after = after?.frame?.let { Images.thumb(it) },
            videoMime = bestUsed?.videoMimeType,
            audioMime = bestUsed?.audioMimeType,
        )
    }

    fun clearCache(ctx: Context) {
        ctx.cacheDir.listFiles { f -> f.name.startsWith("shrinky_") && f.name.endsWith(".mp4") }?.forEach { it.delete() }
    }

    /** Transformer must be driven from a thread with a Looper, so everything here runs on the main thread. */
    private suspend fun runTransformer(
        ctx: Context, uri: Uri, dest: File, bitrate: Int, effects: List<Effect>,
        req: VideoRequest, hasAudio: Boolean, onProgress: (Float) -> Unit,
    ) = suspendCancellableCoroutine<ExportResult> { cont ->
        val main = Handler(Looper.getMainLooper())
        main.post {
            if (!cont.isActive) return@post
            dest.delete()
            val encoder = DefaultEncoderFactory.Builder(ctx)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(req.audioBps.takeIf { it > 0 } ?: 128_000).build())
                .build()
            lateinit var poll: Runnable
            val builder = Transformer.Builder(ctx)
                .setVideoMimeType(req.video.mime)
                .setEncoderFactory(encoder)
            req.audio.mime?.let { builder.setAudioMimeType(it) }
            val transformer = builder
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        main.removeCallbacks(poll)
                        if (cont.isActive) cont.resume(exportResult)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        main.removeCallbacks(poll)
                        if (cont.isActive) cont.resumeWithException(
                            IllegalStateException(exportException.message ?: "Couldn't compress this video."),
                        )
                    }
                })
                .build()

            val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setEffects(Effects(emptyList(), effects))
                .setRemoveAudio(req.audio == AudioCodec.MUTE && hasAudio)
                .build()

            val holder = ProgressHolder()
            poll = Runnable {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                main.postDelayed(poll, 250)
            }
            cont.invokeOnCancellation { main.post { main.removeCallbacks(poll); transformer.cancel() } }

            try {
                transformer.start(item, dest.absolutePath)
                main.postDelayed(poll, 250)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    }
}
