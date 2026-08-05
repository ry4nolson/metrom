package com.metrom.app

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
import androidx.core.app.ServiceCompat

/**
 * Keeps the metronome alive in the background with a compact transport notification.
 * The engine lives in [MetronomeViewModel]; this service only hosts the FGS notification
 * and forwards play/stop actions through [PlaybackBridge].
 */
class PlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                PlaybackBridge.onToggle?.invoke()
                // Bridge updates playing flag synchronously inside toggle → sync again below.
            }
            ACTION_STOP -> {
                if (PlaybackBridge.playing) {
                    PlaybackBridge.onStop?.invoke() ?: PlaybackBridge.onToggle?.invoke()
                }
                tearDownAndStop()
                return START_NOT_STICKY
            }
            else -> Unit
        }

        if (!PlaybackBridge.playing) {
            tearDownAndStop()
            return START_NOT_STICKY
        }

        ensureChannel()
        promoteForeground(buildNotification())
        // NOT_STICKY: don't resurrect a zombie FGS after process death overnight
        // without a live ViewModel/engine (START_STICKY was keeping the service warm).
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun tearDownAndStop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggle = PendingIntent.getService(
            this,
            1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playing = PlaybackBridge.playing
        val playLabel = if (playing) "Pause" else "Play"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_metrom)
            .setContentTitle(if (playing) "${PlaybackBridge.bpm} BPM" else "Metrom")
            .setContentText(
                if (playing) PlaybackBridge.subtitle else "Stopped"
            )
            .setContentIntent(open)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, playLabel, toggle)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Metronome",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Playing metronome"
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "metrom_playback"
        private const val NOTIFICATION_ID = 42
        const val ACTION_TOGGLE = "com.metrom.app.action.TOGGLE"
        const val ACTION_STOP = "com.metrom.app.action.STOP"
        private const val ACTION_SYNC = "com.metrom.app.action.SYNC"

        /**
         * Start / refresh the FGS while playing; stop the service when idle.
         * Avoids startService() while backgrounded (restricted on modern Android).
         *
         * Failures are swallowed: if POST_NOTIFICATIONS is denied (or the OS
         * blocks FGS start), the in-process engine in [MetronomeViewModel] still
         * keeps clicking — the user just won't see a shade tile.
         */
        fun sync(context: Context) {
            val app = context.applicationContext
            try {
                if (PlaybackBridge.playing) {
                    val intent = Intent(app, PlaybackService::class.java).setAction(ACTION_SYNC)
                    app.startForegroundService(intent)
                } else {
                    app.stopService(Intent(app, PlaybackService::class.java))
                }
            } catch (_: Exception) {
                // Notification / background-start restriction — audio continues.
            }
        }
    }
}
