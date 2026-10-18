package app.morsel.data

import android.content.Context
import java.io.File

/** How much of the phone's storage the app takes. */
class StorageInfo(private val context: Context) {

    data class Usage(val appBytes: Long, val dataBytes: Long) {
        val totalBytes get() = appBytes + dataBytes
    }

    /** The installed app itself, plus everything it has saved: database, photos, settings and cache. */
    fun usage(): Usage {
        val info = context.applicationInfo
        val app = File(info.sourceDir).length() + info.splitSourceDirs.orEmpty().sumOf { File(it).length() }
        return Usage(app, folderSize(context.dataDir))
    }

    private fun folderSize(file: File): Long =
        if (file.isFile) file.length() else file.listFiles()?.sumOf(::folderSize) ?: 0L
}

/** "840 KB", "3.2 MB". */
fun formatBytes(bytes: Long): String =
    if (bytes < 1024 * 1024) "${(bytes / 1024).coerceAtLeast(if (bytes > 0) 1 else 0)} KB"
    else String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
