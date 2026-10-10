package com.davexh.shrinky.engine

import android.media.MediaCodecList
import androidx.media3.common.MimeTypes

/** [bpp] = bits per pixel per frame needed for clean-looking output. Newer codecs need fewer bits. */
enum class VideoCodec(val label: String, val mime: String, val bpp: Double) {
    H264("H.264", MimeTypes.VIDEO_H264, 0.070),
    H265("H.265", MimeTypes.VIDEO_H265, 0.050),
    AV1("AV1", MimeTypes.VIDEO_AV1, 0.040),
    VP9("VP9", MimeTypes.VIDEO_VP9, 0.055);

    companion object {
        /** Only codecs this phone can actually encode. H.264 is always there. */
        val available: List<VideoCodec> by lazy {
            val types = runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .filter { it.isEncoder }
                    .flatMap { it.supportedTypes.toList() }
                    .map { it.lowercase() }
                    .toSet()
            }.getOrDefault(emptySet())
            entries.filter { it == H264 || it.mime.lowercase() in types }
        }

        fun labelOf(mime: String?): String = when (mime?.lowercase()) {
            MimeTypes.VIDEO_H264 -> "H.264"
            MimeTypes.VIDEO_H265 -> "H.265"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_VP9 -> "VP9"
            null -> "?"
            else -> mime.substringAfter('/')
        }
    }
}

/** [mime] null = keep the original track untouched (or drop it for MUTE). [bps] is the budget reserved for audio. */
enum class AudioCodec(val label: String, val mime: String?, val bps: Int) {
    ORIGINAL("Original", null, 128_000),
    AAC("AAC", MimeTypes.AUDIO_AAC, 128_000),
    OPUS("Opus", MimeTypes.AUDIO_OPUS, 96_000),
    MUTE("Mute", null, 0);

    companion object {
        fun labelOf(mime: String?): String = when (mime?.lowercase()) {
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_OPUS -> "Opus"
            null -> "none"
            else -> mime.substringAfter('/')
        }
    }
}
