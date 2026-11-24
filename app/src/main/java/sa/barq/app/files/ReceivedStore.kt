package sa.barq.app.files

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class ReceivedFile(val uri: Uri, val name: String, val size: Long, val addedAt: Long)

enum class FileCategory(val label: String) {
    ALL("الكل"), APPS("تطبيقات"), IMAGES("صور"), VIDEOS("فيديو"), OTHER("أخرى")
}

/** يقرأ الملفات التي استقبلها برق من مجلد Download/Barq بدون صلاحيات إضافية (هي ملفات التطبيق نفسه). */
object ReceivedStore {
    private val images = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp")
    private val videos = setOf("mp4", "mov", "mkv", "avi", "3gp", "webm")

    fun categoryOf(name: String): FileCategory {
        val n = name.lowercase()
        if (n.contains(".apk") || n.contains(".barqapp") || n.endsWith(".xapk")) return FileCategory.APPS
        return when (n.substringAfterLast('.', "")) {
            in images -> FileCategory.IMAGES
            in videos -> FileCategory.VIDEOS
            else -> FileCategory.OTHER
        }
    }

    /** الأحدث أولاً. نداء قاعدة بيانات: شغّله خارج الخيط الرئيسي. */
    fun list(ctx: Context): List<ReceivedFile> {
        val out = mutableListOf<ReceivedFile>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED
        )
        try {
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("Download/Barq%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                while (c.moveToNext()) {
                    out += ReceivedFile(
                        uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(iId)),
                        name = c.getString(iName) ?: "file",
                        size = c.getLong(iSize),
                        addedAt = c.getLong(iDate) * 1000L
                    )
                }
            }
        } catch (_: SecurityException) { /* لا شيء نعرضه */ }
        return out
    }

    fun delete(ctx: Context, uri: Uri): Boolean =
        try { ctx.contentResolver.delete(uri, null, null) > 0 } catch (_: SecurityException) { false }
}
