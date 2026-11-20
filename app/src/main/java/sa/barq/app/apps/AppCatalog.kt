package sa.barq.app.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.io.File

/** تطبيق مثبّت على هذا الجوال وجاهز للإرسال. أول مسار في [apkPaths] هو الملف الأساسي. */
data class AppEntry(
    val pkg: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val sizeBytes: Long,
    val apkPaths: List<String>,
    val isSystem: Boolean,
    val updatedAt: Long
) {
    /** التطبيقات الحديثة تأتي غالباً مقسّمة إلى عدة ملفات APK (base + splits). */
    val isSplit: Boolean get() = apkPaths.size > 1
}

object AppCatalog {
    private val icons = LruCache<String, ImageBitmap>(200)

    fun cached(pkg: String): ImageBitmap? = icons.get(pkg)

    /** يقرأ كل التطبيقات المثبتة (يتطلب QUERY_ALL_PACKAGES). نداء ثقيل: شغّله خارج الخيط الرئيسي. */
    @Suppress("DEPRECATION")
    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        return pm.getInstalledApplications(0).mapNotNull { ai ->
            try {
                val base = ai.sourceDir ?: return@mapNotNull null
                val paths = buildList {
                    add(base)
                    ai.splitSourceDirs?.let { addAll(it) }
                }
                // ملف لا نستطيع قراءته لا نستطيع إرساله؛ نخفيه بدل أن يفشل لاحقاً
                if (!paths.all { File(it).canRead() }) return@mapNotNull null
                val pi = pm.getPackageInfo(ai.packageName, 0)
                val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                AppEntry(
                    pkg = ai.packageName,
                    label = ai.loadLabel(pm).toString().ifBlank { ai.packageName },
                    versionName = pi.versionName.orEmpty(),
                    versionCode = pi.longVersionCode,
                    sizeBytes = paths.sumOf { File(it).length() },
                    apkPaths = paths,
                    isSystem = isSystem,
                    updatedAt = pi.lastUpdateTime
                )
            } catch (_: Exception) {
                null
            }
        }.sortedBy { it.label.lowercase() }
    }

    fun icon(ctx: Context, pkg: String): ImageBitmap? {
        val hit = icons.get(pkg)
        if (hit != null) return hit
        return try {
            val bmp = ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96)
            bmp.asImageBitmap().also { icons.put(pkg, it) }
        } catch (_: Exception) {
            null
        }
    }
}
