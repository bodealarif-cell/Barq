package sa.barq.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import sa.barq.app.BarqEngine
import sa.barq.app.Mode
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

// ───────────────────────── البداية ─────────────────────────

@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        Spacer(Modifier.weight(1f))
        BoltStrike(height = 150.dp, color = BarqColors.Volt)
        Spacer(Modifier.height(14.dp))
        Reveal(delayMs = 500) {
            Column {
                BText("برق", BarqType.Hero)
                BText("ينقل ملفاتك بسرعة البرق", BarqType.Subtitle, BarqColors.Volt)
            }
        }
        Spacer(Modifier.weight(1f))
        Reveal(delayMs = 800) {
            Column {
                Box(Modifier.fillMaxWidth().height(1.dp).background(BarqColors.Line))
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    val facts = listOf("بدون إنترنت", "بدون باقة", "بدون كيبل")
                    facts.forEachIndexed { i, f ->
                        BText(f, BarqType.Label, BarqColors.Mute, Modifier.weight(1f), textAlign = TextAlign.Center)
                        if (i < facts.lastIndex) Box(Modifier.width(1.dp).height(24.dp).background(BarqColors.Line))
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(BarqColors.Line))
            }
        }
        Spacer(Modifier.height(24.dp))
        Reveal(delayMs = 1000) { SlashButton("يلا نبدأ", onStart) }
        Credit()
    }
}

// ───────────────────────── الرئيسية ─────────────────────────

@Composable
private fun NameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val shape = SlashShape(20.dp)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(shape).background(BarqColors.Surface1).border(1.dp, BarqColors.Line, shape).padding(24.dp)
        ) {
            BText("اسمك في برق", BarqType.Title)
            Spacer(Modifier.height(4.dp))
            BText("يظهر للأجهزة القريبة عند مشاركة الملفات", BarqType.Body, BarqColors.Mute)
            Spacer(Modifier.height(22.dp))
            BasicTextField(
                value = value,
                onValueChange = { if (it.length <= 24) value = it },
                singleLine = true,
                textStyle = BarqType.Subtitle.copy(color = BarqColors.Bone, fontSize = 22.sp),
                cursorBrush = SolidColor(BarqColors.Volt),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Column {
                        inner()
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(2.dp).background(BarqColors.Volt))
                    }
                }
            )
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SlashButton("حفظ", { val n = value.trim(); if (n.isNotEmpty()) onSave(n) }, Modifier.weight(1f), height = 50.dp)
                SlashButton("إلغاء", onDismiss, Modifier.weight(1f), filled = false, height = 50.dp)
            }
        }
    }
}

@Composable
private fun ActionSlab(
    index: String, title: String, sub: String, icon: BarqIcon, accent: Color,
    solid: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier
) {
    val shape = SlashShape(24.dp)
    val fg = if (solid) BarqColors.Ink else BarqColors.Bone
    val subColor = if (solid) BarqColors.Ink.copy(alpha = 0.72f) else BarqColors.Mute
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (solid) accent else BarqColors.Surface1)
            .then(if (solid) Modifier else Modifier.border(1.dp, accent.copy(alpha = 0.55f), shape))
            .drawBehind {
                // خامة خلفية: صاعقة عملاقة للإرسال، وحلقات رادار للاستقبال
                if (solid) {
                    drawPath(boltPath(size.height * 1.5f, left = -size.height * 0.1f, top = -size.height * 0.2f),
                        Color.Black.copy(alpha = 0.07f))
                } else {
                    for (k in 1..4) drawCircle(
                        accent.copy(alpha = 0.09f), radius = size.height * 0.28f * k,
                        center = Offset(0f, size.height), style = Stroke(1.dp.toPx())
                    )
                }
            }
            .barqClickable(onClick)
    ) {
        Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BText(index, BarqType.Tag, subColor, Modifier.weight(1f))
                BarqIconView(icon, fg, 28.dp)
            }
            Column {
                BText(title, BarqType.Headline, fg)
                BText(sub, BarqType.Body, subColor)
            }
        }
    }
}

@Composable
fun HomeScreen(
    displayName: String,
    onSaveName: (String) -> Unit,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onShare: () -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    if (editing) {
        NameDialog(displayName, onSave = { onSaveName(it); editing = false }, onDismiss = { editing = false })
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            BoltMark(38.dp)
            Spacer(Modifier.width(10.dp))
            BText("برق", BarqType.Title)
        }
        Spacer(Modifier.height(26.dp))
        Reveal(delayMs = 60) {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BText("أهلاً", BarqType.Label, BarqColors.Mute, Modifier.weight(1f))
                    TextAction(if (displayName.isBlank()) "إضافة اسم" else "تعديل", BarqColors.Volt, { editing = true })
                }
                BText(displayName.ifBlank { "من أنت؟" }, BarqType.Headline, maxLines = 1)
                BText(
                    if (displayName.isBlank()) "أضف اسماً يراه من تتبادل معه الملفات" else "وش تبي تنقل اليوم؟",
                    BarqType.Body, BarqColors.Mute
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Reveal(delayMs = 160, modifier = Modifier.weight(1f)) {
            ActionSlab("٠١", "أرسل", "اختر ملفاتك وأرسلها لأقرب جوال", BarqIcon.SEND,
                BarqColors.Volt, solid = true, onClick = onSend, modifier = Modifier.fillMaxHeight())
        }
        Spacer(Modifier.height(12.dp))
        Reveal(delayMs = 260, modifier = Modifier.weight(1f)) {
            ActionSlab("٠٢", "استقبل", "خلّ جوالك جاهز يستقبل الملفات", BarqIcon.RECEIVE,
                BarqColors.Arc, solid = false, onClick = onReceive, modifier = Modifier.fillMaxHeight())
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BText("الملفات المستلمة تنحفظ في: التنزيلات ← Barq", BarqType.Tag, BarqColors.Mute)
            }
            TextAction("شارك برق مع ربعك", BarqColors.Bone, onShare, icon = BarqIcon.SHARE)
        }
        Credit()
    }
}

// ───────────────────────── النقل ─────────────────────────

private fun formatFileSize(size: Long): String = when {
    size < 0L -> "حجم غير معروف"
    size < 1024L -> "$size بايت"
    size < 1024L * 1024L -> "%.1f كيلوبايت".format(size / 1024.0)
    else -> "%.1f ميجابايت".format(size / (1024.0 * 1024.0))
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        BText(label, BarqType.Tag, BarqColors.Mute)
        BText(value, BarqType.Subtitle, maxLines = 1)
    }
}

@Composable
private fun PeerRow(name: String, accent: Color, index: Int, onClick: () -> Unit) {
    val shape = SlashShape(14.dp)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay((index * 70L).coerceAtMost(350L)); shown = true }
    val appear by animateFloatAsState(if (shown) 1f else 0f, tween(350), label = "peerA")
    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer { alpha = appear; translationY = (1f - appear) * 12.dp.toPx() }
            .clip(shape).background(BarqColors.Surface1).border(1.dp, BarqColors.Line, shape)
            .barqClickable(onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp).clip(SlashShape(10.dp)).background(accent),
            contentAlignment = Alignment.Center
        ) { BText(name.trim().take(1).uppercase().ifBlank { "؟" }, BarqType.Title, BarqColors.Ink) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            BText(name.ifBlank { "جوال" }, BarqType.Subtitle, maxLines = 1)
            BText("اضغط للاتصال وإرسال الملفات", BarqType.Tag, BarqColors.Mute)
        }
        BarqIconView(BarqIcon.PHONE, BarqColors.Mute, 22.dp)
    }
}

@Composable
fun TransferScreen(e: BarqEngine, onBack: () -> Unit, onPickMore: () -> Unit) {
    val send = e.mode == Mode.SEND
    val accent = if (send) BarqColors.Volt else BarqColors.Arc
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(e.done, e.failed) {
        if (e.done || e.failed) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val searching = !e.connected && !e.done && !e.failed

    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        // الرأس
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BText(if (send) "إرسال" else "استقبال", BarqType.Headline, accent)
                if (!send && e.senderName.isNotBlank()) {
                    BText("من ${e.senderName}", BarqType.Label, BarqColors.Bone)
                }
            }
            Box(
                Modifier.size(44.dp).clip(SlashShape(10.dp)).border(1.dp, BarqColors.Line, SlashShape(10.dp))
                    .barqClickable(onBack),
                contentAlignment = Alignment.Center
            ) { BarqIconView(BarqIcon.CLOSE, BarqColors.Bone, 20.dp) }
        }
        BText(e.status, BarqType.Body, BarqColors.Mute, Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(18.dp))

        when {
            e.done -> {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    BoltStrike(height = 96.dp, color = BarqColors.Ok)
                    Spacer(Modifier.height(6.dp))
                    BText("تم بحمد الله", BarqType.Title, BarqColors.Ok)
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth()) {
                        StatCell("الملفات", "${e.filesCount}", Modifier.weight(1f))
                        StatCell("السرعة", e.speed.ifBlank { "—" }, Modifier.weight(1f))
                    }
                    if (e.connectionQuality.isNotBlank()) {
                        BText(e.connectionQuality, BarqType.Tag, BarqColors.Mute, Modifier.fillMaxWidth().padding(top = 10.dp))
                    }
                }
            }
            e.failed -> {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    BoltStrike(height = 96.dp, color = BarqColors.Danger, broken = true)
                    Spacer(Modifier.height(6.dp))
                    BText("ما كمل النقل، جرّب مرة ثانية", BarqType.Subtitle, BarqColors.Danger)
                }
            }
            e.connected -> {
                val smooth by animateFloatAsState(e.progress, tween(300), label = "prog")
                Row(verticalAlignment = Alignment.Bottom) {
                    if (e.progressIndeterminate) {
                        BText("جارٍ النقل", BarqType.Headline, accent)
                    } else {
                        BText("${(smooth * 100).toInt()}", BarqType.Numeral, accent)
                        BText("٪", BarqType.Title, BarqColors.Mute, Modifier.padding(start = 6.dp, bottom = 22.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                ChargeBar(smooth, e.progressIndeterminate, accent)
                Spacer(Modifier.height(16.dp))
                BText(e.currentFile.ifBlank { "…" }, BarqType.Body, maxLines = 1)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    StatCell("السرعة", e.speed.ifBlank { "—" }, Modifier.weight(1f))
                    StatCell("الملفات", "${e.filesCount}", Modifier.weight(1f))
                }
                if (e.connectionQuality.isNotBlank()) {
                    BText(e.connectionQuality, BarqType.Tag, BarqColors.Mute, Modifier.fillMaxWidth().padding(top = 10.dp))
                }
            }
            send -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Radar(accent, Modifier.size(96.dp)) { BoltMark(30.dp, tile = accent) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        BText("الأجهزة القريبة", BarqType.Subtitle)
                        BText(
                            if (e.peers.isEmpty()) "نبحث حولك…" else "${e.peers.size} جهاز متاح",
                            BarqType.Label, BarqColors.Mute
                        )
                    }
                    TextAction("تحديث", accent, { e.discover() }, icon = BarqIcon.REFRESH)
                }
            }
            else -> {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Radar(accent, Modifier.size(210.dp)) { BoltMark(58.dp, tile = accent) }
                    Spacer(Modifier.height(10.dp))
                    BText("اسم جوالك", BarqType.Tag, BarqColors.Mute)
                    BText(e.displayName.ifBlank { e.myName }.ifBlank { "…" }, BarqType.Title, accent)
                    Spacer(Modifier.height(6.dp))
                    BText("تأكد من تشغيل Wi-Fi على الهاتفين", BarqType.Label, BarqColors.Mute)
                    TextAction("ابحث عن أجهزة قريبة", accent, { e.discover() }, icon = BarqIcon.REFRESH)
                }
            }
        }

        // المنطقة المرنة: الأجهزة القريبة أثناء البحث، وإلا سجل الملفات
        Spacer(Modifier.height(12.dp))
        if (searching && send) {
            if (e.peers.isEmpty()) {
                BText(
                    "ما لقينا أحد قريب.. تأكد أن الطرف الثاني فاتح برق ومختار \"استقبل\"",
                    BarqType.Body, BarqColors.Mute, Modifier.weight(1f)
                )
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(e.peers.size) { i ->
                        val d = e.peers[i]
                        PeerRow(d.deviceName.orEmpty(), accent, i) { e.connect(d) }
                    }
                }
            }
        } else if (e.transferItems.isNotEmpty()) {
            BText("الملفات", BarqType.Label, BarqColors.Mute, Modifier.padding(bottom = 8.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(e.transferItems) { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FileTile(item.uri, item.name)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            BText(item.name, BarqType.Body, maxLines = 1)
                            BText(
                                "${formatFileSize(item.size)} • ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.startedAt))}",
                                BarqType.Tag, BarqColors.Mute
                            )
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        // الإجراءات
        Spacer(Modifier.height(10.dp))
        if (e.done || e.failed) {
            SlashButton(if (send) "إعادة الإرسال" else "استقبال ملفات أخرى", { e.retry() }, accent = accent)
            if (send) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextAction("إرسال ملفات أخرى", BarqColors.Bone, onPickMore)
                }
            }
            SlashButton("رجوع", onBack, filled = false, height = 50.dp)
        } else {
            SlashButton("إلغاء", onBack, filled = false, height = 50.dp)
        }
        Spacer(Modifier.height(16.dp))
    }
}
