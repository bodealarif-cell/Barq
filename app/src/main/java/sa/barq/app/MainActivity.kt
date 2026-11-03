package sa.barq.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.widget.Toast
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
import java.io.IOException
import java.text.DateFormat
import java.util.Date

object C {
    val Navy = Color(0xFF0A1230); val Navy2 = Color(0xFF16215A); val Card = Color(0xFF111B45)
    val Blue = Color(0xFF2F6BFF); val Cyan = Color(0xFF22D3EE); val Gold = Color(0xFFFFC531)
    val Text = Color(0xFFF4F6FF); val Muted = Color(0xFF9AA6D6); val Ok = Color(0xFF3DDC97)
}
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
                                onSend = { withPerms { picker.launch(arrayOf("*/*")) } },
                                onReceive = {
                                    withPerms {
                                        engine.begin(Mode.RECEIVE, emptyList(), displayName)
                                        screen = Screen.TRANSFER
                                    }
                                },
                                onShare = { shareApp() })
                            Screen.TRANSFER -> {
                                BackHandler { engine.stop(); screen = Screen.HOME }
                                Transfer(
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

    override fun onDestroy() { unregisterReceiver(engine.receiver); engine.stop(); super.onDestroy() }
}

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
fun Transfer(e: BarqEngine, onBack: () -> Unit, onPickMore: () -> Unit) {
    val send = e.mode == Mode.SEND
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (send) "إرسال" else "استقبال", color = C.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth())
        if (!send && e.senderName.isNotBlank()) {
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
                    }
                }
            }
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
