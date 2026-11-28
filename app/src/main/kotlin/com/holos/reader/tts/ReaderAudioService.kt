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
import android.graphics.Bitmap
import android.graphics.drawable.Icon
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
import com.holos.reader.epub.BookDatabase
import com.holos.reader.ui.AppStrings
import com.holos.reader.ui.ReaderActivity
import com.holos.reader.util.BitmapDecode

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
                pausedByFocusLoss = false
                activeController?.pauseIfPlaying(immediate = true)
                updateMediaSessionState()
                updateNotification(currentTitle, currentSubtitle)
            }
        }
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedByFocusLoss = false
                activeController?.pauseIfPlaying(immediate = true)
                releaseWakeLock()
                updateMediaSessionState()
                updateNotification(currentTitle, currentSubtitle)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                val ctrl = activeController
                if (ctrl != null && ctrl.isPlaying && !ctrl.isPaused) {
                    pausedByFocusLoss = true
                    ctrl.pauseIfPlaying(immediate = true)
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
                        if (requestAudioFocus() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                            ctrl.resumeIfPaused()
                            acquireWakeLock()
                        } else {
                            ctrl.pauseIfPlaying()
                            releaseWakeLock()
                        }
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

                override fun onSeekTo(pos: Long) {
                    val dur = currentDurationMs
                    if (dur > 0L) {
                        val frac = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                        onSeekFractionAction?.invoke(frac)
                    }
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
                val ctrl = activeController
                if (ctrl != null && ctrl.isPlaying) {
                    if (ctrl.isPaused) {
                        if (requestAudioFocus() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                            ctrl.resumeIfPaused()
                            acquireWakeLock()
                        } else {
                            releaseWakeLock()
                        }
                    } else {
                        ctrl.pauseIfPlaying()
                        releaseWakeLock()
                    }
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
            ACTION_REFRESH -> {
                intent.getStringExtra(EXTRA_TITLE)?.let { currentTitle = it }
                intent.getStringExtra(EXTRA_SUBTITLE)?.let { currentSubtitle = it }
                intent.getStringExtra(EXTRA_ALBUM)?.let { currentAlbum = it }
                if (intent.hasExtra(EXTRA_POS_MS)) currentPositionMs = intent.getLongExtra(EXTRA_POS_MS, currentPositionMs)
                if (intent.hasExtra(EXTRA_DUR_MS)) currentDurationMs = intent.getLongExtra(EXTRA_DUR_MS, currentDurationMs)
                if (intent.hasExtra(EXTRA_SPEED)) currentSpeed = intent.getFloatExtra(EXTRA_SPEED, currentSpeed)
                if (activeController?.isPaused == true) releaseWakeLock() else acquireWakeLock()
                updateMediaSessionMetadata(currentTitle, currentSubtitle)
                updateMediaSessionState()
                updateNotification(currentTitle, currentSubtitle)
            }
            ACTION_RESUME -> {
                val ctrl = activeController
                if (ctrl != null && ctrl.isPlaying && ctrl.isPaused) {
                    if (requestAudioFocus() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        ctrl.resumeIfPaused()
                        acquireWakeLock()
                    } else {
                        ctrl.pauseIfPlaying()
                        releaseWakeLock()
                    }
                    updateMediaSessionState()
                    updateNotification(currentTitle, currentSubtitle)
                }
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
                intent?.getStringExtra(EXTRA_ALBUM)?.let { currentAlbum = it }
                intent?.getStringExtra(EXTRA_BOOK_ID)?.let {
                    if (it != currentBookId) cachedCoverBitmap = null
                    currentBookId = it
                }
                if (intent?.hasExtra(EXTRA_POS_MS) == true) currentPositionMs = intent.getLongExtra(EXTRA_POS_MS, 0L)
                if (intent?.hasExtra(EXTRA_DUR_MS) == true) currentDurationMs = intent.getLongExtra(EXTRA_DUR_MS, 0L)
                if (intent?.hasExtra(EXTRA_SPEED) == true) currentSpeed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)

                updateMediaSessionMetadata(currentTitle, currentSubtitle)
                val notif = buildNotification(currentTitle, currentSubtitle)
                var fgOk = false
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    } else {
                        startForeground(NOTIF_ID, notif)
                    }
                    fgOk = true
                } catch (e: Exception) {
                    Log.w(TAG, "startForeground failed", e)
                    stopSelf()
                }
                val sessionGen = intent?.getLongExtra(EXTRA_SESSION_GEN, 0L) ?: 0L
                if (fgOk) {
                    if (requestAudioFocus() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        acquireWakeLock()
                        if (sessionGen > 0L) {
                            activeController?.onAudioFocusGranted(sessionGen)
                        }
                    } else {
                        if (sessionGen > 0L) {
                            activeController?.onAudioFocusDenied(sessionGen)
                        } else {
                            activeController?.pauseIfPlaying()
                        }
                        releaseWakeLock()
                    }
                    updateMediaSessionState()
                    updateNotification(currentTitle, currentSubtitle)
                } else if (sessionGen > 0L) {
                    activeController?.onAudioFocusDenied(sessionGen)
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

    private fun requestAudioFocus(): Int {
        val am = audioManager ?: return AudioManager.AUDIOFOCUS_REQUEST_FAILED
        return try {
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
            focusRequest?.let { am.requestAudioFocus(it) } ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        } catch (e: Exception) {
            Log.w(TAG, "AudioFocus request failed", e)
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
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
            // Renew the 4h safety ceiling on playback refreshes (setReferenceCounted(false)) so long sessions never hit a gap.
            wakeLock?.acquire(4 * 60 * 60 * 1000L)
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

    private fun loadCoverBitmap(): Bitmap? {
        cachedCoverBitmap?.let { return it }
        val bid = currentBookId ?: return null
        return try {
            val db = BookDatabase(this)
            val rec = db.getBook(bid) ?: return null
            val cf = db.getCoverFile(rec) ?: return null
            BitmapDecode.decodeFileBounded(cf.absolutePath, 256)?.also { cachedCoverBitmap = it }
        } catch (_: Exception) {
            null
        }
    }

    private fun updateMediaSessionMetadata(title: String, subtitle: String) {
        try {
            val mdBuilder = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, currentAlbum)
            if (currentDurationMs > 0L) {
                mdBuilder.putLong(MediaMetadata.METADATA_KEY_DURATION, currentDurationMs)
            }
            loadCoverBitmap()?.let { bmp ->
                mdBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp)
                mdBuilder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, bmp)
            }
            mediaSession?.setMetadata(mdBuilder.build())
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
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO
            val speed = if (stateCode == PlaybackState.STATE_PLAYING) currentSpeed else 0f
            val pos = if (currentDurationMs > 0L) currentPositionMs.coerceIn(0L, currentDurationMs) else 0L
            val ps = PlaybackState.Builder()
                .setActions(actions)
                .setState(stateCode, pos, speed)
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
        val prevLabel = AppStrings.t(this, "Назад", "Previous")
        val pauseLabel = if (paused) AppStrings.t(this, "Продовжити", "Resume") else AppStrings.t(this, "Пауза", "Pause")
        val nextLabel = AppStrings.t(this, "Далі", "Next")
        val pausedSub = AppStrings.t(this, "На паузі", "Paused")
        val stopLabel = AppStrings.t(this, "Стоп", "Stop")

        val prevIcon = Icon.createWithResource(this, R.drawable.ic_skip_prev)
        val pauseIcon = Icon.createWithResource(this, if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        val nextIcon = Icon.createWithResource(this, R.drawable.ic_skip_next)
        val stopIcon = Icon.createWithResource(this, R.drawable.ic_stop)

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(R.drawable.ic_stat_holos)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(!paused)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(prevIcon, prevLabel, prevIntent).build())
            .addAction(Notification.Action.Builder(pauseIcon, pauseLabel, pauseIntent).build())
            .addAction(Notification.Action.Builder(nextIcon, nextLabel, nextIntent).build())

        loadCoverBitmap()?.let { builder.setLargeIcon(it) }

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
                const val ACTION_REFRESH = "com.holos.reader.ACTION_REFRESH"
        const val ACTION_RESUME = "com.holos.reader.ACTION_RESUME"
        const val ACTION_STOP = "com.holos.reader.ACTION_STOP"
        const val EXTRA_SESSION_GEN = "session_gen"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_ALBUM = "album"
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_POS_MS = "pos_ms"
        const val EXTRA_DUR_MS = "dur_ms"
        const val EXTRA_SPEED = "speed"

        @Volatile
        var activeController: TtsPlaybackController? = null
        @Volatile
        var onSkipNextAction: (() -> Unit)? = null
        @Volatile
        var onSkipPrevAction: (() -> Unit)? = null
        @Volatile
        var onSeekFractionAction: ((Float) -> Unit)? = null
        @Volatile
        private var currentTitle: String = "HolosReader"
        @Volatile
        private var currentSubtitle: String = "Озвучення книги…"
        @Volatile
        private var currentAlbum: String = "HolosReader"
        @Volatile
        private var currentBookId: String? = null
        @Volatile
        private var cachedCoverBitmap: Bitmap? = null
        @Volatile
        private var currentPositionMs: Long = 0L
        @Volatile
        private var currentDurationMs: Long = 0L
        @Volatile
        private var currentSpeed: Float = 1.0f

        fun start(
            context: Context,
            title: String,
            subtitle: String,
            bookId: String? = null,
            album: String? = null,
            positionMs: Long = 0L,
            durationMs: Long = 0L,
            speed: Float = 1.0f,
            sessionGen: Long = 0L,
        ) {
            val intent = Intent(context, ReaderAudioService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE, subtitle)
                if (album != null) putExtra(EXTRA_ALBUM, album)
                if (bookId != null) putExtra(EXTRA_BOOK_ID, bookId)
                putExtra(EXTRA_POS_MS, positionMs)
                putExtra(EXTRA_DUR_MS, durationMs)
                putExtra(EXTRA_SPEED, speed)
                if (sessionGen > 0L) putExtra(EXTRA_SESSION_GEN, sessionGen)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start foreground service", e)
                if (sessionGen > 0L) {
                    activeController?.onAudioFocusDenied(sessionGen)
                }
            }
        }

        fun requestResume(context: Context) {
            val intent = Intent(context, ReaderAudioService::class.java).apply {
                action = ACTION_RESUME
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to request audio focus resume", e)
                activeController?.pauseIfPlaying()
            }
        }

        fun refresh(
            context: Context,
            title: String? = null,
            subtitle: String? = null,
            album: String? = null,
            positionMs: Long? = null,
            durationMs: Long? = null,
            speed: Float? = null,
        ) {
            try {
                val intent = Intent(context, ReaderAudioService::class.java).setAction(ACTION_REFRESH).apply {
                    if (title != null) putExtra(EXTRA_TITLE, title)
                    if (subtitle != null) putExtra(EXTRA_SUBTITLE, subtitle)
                    if (album != null) putExtra(EXTRA_ALBUM, album)
                    if (positionMs != null) putExtra(EXTRA_POS_MS, positionMs)
                    if (durationMs != null) putExtra(EXTRA_DUR_MS, durationMs)
                    if (speed != null) putExtra(EXTRA_SPEED, speed)
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to refresh foreground service", e)
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
