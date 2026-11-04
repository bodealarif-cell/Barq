package sa.barq.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageInstaller
import android.content.ContentUris
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.app.Activity
import android.app.PendingIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object C {
    val Navy = Color(0xFF0A1230); val Navy2 = Color(0xFF16215A); val Card = Color(0xFF111B45)
    val Blue = Color(0xFF2F6BFF); val Cyan = Color(0xFF22D3EE); val Gold = Color(0xFFFFC531)
    val Text = Color(0xFFF4F6FF); val Muted = Color(0xFF9AA6D6); val Ok = Color(0xFF3DDC97)
}
enum class Screen { WELCOME, HOME, SEND_PICKER, TRANSFER }
enum class SendCategory(val title: String, val icon: String) {
    APPS("تطبيقات", "📱"),
    VIDEOS("فيديوهات", "🎞️"),
    FILES("ملفات", "📁"),
    PACKAGES("حزم", "📦"),
    AUDIO("صوتيات", "🎵")
}
data class InstalledApp(val packageName: String, val label: String)
data class DeviceMedia(val uri: Uri, val name: String, val size: Long)

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
    private var sendCategory by mutableStateOf(SendCategory.APPS)
    private var afterPerms: (() -> Unit)? = null
    private data class PendingInstall(val uri: Uri, val isPackageArchive: Boolean)
    private var pendingInstall: PendingInstall? = null

    private val installSourceSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val request = pendingInstall
        pendingInstall = null
        if (request != null && packageManager.canRequestPackageInstalls()) {
            launchInstaller(request.uri, request.isPackageArchive)
        } else if (request != null) {
            Toast.makeText(this, "اسمح لبرق بتثبيت التطبيقات من الإعدادات ثم حاول مجددًا", Toast.LENGTH_LONG).show()
        }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val action = afterPerms
        afterPerms = null
        if (r.values.all { it }) action?.invoke()
        else Toast.makeText(this, "نحتاج الإذن عشان نلقى الأجهزة القريبة", Toast.LENGTH_LONG).show()
    }
    private val mediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadMedia(sendCategory)
            else Toast.makeText(this, "اسمح لبرق بالوصول إلى الوسائط لاختيارها", Toast.LENGTH_LONG).show()
        }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        selectedFiles = (selectedFiles + uris).distinct()
    }

    @Suppress("DEPRECATION")
    private fun installedApps(): List<InstalledApp> {
        return packageManager.getInstalledApplications(0)
            .filter {
                it.packageName != packageName &&
                    (it.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED) != 0
            }
            .map { appInfo ->
                InstalledApp(appInfo.packageName, packageManager.getApplicationLabel(appInfo).toString())
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun openSendPicker() {
        withPerms {
            try {
                availableApps = installedApps()
                selectedApps = emptySet()
                selectedFiles = emptyList()
                selectedMedia = emptySet()
                availableMedia = emptyList()
                sendCategory = SendCategory.APPS
                screen = Screen.SEND_PICKER
            } catch (_: SecurityException) {
                Toast.makeText(this, "تعذّر قراءة قائمة التطبيقات المثبتة", Toast.LENGTH_LONG).show()
            }
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
            } catch (e: PackageManager.NameNotFoundException) {
                runOnUiThread {
                    Toast.makeText(this, "أحد التطبيقات لم يعد مثبتًا؛ ارجع وحدّث القائمة", Toast.LENGTH_LONG).show()
                }
            } catch (e: SecurityException) {
                runOnUiThread {
                    Toast.makeText(this, "لا يملك برق إذن قراءة ملفات التطبيق", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun exportInstalledApp(app: InstalledApp): Uri {
        val appInfo = packageManager.getApplicationInfo(app.packageName, 0)
        val apkFiles = (listOf(appInfo.sourceDir) + appInfo.splitSourceDirs.orEmpty())
            .map(::File)
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
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
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
        if (ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED) continueIfReady()
        else { afterPerms = { continueIfReady() }; permLauncher.launch(arrayOf(p)) }
    }

    private fun shareApp() {
        try {
            val dir = File(cacheDir, "share").apply { mkdirs() }
            val apk = File(dir, "Barq.apk")
            File(applicationInfo.sourceDir).copyTo(apk, true)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", apk)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "جرّب تطبيق برق ⚡ ينقل الملفات بين الجوالات بسرعة البرق، بدون إنترنت وبدون باقة.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "أرسل برق لصديقك"))
        } catch (e: Exception) {
            Toast.makeText(this, "تعذّر تجهيز التطبيق للمشاركة", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayName = getSharedPreferences("barq_preferences", MODE_PRIVATE)
            .getString("display_name", "").orEmpty()
        engine = BarqEngine(this)
        ContextCompat.registerReceiver(this, engine.receiver, engine.filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(C.Navy, C.Navy2)))
                        .systemBarsPadding()) {
                        when (screen) {
                            Screen.WELCOME -> Welcome { screen = Screen.HOME }
                            Screen.HOME -> Home(
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
                                onShare = { shareApp() })
                            Screen.SEND_PICKER -> {
                                BackHandler { screen = Screen.HOME }
                                SendPicker(
                                    apps = availableApps,
                                    selectedApps = selectedApps,
                                    media = availableMedia,
                                    selectedMedia = selectedMedia,
                                    loadingMedia = loadingMedia,
                                    selectedFileCount = selectedFiles.size,
                                    category = sendCategory,
                                    onCategorySelected = { selectSendCategory(it) },
                                    onToggleApp = { app ->
                                        selectedApps = if (app.packageName in selectedApps) {
                                            selectedApps - app.packageName
                                        } else {
                                            selectedApps + app.packageName
                                        }
                                    },
                                    onToggleMedia = { item ->
                                        selectedMedia = if (item.uri in selectedMedia) {
                                            selectedMedia - item.uri
                                        } else {
                                            selectedMedia + item.uri
                                        }
                                    },
                                    onOpenFiles = { openCategoryPicker(sendCategory) },
                                    onSend = { sendSelection() },
                                    onBack = { screen = Screen.HOME }
                                )
                            }
                            Screen.TRANSFER -> {
                                BackHandler { engine.stop(); screen = Screen.HOME }
                                Transfer(
                                    engine,
                                    onBack = { engine.stop(); screen = Screen.HOME },
                                    onPickMore = { openSendPicker() },
                                    onOpenFile = { uri, name -> openReceivedFile(uri, name) },
                                    onInstallApk = { uri, name -> installReceivedPackage(uri, name) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openReceivedFile(uri: Uri, name: String) {
        if (name.isApkFile()) {
            installPackage(uri, isPackageArchive = false)
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
            try {
                installSourceSettings.launch(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
            } catch (_: ActivityNotFoundException) {
                pendingInstall = null
                Toast.makeText(this, "تعذّر فتح إعدادات تثبيت التطبيقات", Toast.LENGTH_LONG).show()
            }
            return
        }
        launchInstaller(uri, isPackageArchive)
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
                val input = contentResolver.openInputStream(uri)
                    ?: throw IOException("تعذّر قراءة ملف التطبيق")
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
                val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    sessionId,
                    callback,
                    pendingIntentFlags
                )
                session.commit(pendingIntent.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            throw e
        }
    }

    override fun onDestroy() { unregisterReceiver(engine.receiver); engine.stop(); super.onDestroy() }
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
                Toast.makeText(this, "لم يكتمل تثبيت التطبيق${if (detail.isBlank()) "" else ": $detail"}", Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }
}

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

private fun String.isApkFile() = substringAfterLast('.', "").equals("apk", ignoreCase = true)
private fun String.isAppArchiveFile() = substringAfterLast('.', "").equals("barqapp", ignoreCase = true)
private fun String.isInstallableAppFile() = isApkFile() || isAppArchiveFile()

@Composable
fun BarqLogo(dim: Dp) {
    Box(Modifier.size(dim).clip(RoundedCornerShape(dim * 0.28f))
        .background(Brush.linearGradient(listOf(C.Blue, C.Cyan))), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize(0.6f)) {
            val s = size.minDimension / 100f
            val p = Path().apply {
                moveTo(58 * s, 2 * s); lineTo(22 * s, 56 * s); lineTo(46 * s, 56 * s)
                lineTo(40 * s, 98 * s); lineTo(78 * s, 40 * s); lineTo(54 * s, 40 * s); close()
            }
            drawPath(p, C.Gold)
        }
    }
}

@Composable
fun Credit() {
    Text("تطوير عبدالوهاب بن العارف", color = C.Muted, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(12.dp), textAlign = TextAlign.Center)
}

@Composable
fun Welcome(onStart: () -> Unit) {
    val sc by animateFloatAsState(1f, tween(900, easing = FastOutSlowInEasing), label = "s")
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val s2 by animateFloatAsState(if (shown) 1f else 0.6f, spring(Spring.DampingRatioMediumBouncy), label = "b")
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.scale(s2 * sc)) { BarqLogo(128.dp) }
        Spacer(Modifier.height(24.dp))
        Text("برق", color = C.Text, fontSize = 52.sp, fontWeight = FontWeight.ExtraBold)
        Text("ينقل ملفاتك بسرعة البرق ⚡", color = C.Gold, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(14.dp))
        Text("بدون إنترنت.. وبدون باقة.. وبدون كيبل", color = C.Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = C.Gold, contentColor = C.Navy)) {
            Text("يلا نبدأ", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        Credit()
    }
}

@Composable
fun ActionCard(title: String, sub: String, icon: String, brush: Brush, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(brush)
        .clickable(onClick = onClick).padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 38.sp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = Color.White.copy(alpha = .85f), fontSize = 14.sp)
        }
    }
}

@Composable
fun Home(
    displayName: String,
    onSaveName: (String) -> Unit,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onShare: () -> Unit
) {
    var editingName by remember { mutableStateOf(false) }
    var editedName by remember(displayName) { mutableStateOf(displayName) }
    if (editingName) {
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("اسمك في برق") },
            text = {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    singleLine = true,
                    label = { Text("الاسم") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = editedName.trim()
                    if (name.isNotEmpty()) onSaveName(name)
                    editingName = false
                }) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { editingName = false }) { Text("إلغاء") }
            }
        )
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            BarqLogo(44.dp); Spacer(Modifier.width(10.dp))
            Text("برق", color = C.Text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("يا هلا فيك 👋", color = C.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(displayName.ifBlank { "اختر اسماً يظهر عند مشاركة الملفات" }, color = C.Muted, fontSize = 14.sp)
            }
            TextButton(onClick = { editedName = displayName; editingName = true }) {
                Text(if (displayName.isBlank()) "إضافة اسم" else "تعديل الاسم", color = C.Gold)
            }
        }
        Text("وش تبي تسوي اليوم؟", color = C.Muted, fontSize = 16.sp)
        Spacer(Modifier.height(24.dp))
        ActionCard("أرسل", "اختر ملفاتك وأرسلها لأقرب جوال", "📤",
            Brush.linearGradient(listOf(C.Blue, C.Cyan)), onSend)
        Spacer(Modifier.height(16.dp))
        ActionCard("استقبل", "خلّ جوالك جاهز يستقبل الملفات", "📥",
            Brush.linearGradient(listOf(Color(0xFF7A3BFF), C.Blue)), onReceive)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).border(1.5.dp, C.Gold, RoundedCornerShape(24.dp))
            .clickable(onClick = onShare).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🎁", fontSize = 30.sp); Spacer(Modifier.width(14.dp))
            Column {
                Text("شارك برق مع ربعك", color = C.Gold, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("أرسل التطبيق لصديقك بضغطة وحدة", color = C.Muted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        Text("الملفات المستلمة تنحفظ في: التنزيلات ← Barq", color = C.Muted, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Credit()
    }
}

@Composable
fun SendPicker(
    apps: List<InstalledApp>,
    selectedApps: Set<String>,
    media: List<DeviceMedia>,
    selectedMedia: Set<Uri>,
    loadingMedia: Boolean,
    selectedFileCount: Int,
    category: SendCategory,
    onCategorySelected: (SendCategory) -> Unit,
    onToggleApp: (InstalledApp) -> Unit,
    onToggleMedia: (DeviceMedia) -> Unit,
    onOpenFiles: () -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit
) {
    val selectedCount = selectedApps.size + selectedFileCount + selectedMedia.size
    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("رجوع", color = C.Cyan) }
            Text(
                "اختر ما تريد إرساله",
                color = C.Text,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.width(58.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SendCategory.entries.forEach { item ->
                val selected = item == category
                Box(
                    Modifier.clip(RoundedCornerShape(18.dp))
                        .background(if (selected) C.Blue else C.Card)
                        .clickable { onCategorySelected(item) }
                ) {
                    Text(
                        "${item.icon} ${item.title}",
                        color = if (selected) Color.White else C.Muted,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        when (category) {
            SendCategory.APPS -> {
                Text(
                    "التطبيقات المثبتة على الهاتف",
                    color = C.Muted,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                if (apps.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("لم يتم العثور على تطبيقات قابلة للتشغيل.", color = C.Muted)
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(apps, key = { it.packageName }) { app ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                    .background(C.Card).clickable { onToggleApp(app) }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("📱", fontSize = 24.sp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, color = C.Text, fontSize = 15.sp, maxLines = 1)
                                    Text(app.packageName, color = C.Muted, fontSize = 11.sp, maxLines = 1)
                                }
                                Checkbox(
                                    checked = app.packageName in selectedApps,
                                    onCheckedChange = { onToggleApp(app) }
                                )
                            }
                        }
                    }
                }
            }
            SendCategory.VIDEOS, SendCategory.AUDIO -> {
                if (loadingMedia) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = C.Cyan)
                    }
                } else if (media.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("لا توجد ${category.title} متاحة.", color = C.Muted)
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(media, key = { it.uri.toString() }) { item ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                    .background(C.Card).clickable { onToggleMedia(item) }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(category.icon, fontSize = 24.sp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, color = C.Text, fontSize = 14.sp, maxLines = 1)
                                    Text(formatFileSize(item.size), color = C.Muted, fontSize = 11.sp)
                                }
                                Checkbox(
                                    checked = item.uri in selectedMedia,
                                    onCheckedChange = { onToggleMedia(item) }
                                )
                            }
                        }
                    }
                }
            }
            else -> {
                val description = when (category) {
                    SendCategory.FILES -> "اختر ملفاتك ومجلداتك من مستعرض أندرويد"
                    SendCategory.PACKAGES -> "اختر حزم APK أو تطبيقات محفوظة"
                    else -> ""
                }
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(category.icon, fontSize = 56.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(description, color = C.Text, fontSize = 16.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "يعرض أندرويد الملفات التي تختار إتاحتها لبرق.",
                            color = C.Muted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(18.dp))
                        Button(
                            onClick = onOpenFiles,
                            colors = ButtonDefaults.buttonColors(containerColor = C.Blue)
                        ) {
                            Text("استعراض ${category.title}", color = Color.White)
                        }
                    }
                }
            }
        }
        if (selectedFileCount > 0) {
            Text(
                "تم اختيار $selectedFileCount ملفًا من الجهاز",
                color = C.Cyan,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
            )
        }
        if (selectedMedia.isNotEmpty()) {
            Text(
                "تم اختيار ${selectedMedia.size} من الوسائط",
                color = C.Cyan,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
            )
        }
        Button(
            onClick = onSend,
            enabled = selectedCount > 0,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = C.Gold, contentColor = C.Navy),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                if (selectedCount == 0) "اختر عناصر للإرسال" else "إرسال المحدد ($selectedCount)",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Credit()
    }
}

@Composable
fun Transfer(
    e: BarqEngine,
    onBack: () -> Unit,
    onPickMore: () -> Unit,
    onOpenFile: (android.net.Uri, String) -> Unit,
    onInstallApk: (android.net.Uri, String) -> Unit
) {
    val send = e.mode == Mode.SEND
    val context = LocalContext.current
    var pendingInstall by remember { mutableStateOf<TransferItem?>(null) }
    Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (send) "إرسال" else "استقبال", color = C.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth())
        if (e.peerName.isNotBlank()) {
            Text("الطرف الآخر: ${e.peerName}", color = C.Gold, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth())
        } else if (!send && e.senderName.isNotBlank()) {
            Text("مرسل الملفات: ${e.senderName}", color = C.Gold, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(10.dp))
        Text(e.status, color = C.Muted, fontSize = 15.sp, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
        when {
            e.done -> {
                Spacer(Modifier.height(40.dp))
                Text("✅", fontSize = 80.sp)
                Text("تم بحمد الله", color = C.Ok, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("${e.filesCount} ملف بسرعة ${e.speed}", color = C.Muted, fontSize = 15.sp)
                if (e.connectionQuality.isNotBlank()) {
                    Text(e.connectionQuality, color = C.Cyan, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }
            e.failed -> {
                Spacer(Modifier.height(40.dp)); Text("⚠️", fontSize = 70.sp)
                Text("ما كمل النقل، جرّب مرة ثانية", color = C.Gold, fontSize = 18.sp)
            }
            e.connected -> {
                Spacer(Modifier.height(30.dp))
                Text(if (e.progressIndeterminate) "جارٍ النقل" else "${(e.progress * 100).toInt()}٪",
                    color = C.Gold, fontSize = if (e.progressIndeterminate) 24.sp else 64.sp,
                    fontWeight = FontWeight.ExtraBold)
                val progressModifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(8.dp))
                if (e.progressIndeterminate) {
                    LinearProgressIndicator(modifier = progressModifier, color = C.Cyan, trackColor = C.Card)
                } else {
                    LinearProgressIndicator(progress = { e.progress }, modifier = progressModifier,
                        color = C.Cyan, trackColor = C.Card)
                }
                Spacer(Modifier.height(14.dp))
                Text(e.currentFile, color = C.Text, fontSize = 15.sp, maxLines = 1)
                Text(e.speed, color = C.Cyan, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (e.connectionQuality.isNotBlank()) {
                    Text(e.connectionQuality, color = C.Gold, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }
            send -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("الأجهزة القريبة", color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { e.discover() }) { Text("🔄 تحديث", color = C.Gold) }
                }
                if (e.peers.isEmpty())
                    Text("ما لقينا أحد قريب.. تأكد أن الطرف الثاني فاتح برق ومختار \"استقبل\"",
                        color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(e.peers) { d ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Card)
                            .clickable { e.connect(d) }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("📱", fontSize = 28.sp); Spacer(Modifier.width(12.dp))
                            Text(d.deviceName.ifBlank { "جوال" }, color = C.Text, fontSize = 17.sp)
                        }
                    }
                }
            }
            else -> {
                val t = rememberInfiniteTransition(label = "p")
                val k by t.animateFloat(0.9f, 1.12f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "k")
                Spacer(Modifier.height(36.dp))
                Box(Modifier.scale(k)) { BarqLogo(110.dp) }
                Spacer(Modifier.height(24.dp))
                Text("اسم جوالك", color = C.Muted, fontSize = 13.sp)
                Text(e.displayName.ifBlank { e.myName }.ifBlank { "…" }, color = C.Gold,
                    fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("تأكد من تشغيل Wi-Fi على الهاتفين", color = C.Muted, fontSize = 14.sp)
                TextButton(onClick = { e.discover() }) { Text("🔄 ابحث عن أجهزة قريبة", color = C.Gold) }
            }
        }
        if (e.transferItems.isNotEmpty()) {
            Text("الملفات المرسلة والمستلمة", color = C.Text, fontSize = 16.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 190.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(e.transferItems) { item ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Card).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FileThumbnail(context, item.uri, item.name)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.name, color = C.Text, fontSize = 14.sp, maxLines = 1)
                            Text("${formatFileSize(item.size)} • بدأ ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.startedAt))}",
                                color = C.Muted, fontSize = 12.sp)
                        }
                        if (!send && e.done && item.uri != null) {
                            TextButton(
                                onClick = {
                                    if (item.name.isInstallableAppFile()) pendingInstall = item
                                    else onOpenFile(item.uri, item.name)
                                }
                            ) {
                                Text(
                                    if (item.name.isInstallableAppFile()) "تثبيت" else "فتح",
                                    color = C.Cyan,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
        pendingInstall?.let { item ->
            AlertDialog(
                onDismissRequest = { pendingInstall = null },
                title = {
                    Text("تثبيت التطبيق؟", color = C.Text, fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        "سيُفتح مثبّت أندرويد الرسمي لإكمال تثبيت ${item.name}. لا تثبّت ملفات APK إلا من مصدر تثق به.",
                        color = C.Muted
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        item.uri?.let { onInstallApk(it, item.name) }
                        pendingInstall = null
                    }) {
                        Text("متابعة", color = C.Cyan, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingInstall = null }) {
                        Text("إلغاء", color = C.Muted)
                    }
                },
                containerColor = C.Card,
                titleContentColor = C.Text,
                textContentColor = C.Muted
            )
        }
        if (e.done || e.failed) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { e.retry() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(if (send) "إعادة الإرسال" else "استقبال ملفات أخرى")
            }
            if (send) {
                TextButton(onClick = onPickMore) { Text("إرسال ملفات أخرى", color = C.Cyan) }
            }
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp)) {
            Text(if (e.done || e.failed) "رجوع" else "إلغاء", color = C.Text)
        }
        Credit()
    }
}

@Composable
private fun FileThumbnail(context: android.content.Context, uri: android.net.Uri?, name: String) {
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = try {
            if (uri != null && context.contentResolver.getType(uri)?.startsWith("image/") == true) {
                context.contentResolver.loadThumbnail(uri, Size(96, 96), null)
            } else null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
    if (bitmap != null) {
        Image(bitmap!!.asImageBitmap(), contentDescription = name,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(9.dp)))
    } else {
        val icon = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "png", "gif", "webp", "heic" -> "🖼️"
            "pdf" -> "📕"
            "mp4", "mov", "mkv" -> "🎞️"
            "mp3", "wav", "m4a" -> "🎵"
            else -> "📄"
        }
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(9.dp)).background(C.Navy2),
            contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 25.sp)
        }
    }
}

private fun formatFileSize(size: Long): String = when {
    size < 0L -> "حجم غير معروف"
    size < 1024L -> "$size بايت"
    size < 1024L * 1024L -> "%.1f كيلوبايت".format(size / 1024.0)
    else -> "%.1f ميجابايت".format(size / (1024.0 * 1024.0))
}
