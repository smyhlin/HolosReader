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
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.holos.reader.R
import com.holos.reader.ui.AppStrings
import com.holos.reader.ui.ReaderActivity

/**
 * Foreground Media Playback Service (pure framework Android, zero-AndroidX):
 * - Android 14/15 compliant 3-arg `registerReceiver(..., RECEIVER_NOT_EXPORTED)`
 * - Framework `MediaSession` + `Notification.MediaStyle` for lock-screen, Bluetooth & headset controls
 * - `AudioFocusRequest` handling (auto-pause on calls/other media, resume after transient loss)
 * - `PARTIAL_WAKE_LOCK` during active synthesis/playback so CPU doesn't throttle with screen off
 * - Carries `EXTRA_BOOK_ID` in notification PendingIntent so tapping always returns to the active book
 */
class ReaderAudioService : Service() {

    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pausedByFocusLoss: Boolean = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                activeController?.pauseIfPlaying()
                updateMediaSessionState()
                updateNotification(currentTitle, currentSubtitle)
            }
        }
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedByFocusLoss = false
                activeController?.pauseIfPlaying()
                releaseWakeLock()
                updateMediaSessionState()
                updateNotification(currentTitle, currentSubtitle)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                val ctrl = activeController
                if (ctrl != null && ctrl.isPlaying && !ctrl.isPaused) {
                    pausedByFocusLoss = true
                    ctrl.pauseIfPlaying()
                    updateMediaSessionState()
                    updateNotification(currentTitle, currentSubtitle)
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                val ctrl = activeController
                if (pausedByFocusLoss && ctrl != null && ctrl.isPlaying && ctrl.isPaused) {
                    pausedByFocusLoss = false
                    ctrl.togglePause()
                    acquireWakeLock()
                    updateMediaSessionState()
                    updateNotification(currentTitle, currentSubtitle)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        audioManager = getSystemService(AudioManager::class.java)
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(noisyReceiver, filter)
        }
        initMediaSession()
    }

    private fun initMediaSession() {
        try {
            val session = MediaSession(this, "HolosReaderSession")
            session.setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    val ctrl = activeController
                    if (ctrl != null && ctrl.isPlaying && ctrl.isPaused) {
                        requestAudioFocus()
                        ctrl.togglePause()
                        acquireWakeLock()
                        updateMediaSessionState()
                        updateNotification(currentTitle, currentSubtitle)
                    }
                }

                override fun onPause() {
                    activeController?.pauseIfPlaying()
                    releaseWakeLock()
                    updateMediaSessionState()
                    updateNotification(currentTitle, currentSubtitle)
                }

                override fun onStop() {
                    activeController?.stop()
                    abandonAudioFocus()
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }

                override fun onSkipToNext() {
                    onSkipNextAction?.invoke()
                }

                override fun onSkipToPrevious() {
                    onSkipPrevAction?.invoke()
                }
            })
            session.isActive = true
            mediaSession = session
        } catch (e: Exception) {
            Log.w(TAG, "MediaSession init failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PAUSE -> {
                val nowPaused = activeController?.togglePause() == true
                if (nowPaused) releaseWakeLock() else {
                    requestAudioFocus()
                    acquireWakeLock()
                }
                updateMediaSessionState()
                updateNotification(currentTitle, currentSubtitle)
            }
            ACTION_PREV -> {
                onSkipPrevAction?.invoke()
            }
            ACTION_NEXT -> {
                onSkipNextAction?.invoke()
            }
            ACTION_STOP -> {
                activeController?.stop()
                abandonAudioFocus()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                currentTitle = intent?.getStringExtra(EXTRA_TITLE) ?: "HolosReader"
                currentSubtitle = intent?.getStringExtra(EXTRA_SUBTITLE)
                    ?: AppStrings.t(this, "Озвучення книги…", "Reading aloud…")
                intent?.getStringExtra(EXTRA_BOOK_ID)?.let { currentBookId = it }

                requestAudioFocus()
                acquireWakeLock()
                updateMediaSessionMetadata(currentTitle, currentSubtitle)
                updateMediaSessionState()
                val notif = buildNotification(currentTitle, currentSubtitle)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    } else {
                        startForeground(NOTIF_ID, notif)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "startForeground failed", e)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(noisyReceiver) } catch (e: Exception) {
            Log.w(TAG, "unregisterReceiver failed", e)
        }
        abandonAudioFocus()
        releaseWakeLock()
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (e: Exception) {
            Log.w(TAG, "MediaSession release failed", e)
        }
        mediaSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        try {
            if (focusRequest == null) {
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
            }
            focusRequest?.let { am.requestAudioFocus(it) }
        } catch (e: Exception) {
            Log.w(TAG, "AudioFocus request failed", e)
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        try {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } catch (e: Exception) {
            Log.w(TAG, "AudioFocus abandon failed", e)
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(PowerManager::class.java)
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HolosReader:TtsPlayback")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(4 * 60 * 60 * 1000L) // 4h safety ceiling
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquire failed", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock release failed", e)
        }
    }

    private fun updateMediaSessionMetadata(title: String, subtitle: String) {
        try {
            val md = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .build()
            mediaSession?.setMetadata(md)
        } catch (e: Exception) {
            Log.w(TAG, "MediaMetadata update failed", e)
        }
    }

    private fun updateMediaSessionState() {
        try {
            val ctrl = activeController
            val stateCode = when {
                ctrl == null || !ctrl.isPlaying -> PlaybackState.STATE_STOPPED
                ctrl.isPaused -> PlaybackState.STATE_PAUSED
                else -> PlaybackState.STATE_PLAYING
            }
            val actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS
            val ps = PlaybackState.Builder()
                .setActions(actions)
                .setState(stateCode, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
            mediaSession?.setPlaybackState(ps)
        } catch (e: Exception) {
            Log.w(TAG, "PlaybackState update failed", e)
        }
    }

    private fun updateNotification(title: String, subtitle: String) {
        val nm = getSystemService(NotificationManager::class.java)
        try {
            nm?.notify(NOTIF_ID, buildNotification(title, subtitle))
        } catch (e: Exception) {
            Log.w(TAG, "Notification notify failed", e)
        }
    }

    private fun buildNotification(title: String, subtitle: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ReaderActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                currentBookId?.let { putExtra(ReaderActivity.EXTRA_BOOK_ID, it) }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val prevIntent = PendingIntent.getService(
            this, 3,
            Intent(this, ReaderAudioService::class.java).setAction(ACTION_PREV),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ReaderAudioService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val nextIntent = PendingIntent.getService(
            this, 4,
            Intent(this, ReaderAudioService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, ReaderAudioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val paused = activeController?.isPaused == true
        val pauseLabel = if (paused) AppStrings.t(this, "Продовжити", "Resume") else AppStrings.t(this, "Пауза", "Pause")
        val pausedSub = AppStrings.t(this, "На паузі", "Paused")
        val stopLabel = AppStrings.t(this, "Стоп", "Stop")

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (paused) pausedSub else subtitle)
            .setSmallIcon(R.drawable.ic_stat_holos)
            .setOngoing(!paused)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "⏮", prevIntent).build())
            .addAction(Notification.Action.Builder(null, pauseLabel, pauseIntent).build())
            .addAction(Notification.Action.Builder(null, "⏭", nextIntent).build())
            .addAction(Notification.Action.Builder(null, stopLabel, stopIntent).build())

        mediaSession?.sessionToken?.let { token ->
            builder.setStyle(
                Notification.MediaStyle()
                    .setMediaSession(token)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                AppStrings.t(this, "Озвучення книг HolosReader", "HolosReader Audio Playback"),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    companion object {
        private const val TAG = "ReaderAudioService"
        private const val CHANNEL_ID = "holos_reader_audio"
        private const val NOTIF_ID = 7701
        const val ACTION_START = "com.holos.reader.ACTION_START"
        const val ACTION_TOGGLE_PAUSE = "com.holos.reader.ACTION_TOGGLE_PAUSE"
        const val ACTION_PREV = "com.holos.reader.ACTION_PREV"
        const val ACTION_NEXT = "com.holos.reader.ACTION_NEXT"
        const val ACTION_STOP = "com.holos.reader.ACTION_STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_BOOK_ID = "book_id"

        @Volatile
        var activeController: TtsPlaybackController? = null
        @Volatile
        var onSkipNextAction: (() -> Unit)? = null
        @Volatile
        var onSkipPrevAction: (() -> Unit)? = null
        @Volatile
        private var currentTitle: String = "HolosReader"
        @Volatile
        private var currentSubtitle: String = "Озвучення книги…"
        @Volatile
        private var currentBookId: String? = null

        fun start(context: Context, title: String, subtitle: String, bookId: String? = null) {
            val intent = Intent(context, ReaderAudioService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE, subtitle)
                if (bookId != null) putExtra(EXTRA_BOOK_ID, bookId)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start foreground service", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ReaderAudioService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Unable to stop foreground service", e)
            }
        }
    }
}
