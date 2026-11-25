package com.holos.reader.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import com.holos.reader.ui.ReaderActivity

/**
 * Lightweight Foreground Service keeping HolosTTS playback alive when the screen turns off,
 * displaying media controls in the notification shade, and auto-pausing when headphones unplug.
 */
class ReaderAudioService : Service() {

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                activeController?.pauseIfPlaying()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PAUSE -> {
                activeController?.togglePause()
                updateNotification(currentTitle, currentSubtitle)
            }
            ACTION_STOP -> {
                activeController?.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                currentTitle = intent?.getStringExtra(EXTRA_TITLE) ?: "HolosReader"
                currentSubtitle = intent?.getStringExtra(EXTRA_SUBTITLE) ?: "Озвучення книги…"
                startForeground(NOTIF_ID, buildNotification(currentTitle, currentSubtitle))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(title: String, subtitle: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIF_ID, buildNotification(title, subtitle))
    }

    private fun buildNotification(title: String, subtitle: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ReaderActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ReaderAudioService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ReaderAudioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val paused = activeController?.isPaused == true
        val pauseLabel = if (paused) "Продовжити" else "Пауза"

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (paused) "На паузі" else subtitle)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, pauseLabel, pauseIntent).build())
            .addAction(Notification.Action.Builder(null, "Стоп", stopIntent).build())
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Озвучення книг HolosReader",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    companion object {
        private const val CHANNEL_ID = "holos_reader_audio"
        private const val NOTIF_ID = 7701
        const val ACTION_START = "com.holos.reader.ACTION_START"
        const val ACTION_TOGGLE_PAUSE = "com.holos.reader.ACTION_TOGGLE_PAUSE"
        const val ACTION_STOP = "com.holos.reader.ACTION_STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"

        @Volatile
        var activeController: TtsPlaybackController? = null
        @Volatile
        private var currentTitle: String = "HolosReader"
        @Volatile
        private var currentSubtitle: String = "Озвучення книги…"

        fun start(context: Context, title: String, subtitle: String) {
            val intent = Intent(context, ReaderAudioService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE, subtitle)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReaderAudioService::class.java))
        }
    }
}
