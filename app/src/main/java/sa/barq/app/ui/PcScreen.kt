package sa.barq.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import sa.barq.app.lan.LanEngine
import sa.barq.app.lan.LanPhase
import sa.barq.app.lan.Offer
import sa.barq.app.lan.Peer

@Composable
private fun IncomingDialog(offer: Offer, onAccept: () -> Unit, onReject: () -> Unit) {
    BarqDialog(onDismiss = onReject) {
        BText("${offer.peerName} يبي يرسل لك", BarqType.Title)
        Spacer(Modifier.height(6.dp))
        BText(
            "${offer.files.size} ملف • " + (if (offer.totalBytes >= 0) formatFileSize(offer.totalBytes) else "حجم غير معروف"),
            BarqType.Body, BarqColors.Mute
        )
        Spacer(Modifier.height(12.dp))
        offer.files.take(4).forEach { BText(it.name, BarqType.Label, maxLines = 1) }
        if (offer.files.size > 4) BText("و ${offer.files.size - 4} ملفات أخرى", BarqType.Tag, BarqColors.Mute)
        Spacer(Modifier.height(20.dp))
        SlashButton("قبول", onAccept, accent = BarqColors.Arc, height = 52.dp)
        Spacer(Modifier.height(8.dp))
        SlashButton("رفض", onReject, filled = false, height = 48.dp)
    }
}

@Composable
private fun PcStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        BText(label, BarqType.Tag, BarqColors.Mute)
        BText(value, BarqType.Subtitle, maxLines = 1)
    }
}

@Composable
private fun PcPeerRow(peer: Peer, onClick: () -> Unit) {
    val shape = SlashShape(14.dp)
    val isPc = peer.platform.equals("windows", ignoreCase = true)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(BarqColors.Surface1)
            .border(1.dp, BarqColors.Line, shape).barqClickable(onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp).clip(SlashShape(10.dp)).background(BarqColors.Volt),
            contentAlignment = Alignment.Center
        ) { BarqIconView(if (isPc) BarqIcon.PC else BarqIcon.PHONE, BarqColors.Ink, 24.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            BText(peer.name, BarqType.Subtitle, maxLines = 1)
            BText(
                (if (isPc) "حاسوب ويندوز" else "جوال") + " • اضغط لاختيار الملفات",
                BarqType.Tag, BarqColors.Mute, maxLines = 1
            )
        }
    }
}

/** شاشة نقل الملفات بين الجوال والحاسوب عبر الشبكة المحلية (بدون إنترنت). */
@Composable
fun PcScreen(
    lan: LanEngine,
    onBack: () -> Unit,
    onPick: (Peer) -> Unit,
    onShareDesktop: () -> Unit,
    onOpenReceived: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    // لا تنطفئ الشاشة أثناء النقل أو الانتظار
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    LaunchedEffect(lan.phase) {
        if (lan.phase == LanPhase.DONE || lan.phase == LanPhase.FAILED) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    val incoming = lan.incoming
    if (incoming != null) IncomingDialog(incoming, onAccept = { lan.respond(true) }, onReject = { lan.respond(false) })

    val receivingNow = lan.phase == LanPhase.ACTIVE && !lan.sending
    val accent = if (receivingNow) BarqColors.Arc else BarqColors.Volt

    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BText("الحاسوب", BarqType.Headline, accent)
                BText("جوالك ظاهر باسم ${lan.selfName}", BarqType.Label, BarqColors.Bone, maxLines = 1)
            }
            CloseButton(onBack)
        }
        BText("عبر نفس الشبكة بدون إنترنت — لا يستهلك باقتك", BarqType.Tag, BarqColors.Mute, Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(16.dp))

        when (lan.phase) {
            LanPhase.ACTIVE -> {
                val known = lan.totalBytes > 0
                val progress = if (known) (lan.doneBytes.toFloat() / lan.totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
                val smooth by animateFloatAsState(progress, tween(300), label = "pcProgress")
                BText(
                    (if (lan.sending) "يرسل إلى " else "يستقبل من ") + lan.peerName,
                    BarqType.Label, BarqColors.Mute
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    if (known) {
                        BText("${(smooth * 100).toInt()}", BarqType.Numeral, accent)
                        BText("٪", BarqType.Title, BarqColors.Mute, Modifier.padding(start = 6.dp, bottom = 22.dp))
                    } else {
                        BText("جارٍ النقل", BarqType.Headline, accent)
                    }
                }
                Spacer(Modifier.height(6.dp))
                ChargeBar(smooth, !known, accent)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BText(lan.fileName.ifBlank { "…" }, BarqType.Body, modifier = Modifier.weight(1f), maxLines = 1)
                    BText("${lan.fileIndex + 1}/${lan.fileCount}", BarqType.Tag, BarqColors.Mute)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    PcStat("السرعة", if (lan.speed > 0) formatFileSize(lan.speed) + "/ث" else "—", Modifier.weight(1f))
                    PcStat(
                        "تم نقله",
                        formatFileSize(lan.doneBytes) + if (known) " من " + formatFileSize(lan.totalBytes) else "",
                        Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.weight(1f))
                SlashButton("إلغاء", { lan.cancel() }, filled = false, height = 50.dp)
                Spacer(Modifier.height(16.dp))
            }
            LanPhase.DONE -> {
                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    BoltStrike(height = 96.dp, color = BarqColors.Ok)
                    Spacer(Modifier.height(8.dp))
                    BText("تم بحمد الله", BarqType.Title, BarqColors.Ok)
                    BText(
                        (if (lan.sending) "وصلت ملفاتك إلى " else "استلمنا ملفاتك من ") + lan.peerName,
                        BarqType.Body, BarqColors.Mute
                    )
                    Spacer(Modifier.height(10.dp))
                    BText("${lan.fileCount} ملف • " + formatFileSize(lan.doneBytes), BarqType.Label, BarqColors.Bone)
                }
                if (!lan.sending) {
                    SlashButton("افتح المستلمة", onOpenReceived, accent = BarqColors.Arc)
                    Spacer(Modifier.height(8.dp))
                }
                SlashButton("تم", { lan.dismiss() }, filled = lan.sending, height = 50.dp)
                Spacer(Modifier.height(16.dp))
            }
            LanPhase.FAILED, LanPhase.CANCELLED -> {
                val cancelled = lan.phase == LanPhase.CANCELLED
                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    BoltStrike(height = 96.dp, color = if (cancelled) BarqColors.Mute else BarqColors.Danger, broken = true)
                    Spacer(Modifier.height(8.dp))
                    BText(
                        if (cancelled) "تم الإلغاء" else "ما كمل النقل",
                        BarqType.Title, if (cancelled) BarqColors.Mute else BarqColors.Danger
                    )
                    if (lan.error.isNotBlank()) BText(lan.error, BarqType.Body, BarqColors.Mute)
                }
                SlashButton("رجوع", { lan.dismiss() }, height = 50.dp)
                Spacer(Modifier.height(16.dp))
            }
            LanPhase.IDLE -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Radar(accent, Modifier.size(96.dp)) { BoltMark(30.dp, tile = accent) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        BText("الأجهزة القريبة", BarqType.Subtitle)
                        BText(
                            if (lan.peers.isEmpty()) "نبحث حولك…" else "${lan.peers.size} جهاز متاح",
                            BarqType.Label, BarqColors.Mute
                        )
                    }
                    TextAction("تحديث", accent, { lan.refresh() }, icon = BarqIcon.REFRESH)
                }
                Spacer(Modifier.height(12.dp))
                if (lan.peers.isEmpty()) {
                    BText(
                        "افتح برنامج برق على الحاسوب وتأكد أن الجهازين على نفس الشبكة (Wi-Fi أو نقطة اتصال جوالك). لا يوجد البرنامج عندك؟ شاركه لحاسوبك من الزر بالأسفل.",
                        BarqType.Body, BarqColors.Mute, Modifier.weight(1f)
                    )
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(lan.peers, key = { it.id }) { peer -> PcPeerRow(peer) { onPick(peer) } }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BText("من الحاسوب تقدر ترسل لجوالك والشاشة هذي مفتوحة", BarqType.Tag, BarqColors.Mute, Modifier.weight(1f))
                    TextAction("شارك برنامج الحاسوب", accent, onShareDesktop, icon = BarqIcon.SHARE)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
