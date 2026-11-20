package sa.barq.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import sa.barq.app.apps.InstallMessage

@Composable
fun BarqDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shape = SlashShape(20.dp)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(shape).background(BarqColors.Surface1).border(1.dp, BarqColors.Line, shape).padding(24.dp),
            content = content
        )
    }
}

@Composable
private fun ChoiceRow(icon: BarqIcon, title: String, sub: String, accent: Color, onClick: () -> Unit) {
    val shape = SlashShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, BarqColors.Line, shape).barqClickable(onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(50.dp).clip(SlashShape(11.dp)).background(accent),
            contentAlignment = Alignment.Center
        ) { BarqIconView(icon, BarqColors.Ink, 26.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            BText(title, BarqType.Subtitle)
            BText(sub, BarqType.Label, BarqColors.Mute)
        }
    }
}

/** "وش تبي ترسل؟" — ملفات أو تطبيقات. */
@Composable
fun SendKindDialog(onFiles: () -> Unit, onApps: () -> Unit, onDismiss: () -> Unit) {
    BarqDialog(onDismiss) {
        BText("وش تبي ترسل؟", BarqType.Title)
        Spacer(Modifier.height(18.dp))
        ChoiceRow(BarqIcon.FILES, "ملفات", "صور وفيديو ومستندات وأي ملف", BarqColors.Volt, onFiles)
        Spacer(Modifier.height(10.dp))
        ChoiceRow(BarqIcon.APPS, "تطبيقات", "أي تطبيق على جوالك بصيغة APK", BarqColors.Volt, onApps)
    }
}

/** القيد: تثبيت التطبيقات غير مفعّل → شرح بسيط ثم نفتح صفحة التفعيل مباشرة. */
@Composable
fun InstallLockedDialog(onEnable: () -> Unit, onLater: () -> Unit) {
    BarqDialog(onLater) {
        BText("تثبيت التطبيقات مقفل", BarqType.Title)
        Spacer(Modifier.height(6.dp))
        BText(
            "عشان نثبّت لك التطبيقات اللي توصلك، لازم تسمح لبرق من الإعدادات. بنفتح لك الصفحة مباشرة، فعّل خيار «السماح من هذا المصدر» وارجع للتطبيق.",
            BarqType.Body, BarqColors.Mute
        )
        Spacer(Modifier.height(22.dp))
        SlashButton("يعم أنا واثق فيك وهفعل", onEnable, height = 52.dp)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextAction("مو الحين", BarqColors.Mute, onLater)
        }
    }
}

/** تنبيه الثقة: يظهر قبل أول تثبيت فقط. */
@Composable
fun InstallWarnDialog(sender: String, fileName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    BarqDialog(onDismiss) {
        BText("ثبّت بس اللي تثق فيه", BarqType.Title)
        Spacer(Modifier.height(6.dp))
        BText(
            "«${fileName}» وصلك من ${sender.ifBlank { "جهاز آخر" }}. التطبيقات من مصادر مجهولة ممكن تضر بجوالك وبياناتك، تأكد إنك تعرف مصدرها.",
            BarqType.Body, BarqColors.Mute
        )
        Spacer(Modifier.height(22.dp))
        SlashButton("كمّل التثبيت", onConfirm, height = 52.dp)
        Spacer(Modifier.height(8.dp))
        SlashButton("إلغاء", onDismiss, filled = false, height = 48.dp)
    }
}

/** شريط نتيجة التثبيت: ينزل من أعلى الشاشة ويختفي وحده. */
@Composable
fun InstallBanner(message: InstallMessage?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf<InstallMessage?>(null) }
    if (message != null) last = message
    LaunchedEffect(message) {
        if (message != null) { delay(7000); onDismiss() }
    }
    val shown = last
    val a by animateFloatAsState(if (message != null) 1f else 0f, tween(260), label = "banner")
    if (shown == null || a <= 0.01f) return
    val accent = if (shown.ok) BarqColors.Ok else BarqColors.Danger
    val shape = SlashShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = a; translationY = (a - 1f) * 24.dp.toPx() }
            .clip(shape).background(BarqColors.Surface2).border(1.dp, accent, shape)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BText(shown.text, BarqType.Label, BarqColors.Bone, Modifier.weight(1f))
        Box(Modifier.size(40.dp).barqClickable(onDismiss), contentAlignment = Alignment.Center) {
            BarqIconView(BarqIcon.CLOSE, BarqColors.Mute, 16.dp)
        }
    }
}
