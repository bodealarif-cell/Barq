package sa.barq.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** حوار بشكل برق (لوح مقصوص قطرياً) يُستخدم في كل الرسائل الجديدة. */
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
        Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
            TextAction("مو الحين", BarqColors.Mute, onLater)
        }
    }
}

/** تأكيد حذف ملف مستلم. */
@Composable
fun DeleteFileDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    BarqDialog(onDismiss) {
        BText("نحذف الملف؟", BarqType.Title)
        Spacer(Modifier.height(6.dp))
        BText(name, BarqType.Body, BarqColors.Mute, maxLines = 2)
        Spacer(Modifier.height(22.dp))
        SlashButton("احذف", onConfirm, accent = BarqColors.Danger, height = 50.dp)
        Spacer(Modifier.height(8.dp))
        SlashButton("إلغاء", onDismiss, filled = false, height = 48.dp)
    }
}
