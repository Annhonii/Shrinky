package com.davexh.shrinky

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.davexh.shrinky.engine.AudioCodec
import com.davexh.shrinky.engine.Shrunk
import com.davexh.shrinky.engine.ToolJob
import com.davexh.shrinky.engine.VideoCodec
import com.davexh.shrinky.engine.VideoEngine
import com.davexh.shrinky.engine.VideoJob
import com.davexh.shrinky.ui.formatSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs every compression (video, photo, PDF, audio) as a foreground service so it keeps going in the background,
 * with a notification (progress for video, a busy bar for the rest) and a Cancel button.
 */
class CompressService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastText: String? = null
    private var pass = 0
    private var passStart = 0L
    private var title = "Compressing"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }
        val video = VideoJob.request
        val isVideo = VideoJob.running && video != null
        val isTool = !isVideo && ToolJob.running && ToolJob.task != null
        title = if (isVideo) "Compressing video" else ToolJob.label
        // A service started with startForegroundService() must call startForeground() right away, whatever happens next.
        goForeground(indeterminate = !isVideo)

        if (job?.isActive == true || (!isVideo && !isTool)) {
            if (job?.isActive != true) finishService()
            return START_NOT_STICKY
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shrinky:compress").apply { acquire(6 * 60 * 60 * 1000L) }
        pass = 0; lastText = null; passStart = SystemClock.elapsedRealtime()

        job = if (isVideo) scope.launch { runVideo(video!!) } else scope.launch { runTool() }
        return START_NOT_STICKY
    }

    private suspend fun runVideo(req: com.davexh.shrinky.engine.VideoRequest) {
        try {
            val out = VideoEngine.compress(applicationContext, req) { attempt, p ->
                if (attempt != pass) { pass = attempt; passStart = SystemClock.elapsedRealtime(); lastText = null }
                VideoJob.progress = p
                val pct = (p * 100).toInt().coerceIn(0, 100)
                val text = progressText(attempt, pct, p)
                if (text != lastText) { lastText = text; notifyProgress(pct, text) }
            }
            val result = Shrunk(
                ByteArray(0), "video/mp4", "mp4", out.hitTarget,
                origW = out.origW, origH = out.origH, newW = out.newW, newH = out.newH,
                file = out.file,
                codecInfo = VideoCodec.labelOf(out.videoMime ?: req.video.mime) + ", " +
                    (if (req.audio == AudioCodec.MUTE) "no audio" else AudioCodec.labelOf(out.audioMime)),
                notice = fallbackNotice(req.video, req.audio, out.videoMime, out.audioMime),
            )
            VideoJob.succeed(result)
            postDone("Video compressed", doneText(req.source.name, req.source.bytes, result.size))
        } catch (e: CancellationException) {
            VideoJob.fail("Compression cancelled.")
            throw e
        } catch (e: Throwable) {
            val msg = e.message ?: "Couldn't compress this video."
            VideoJob.fail(msg)
            postDone("Video compression failed", msg)
        } finally {
            finishService()
        }
    }

    private suspend fun runTool() {
        val req = ToolJob.request
        val task = ToolJob.task
        val doneTitle = ToolJob.label.replace("Compressing", "").trim().replaceFirstChar { it.uppercase() } + " compressed"
        try {
            val result = withContext(Dispatchers.Default) { task!!.invoke() }
            ToolJob.succeed(result)
            postDone(doneTitle, doneText(req?.source?.name ?: "", req?.source?.bytes ?: 0L, result.size))
        } catch (e: CancellationException) {
            ToolJob.fail("Compression cancelled.")
            throw e
        } catch (e: Throwable) {
            val msg = e.message ?: "Something went wrong."
            ToolJob.fail(msg)
            postDone("Compression failed", msg)
        } finally {
            finishService()
        }
    }

    private fun doneText(name: String, before: Long, after: Long): String {
        val sizes = if (before > 0) "${formatSize(before)} \u2192 ${formatSize(after)}" else formatSize(after)
        return (if (name.isNotEmpty()) "$name: " else "") + sizes + ". Open Shrinky to save."
    }

    /** "42% · 3 min left". The estimate is elapsed time scaled by what's left, so it settles after a few seconds. */
    private fun progressText(attempt: Int, pct: Int, p: Float): String {
        val elapsed = (SystemClock.elapsedRealtime() - passStart) / 1000.0
        val eta = if (p >= 0.03f && elapsed >= 3) etaLabel((elapsed * (1 - p) / p).toLong()) else null
        return buildString {
            append("$pct%")
            if (attempt > 1) append(" · adjusting size, pass $attempt")
            append(if (eta != null) " · $eta left" else " · estimating time")
        }
    }

    // Coarse steps so the notification isn't rewritten every second.
    private fun etaLabel(sec: Long): String = when {
        sec < 10 -> "a few sec"
        sec < 60 -> "${(sec + 4) / 5 * 5} sec"
        sec < 3600 -> "${(sec + 59) / 60} min"
        else -> "${sec / 3600} h ${(sec % 3600 + 59) / 60} min"
    }

    private fun fallbackNotice(v: VideoCodec, a: AudioCodec, usedV: String?, usedA: String?): String? {
        val parts = mutableListOf<String>()
        if (usedV != null && !usedV.equals(v.mime, ignoreCase = true)) {
            parts += "${v.label} wasn't available here, so ${VideoCodec.labelOf(usedV)} was used."
        }
        if (a.mime != null && usedA != null && !usedA.equals(a.mime, ignoreCase = true)) {
            parts += "${a.label} wasn't available here, so ${AudioCodec.labelOf(usedA)} was used."
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun finishService() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notifications

    private fun manager() = getSystemService(NotificationManager::class.java)

    private fun ensureChannel() {
        val m = manager()
        if (m.getNotificationChannel(CHANNEL) == null) {
            m.createNotificationChannel(NotificationChannel(CHANNEL, "Compression", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun openApp(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun progressNotification(pct: Int, text: String, indeterminate: Boolean): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, CompressService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val name = VideoJob.request?.source?.name.takeIf { VideoJob.running } ?: ToolJob.request?.source?.name
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_shrinky)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(name)
            .setProgress(100, pct, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(openApp())
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_shrinky), "Cancel", cancel).build(),
            )
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    private fun goForeground(indeterminate: Boolean) {
        ensureChannel()
        val n = progressNotification(0, if (indeterminate) "Working" else "Starting", indeterminate)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID_PROGRESS, n)
    }

    private fun notifyProgress(pct: Int, text: String) {
        manager().notify(ID_PROGRESS, progressNotification(pct, text, false))
    }

    private fun postDone(title: String, text: String) {
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_shrinky)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        manager().notify(ID_DONE, n)
    }

    companion object {
        const val ACTION_CANCEL = "com.davexh.shrinky.CANCEL"
        private const val CHANNEL = "compress"
        private const val ID_PROGRESS = 1
        private const val ID_DONE = 2
    }
}
