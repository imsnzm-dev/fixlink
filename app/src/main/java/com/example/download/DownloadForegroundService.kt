package com.example.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DownloadForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = DownloadManagerHolder.get(this)

        if (intent?.action == ACTION_PAUSE_ALL) {
            engine.pauseAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val initialNotification = buildNotification(engine.tasks.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        if (observerJob == null) {
            observerJob = serviceScope.launch {
                val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                while (isActive) {
                    val tasks = engine.tasks.value
                    val hasActive = tasks.any { it.isActive }
                    if (!hasActive) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        break
                    }
                    notifManager.notify(NOTIFICATION_ID, buildNotification(tasks))
                    delay(1000)
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun buildNotification(tasks: List<DownloadTask>): Notification {
        val activeTasks = tasks.filter { it.isActive }
        val totalSpeed = activeTasks.sumOf { it.speedBytesPerSec }
        val speedText = if (totalSpeed > 0L) {
            com.example.network.VideoLinkExtractor.formatByteSize(totalSpeed)?.let { "$it/s" }.orEmpty()
        } else {
            ""
        }

        val title = if (activeTasks.size == 1) {
            activeTasks.first().fileName
        } else {
            "FiXLink • ${activeTasks.size} active downloads"
        }

        val avgProgress = if (activeTasks.isNotEmpty()) {
            activeTasks.sumOf { it.progressPercent } / activeTasks.size
        } else {
            0
        }

        val contentText = buildString {
            append("$avgProgress%")
            if (speedText.isNotEmpty()) {
                append(" • $speedText")
            }
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_DOWNLOADS, true)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val pauseAllIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadForegroundService::class.java).apply {
                action = ACTION_PAUSE_ALL
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, avgProgress, activeTasks.any { it.totalBytes <= 0L })
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_media_pause, "Pause All", pauseAllIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FiXLink Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Active file download progress"
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "fixlink_downloads_channel"
        const val NOTIFICATION_ID = 4091
        const val ACTION_PAUSE_ALL = "com.example.download.ACTION_PAUSE_ALL"
        const val EXTRA_OPEN_DOWNLOADS = "extra_open_downloads"
    }
}
