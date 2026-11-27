package com.holos.reader.tts

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.holos.reader.R
import com.holos.reader.ui.AppStrings
import com.holos.reader.ui.ReaderSettingsActivity
import java.io.InterruptedIOException
import java.util.concurrent.locks.ReentrantLock

/** A user-started transfer that outlives the library or settings screen. */
class ModelDownloadService : Service() {
    internal data class Status(
        val title: String,
        val message: String,
        val percent: Int?,
        val running: Boolean,
        val failed: Boolean = false,
        val isModel: Boolean,
    )

    private class Task(
        val title: String,
        val model: TtsModelId?,
        val addon: OnnxAddonId?,
        val preferFp16: Boolean,
    ) {
        @Volatile var cancelled = false
    }

    private var ownedTask: Task? = null
    private var downloadThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ownedTask != null) return START_NOT_STICKY
        val task = activeTask
        val initial = status
        if (task == null || initial == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        createChannel()
        try {
            startForeground(NOTIFICATION_ID, notification(initial), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (error: RuntimeException) {
            finishTask(task, error.message ?: text("Не вдалося почати завантаження", "Could not start download"))
            return START_NOT_STICKY
        }
        notifications.cancel(RESULT_NOTIFICATION_ID)
        ownedTask = task
        downloadThread = Thread({ download(task) }, "HolosModelDownload").apply { start() }
        return START_NOT_STICKY
    }

    private fun download(task: Task) {
        var lastUpdate = -500L
        val progress: (String, Int) -> Unit = { message, percent ->
            if (task.cancelled) throw InterruptedIOException()
            val now = SystemClock.elapsedRealtime()
            if (now - lastUpdate >= 500L) {
                lastUpdate = now
                main.post {
                    if (activeTask === task && !task.cancelled) {
                        Status(task.title, message, percent.coerceIn(0, 100), true, isModel = task.model != null).let {
                            publish(it)
                            postNotification(NOTIFICATION_ID, it)
                        }
                    }
                }
            }
        }
        var failure: String? = null
        try {
            withFileAccess {
                val prefs = ReaderPreferences(applicationContext)
                val model = task.model
                if (model != null) {
                    HolosModelManager.downloadBundle(applicationContext, model, progress)
                    if (!task.cancelled && prefs.selectedTtsModelId == model) HolosEngineHolder.resetEngine()
                } else {
                    val addon = checkNotNull(task.addon)
                    OnnxAddonManager.downloadAddon(applicationContext, addon, task.preferFp16, progress)
                    if (!task.cancelled) {
                        when (addon) {
                            OnnxAddonId.FLASH_SR -> prefs.flashSrEnabled = true
                            OnnxAddonId.UK_EMOTION -> prefs.emotionPacingEnabled = true
                            OnnxAddonId.CONTEXTUAL_STRESS -> prefs.contextualStressEnabled = true
                            OnnxAddonId.VOICE_ENCODER -> Unit
                        }
                        HolosEngineHolder.resetAddons()
                    }
                }
            }
        } catch (error: Exception) {
            failure = error.localizedMessage ?: error.javaClass.simpleName
        } finally {
            main.post { finishTask(task, failure) }
        }
    }

    private fun finishTask(task: Task, failure: String?) {
        if (activeTask !== task) return
        val error = if (task.cancelled) text("Завантаження перервано. Спробуйте ще раз.", "Download interrupted. Try again.") else failure
        val result = Status(
            task.title,
            error ?: text("Завантаження завершено", "Download complete"),
            null,
            running = false,
            failed = error != null,
            isModel = task.model != null,
        )
        ownedTask = null
        downloadThread = null
        activeTask = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        publish(result)
        postNotification(RESULT_NOTIFICATION_ID, result)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ownedTask?.cancelled = true
        downloadThread?.interrupt()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        ownedTask?.cancelled = true
        downloadThread?.interrupt()
        super.onDestroy()
    }

    private val notifications get() = requireNotNull(getSystemService(NotificationManager::class.java))
    private fun text(uk: String, en: String) = AppStrings.t(this, uk, en)

    private fun createChannel() {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, text("Завантаження моделей", "Model downloads"), NotificationManager.IMPORTANCE_LOW)
                .apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC },
        )
    }

    private fun notification(status: Status): Notification {
        val settings = Intent(this, ReaderSettingsActivity::class.java)
            .putExtra(ReaderSettingsActivity.EXTRA_TAB_INDEX, 4)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(this, 17, settings, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(status.title)
            .setContentText(status.message)
            .setStyle(Notification.BigTextStyle().bigText(status.message))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(status.running)
            .setAutoCancel(!status.running)
            .apply { if (status.running) setProgress(100, status.percent ?: 0, status.percent == null) }
            .build()
    }

    private fun postNotification(id: Int, status: Status) {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            notifications.notify(id, notification(status))
        }
    }

    companion object {
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 4201
        private const val RESULT_NOTIFICATION_ID = 4202
        private val main = Handler(Looper.getMainLooper())
        private val fileLock = ReentrantLock()
        private val listeners = LinkedHashSet<(Status?) -> Unit>()
        private var activeTask: Task? = null

        internal var status: Status? = null
            private set

        internal fun <T> withFileAccess(block: () -> T): T {
            fileLock.lockInterruptibly()
            try {
                return block()
            } finally {
                fileLock.unlock()
            }
        }

        internal fun observe(listener: (Status?) -> Unit) {
            listeners.add(listener)
            listener(status)
        }

        internal fun removeObserver(listener: (Status?) -> Unit) {
            listeners.remove(listener)
        }

        private fun publish(value: Status) {
            status = value
            listeners.toList().forEach { it(value) }
        }

        internal fun startModel(activity: Activity, model: TtsModelId): Boolean =
            start(activity, Task(TtsModelCatalog.byId(model).displayName(AppStrings.isEnglish(activity)), model, null, false))

        internal fun startAddon(activity: Activity, addon: OnnxAddonId, preferFp16: Boolean): Boolean =
            start(activity, Task(OnnxAddonCatalog.byId(addon).title(AppStrings.isEnglish(activity)), null, addon, preferFp16))

        private fun start(activity: Activity, task: Task): Boolean {
            if (activeTask != null) {
                Toast.makeText(activity, AppStrings.t(activity, "Завантаження вже триває", "A download is already running"), Toast.LENGTH_SHORT).show()
                return false
            }
            activeTask = task
            val preparing = AppStrings.t(activity, "Підготовка завантаження…", "Preparing download…")
            publish(Status(task.title, preparing, null, true, isModel = task.model != null))
            try {
                activity.startForegroundService(Intent(activity, ModelDownloadService::class.java))
            } catch (error: RuntimeException) {
                activeTask = null
                publish(Status(task.title, error.localizedMessage ?: preparing, null, false, failed = true, isModel = task.model != null))
                return false
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !ReaderPreferences(activity).modelDownloadNotificationPermissionAsked
            ) {
                ReaderPreferences(activity).modelDownloadNotificationPermissionAsked = true
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 420)
            }
            return true
        }
    }
}
