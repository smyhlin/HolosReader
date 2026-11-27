package com.holos.reader.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.holos.reader.tts.ReaderPreferences
import com.holos.reader.ui.AppStrings
import com.holos.reader.ui.UiTheme
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Unauthenticated GitHub Releases auto-updater (zero login, zero AndroidX).
 * Checks `https://api.github.com/repos/smyhlin/HolosReader/releases/latest`,
 * compares semantic versions, downloads the `.apk` asset, and launches Android's installer.
 */
object GitHubUpdater {

    private const val RELEASES_LATEST_API = "https://api.github.com/repos/smyhlin/HolosReader/releases/latest"
    private val worker = Executors.newSingleThreadExecutor()

    private val backgroundCheck = java.util.concurrent.atomic.AtomicReference(CheckState.IDLE)

    private enum class CheckState { IDLE, RUNNING, COMPLETE }

    data class ReleaseInfo(
        val tagName: String,
        val version: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val apkSizeBytes: Long,
        val apkFileName: String = "",
        val sha256SumsUrl: String? = null,
    )

    fun checkInBackground(activity: Activity, prefs: ReaderPreferences) {
        if (!backgroundCheck.compareAndSet(CheckState.IDLE, CheckState.RUNNING)) return
        check(activity, prefs, silent = true)
    }

    fun checkManually(activity: Activity, prefs: ReaderPreferences) {
        Toast.makeText(
            activity,
            AppStrings.t(activity, "Перевірка оновлень…", "Checking for updates…"),
            Toast.LENGTH_SHORT,
        ).show()
        check(activity, prefs, silent = false)
    }

    private fun check(activity: Activity, prefs: ReaderPreferences, silent: Boolean) {
        val installedVersion = installedVersionName(activity)
        worker.execute {
            val release = try {
                fetchLatestRelease()
            } catch (e: Exception) {
                if (silent) backgroundCheck.set(CheckState.IDLE)
                if (!silent) {
                    activity.runOnUiThread {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            Toast.makeText(
                                activity,
                                AppStrings.t(activity, "Помилка перевірки: ${e.message}", "Update check failed: ${e.message}"),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
                return@execute
            }

            if (silent) backgroundCheck.set(CheckState.COMPLETE)
            if (release == null) {
                if (!silent) {
                    activity.runOnUiThread {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            Toast.makeText(
                                activity,
                                AppStrings.t(activity, "Релізи ще не опубліковані", "No releases published yet"),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
                return@execute
            }

            val isNewer = compareVersions(release.version, installedVersion) > 0
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (isNewer) {
                    if (silent && prefs.dismissedUpdateTag == release.tagName) return@runOnUiThread
                    showUpdatePrompt(activity, prefs, release, installedVersion)
                } else if (!silent) {
                    Toast.makeText(
                        activity,
                        AppStrings.t(
                            activity,
                            "У вас остання версія ($installedVersion)",
                            "You're on the latest version ($installedVersion)",
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo? {
        val conn = (URL(RELEASES_LATEST_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "HolosReader-Android")
        }
        try {
            val code = conn.responseCode
            if (code == 404) return null
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "").trim()
            if (tag.isEmpty()) return null
            val version = tag.removePrefix("v").removePrefix("V").trim()
            val name = json.optString("name", tag).ifBlank { tag }
            val notes = json.optString("body", "").trim()
            val assets = json.optJSONArray("assets") ?: return null
            val apkAssets = mutableListOf<Triple<String, String, Long>>()
            var sha256SumsUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val assetName = a.optString("name", "")
                val url = a.optString("browser_download_url", "")
                if (assetName.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                    apkAssets.add(Triple(assetName, url, a.optLong("size", 0L)))
                } else if (assetName.equals("SHA256SUMS", ignoreCase = true) && url.isNotBlank()) {
                    sha256SumsUrl = url
                }
            }
            if (apkAssets.isEmpty()) return null
            val supportedAbis = Build.SUPPORTED_ABIS ?: emptyArray()
            val chosen = selectBestApkAsset(apkAssets, supportedAbis) ?: return null
            return ReleaseInfo(tag, version, name, notes, chosen.second, chosen.third, chosen.first, sha256SumsUrl)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Picks the best matching APK asset using exact ABI token boundaries (so `x86` never matches
     * `x86_64`), falling back to `universal`, and failing closed (`null`) if no compatible asset exists (H33).
     */
    internal fun selectBestApkAsset(
        apkAssets: List<Triple<String, String, Long>>,
        supportedAbis: Array<String>,
    ): Triple<String, String, Long>? {
        for (abi in supportedAbis) {
            val tokenRe = Regex("(?i)(?:^|[-.])${Regex.escape(abi)}(?:[-.]|\\.apk$)")
            apkAssets.firstOrNull { tokenRe.containsMatchIn(it.first) }?.let { return it }
        }
        val univRe = Regex("(?i)(?:^|[-_.])universal(?:[-_.]|\\.apk$)")
        return apkAssets.firstOrNull { univRe.containsMatchIn(it.first) }
    }

    private fun showUpdatePrompt(
        activity: Activity,
        prefs: ReaderPreferences,
        release: ReleaseInfo,
        installedVersion: String,
    ) {
        val en = AppStrings.isEnglish(activity)
        val sizeMb = if (release.apkSizeBytes > 0) {
            String.format(" (%.1f %s)", release.apkSizeBytes / (1024.0 * 1024.0), if (en) "MB" else "МБ")
        } else ""
        val msg = buildString {
            append(if (en) "Installed: v$installedVersion\nLatest: ${release.tagName}$sizeMb" else "Встановлено: v$installedVersion\nДоступна: ${release.tagName}$sizeMb")
            if (release.notes.isNotBlank()) {
                append("\n\n")
                append(release.notes.take(400))
            }
        }
        UiTheme.showThemedDialog(
            UiTheme.dialogBuilder(activity, prefs.theme)
                .setTitle(if (en) "Update Available" else "Доступне оновлення")
                .setMessage(msg)
                .setPositiveButton(if (en) "Update" else "Оновити") { _, _ ->
                    downloadAndInstall(activity, prefs, release)
                }
                .setNegativeButton(if (en) "Later" else "Пізніше") { _, _ ->
                    prefs.dismissedUpdateTag = release.tagName
                },
            prefs.theme,
        )
    }

    private fun downloadAndInstall(activity: Activity, prefs: ReaderPreferences, release: ReleaseInfo) {
        val en = AppStrings.isEnglish(activity)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                activity,
                if (en) "Allow app installs for HolosReader, then tap Update again"
                else "Дозвольте встановлення оновлень для HolosReader",
                Toast.LENGTH_LONG,
            ).show()
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return
        }

        val dp = activity.resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val p = UiTheme.palette(prefs.theme)
        val statusTv = TextView(activity).apply {
            text = if (en) "Downloading ${release.tagName}…" else "Завантаження ${release.tagName}…"
            textSize = 14f
            setTextColor(p.primaryText)
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(statusTv)
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (12 * dp).toInt() })
        }
        val dlg: AlertDialog = UiTheme.showThemedDialog(
            UiTheme.dialogBuilder(activity, prefs.theme)
                .setTitle(if (en) "Downloading Update" else "Завантаження оновлення")
                .setView(box)
                .setCancelable(false),
            prefs.theme,
        )

        worker.execute {
            val updatesDir = File(activity.cacheDir, ApkFileProvider.UPDATES_DIR).apply { mkdirs() }
            val outFile = File(updatesDir, ApkFileProvider.APK_FILE_NAME)
            val tmpFile = File(updatesDir, ApkFileProvider.APK_FILE_NAME + ".part")
            try {
                downloadWithRedirects(release.apkUrl, tmpFile, release.apkSizeBytes) { pct ->
                    activity.runOnUiThread {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            bar.progress = pct
                            statusTv.text = if (en) "Downloading ${release.tagName}… $pct%" else "Завантаження ${release.tagName}… $pct%"
                        }
                    }
                }
                verifyAndStageApk(
                    tmpFile = tmpFile,
                    outFile = outFile,
                    expectedBytes = release.apkSizeBytes,
                    apkFileName = release.apkFileName,
                    sha256SumsUrl = release.sha256SumsUrl,
                    fetchManifest = { url -> fetchTextWithRedirects(url) },
                )
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    val canLaunch = dlg.window?.decorView?.hasWindowFocus() == true
                    if (dlg.isShowing) dlg.dismiss()
                    if (canLaunch) launchInstaller(activity)
                }
            } catch (e: Exception) {
                tmpFile.delete()
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    if (dlg.isShowing) dlg.dismiss()
                    Toast.makeText(
                        activity,
                        if (en) "Download failed: ${e.message}" else "Помилка завантаження: ${e.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    internal fun verifyAndStageApk(
        tmpFile: File,
        outFile: File,
        expectedBytes: Long,
        apkFileName: String,
        sha256SumsUrl: String?,
        fetchManifest: (String) -> String,
        mover: (File, File) -> Unit = { src, dst ->
            try {
                java.nio.file.Files.move(
                    src.toPath(),
                    dst.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    src.toPath(),
                    dst.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
        },
    ) {
        if (!tmpFile.isFile || tmpFile.length() <= 0L) {
            throw IllegalStateException("Downloaded APK is missing or empty")
        }
        if (expectedBytes > 0L && tmpFile.length() != expectedBytes) {
            throw IllegalStateException("APK size mismatch: got ${tmpFile.length()}, expected $expectedBytes")
        }
        if (sha256SumsUrl.isNullOrBlank()) {
            throw IllegalStateException("Release is missing SHA256SUMS checksum manifest; refusing unverified APK")
        }
        if (apkFileName.isBlank()) {
            throw IllegalStateException("Missing APK asset filename for SHA-256 verification")
        }
        val manifestText = fetchManifest(sha256SumsUrl)
        val expectedSha = parseSha256ForAsset(manifestText, apkFileName)
            ?: throw IllegalStateException("Missing SHA-256 entry for $apkFileName in SHA256SUMS")
        val actualSha = com.holos.reader.tts.VerifiedHttpDownloader.sha256Hex(tmpFile)
        if (!actualSha.equals(expectedSha, ignoreCase = true)) {
            throw IllegalStateException("APK SHA-256 mismatch: got $actualSha, expected $expectedSha")
        }
        mover(tmpFile, outFile)
    }

    internal fun parseSha256ForAsset(manifestText: String, fileName: String): String? {
        for (raw in manifestText.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("""\s+"""), limit = 2)
            if (parts.size != 2) continue
            val hash = parts[0].trim()
            val name = parts[1].trim().removePrefix("*").substringAfterLast('/')
            if (hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } && name == fileName) {
                return hash.lowercase()
            }
        }
        return null
    }

    private fun fetchTextWithRedirects(startUrl: String): String {
        var currentUrl = startUrl
        var redirects = 0
        while (redirects < 6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "HolosReader-Android")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IllegalStateException("Missing redirect Location")
                    currentUrl = loc
                    redirects++
                    continue
                }
                if (code !in 200..299) throw IllegalStateException("HTTP $code fetching checksums")
                return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("Too many redirects fetching checksums")
    }

    internal fun downloadWithRedirects(
        startUrl: String,
        dest: File,
        expectedBytes: Long,
        onProgress: (Int) -> Unit,
    ) {
        var currentUrl = startUrl
        var redirects = 0
        while (redirects < 6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "HolosReader-Android")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IllegalStateException("Missing redirect Location")
                    currentUrl = loc
                    redirects++
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP $code")
                val headerLen = conn.contentLengthLong
                if (expectedBytes > 0L && headerLen > 0L && headerLen != expectedBytes) {
                    throw IllegalStateException("Content-Length $headerLen != expected $expectedBytes")
                }
                val total = headerLen.takeIf { it > 0L } ?: expectedBytes
                var downloaded = 0L
                var lastPct = -1
                conn.inputStream.use { input ->
                    FileOutputStream(dest).use { output ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            if (total > 0L) {
                                val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (downloaded <= 0L) throw IllegalStateException("Empty APK download")
                if (total > 0L && downloaded != total) {
                    throw IllegalStateException("Truncated APK download: $downloaded / $total bytes")
                }
                return
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    private fun launchInstaller(activity: Activity) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(ApkFileProvider.contentUri(), "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    fun installedVersionName(activity: Activity): String = try {
        val pi = activity.packageManager.getPackageInfo(activity.packageName, 0)
        pi.versionName ?: "5.1.0"
    } catch (_: Exception) {
        "5.1.0"
    }

    fun compareVersions(remote: String, local: String): Int {
        val rParts = remote.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val lParts = local.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r != l) return r.compareTo(l)
        }
        return 0
    }
}
