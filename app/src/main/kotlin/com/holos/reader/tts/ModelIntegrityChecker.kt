package com.holos.reader.tts

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Per-file model & ONNX add-on bundle integrity manifest, diagnostics, and selective repair (#47).
 * Persists `.verified.json` inside each bundle directory so normal readiness checks stay fast
 * while explicit Verify / Repair runs deep SHA-256 + ONNX header checks off the UI thread and
 * re-downloads only missing, corrupt, or wrong-variant files.
 */
object ModelIntegrityChecker {

    const val MANIFEST_NAME = ".verified.json"
    private const val MIN_ONNX_BYTES = 16L

    enum class FileStatus {
        VERIFIED,
        LEGACY_UNVERIFIED,
        CORRUPT,
        MISSING,
        VARIANT_MISMATCH,
    }

    data class ExpectedFile(
        val relPath: String,
        val file: File,
        val sourceUrl: String,
        val variant: String = "",
        val expectedBytes: Long = -1L,
        val expectedSha256: String? = null,
        val validateContent: ((File) -> Boolean)? = null,
    )

    data class FileDiagnostic(
        val relPath: String,
        val file: File,
        val sourceUrl: String,
        val status: FileStatus,
        val detail: String,
    )

    data class BundleReport(
        val status: FileStatus,
        val files: List<FileDiagnostic>,
        val verifiedCount: Int,
        val repairableCount: Int,
    )

    data class VerifiedEntry(
        val size: Long,
        val sha256: String,
        val sourceUrl: String,
        val variant: String,
    )

    fun manifestFile(bundleDir: File): File = File(bundleDir, MANIFEST_NAME)

    fun readManifest(bundleDir: File): MutableMap<String, VerifiedEntry> {
        val mf = manifestFile(bundleDir)
        val out = LinkedHashMap<String, VerifiedEntry>()
        if (!mf.isFile) return out
        return try {
            for (raw in mf.readLines(Charsets.UTF_8)) {
                val line = raw.trimEnd('\r', '\n')
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("version=")) continue
                val parts = line.split('\t')
                if (parts.size >= 5) {
                    val key = parts[0]
                    val size = parts[1].toLongOrNull() ?: -1L
                    val sha = parts[2]
                    val variant = parts[3]
                    val url = parts[4]
                    if (key.isNotEmpty()) {
                        out[key] = VerifiedEntry(size = size, sha256 = sha, sourceUrl = url, variant = variant)
                    }
                }
            }
            out
        } catch (_: Exception) {
            out
        }
    }

    fun recordVerifiedFile(
        bundleDir: File,
        relPath: String,
        file: File,
        sourceUrl: String,
        variant: String = "",
        precomputedSha256: String? = null,
    ) {
        if (!file.isFile || file.length() <= 0L) return
        bundleDir.mkdirs()
        val entries = readManifest(bundleDir)
        val sha = precomputedSha256?.takeIf { it.isNotBlank() } ?: VerifiedHttpDownloader.sha256Hex(file)
        entries[relPath] = VerifiedEntry(
            size = file.length(),
            sha256 = sha.lowercase(),
            sourceUrl = sourceUrl,
            variant = variant,
        )
        writeManifestAtomically(bundleDir, entries)
    }

    private fun writeManifestAtomically(bundleDir: File, entries: Map<String, VerifiedEntry>) {
        val sb = StringBuilder(256)
        sb.append("version=1\n")
        for ((k, v) in entries) {
            sb.append(k.replace('\t', ' ').replace('\n', ' ')).append('\t')
                .append(v.size).append('\t')
                .append(v.sha256).append('\t')
                .append(v.variant.replace('\t', ' ').replace('\n', ' ')).append('\t')
                .append(v.sourceUrl.replace('\t', ' ').replace('\n', ' ')).append('\n')
        }
        val target = manifestFile(bundleDir)
        val tmp = File(target.path + ".tmp")
        tmp.writeText(sb.toString(), Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                if (target.exists()) target.delete()
                tmp.renameTo(target)
            }
        }
    }

    /** Basic sanity check so truncated or HTML error pages saved as `.onnx` are caught immediately. */
    fun isPlausibleOnnxFile(file: File): Boolean {
        if (!file.isFile) return false
        val len = file.length()
        if (len < MIN_ONNX_BYTES) return false
        return try {
            val head = ByteArray(16)
            val n = FileInputStream(file).use { it.read(head) }
            if (n < 4) return false
            val prefix = String(head, 0, n, Charsets.US_ASCII).trimStart()
            if (prefix.startsWith("<!", ignoreCase = true) ||
                prefix.startsWith("<html", ignoreCase = true) ||
                prefix.startsWith("{")
            ) {
                return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun diagnoseBundle(
        bundleDir: File,
        expected: List<ExpectedFile>,
        deepHashCheck: Boolean = true,
    ): BundleReport {
        val manifest = readManifest(bundleDir)
        val diagnostics = ArrayList<FileDiagnostic>(expected.size)
        var verified = 0
        var repairable = 0

        for (item in expected) {
            val f = item.file
            if (!f.isFile || f.length() <= 0L) {
                diagnostics.add(FileDiagnostic(item.relPath, f, item.sourceUrl, FileStatus.MISSING, "Missing or empty"))
                repairable++
                continue
            }
            if (f.name.endsWith(".onnx", ignoreCase = true) && !isPlausibleOnnxFile(f)) {
                diagnostics.add(FileDiagnostic(item.relPath, f, item.sourceUrl, FileStatus.CORRUPT, "Invalid or truncated ONNX header"))
                repairable++
                continue
            }
            if (item.validateContent?.invoke(f) == false) {
                diagnostics.add(FileDiagnostic(item.relPath, f, item.sourceUrl, FileStatus.CORRUPT, "Invalid bundle metadata"))
                repairable++
                continue
            }
            if (item.expectedBytes > 0L && f.length() != item.expectedBytes) {
                diagnostics.add(FileDiagnostic(item.relPath, f, item.sourceUrl, FileStatus.CORRUPT, "Size ${f.length()} != expected ${item.expectedBytes}"))
                repairable++
                continue
            }

            val recorded = manifest[item.relPath]
            if (recorded != null) {
                if (item.variant.isNotEmpty() && recorded.variant.isNotEmpty() && recorded.variant != item.variant) {
                    diagnostics.add(FileDiagnostic(item.relPath, f, item.sourceUrl, FileStatus.VARIANT_MISMATCH, "Installed variant '${recorded.variant}' != '${item.variant}'"))
                    repairable++
                    continue
                }
                if (item.sourceUrl.isNotEmpty() && recorded.sourceUrl.isNotEmpty() && recorded.sourceUrl != item.sourceUrl) {
                    diagnostics.add(FileDiagnostic(item.relPath, f, item.sourceUrl, FileStatus.VARIANT_MISMATCH, "Source URL changed"))
                    repairable++
                    continue
                }
                if (recorded.size > 0L && f.length() != recorded.size) {
                    diagnostics.add(FileDiagnostic(item.relPath, f, item.sourceUrl, FileStatus.CORRUPT, "Size ${f.length()} != recorded ${recorded.size}"))
                    repairable++
                    continue
                }
            }

            if (deepHashCheck) {
                val actualSha = VerifiedHttpDownloader.sha256Hex(f)
                if (!item.expectedSha256.isNullOrBlank()) {
                    if (!actualSha.equals(item.expectedSha256, ignoreCase = true)) {
                        diagnostics.add(FileDiagnostic(item.relPath, f, item.sourceUrl, FileStatus.CORRUPT, "SHA-256 mismatch"))
                        repairable++
                        continue
                    }
                    // Verified against known release hash — persist manifest if missing
                    if (recorded == null) {
                        recordVerifiedFile(bundleDir, item.relPath, f, item.sourceUrl, item.variant, actualSha)
                    }
                    diagnostics.add(FileDiagnostic(item.relPath, f, item.sourceUrl, FileStatus.VERIFIED, "SHA-256 verified"))
                    verified++
                    continue
                }
                if (recorded != null && recorded.sha256.isNotBlank()) {
                    if (!actualSha.equals(recorded.sha256, ignoreCase = true)) {
                        diagnostics.add(FileDiagnostic(item.relPath, f, item.sourceUrl, FileStatus.CORRUPT, "Recorded SHA-256 mismatch"))
                        repairable++
                        continue
                    }
                    diagnostics.add(FileDiagnostic(item.relPath, f, item.sourceUrl, FileStatus.VERIFIED, "Manifest SHA-256 verified"))
                    verified++
                    continue
                }
                diagnostics.add(FileDiagnostic(item.relPath, f, item.sourceUrl, FileStatus.LEGACY_UNVERIFIED, "Legacy file without checksum manifest"))
                continue
            } else {
                if (recorded != null) {
                    diagnostics.add(FileDiagnostic(item.relPath, f, item.sourceUrl, FileStatus.VERIFIED, "Manifest metadata ok"))
                    verified++
                } else {
                    diagnostics.add(FileDiagnostic(item.relPath, f, item.sourceUrl, FileStatus.LEGACY_UNVERIFIED, "Legacy unverified file"))
                }
            }
        }

        val overall = when {
            diagnostics.any { it.status == FileStatus.CORRUPT } -> FileStatus.CORRUPT
            diagnostics.any { it.status == FileStatus.VARIANT_MISMATCH } -> FileStatus.VARIANT_MISMATCH
            diagnostics.any { it.status == FileStatus.MISSING } -> FileStatus.MISSING
            diagnostics.any { it.status == FileStatus.LEGACY_UNVERIFIED } -> FileStatus.LEGACY_UNVERIFIED
            else -> FileStatus.VERIFIED
        }
        return BundleReport(overall, diagnostics, verified, repairable)
    }

    /**
     * Selectively repairs ONLY files that are MISSING, CORRUPT, or VARIANT_MISMATCH.
     * Already VERIFIED files are never re-downloaded. Existing valid files remain untouched if
     * a download fails mid-repair because [fetcher] writes to a staging `.part` file first.
     */
    fun repairBundle(
        bundleDir: File,
        expected: List<ExpectedFile>,
        includeLegacyUnverified: Boolean = false,
        fetcher: (ExpectedFile) -> Unit,
    ): BundleReport {
        val before = diagnoseBundle(bundleDir, expected, deepHashCheck = true)
        val statusByPath = before.files.associate { it.relPath to it.status }
        for (item in expected) {
            val st = statusByPath[item.relPath] ?: FileStatus.MISSING
            val needsFetch = st == FileStatus.MISSING ||
                st == FileStatus.CORRUPT ||
                st == FileStatus.VARIANT_MISMATCH ||
                (includeLegacyUnverified && st == FileStatus.LEGACY_UNVERIFIED)
            if (!needsFetch) continue
            fetcher(item)
            recordVerifiedFile(bundleDir, item.relPath, item.file, item.sourceUrl, item.variant)
        }
        return diagnoseBundle(bundleDir, expected, deepHashCheck = true)
    }
}
