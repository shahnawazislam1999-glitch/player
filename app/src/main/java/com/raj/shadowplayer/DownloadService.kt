package com.raj.shadowplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Saves YouTube videos (and their subtitles) for offline play. Shows a progress notification; one download at a time. */
class DownloadService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val cancel = AtomicBoolean(false)
    private val pending = AtomicInteger(0)
    private val ui = Handler(Looper.getMainLooper())
    private var channelReady = false

    override fun onBind(intent: Intent?): IBinder? = null

    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel() {
        if (channelReady) return
        nm().createNotificationChannel(NotificationChannel(CHANNEL, "Offline downloads", NotificationManager.IMPORTANCE_LOW))
        channelReady = true
    }

    private fun progressNote(title: String, pct: Int): Notification {
        val cancelPi = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Saving offline")
            .setContentText(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Cancel", cancelPi)
        if (pct in 0..100) b.setProgress(100, pct, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        if (intent?.action == ACTION_CANCEL) {
            cancel.set(true)
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(EXTRA_ID) ?: ""
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "YouTube video"
        val height = intent?.getIntExtra(EXTRA_HEIGHT, 360) ?: 360
        try {
            startForeground(NOTIF_ID, progressNote(title, -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            toast("Android did not allow the download to start. Open the app and try again.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (id.isEmpty() || !active.add(id)) {
            if (id.isNotEmpty()) toast("Already downloading this video")
            if (pending.get() == 0) stopSelf()
            return START_NOT_STICKY
        }
        pending.incrementAndGet()
        worker.execute {
            cancel.set(false)
            var shownTitle = title
            var result = ""
            try {
                val e = Offline.download(applicationContext, id, height, { pct ->
                    try { nm().notify(NOTIF_ID, progressNote(shownTitle, pct)) } catch (ex: Exception) { }
                }, { cancel.get() })
                shownTitle = e.title
                result = "Saved offline: " + e.title + " (" + e.height + "p" + (if (e.hasSubs) ", with subtitles" else ", no subtitles found") + ")"
                toast(result)
            } catch (e: Offline.Cancelled) {
                result = "Download cancelled"
                toast(result)
            } catch (e: YouTube.YtError) {
                result = e.message ?: "The download failed."
                toast(result)
            } catch (e: Exception) {
                result = "The download failed (" + (e.message ?: "unknown error") + ")"
                toast(result)
            } finally {
                active.remove(id)
                cancel.set(false)
                if (pending.decrementAndGet() <= 0) {
                    ui.post {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            if (result.isNotEmpty()) {
                try {
                    nm().notify(
                        NOTIF_ID + 1 + (id.hashCode() and 0xFFF),
                        NotificationCompat.Builder(this, CHANNEL)
                            .setSmallIcon(R.drawable.ic_notify)
                            .setContentTitle("ShadowPlayer")
                            .setContentText(result)
                            .setAutoCancel(true)
                            .build()
                    )
                } catch (e: Exception) {
                    // notifications not allowed: the toast above was enough
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun toast(msg: String) {
        ui.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy() {
        cancel.set(true)
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "shadowplayer_dl"
        private const val NOTIF_ID = 4720
        private const val ACTION_CANCEL = "com.raj.shadowplayer.CANCEL_DL"
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HEIGHT = "height"
        private val active: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

        fun isBusy(id: String): Boolean = active.contains(id)

        fun start(ctx: Context, id: String, title: String, height: Int) {
            val i = Intent(ctx.applicationContext, DownloadService::class.java)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_HEIGHT, height)
            ContextCompat.startForegroundService(ctx.applicationContext, i)
        }
    }
}
