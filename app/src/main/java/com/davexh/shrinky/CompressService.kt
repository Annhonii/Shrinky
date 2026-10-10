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
import com.davexh.shrinky.engine.AudioCodec
import com.davexh.shrinky.engine.Shrunk
import com.davexh.shrinky.engine.VideoCodec
import com.davexh.shrinky.engine.VideoEngine
import com.davexh.shrinky.engine.VideoJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs video compression as a foreground service so it continues when the app is in the background,
 * with a progress notification (and a Cancel button).
 */
class CompressService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastPct = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }
        // A service started with startForegroundService() must call startForeground() right away, whatever happens next.
        goForeground(0)
        val req = VideoJob.request
        if (req == null || !VideoJob.running || job?.isActive == true) {
            if (job?.isActive != true) finishService()
            return START_NOT_STICKY
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shrinky:compress").apply { acquire(6 * 60 * 60 * 1000L) }

        job = scope.launch {
            try {
                val out = VideoEngine.compress(applicationContext, req) { p ->
                    VideoJob.progress = p
                    val pct = (p * 100).toInt().coerceIn(0, 100)
                    if (pct != lastPct) { lastPct = pct; notifyProgress(pct) }
                }
                VideoJob.succeed(
                    Shrunk(
                        ByteArray(0), "video/mp4", "mp4", out.hitTarget,
                        origW = out.origW, origH = out.origH, newW = out.newW, newH = out.newH,
                        before = out.before, after = out.after, file = out.file,
                        codecInfo = VideoCodec.labelOf(out.videoMime ?: req.video.mime) + ", " +
                            (if (req.audio == AudioCodec.MUTE) "no audio" else AudioCodec.labelOf(out.audioMime)),
                        notice = fallbackNotice(req.video, req.audio, out.videoMime, out.audioMime),
                    ),
                )
                postDone("Compression finished", "Open Shrinky to save your video.")
            } catch (e: CancellationException) {
                VideoJob.fail("Compression cancelled.")
                throw e
            } catch (e: Throwable) {
                val msg = e.message ?: "Couldn't compress this video."
                VideoJob.fail(msg)
                postDone("Compression failed", msg)
            } finally {
                finishService()
            }
        }
        return START_NOT_STICKY
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
            m.createNotificationChannel(NotificationChannel(CHANNEL, "Video compression", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun openApp(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun progressNotification(pct: Int): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, CompressService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_shrinky)
            .setContentTitle("Compressing video")
            .setContentText("$pct%")
            .setSubText(VideoJob.request?.source?.name)
            .setProgress(100, pct, false)
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

    private fun goForeground(pct: Int) {
        ensureChannel()
        val n = progressNotification(pct)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID_PROGRESS, n)
    }

    private fun notifyProgress(pct: Int) {
        manager().notify(ID_PROGRESS, progressNotification(pct))
    }

    private fun postDone(title: String, text: String) {
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_shrinky)
            .setContentTitle(title)
            .setContentText(text)
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
