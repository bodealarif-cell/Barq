package sa.barq.app.apps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

data class InstallMessage(val ok: Boolean, val text: String)

/** قناة بسيطة تنقل نتيجة التثبيت من الـReceiver إلى الواجهة. */
object InstallBus {
    var message by mutableStateOf<InstallMessage?>(null)
}

object ApkInstaller {
    const val ACTION = "sa.barq.app.INSTALL_RESULT"
    private enum class Kind { SINGLE, BUNDLE }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** صفحة "تثبيت تطبيقات غير معروفة" الخاصة ببرق مباشرة، بلا تنقّل بين القوائم. */
    fun unknownSourcesIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    fun isInstallable(fileName: String): Boolean {
        val n = fileName.lowercase()
        return n.contains(".apk") || n.endsWith(".xapk")
    }

    fun labelOf(fileName: String): String =
        fileName.replace(Regex("\\.(apk|apks|apkm|xapk)(\\.bin|\\.zip)?$", RegexOption.IGNORE_CASE), "").ifBlank { fileName }

    /** ملف APK واحد أم حزمة؟ نفحص أسماء أول مدخلات الـZIP، وإن تعذّر نعتمد على الامتداد. */
    private fun detect(cr: ContentResolver, uri: Uri, fileName: String): Kind {
        try {
            val stream = cr.openInputStream(uri) ?: throw IOException("لا يمكن فتح الملف")
            ZipInputStream(BufferedInputStream(stream)).use { z ->
                var e = z.nextEntry
                var seen = 0
                while (e != null && seen < 60) {
                    val n = e.name
                    if (n == "AndroidManifest.xml") return Kind.SINGLE
                    if (!e.isDirectory && n.lowercase().endsWith(".apk")) return Kind.BUNDLE
                    z.closeEntry()
                    e = z.nextEntry
                    seen++
                }
            }
        } catch (_: IOException) { /* نرجع للتخمين بالاسم */ }
        val n = fileName.lowercase()
        return if (n.contains(".apks") || n.endsWith(".xapk") || n.contains(".apkm")) Kind.BUNDLE else Kind.SINGLE
    }

    private fun writeEntry(session: PackageInstaller.Session, name: String, input: InputStream) {
        session.openWrite(name, 0, -1).use { out ->
            input.copyTo(out, 1 shl 16)
            session.fsync(out)
        }
    }

    private fun writeApks(ctx: Context, uri: Uri, fileName: String, session: PackageInstaller.Session): Int {
        val cr = ctx.contentResolver
        if (detect(cr, uri, fileName) == Kind.SINGLE) {
            (cr.openInputStream(uri) ?: throw IOException("لا يمكن فتح الملف")).use { writeEntry(session, "base.apk", it) }
            return 1
        }
        var count = 0
        val used = HashSet<String>()
        ZipInputStream(BufferedInputStream(cr.openInputStream(uri) ?: throw IOException("لا يمكن فتح الملف"))).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name.lowercase().endsWith(".apk")) {
                    var n = e.name.substringAfterLast('/')
                    if (!used.add(n)) n = "${count}_$n"
                    writeEntry(session, n, z)
                    count++
                }
                z.closeEntry()
                e = z.nextEntry
            }
        }
        return count
    }

    /**
     * يكتب الـAPK(ات) في جلسة تثبيت ثم يسلّمها للنظام الذي يعرض نافذة التأكيد.
     * نداء ثقيل (يقرأ الملف كله): شغّله خارج الخيط الرئيسي. يرمي استثناءً عند الفشل قبل التسليم.
     */
    fun install(ctx: Context, uri: Uri, fileName: String) {
        val installer = ctx.packageManager.packageInstaller
        val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        var committed = false
        try {
            installer.openSession(id).use { session ->
                if (writeApks(ctx, uri, fileName, session) == 0) throw IOException("لا يوجد ملف تطبيق صالح داخل الحزمة")
                val intent = Intent(ctx, InstallResultReceiver::class.java)
                    .setAction(ACTION)
                    .putExtra("label", labelOf(fileName))
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(ctx, id, intent, flags)
                session.commit(pending.intentSender)
                committed = true
            }
        } catch (e: Exception) {
            if (!committed) try { installer.abandonSession(id) } catch (_: Exception) { }
            throw e
        }
    }
}

/** يستقبل نتيجة جلسة التثبيت: يفتح نافذة تأكيد النظام عند الطلب، ويبلّغ الواجهة بالنتيجة. */
class InstallResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra("label") ?: "التطبيق"
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(confirm) } catch (_: Exception) {
                        InstallBus.message = InstallMessage(false, "تعذّر فتح نافذة تأكيد التثبيت")
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                InstallBus.message = InstallMessage(true, "تم تثبيت «$label» بنجاح")
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                InstallBus.message = InstallMessage(false, "تم إلغاء تثبيت «$label»")
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                InstallBus.message = InstallMessage(false, "الجهاز منع تثبيت «$label» (راجع إعدادات الأمان أو Play Protect)")
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                InstallBus.message = InstallMessage(false, "«$label» يتعارض مع نسخة مثبّتة بتوقيع مختلف. احذف النسخة القديمة ثم أعد المحاولة")
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                InstallBus.message = InstallMessage(false, "«$label» غير متوافق مع هذا الجهاز")
            PackageInstaller.STATUS_FAILURE_INVALID ->
                InstallBus.message = InstallMessage(false, "ملف «$label» غير صالح أو ناقص")
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                InstallBus.message = InstallMessage(false, "المساحة لا تكفي لتثبيت «$label»")
            else ->
                InstallBus.message = InstallMessage(false, "تعذّر تثبيت «$label»")
        }
    }
}
