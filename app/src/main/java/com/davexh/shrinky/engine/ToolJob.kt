package com.davexh.shrinky.engine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.davexh.shrinky.Source

class ToolRequest(
    val source: Source,
    val targetText: String,
    val unitMb: Boolean,
    val format: OutFormat,
    val audioOut: AudioOut,
    val channels: Int,
)

/**
 * Photo, PDF and audio jobs. Like [VideoJob] it lives outside the ViewModel and is run by CompressService,
 * so the work finishes (and notifies) even if the app is left.
 */
object ToolJob {
    var running by mutableStateOf(false); private set
    var result by mutableStateOf<Shrunk?>(null)
    var failure by mutableStateOf<String?>(null)
    var request: ToolRequest? = null; private set
    var label: String = "Compressing"; private set
    var task: (() -> Shrunk)? = null; private set

    fun begin(r: ToolRequest, label: String, task: () -> Shrunk) {
        result?.file?.delete()
        request = r; this.label = label; this.task = task
        running = true; result = null; failure = null
    }

    fun succeed(r: Shrunk) { result = r; running = false; task = null }
    fun fail(message: String) { failure = message; running = false; task = null }

    fun clear() {
        if (running) return
        result?.file?.delete()
        result = null; failure = null; request = null; task = null
    }
}
