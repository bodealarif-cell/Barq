package sa.barq.app.apps

import android.content.Context
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * يجهّز التطبيقات المختارة للإرسال:
 *  - تطبيق من ملف واحد  → يُرسل كما هو من مكانه (بدون نسخ) باسم "الاسم الإصدار.apk"
 *  - تطبيق مقسّم (splits) → يُجمّع في "الاسم الإصدار.apks" بدون ضغط، فيبقى حجمه معروفاً
 *    ويعمل شريط التقدم بدقة.
 */
object AppBundler {
    class Prepared(val uri: Uri, val name: String, val pkg: String, val size: Long)
    class Result(val items: List<Prepared>, val skipped: List<String>) {
        val uris: List<Uri> get() = items.map { it.uri }
        val names: Map<Uri, String> get() = items.associate { it.uri to it.name }
    }

    private val pkgByUri = ConcurrentHashMap<String, String>()

    /** اسم الحزمة لملف جهّزناه (ليعرض الأيقونة الحقيقية في قائمة النقل). */
    fun packageFor(uri: Uri?): String? = uri?.let { pkgByUri[it.toString()] }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "barq_apps")

    fun clean(ctx: Context) {
        try { dir(ctx).deleteRecursively() } catch (_: Exception) { }
        pkgByUri.clear()
    }

    private fun displayBase(app: AppEntry): String {
        val raw = if (app.versionName.isBlank()) app.label else "${app.label} ${app.versionName}"
        return raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").trim().take(80).ifBlank { app.pkg }
    }

    fun prepare(ctx: Context, apps: List<AppEntry>, onProgress: (done: Int, total: Int, label: String) -> Unit): Result {
        val d = dir(ctx)
        d.deleteRecursively()
        d.mkdirs()
        pkgByUri.clear()
        val items = mutableListOf<Prepared>()
        val skipped = mutableListOf<String>()
        apps.forEachIndexed { i, app ->
            onProgress(i, apps.size, app.label)
            try {
                val base = displayBase(app)
                val prepared: Prepared
                if (!app.isSplit) {
                    val f = File(app.apkPaths[0])
                    prepared = Prepared(Uri.fromFile(f), "$base.apk", app.pkg, f.length())
                } else {
                    if (d.usableSpace < app.sizeBytes + 32L * 1024 * 1024) {
                        skipped += "${app.label} (المساحة لا تكفي)"
                        return@forEachIndexed
                    }
                    val out = File(d, "${app.pkg}.apks")
                    ZipOutputStream(BufferedOutputStream(FileOutputStream(out), 1 shl 16)).use { z ->
                        z.setLevel(Deflater.NO_COMPRESSION)
                        for (p in app.apkPaths) {
                            z.putNextEntry(ZipEntry(File(p).name))
                            FileInputStream(p).use { it.copyTo(z, 1 shl 16) }
                            z.closeEntry()
                        }
                    }
                    prepared = Prepared(Uri.fromFile(out), "$base.apks", app.pkg, out.length())
                }
                items += prepared
                pkgByUri[prepared.uri.toString()] = app.pkg
            } catch (_: Exception) {
                skipped += app.label
            }
        }
        onProgress(apps.size, apps.size, "")
        return Result(items, skipped)
    }
}
