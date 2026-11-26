package com.holos.reader.update

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Pure-framework ContentProvider (zero-AndroidX) that safely exposes downloaded update APKs
 * from `cacheDir/updates/` to the Android Package Installer.
 */
class ApkFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = resolveApkFile(uri) ?: throw FileNotFoundException("APK not found: $uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = resolveApkFile(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols, 1)
        val row = Array<Any?>(cols.size) { i ->
            when (cols[i]) {
                OpenableColumns.DISPLAY_NAME -> file?.name ?: APK_FILE_NAME
                OpenableColumns.SIZE -> file?.length() ?: 0L
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    private fun resolveApkFile(uri: Uri): File? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (name != APK_FILE_NAME) return null
        val f = File(File(ctx.cacheDir, UPDATES_DIR), APK_FILE_NAME)
        return if (f.exists()) f else null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.holos.reader.apkprovider"
        const val UPDATES_DIR = "updates"
        const val APK_FILE_NAME = "HolosReader-update.apk"

        fun contentUri(): Uri = Uri.parse("content://$AUTHORITY/$APK_FILE_NAME")
    }
}
