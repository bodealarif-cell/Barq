package sa.barq.app.ui

import android.graphics.Bitmap
import android.util.Size as AndroidSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** نقرة بإحساس لمسي وانضغاط خفيف (بدون موجة Material الرمادية). */
@Composable
fun Modifier.barqClickable(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.97f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press"
    )
    val haptic = LocalHapticFeedback.current
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = null) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
}

/** ظهور متدرّج (شفافية + صعود بسيط) بتأخير، لإيقاع دخول مدروس. */
@Composable
fun Reveal(delayMs: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(delayMs.toLong()); shown = true }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(440), label = "revealA")
    val rise by animateDpAsState(if (shown) 0.dp else 16.dp, tween(440, easing = FastOutSlowInEasing), label = "revealY")
    Box(modifier.graphicsLayer { this.alpha = alpha; translationY = rise.toPx() }) { content() }
}

// ───────────────────────── الأيقونات (مرسومة يدوياً على شبكة 24) ─────────────────────────

enum class BarqIcon { SEND, RECEIVE, CLOSE, SHARE, REFRESH, PHONE }

@Composable
fun BarqIconView(icon: BarqIcon, tint: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val st = Stroke(width = 2.1f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), strokeWidth = st.width, cap = StrokeCap.Round)
        when (icon) {
            BarqIcon.SEND -> {
                line(5f, 3f, 19f, 3f); line(12f, 20f, 12f, 8f)
                line(6f, 14f, 12f, 8f); line(18f, 14f, 12f, 8f)
            }
            BarqIcon.RECEIVE -> {
                line(5f, 21f, 19f, 21f); line(12f, 4f, 12f, 16f)
                line(6f, 10f, 12f, 16f); line(18f, 10f, 12f, 16f)
            }
            BarqIcon.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            BarqIcon.SHARE -> {
                line(8.2f, 11f, 15.8f, 6.6f); line(8.2f, 13f, 15.8f, 17.4f)
                drawCircle(tint, 2.6f * u, Offset(18f * u, 5.5f * u), style = st)
                drawCircle(tint, 2.6f * u, Offset(6f * u, 12f * u), style = st)
                drawCircle(tint, 2.6f * u, Offset(18f * u, 18.5f * u), style = st)
            }
            BarqIcon.REFRESH -> {
                drawArc(
                    tint, startAngle = -60f, sweepAngle = 270f, useCenter = false,
                    topLeft = Offset(5f * u, 5f * u), size = Size(14f * u, 14f * u), style = st
                )
                val a = (-60f + 270f) * (PI / 180.0)
                val tipX = 12f + 7f * cos(a).toFloat(); val tipY = 12f + 7f * sin(a).toFloat()
                val dx = -sin(a).toFloat(); val dy = cos(a).toFloat()
                fun arm(deg: Double): Offset {
                    val r = deg * (PI / 180.0)
                    val rx = (dx * cos(r) - dy * sin(r)).toFloat(); val ry = (dx * sin(r) + dy * cos(r)).toFloat()
                    return Offset((tipX - rx * 4.2f) * u, (tipY - ry * 4.2f) * u)
                }
                drawLine(tint, Offset(tipX * u, tipY * u), arm(32.0), strokeWidth = st.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(tipX * u, tipY * u), arm(-32.0), strokeWidth = st.width, cap = StrokeCap.Round)
            }
            BarqIcon.PHONE -> {
                drawRoundRect(
                    tint, topLeft = Offset(6.5f * u, 2.5f * u), size = Size(11f * u, 19f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.8f * u), style = st
                )
                line(10.5f, 18f, 13.5f, 18f)
            }
        }
    }
}

// ───────────────────────── الشعار والصاعقة ─────────────────────────

/** علامة برق الصغيرة: لوح مقصوص قطرياً بصاعقة حبرية. */
@Composable
fun BoltMark(dim: Dp, tile: Color = BarqColors.Volt, bolt: Color = BarqColors.Ink) {
    Box(
        Modifier.size(dim).clip(SlashShape(dim * 0.26f)).background(tile),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(dim * 0.56f)) {
            val h = size.height
            drawPath(boltPath(h, left = (size.width - h * 56f / 96f) / 2f), bolt)
        }
    }
}

private fun DrawScope.drawBoltTrimmed(path: Path, progress: Float, color: Color, strokeWidth: Float) {
    val pm = PathMeasure().also { it.setPath(path, true) }
    val seg = Path()
    pm.getSegment(0f, pm.length * progress, seg, true)
    drawPath(seg, color, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * ضربة الصاعقة: ترسم حدودها أولاً ثم تمتلئ بوميض.
 * [broken] تنكسر إلى نصفين منزاحين (حالة الفشل).
 */
@Composable
fun BoltStrike(height: Dp, color: Color, broken: Boolean = false, modifier: Modifier = Modifier) {
    val trace = remember { Animatable(0f) }
    val fill = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        trace.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
        fill.animateTo(1f, tween(260, easing = LinearEasing))
    }
    Canvas(modifier.size(width = height * 56f / 96f + 20.dp, height = height + 12.dp)) {
        val h = height.toPx()
        val path = boltPath(h, left = 4.dp.toPx(), top = 2.dp.toPx())
        val sw = 3.dp.toPx()
        if (!broken) {
            drawBoltTrimmed(path, trace.value, color, sw)
            drawPath(path, color.copy(alpha = fill.value))
        } else {
            val mid = 2.dp.toPx() + h * 0.46f
            clipRect(0f, 0f, size.width, mid) {
                drawBoltTrimmed(path, trace.value, color, sw)
                drawPath(path, color.copy(alpha = fill.value * 0.9f))
            }
            translate(left = 9.dp.toPx(), top = 9.dp.toPx()) {
                clipRect(0f, mid, size.width, size.height) {
                    drawBoltTrimmed(path, trace.value, color, sw)
                    drawPath(path, color.copy(alpha = fill.value * 0.45f))
                }
            }
        }
    }
}

// ───────────────────────── الأزرار ─────────────────────────

@Composable
fun SlashButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = BarqColors.Volt,
    filled: Boolean = true,
    height: Dp = 58.dp
) {
    val shape = SlashShape(14.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .then(if (filled) Modifier.background(accent) else Modifier.border(1.dp, BarqColors.Line, shape))
            .barqClickable(onClick),
        contentAlignment = Alignment.Center
    ) {
        BText(text, BarqType.Subtitle, color = if (filled) BarqColors.Ink else BarqColors.Bone)
    }
}

@Composable
fun TextAction(text: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier, icon: BarqIcon? = null) {
    androidx.compose.foundation.layout.Row(
        modifier.barqClickable(onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            BarqIconView(icon, color, 18.dp)
            Box(Modifier.width(8.dp))
        }
        BText(text, BarqType.Label, color = color)
    }
}

@Composable
fun Credit(modifier: Modifier = Modifier) {
    BText(
        "تطوير عبدالوهاب بن العارف", BarqType.Tag, BarqColors.Mute,
        modifier.fillMaxWidth().padding(vertical = 14.dp), textAlign = TextAlign.Center
    )
}

// ───────────────────────── مؤشر الشحن والرادار ─────────────────────────

/** شريط شحن من شرائح مائلة؛ في الوضع غير المحدد تعبر وميضة بين الشرائح. */
@Composable
fun ChargeBar(progress: Float, indeterminate: Boolean, color: Color, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val sweep by rememberInfiniteTransition(label = "sweep")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "sweepV")
    Canvas(modifier.fillMaxWidth().height(34.dp)) {
        val n = 22
        val h = size.height
        val skew = h * 0.34f
        val gap = 5.dp.toPx()
        val segW = (size.width - skew - gap * (n - 1)) / n
        val filledCount = progress.coerceIn(0f, 1f) * n
        for (j in 0 until n) {
            val i = if (rtl) n - 1 - j else j          // ترتيب منطقي: يبدأ الملء من جهة القراءة
            val x = j * (segW + gap)
            val alpha = if (indeterminate) {
                val d = kotlin.math.abs(i / n.toFloat() - sweep)
                (1f - d * 5f).coerceIn(0f, 1f)
            } else (filledCount - i).coerceIn(0f, 1f)
            val p = Path().apply {
                moveTo(x + skew, 0f); lineTo(x + skew + segW, 0f); lineTo(x + segW, h); lineTo(x, h); close()
            }
            drawPath(p, BarqColors.Surface2)
            if (alpha > 0f) drawPath(p, color.copy(alpha = alpha))
        }
    }
}

/** رادار بحث: ثلاث حلقات تتمدد وتخفت حول مركز ثابت. */
@Composable
fun Radar(color: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val t by rememberInfiniteTransition(label = "radar")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "radarV")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val maxR = size.minDimension / 2f
            for (k in 0 until 3) {
                val ph = (t + k / 3f) % 1f
                drawCircle(
                    color.copy(alpha = (1f - ph) * 0.55f), radius = maxR * (0.3f + 0.7f * ph),
                    style = Stroke(1.5.dp.toPx())
                )
            }
        }
        content()
    }
}

// ───────────────────────── الملفات ─────────────────────────

private data class FileKind(val label: String, val color: Color)

private fun kindOf(name: String): FileKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "heic" -> FileKind(ext.uppercase(), BarqColors.Volt)
        "mp4", "mov", "mkv", "avi" -> FileKind(ext.uppercase(), BarqColors.Arc)
        "mp3", "wav", "m4a", "aac" -> FileKind(ext.uppercase(), BarqColors.Ok)
        "pdf" -> FileKind("PDF", BarqColors.Danger)
        "" -> FileKind("FILE", BarqColors.Mute)
        else -> FileKind(ext.take(4).uppercase(), BarqColors.Mute)
    }
}

@Composable
fun FileTile(uri: android.net.Uri?, name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = try {
            if (uri != null && context.contentResolver.getType(uri)?.startsWith("image/") == true) {
                context.contentResolver.loadThumbnail(uri, AndroidSize(112, 112), null)
            } else null
        } catch (_: IOException) { null } catch (_: SecurityException) { null }
    }
    val shape = SlashShape(9.dp)
    if (bitmap != null) {
        Image(
            bitmap!!.asImageBitmap(), contentDescription = name,
            modifier = modifier.size(50.dp).clip(shape)
        )
    } else {
        val kind = kindOf(name)
        Box(
            modifier.size(50.dp).clip(shape).background(kind.color.copy(alpha = 0.14f))
                .border(1.dp, kind.color.copy(alpha = 0.5f), shape),
            contentAlignment = Alignment.Center
        ) { BText(kind.label, BarqType.Tag, kind.color) }
    }
}
