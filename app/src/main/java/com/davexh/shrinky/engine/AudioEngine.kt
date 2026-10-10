package com.davexh.shrinky.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

/** Output formats for audio files. All of them use the phone's own encoders, nothing is bundled. */
enum class AudioOut(
    val label: String, val ext: String, val mime: String,
    val lossless: Boolean, val codecMime: String?, val hint: String,
) {
    AAC("AAC", "m4a", "audio/mp4", false, "audio/mp4a-latm", "Small files that play everywhere. Needs a bit more size than Opus for the same quality."),
    OPUS("Opus", "opus", "audio/ogg", false, "audio/opus", "Best quality at small sizes, great for voice. Some older players can't open it."),
    FLAC("FLAC", "flac", "audio/flac", true, "audio/flac", "Lossless: no quality lost, but much bigger. The size can't be set."),
    WAV("WAV", "wav", "audio/wav", true, null, "Uncompressed and the biggest. Only for editing or old devices.");

    companion object {
        private fun hasEncoder(mime: String): Boolean = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .any { info -> info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
        }.getOrDefault(false)

        /** Only formats this phone can actually write. */
        val available: List<AudioOut> by lazy {
            entries.filter { it.codecMime == null || hasEncoder(it.codecMime) }
        }
    }
}

object AudioEngine {
    private const val SAFETY = 0.96
    private const val MAX_TRIES = 3
    private val AAC_RATES = setOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)

    /** [channelPref]: 0 = auto, 1 = mono, 2 = stereo. */
    fun compress(ctx: Context, uri: Uri, target: Long, out: AudioOut, channelPref: Int): Shrunk {
        val seconds = durationSec(ctx, uri)
        var bitrate = 0
        if (!out.lossless) {
            bitrate = ((target * 8 * SAFETY) / seconds).toLong().coerceAtMost(2_000_000L).toInt()
            if (bitrate < minBitrate(out)) error("Target is too small for audio this long. Try a bigger target.")
        }

        var best: File? = null
        var bestSize = Long.MAX_VALUE
        var bestRun: Run? = null
        var hit = false
        val tries = if (out.lossless) 1 else MAX_TRIES
        for (attempt in 1..tries) {
            val tmp = File(ctx.cacheDir, "shrinky_audio_try$attempt.${out.ext}")
            val run = encodeOnce(ctx, uri, tmp, out, bitrate, channelPref)
            val size = tmp.length()
            if (size < bestSize || size <= target && bestSize > target) {
                best?.delete(); best = tmp; bestSize = size; bestRun = run
            } else tmp.delete()
            if (size <= target) { hit = true; break }
            if (out.lossless) break
            bitrate = (bitrate * (target.toDouble() / size) * 0.95).toInt()
            if (bitrate < minBitrate(out)) break
        }

        val chosen = best ?: error("Compression failed.")
        val dest = File(ctx.cacheDir, "shrinky_audio.${out.ext}")
        dest.delete()
        if (!chosen.renameTo(dest)) { chosen.copyTo(dest, overwrite = true); chosen.delete() }
        return Shrunk(
            ByteArray(0), out.mime, out.ext, hit,
            file = dest, codecInfo = bestRun?.desc, notice = bestRun?.notice,
        )
    }

    private class Run(val desc: String, val notice: String?)

    private fun minBitrate(o: AudioOut) = if (o == AudioOut.OPUS) 6_000 else 8_000
    private fun maxBitrate(o: AudioOut, ch: Int) = if (o == AudioOut.OPUS) 256_000 * ch else 160_000 * ch

    private fun autoChannels(o: AudioOut, bitrate: Int, inCh: Int): Int = when {
        inCh <= 1 -> 1
        o.lossless -> min(inCh, 2)
        o == AudioOut.OPUS -> if (bitrate < 28_000) 1 else 2
        else -> if (bitrate < 40_000) 1 else 2
    }

    private fun targetRate(o: AudioOut, sr: Int): Int = when (o) {
        AudioOut.OPUS -> 48_000
        AudioOut.AAC -> if (sr in AAC_RATES) sr else 44_100
        else -> sr
    }

    private fun encodeOnce(ctx: Context, uri: Uri, dest: File, out: AudioOut, bitrate: Int, pref: Int): Run {
        dest.delete()
        var sink: Sink? = null
        var resampler: Resampler? = null
        var inCh = 0
        var outCh = 0
        var outRate = 0
        var usedBr = 0
        var notice: String? = null
        var finished = false
        try {
            PcmDecoder.run(
                ctx, uri,
                onFormat = { sr, ch ->
                    inCh = ch
                    outCh = when (pref) { 1 -> 1; 2 -> 2; else -> autoChannels(out, bitrate, ch) }
                    if (pref == 0 && !out.lossless && outCh == 1 && ch >= 2) {
                        notice = "Switched to mono so the quality holds up at this size."
                    }
                    outRate = targetRate(out, sr)
                    usedBr = if (out.lossless) 0 else bitrate.coerceIn(minBitrate(out), maxBitrate(out, outCh))
                    resampler = if (outRate != sr) Resampler(sr, outRate, outCh) else null
                    sink = if (out == AudioOut.WAV) WavSink(dest, outRate, outCh) else CodecSink(dest, out, outRate, outCh, usedBr)
                },
                onPcm = { data ->
                    val converted = convertChannels(data, inCh, outCh)
                    val r = resampler
                    val s = sink!!
                    if (r == null) s.write(converted) else r.process(converted) { s.write(it) }
                },
            )
            val s = sink ?: error("Couldn't decode this audio.")
            s.finish()
            finished = true
            val desc = buildString {
                append(out.label)
                if (!out.lossless) append(", ${usedBr / 1000} kbps")
                append(if (outCh == 1) ", mono" else ", stereo")
                append(", ${if (outRate % 1000 == 0) outRate / 1000 else (outRate / 100) / 10.0} kHz")
                if (s is CodecSink && s.heAac) append(", HE-AAC")
            }
            return Run(desc, notice)
        } finally {
            if (!finished) sink?.abort()
        }
    }

    private fun durationSec(ctx: Context, uri: Uri): Double {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (ms > 0) return ms / 1000.0
        } catch (ignored: Exception) {
        } finally {
            runCatching { r.release() }
        }
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true && f.containsKey(MediaFormat.KEY_DURATION)) {
                    return f.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0
                }
            }
        } finally {
            ex.release()
        }
        error("Can't read this audio.")
    }

    // ---------------------------------------------------------------- PCM helpers

    private fun convertChannels(data: ShortArray, inCh: Int, outCh: Int): ShortArray {
        if (inCh == outCh) return data
        val frames = data.size / inCh
        return if (outCh == 1) {
            ShortArray(frames) { i -> ((data[i * inCh].toInt() + data[i * inCh + 1].toInt()) / 2).toShort() }
        } else {
            val o = ShortArray(frames * 2)
            for (i in 0 until frames) {
                o[i * 2] = data[i * inCh]
                o[i * 2 + 1] = if (inCh == 1) data[i * inCh] else data[i * inCh + 1]
            }
            o
        }
    }

    /** Streaming linear-interpolation resampler for interleaved 16-bit audio. */
    private class Resampler(inRate: Int, outRate: Int, private val ch: Int) {
        private val step = inRate.toDouble() / outRate
        private var pos = 0.0
        private var prev: ShortArray? = null

        fun process(input: ShortArray, emit: (ShortArray) -> Unit) {
            val m = input.size / ch
            if (m == 0) return
            val p0 = prev ?: ShortArray(ch) { input[it] }
            val n = m + 1                                   // the previous chunk's last frame + this chunk
            fun get(frame: Int, c: Int): Int = if (frame == 0) p0[c].toInt() else input[(frame - 1) * ch + c].toInt()
            val buf = ShortArray((((n - pos) / step).toInt() + 2) * ch)
            var count = 0
            var p = pos
            while (p < n - 1) {
                val i = p.toInt()
                val f = (p - i).toFloat()
                for (c in 0 until ch) {
                    val v = get(i, c) * (1f - f) + get(i + 1, c) * f
                    buf[count++] = v.roundToInt().coerceIn(-32768, 32767).toShort()
                }
                p += step
            }
            pos = p - (n - 1)
            prev = ShortArray(ch) { input[(m - 1) * ch + it] }
            if (count > 0) emit(buf.copyOf(count))
        }
    }

    // ---------------------------------------------------------------- decoding

    private object PcmDecoder {
        fun run(ctx: Context, uri: Uri, onFormat: (sampleRate: Int, channels: Int) -> Unit, onPcm: (ShortArray) -> Unit) {
            val ex = MediaExtractor()
            var dec: MediaCodec? = null
            try {
                ex.setDataSource(ctx, uri, null)
                var idx = -1
                for (i in 0 until ex.trackCount) {
                    if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { idx = i; break }
                }
                if (idx < 0) error("No audio found in this file.")
                ex.selectTrack(idx)
                val fmt = ex.getTrackFormat(idx)
                val d = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
                dec = d
                d.configure(fmt, null, null, 0)
                d.start()

                val info = MediaCodec.BufferInfo()
                var inDone = false
                var outDone = false
                var announced = false
                var floatPcm = false

                fun announce() {
                    val of = d.outputFormat
                    floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    if (!announced) {
                        announced = true
                        onFormat(of.getInteger(MediaFormat.KEY_SAMPLE_RATE), of.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    }
                }

                while (!outDone) {
                    if (!inDone) {
                        val i = d.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val b = d.getInputBuffer(i)!!
                            val n = ex.readSampleData(b, 0)
                            if (n < 0) {
                                d.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                d.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = d.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> announce()
                        o >= 0 -> {
                            if (!announced) announce()
                            val b = d.getOutputBuffer(o)
                            if (b != null && info.size > 0) {
                                b.position(info.offset)
                                b.limit(info.offset + info.size)
                                b.order(ByteOrder.LITTLE_ENDIAN)
                                val arr: ShortArray
                                if (floatPcm) {
                                    val fb = b.asFloatBuffer()
                                    arr = ShortArray(fb.remaining()) { (fb.get() * 32767f).roundToInt().coerceIn(-32768, 32767).toShort() }
                                } else {
                                    val sb = b.asShortBuffer()
                                    arr = ShortArray(sb.remaining())
                                    sb.get(arr)
                                }
                                if (arr.isNotEmpty()) onPcm(arr)
                            }
                            d.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                        }
                    }
                }
            } finally {
                runCatching { dec?.stop() }
                runCatching { dec?.release() }
                runCatching { ex.release() }
            }
        }
    }

    // ---------------------------------------------------------------- writing

    private interface Sink {
        fun write(pcm: ShortArray)
        fun finish()
        fun abort()
    }

    private class WavSink(file: File, private val sr: Int, private val ch: Int) : Sink {
        private val raf = RandomAccessFile(file, "rw")
        private var dataLen = 0L

        init { raf.setLength(0); raf.write(ByteArray(44)) }

        override fun write(pcm: ShortArray) {
            val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bb.asShortBuffer().put(pcm)
            raf.write(bb.array())
            dataLen += pcm.size * 2L
        }

        override fun finish() {
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt((36 + dataLen).toInt()).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(ch.toShort())
            h.putInt(sr).putInt(sr * ch * 2).putShort((ch * 2).toShort()).putShort(16)
            h.put("data".toByteArray()).putInt(dataLen.toInt())
            raf.seek(0); raf.write(h.array()); raf.close()
        }

        override fun abort() { runCatching { raf.close() } }
    }

    /** AAC (m4a) and Opus (ogg) go through MediaMuxer. FLAC has no muxer on Android, so its stream is written raw. */
    private class CodecSink(
        file: File, private val out: AudioOut, private val sr: Int, private val ch: Int, private val bitrate: Int,
    ) : Sink {
        var heAac = false; private set
        private val enc: MediaCodec
        private var muxer: MediaMuxer? = null
        private var raw: FileOutputStream? = null
        private var track = -1
        private var samples = 0L
        private var eos = false
        private var flacHeaderDone = false
        private val info = MediaCodec.BufferInfo()

        init {
            val mime = out.codecMime!!
            var e: MediaCodec? = null
            // Low bitrates sound far better with HE-AAC; fall back to plain AAC-LC if the phone lacks it.
            if (out == AudioOut.AAC && ((ch == 2 && bitrate < 56_000) || (ch == 1 && bitrate < 32_000))) {
                e = runCatching { open(mime, MediaCodecInfo.CodecProfileLevel.AACObjectHE) }.getOrNull()
                heAac = e != null
            }
            if (e == null) e = open(mime, if (out == AudioOut.AAC) MediaCodecInfo.CodecProfileLevel.AACObjectLC else null)
            enc = e
            enc.start()
            if (out == AudioOut.FLAC) raw = FileOutputStream(file)
            else muxer = MediaMuxer(
                file.absolutePath,
                if (out == AudioOut.OPUS) MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
        }

        private fun open(mime: String, profile: Int?): MediaCodec {
            fun build(cbr: Boolean): MediaCodec {
                val f = MediaFormat.createAudioFormat(mime, sr, ch)
                if (out != AudioOut.FLAC) f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                if (profile != null) f.setInteger(MediaFormat.KEY_AAC_PROFILE, profile)
                if (out == AudioOut.FLAC) f.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
                if (cbr && out != AudioOut.FLAC) f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                val c = MediaCodec.createEncoderByType(mime)
                try {
                    c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                } catch (ex: Exception) {
                    c.release(); throw ex
                }
                return c
            }
            return try { build(true) } catch (ignored: Exception) { build(false) }
        }

        override fun write(pcm: ShortArray) {
            val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bb.asShortBuffer().put(pcm)
            val bytes = bb.array()
            val frame = ch * 2
            var off = 0
            while (off < bytes.size) {
                drain(0)
                val i = enc.dequeueInputBuffer(10_000)
                if (i < 0) continue
                val b = enc.getInputBuffer(i)!!
                b.clear()
                var len = min(b.remaining(), bytes.size - off)
                len -= len % frame
                b.put(bytes, off, len)
                enc.queueInputBuffer(i, 0, len, samples * 1_000_000L / sr, 0)
                samples += len / frame
                off += len
            }
        }

        override fun finish() {
            while (true) {
                val i = enc.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    enc.queueInputBuffer(i, 0, 0, samples * 1_000_000L / sr, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(0)
            }
            var guard = 0
            while (!eos && guard++ < 3000) drain(10_000)
            val done = eos
            close()
            if (!done) error("The encoder didn't finish.")
        }

        override fun abort() = close()

        private fun close() {
            runCatching { enc.stop() }
            runCatching { enc.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { raw?.close() }
        }

        private fun drain(timeoutUs: Long) {
            while (true) {
                val o = enc.dequeueOutputBuffer(info, timeoutUs)
                when {
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> muxer?.let { track = it.addTrack(enc.outputFormat); it.start() }
                    o >= 0 -> {
                        val buf = enc.getOutputBuffer(o)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0 && buf != null) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            if (raw != null) writeFlac(buf, isConfig)
                            else if (!isConfig && track >= 0) muxer!!.writeSampleData(track, buf, info)
                        }
                        enc.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { eos = true; return }
                    }
                }
            }
        }

        private fun writeFlac(buf: ByteBuffer, isConfig: Boolean) {
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            val o = raw!!
            if (isConfig) {
                o.write(flacHeader(bytes)); flacHeaderDone = true
            } else {
                if (!flacHeaderDone) { o.write(flacHeader(ByteArray(0))); flacHeaderDone = true }
                o.write(bytes)
            }
        }

        /** A .flac file starts with "fLaC" + a STREAMINFO block. Phones differ in what their encoder hands over, so normalise. */
        private fun flacHeader(cfg: ByteArray): ByteArray {
            val marker = "fLaC".toByteArray()
            if (cfg.size >= 4 && cfg[0] == 'f'.code.toByte() && cfg[1] == 'L'.code.toByte() && cfg[2] == 'a'.code.toByte() && cfg[3] == 'C'.code.toByte()) {
                return cfg
            }
            if (cfg.size == 38 && cfg[3].toInt() == 34) {
                val c = cfg.copyOf(); c[0] = (c[0].toInt() or 0x80).toByte()   // mark as the last metadata block
                return marker + c
            }
            if (cfg.size == 34) return marker + byteArrayOf(0x80.toByte(), 0, 0, 34) + cfg
            val si = ByteBuffer.allocate(34)
            si.putShort(4096).putShort(4096)
            si.put(ByteArray(6))                                           // min / max frame size: unknown
            si.putLong((sr.toLong() shl 44) or ((ch - 1).toLong() shl 41) or (15L shl 36))
            si.put(ByteArray(16))                                          // MD5: unknown
            return marker + byteArrayOf(0x80.toByte(), 0, 0, 34) + si.array()
        }
    }
}
