package sa.barq.app

import android.Manifest
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import sa.barq.app.apps.ApkInstaller
import sa.barq.app.apps.AppBundler
import sa.barq.app.apps.InstallBus
import sa.barq.app.apps.InstallMessage
import sa.barq.app.ui.AppsScreen
import sa.barq.app.ui.BarqTheme
import sa.barq.app.ui.InstallBanner
import sa.barq.app.ui.InstallLockedDialog
import sa.barq.app.ui.InstallWarnDialog
import sa.barq.app.ui.SendKindDialog
import sa.barq.app.ui.HomeScreen
import sa.barq.app.ui.InkBackdrop
import sa.barq.app.ui.TransferScreen
import sa.barq.app.ui.WelcomeScreen
import java.io.File

enum class Screen { WELCOME, HOME, APPS, TRANSFER }

class MainActivity : ComponentActivity() {
    private lateinit var engine: BarqEngine
    private var screen by mutableStateOf(Screen.WELCOME)
    private var displayName by mutableStateOf("")
    private var afterPerms: (() -> Unit)? = null

    // إرسال: اختيار نوع الإرسال
    private var showSendKind by mutableStateOf(false)
    // تثبيت: الملف المنتظر + الحوارات + هل ننتظر عودة المستخدم من الإعدادات
    private var pendingInstall by mutableStateOf<TransferItem?>(null)
    private var showInstallLocked by mutableStateOf(false)
    private var showInstallWarn by mutableStateOf(false)
    private var waitingForSettings = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val action = afterPerms
        afterPerms = null
        if (r.values.all { it }) action?.invoke()
        else Toast.makeText(this, "نحتاج الإذن عشان نلقى الأجهزة القريبة", Toast.LENGTH_LONG).show()
    }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            engine.begin(Mode.SEND, uris, displayName)
            screen = Screen.TRANSFER
        }
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

    private val prefs get() = getSharedPreferences("barq_preferences", MODE_PRIVATE)

    private fun leaveTransfer() {
        engine.stop()
        AppBundler.clean(this)
        screen = Screen.HOME
    }

    private fun startAppsTransfer(result: AppBundler.Result) {
        if (result.items.isEmpty()) {
            Toast.makeText(this, "ما قدرنا نجهّز التطبيقات المختارة", Toast.LENGTH_LONG).show()
            return
        }
        if (result.skipped.isNotEmpty()) {
            Toast.makeText(this, "تعذّر تجهيز: " + result.skipped.joinToString("، "), Toast.LENGTH_LONG).show()
        }
        withPerms {
            engine.begin(Mode.SEND, result.uris, displayName, result.names)
            screen = Screen.TRANSFER
        }
    }

    /** فتح ملف مستلم بالتطبيق المناسب. */
    private fun openItem(item: TransferItem) {
        val uri = item.uri ?: return
        val ext = item.name.substringAfterLast('.', "").lowercase()
        val mime = contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "*/*"
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(i) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "ما لقينا تطبيق يفتح هذا الملف", Toast.LENGTH_LONG).show() }
        catch (_: SecurityException) { Toast.makeText(this, "ما قدرنا نفتح الملف", Toast.LENGTH_LONG).show() }
    }

    /** نقطة الدخول لأي تثبيت: القيد أولاً، ثم تنبيه الثقة (مرة واحدة)، ثم التثبيت. */
    private fun requestInstall(item: TransferItem) {
        pendingInstall = item
        if (!ApkInstaller.canInstall(this)) { showInstallLocked = true; return }
        continueInstall()
    }

    private fun continueInstall() {
        val item = pendingInstall ?: return
        if (!prefs.getBoolean("install_warned", false)) { showInstallWarn = true; return }
        runInstall(item)
    }

    private fun runInstall(item: TransferItem) {
        pendingInstall = null
        val uri: Uri = item.uri ?: return
        Toast.makeText(this, "جارٍ تجهيز التثبيت…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                ApkInstaller.install(applicationContext, uri, item.name)
            } catch (e: Exception) {
                InstallBus.message = InstallMessage(false, "تعذّر تجهيز التثبيت: ${e.message ?: "ملف غير صالح"}")
            }
        }.start()
    }

    /** المستخدم رجع من صفحة الإعدادات: نكمل التثبيت تلقائياً إن فعّل الخيار. */
    override fun onResume() {
        super.onResume()
        if (!waitingForSettings) return
        waitingForSettings = false
        if (ApkInstaller.canInstall(this)) continueInstall()
        else {
            pendingInstall = null
            Toast.makeText(this, "ما انفعّل الخيار، التثبيت متوقف", Toast.LENGTH_LONG).show()
        }
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
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        displayName = getSharedPreferences("barq_preferences", MODE_PRIVATE)
            .getString("display_name", "").orEmpty()
        engine = BarqEngine(this)
        AppBundler.clean(this)
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
                                        onSend = { showSendKind = true },
                                        onReceive = {
                                            withPerms {
                                                engine.begin(Mode.RECEIVE, emptyList(), displayName)
                                                screen = Screen.TRANSFER
                                            }
                                        },
                                        onShare = { shareApp() }
                                    )
                                    Screen.APPS -> {
                                        BackHandler { screen = Screen.HOME }
                                        AppsScreen(
                                            onBack = { screen = Screen.HOME },
                                            onPrepared = { startAppsTransfer(it) }
                                        )
                                    }
                                    Screen.TRANSFER -> {
                                        BackHandler { leaveTransfer() }
                                        TransferScreen(
                                            engine,
                                            onBack = { leaveTransfer() },
                                            onPickMore = { picker.launch(arrayOf("*/*")) },
                                            onOpen = { openItem(it) },
                                            onInstall = { requestInstall(it) }
                                        )
                                    }
                                }
                            }
                            if (showSendKind) {
                                SendKindDialog(
                                    onFiles = { showSendKind = false; withPerms { picker.launch(arrayOf("*/*")) } },
                                    onApps = { showSendKind = false; withPerms { screen = Screen.APPS } },
                                    onDismiss = { showSendKind = false }
                                )
                            }
                            if (showInstallLocked) {
                                InstallLockedDialog(
                                    onEnable = {
                                        showInstallLocked = false
                                        waitingForSettings = true
                                        startActivity(ApkInstaller.unknownSourcesIntent(this@MainActivity))
                                    },
                                    onLater = { showInstallLocked = false; pendingInstall = null }
                                )
                            }
                            if (showInstallWarn) {
                                InstallWarnDialog(
                                    sender = engine.senderName,
                                    fileName = ApkInstaller.labelOf(pendingInstall?.name.orEmpty()),
                                    onConfirm = {
                                        showInstallWarn = false
                                        prefs.edit().putBoolean("install_warned", true).apply()
                                        pendingInstall?.let { runInstall(it) }
                                    },
                                    onDismiss = { showInstallWarn = false; pendingInstall = null }
                                )
                            }
                            InstallBanner(
                                message = InstallBus.message,
                                onDismiss = { InstallBus.message = null },
                                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() { unregisterReceiver(engine.receiver); engine.stop(); super.onDestroy() }
}
