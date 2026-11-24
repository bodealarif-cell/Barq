package sa.barq.app

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import sa.barq.app.ui.BarqTheme
import sa.barq.app.ui.DeviceMedia
import sa.barq.app.ui.HomeScreen
import sa.barq.app.ui.InkBackdrop
import sa.barq.app.ui.InstallLockedDialog
import sa.barq.app.ui.InstalledApp
import sa.barq.app.ui.ReceivedScreen
import sa.barq.app.ui.SendCategory
import sa.barq.app.ui.SendPickerScreen
import sa.barq.app.ui.TransferScreen
import sa.barq.app.ui.WelcomeScreen
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class Screen { WELCOME, HOME, SEND_PICKER, RECEIVED, TRANSFER }

class MainActivity : ComponentActivity() {
    private lateinit var engine: BarqEngine
    private var screen by mutableStateOf(Screen.WELCOME)
    private var displayName by mutableStateOf("")
    private var availableApps by mutableStateOf(emptyList<InstalledApp>())
    private var availableMedia by mutableStateOf(emptyList<DeviceMedia>())
    private var selectedApps by mutableStateOf(setOf<String>())
    private var selectedFiles by mutableStateOf(emptyList<Uri>())
    private var selectedMedia by mutableStateOf(setOf<Uri>())
    private var loadingMedia by mutableStateOf(false)
    private var loadingApps by mutableStateOf(false)
    private var showInstallLocked by mutableStateOf(false)
    private var sendCategory by mutableStateOf(SendCategory.APPS)
    private var afterPerms: (() -> Unit)? = null
    private data class PendingInstall(val uri: Uri, val isPackageArchive: Boolean)
    private var pendingInstall: PendingInstall? = null

    private val installSourceSettings =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val request = pendingInstall
            pendingInstall = null
            if (request != null && packageManager.canRequestPackageInstalls()) {
                launchInstaller(request.uri, request.isPackageArchive)
            } else if (request != null) {
                Toast.makeText(this, "اسمح لبرق بتثبيت التطبيقات من الإعدادات ثم حاول مجددًا", Toast.LENGTH_LONG).show()
            }
        }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val action = afterPerms
            afterPerms = null
            if (results.values.all { it }) action?.invoke()
            else Toast.makeText(this, "نحتاج الإذن عشان نلقى الأجهزة القريبة", Toast.LENGTH_LONG).show()
        }

    private val mediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadMedia(sendCategory)
            else Toast.makeText(this, "اسمح لبرق بالوصول إلى الوسائط لاختيارها", Toast.LENGTH_LONG).show()
        }

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            selectedFiles = (selectedFiles + uris).distinct()
        }

    /**
     * التطبيقات التي يعرفها المستخدم فقط: المحمّلة من جوجل بلاي أو غيره، وتحديثات تطبيقات النظام
     * (يوتيوب، كروم...). نستبعد حزم النظام الداخلية وكل ما ليس له أيقونة إطلاق.
     * نداء ثقيل (يقرأ الأيقونات): يُشغَّل في خيط خلفي.
     */
    @Suppress("DEPRECATION")
    private fun installedApps(): List<InstalledApp> {
        val pm = packageManager
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).mapNotNull { it.activityInfo?.packageName }.toSet()
        return pm.getInstalledApplications(0).mapNotNull { ai ->
            try {
                val pureSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                if (ai.packageName == packageName || pureSystem || ai.packageName !in launchable) return@mapNotNull null
                val files = (listOf(ai.sourceDir) + ai.splitSourceDirs.orEmpty()).map(::File)
                if (files.any { !it.isFile || !it.canRead() }) return@mapNotNull null
                val icon = try { ai.loadIcon(pm).toBitmap(96, 96).asImageBitmap() } catch (_: Exception) { null }
                InstalledApp(
                    packageName = ai.packageName,
                    label = ai.loadLabel(pm).toString().ifBlank { ai.packageName },
                    icon = icon,
                    sizeBytes = files.sumOf { it.length() },
                    versionName = pm.getPackageInfo(ai.packageName, 0).versionName.orEmpty(),
                    isSplit = files.size > 1
                )
            } catch (_: Exception) {
                null
            }
        }.sortedBy { it.label.lowercase() }
    }

    private fun openSendPicker() {
        withPerms {
            availableApps = emptyList()
            availableMedia = emptyList()
            selectedApps = emptySet()
            selectedFiles = emptyList()
            selectedMedia = emptySet()
            sendCategory = SendCategory.APPS
            loadingApps = true
            screen = Screen.SEND_PICKER
            Thread {
                try {
                    val apps = installedApps()
                    runOnUiThread { availableApps = apps; loadingApps = false }
                } catch (_: SecurityException) {
                    runOnUiThread {
                        loadingApps = false
                        Toast.makeText(this, "تعذّر قراءة قائمة التطبيقات المثبتة", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    private fun openCategoryPicker(category: SendCategory) {
        val mimeTypes = when (category) {
            SendCategory.APPS -> return
            SendCategory.VIDEOS -> arrayOf("video/*")
            SendCategory.FILES -> arrayOf("*/*")
            SendCategory.PACKAGES -> arrayOf(APK_MIME_TYPE, "application/octet-stream")
            SendCategory.AUDIO -> arrayOf("audio/*")
        }
        sendCategory = category
        picker.launch(mimeTypes)
    }

    private fun selectSendCategory(category: SendCategory) {
        sendCategory = category
        if (category != SendCategory.VIDEOS && category != SendCategory.AUDIO) {
            availableMedia = emptyList()
            loadingMedia = false
            return
        }
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            if (category == SendCategory.VIDEOS) Manifest.permission.READ_MEDIA_VIDEO
            else Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadMedia(category)
        } else {
            mediaPermissionLauncher.launch(permission)
        }
    }

    private fun loadMedia(category: SendCategory) {
        loadingMedia = true
        Thread {
            val media = mutableListOf<DeviceMedia>()
            try {
                val collection = if (category == SendCategory.VIDEOS) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }
                val projection = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE
                )
                contentResolver.query(
                    collection,
                    projection,
                    null,
                    null,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    while (cursor.moveToNext()) {
                        media += DeviceMedia(
                            ContentUris.withAppendedId(collection, cursor.getLong(idColumn)),
                            cursor.getString(nameColumn).orEmpty(),
                            cursor.getLong(sizeColumn)
                        )
                    }
                }
                runOnUiThread {
                    if (sendCategory == category) {
                        availableMedia = media
                        loadingMedia = false
                    }
                }
            } catch (_: SecurityException) {
                runOnUiThread {
                    if (sendCategory == category) {
                        availableMedia = emptyList()
                        loadingMedia = false
                        Toast.makeText(this, "تعذّر الوصول إلى الوسائط؛ تحقق من إذن برق", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun sendSelection() {
        val apps = availableApps.filter { it.packageName in selectedApps }
        val files = selectedFiles + selectedMedia
        if (apps.isEmpty() && files.isEmpty()) return
        Toast.makeText(this, "جارٍ تجهيز المحتوى للإرسال…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val uris = apps.map(::exportInstalledApp) + files
                runOnUiThread {
                    engine.begin(Mode.SEND, uris, displayName)
                    screen = Screen.TRANSFER
                }
            } catch (e: IOException) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "تعذّر تجهيز التطبيق للإرسال: ${e.localizedMessage ?: "خطأ في قراءة ملفات التطبيق"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (_: PackageManager.NameNotFoundException) {
                runOnUiThread {
                    Toast.makeText(this, "أحد التطبيقات لم يعد مثبتًا؛ ارجع وحدّث القائمة", Toast.LENGTH_LONG).show()
                }
            } catch (_: SecurityException) {
                runOnUiThread {
                    Toast.makeText(this, "لا يملك برق إذن قراءة ملفات التطبيق", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun exportInstalledApp(app: InstalledApp): Uri {
        val appInfo = packageManager.getApplicationInfo(app.packageName, 0)
        val apkFiles = (listOf(appInfo.sourceDir) + appInfo.splitSourceDirs.orEmpty()).map(::File)
        if (apkFiles.isEmpty() || apkFiles.any { !it.isFile || !it.canRead() }) {
            throw IOException("تعذّر الوصول إلى ملفات ${app.label}")
        }
        val safeName = app.label.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim().ifBlank { app.packageName }
        val archive = File(cacheDir, "share/${safeName}_${app.packageName}.barqapp")
        archive.parentFile?.mkdirs()
        var completed = false
        try {
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                apkFiles.forEach { apk ->
                    zip.putNextEntry(java.util.zip.ZipEntry(apk.name))
                    apk.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            completed = true
        } finally {
            if (!completed) archive.delete()
        }
        return FileProvider.getUriForFile(this, "$packageName.files", archive)
    }

    private fun withPerms(then: () -> Unit) {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
        fun continueIfReady() {
            if (Build.VERSION.SDK_INT <= 32) {
                val location = getSystemService(LOCATION_SERVICE) as LocationManager
                if (!location.isLocationEnabled) {
                    Toast.makeText(this, "فعّل خدمات الموقع للبحث عن الأجهزة القريبة", Toast.LENGTH_LONG).show()
                    return
                }
            }
            then()
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            continueIfReady()
        } else {
            afterPerms = { continueIfReady() }
            permLauncher.launch(arrayOf(permission))
        }
    }

    private fun shareApp() {
        try {
            val dir = File(cacheDir, "share").apply { mkdirs() }
            val apk = File(dir, "Barq.apk")
            File(applicationInfo.sourceDir).copyTo(apk, true)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", apk)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = APK_MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "جرّب تطبيق برق ⚡ ينقل الملفات بين الجوالات بسرعة البرق، بدون إنترنت وبدون باقة.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "أرسل برق لصديقك"))
        } catch (_: IOException) {
            Toast.makeText(this, "تعذّر تجهيز التطبيق للمشاركة", Toast.LENGTH_SHORT).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "لا يوجد تطبيق لمشاركة برق", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        displayName = getSharedPreferences("barq_preferences", MODE_PRIVATE)
            .getString("display_name", "").orEmpty()
        engine = BarqEngine(this)
        engine.updateDisplayName(displayName)
        ContextCompat.registerReceiver(this, engine.receiver, engine.filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                BarqTheme {
                    InkBackdrop {
                        Box(Modifier.fillMaxSize().systemBarsPadding()) {
                            AnimatedContent(
                                targetState = screen,
                                transitionSpec = {
                                    (fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 24 }) togetherWith
                                        fadeOut(tween(140))
                                },
                                label = "screen"
                            ) { current ->
                                when (current) {
                                    Screen.WELCOME -> WelcomeScreen { screen = Screen.HOME }
                                    Screen.HOME -> HomeScreen(
                                        displayName = displayName,
                                        onSaveName = {
                                            displayName = it
                                            getSharedPreferences("barq_preferences", MODE_PRIVATE)
                                                .edit().putString("display_name", it).apply()
                                            engine.updateDisplayName(it)
                                        },
                                        onSend = { openSendPicker() },
                                        onReceive = {
                                            withPerms {
                                                engine.begin(Mode.RECEIVE, emptyList(), displayName)
                                                screen = Screen.TRANSFER
                                            }
                                        },
                                        onShare = { shareApp() },
                                        onReceived = { screen = Screen.RECEIVED }
                                    )
                                    Screen.SEND_PICKER -> {
                                        BackHandler { screen = Screen.HOME }
                                        SendPickerScreen(
                                            apps = availableApps,
                                            media = availableMedia,
                                            selectedApps = selectedApps,
                                            selectedMedia = selectedMedia,
                                            selectedFileCount = selectedFiles.size,
                                            loadingMedia = loadingMedia,
                                            loadingApps = loadingApps,
                                            category = sendCategory,
                                            onCategorySelected = ::selectSendCategory,
                                            onToggleApp = { app ->
                                                selectedApps = if (app.packageName in selectedApps) {
                                                    selectedApps - app.packageName
                                                } else selectedApps + app.packageName
                                            },
                                            onToggleMedia = { item ->
                                                selectedMedia = if (item.uri in selectedMedia) {
                                                    selectedMedia - item.uri
                                                } else selectedMedia + item.uri
                                            },
                                            onOpenFiles = { openCategoryPicker(sendCategory) },
                                            onSend = ::sendSelection,
                                            onBack = { screen = Screen.HOME }
                                        )
                                    }
                                    Screen.RECEIVED -> {
                                        BackHandler { screen = Screen.HOME }
                                        ReceivedScreen(
                                            onBack = { screen = Screen.HOME },
                                            onOpen = ::openReceivedFile,
                                            onInstall = ::installReceivedPackage
                                        )
                                    }
                                    Screen.TRANSFER -> {
                                        BackHandler { engine.stop(); screen = Screen.HOME }
                                        TransferScreen(
                                            engine,
                                            onBack = { engine.stop(); screen = Screen.HOME },
                                            onPickMore = { openSendPicker() },
                                            onOpenFile = ::openReceivedFile,
                                            onInstallPackage = ::installReceivedPackage
                                        )
                                    }
                                }
                            }
                            if (showInstallLocked) {
                                InstallLockedDialog(
                                    onEnable = { showInstallLocked = false; openInstallSourceSettings() },
                                    onLater = { showInstallLocked = false; pendingInstall = null }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openReceivedFile(uri: Uri, name: String) {
        if (name.isInstallableAppFile()) {
            installPackage(uri, name.isAppArchiveFile())
            return
        }
        val extension = name.substringAfterLast('.', "").lowercase()
        val mimeType = contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "فتح الملف باستخدام"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "لا يوجد تطبيق لفتح هذا النوع من الملفات", Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "تعذّر الوصول إلى الملف", Toast.LENGTH_LONG).show()
        }
    }

    private fun installReceivedPackage(uri: Uri, name: String) {
        installPackage(uri, name.isAppArchiveFile())
    }

    private fun installPackage(uri: Uri, isPackageArchive: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            pendingInstall = PendingInstall(uri, isPackageArchive)
            showInstallLocked = true
            return
        }
        launchInstaller(uri, isPackageArchive)
    }

    /** يفتح صفحة "تثبيت تطبيقات غير معروفة" الخاصة ببرق مباشرة؛ عند الرجوع يكمل التثبيت تلقائياً. */
    private fun openInstallSourceSettings() {
        try {
            installSourceSettings.launch(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
        } catch (_: ActivityNotFoundException) {
            pendingInstall = null
            Toast.makeText(this, "تعذّر فتح إعدادات تثبيت التطبيقات", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchInstaller(uri: Uri, isPackageArchive: Boolean) {
        if (isPackageArchive) {
            Toast.makeText(this, "جارٍ تجهيز التطبيق للتثبيت…", Toast.LENGTH_SHORT).show()
            Thread {
                try {
                    installPackageArchive(uri)
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "تعذّر تجهيز التطبيق للتثبيت: ${e.localizedMessage ?: "ملف غير صالح"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.start()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "تعذّر فتح مثبّت أندرويد لهذا الملف", Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "لم يسمح أندرويد بفتح الملف للتثبيت", Toast.LENGTH_LONG).show()
        }
    }

    private fun installPackageArchive(uri: Uri) {
        val installer = packageManager.packageInstaller
        val sessionId = installer.createSession(
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        )
        try {
            installer.openSession(sessionId).use { session ->
                val input = contentResolver.openInputStream(uri) ?: throw IOException("تعذّر قراءة ملف التطبيق")
                var apkCount = 0
                input.use { stream ->
                    ZipInputStream(BufferedInputStream(stream)).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (entry.isDirectory) continue
                            if (!entry.name.endsWith(".apk", ignoreCase = true) ||
                                entry.name.contains('/') || entry.name.contains('\\')
                            ) {
                                throw IOException("ملف حزمة التطبيق يحتوي على مسارات غير صالحة")
                            }
                            session.openWrite(entry.name, 0, -1).use { output ->
                                zip.copyTo(output)
                                session.fsync(output)
                            }
                            apkCount++
                            if (apkCount > 100) throw IOException("عدد ملفات التطبيق يتجاوز الحد المسموح")
                        }
                    }
                }
                if (apkCount == 0) throw IOException("لم يتم العثور على ملفات APK داخل الحزمة")
                val callback = Intent(this, ApkInstallStatusActivity::class.java)
                    .setAction("sa.barq.app.INSTALL_RESULT.$sessionId")
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pendingIntent = PendingIntent.getActivity(this, sessionId, callback, flags)
                session.commit(pendingIntent.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            throw e
        }
    }

    override fun onDestroy() {
        unregisterReceiver(engine.receiver)
        engine.stop()
        super.onDestroy()
    }
}

class ApkInstallStatusActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation != null) {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(confirmation)
                } else {
                    Toast.makeText(this, "تعذّر فتح تأكيد تثبيت التطبيق", Toast.LENGTH_LONG).show()
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(this, "تم تثبيت التطبيق", Toast.LENGTH_LONG).show()
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Toast.makeText(
                    this,
                    "لم يكتمل تثبيت التطبيق${if (detail.isBlank()) "" else ": $detail"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        finish()
    }
}

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

internal fun String.isApkFile() = substringAfterLast('.', "").equals("apk", ignoreCase = true)
internal fun String.isAppArchiveFile() = contains(".barqapp", ignoreCase = true)
private fun String.isInstallableAppFile() = isApkFile() || isAppArchiveFile()
