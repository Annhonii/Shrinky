package com.davexh.shrinky.engine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.davexh.shrinky.Source

class VideoRequest(
    val source: Source,
    val target: Long,
    val maxHeight: Int,
    val video: VideoCodec,
    val audio: AudioCodec,
    val audioKbps: Int,
    val targetText: String,
    val unitMb: Boolean,
) {
    /** Bits per second reserved for the audio track when budgeting the video bitrate. */
    val audioBps: Int
        get() = when (audio) {
            AudioCodec.ORIGINAL -> 128_000
            AudioCodec.MUTE -> 0
            else -> audioKbps * 1000
        }
}

/**
 * The one running (or last finished) video job. It lives outside any ViewModel so compression keeps going
 * when the app is switched away or the screen is recreated; CompressService does the work, the UI just observes.
 */
object VideoJob {
    var running by mutableStateOf(false); private set
    var progress by mutableFloatStateOf(0f)
    var result by mutableStateOf<Shrunk?>(null)
    var failure by mutableStateOf<String?>(null)
    var request: VideoRequest? = null; private set

    fun begin(r: VideoRequest) {
        result?.file?.delete()
        request = r; running = true; progress = 0f; result = null; failure = null
    }

    fun succeed(r: Shrunk) { result = r; running = false }
    fun fail(message: String) { failure = message; running = false }

    fun clear() {
        if (running) return
        result?.file?.delete()
        result = null; failure = null; request = null
    }
}
