package sa.barq.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import sa.barq.app.ui.BarqTheme
import sa.barq.app.ui.HomeScreen
import sa.barq.app.ui.InkBackdrop
import sa.barq.app.ui.TransferScreen
import sa.barq.app.ui.WelcomeScreen
import java.io.File

enum class Screen { WELCOME, HOME, TRANSFER }

class MainActivity : ComponentActivity() {
    private lateinit var engine: BarqEngine
    private var screen by mutableStateOf(Screen.WELCOME)
    private var displayName by mutableStateOf("")
    private var afterPerms: (() -> Unit)? = null

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
                                        onSend = { withPerms { picker.launch(arrayOf("*/*")) } },
                                        onReceive = {
                                            withPerms {
                                                engine.begin(Mode.RECEIVE, emptyList(), displayName)
                                                screen = Screen.TRANSFER
                                            }
                                        },
                                        onShare = { shareApp() }
                                    )
                                    Screen.TRANSFER -> {
                                        BackHandler { engine.stop(); screen = Screen.HOME }
                                        TransferScreen(
                                            engine,
                                            onBack = { engine.stop(); screen = Screen.HOME },
                                            onPickMore = { picker.launch(arrayOf("*/*")) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() { unregisterReceiver(engine.receiver); engine.stop(); super.onDestroy() }
}
