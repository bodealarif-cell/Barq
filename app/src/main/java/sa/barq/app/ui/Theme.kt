package sa.barq.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sa.barq.app.R
import kotlin.math.min

/**
 * هوية برق: حبر دافئ + شرارة صفراء (الإرسال) + جليد سماوي (الاستقبال).
 * لا تدرجات لونية ولا بطاقات دائرية متشابهة؛ الشكل المميز هو "القص القطري".
 */
object BarqColors {
    val Ink = Color(0xFF0C0D10)
    val Surface1 = Color(0xFF15171C)
    val Surface2 = Color(0xFF1E2127)
    val Line = Color(0xFF2B2F37)
    val Bone = Color(0xFFF2EFE6)
    val Mute = Color(0xFF8E929C)
    val Volt = Color(0xFFFFC21A)      // الإرسال / الشرارة
    val Arc = Color(0xFF6FE3D0)       // الاستقبال
    val Ok = Color(0xFF6BE39A)
    val Danger = Color(0xFFFF6B5B)
}

private val Display = FontFamily(Font(R.font.lalezar, FontWeight.Normal))
private val Plex = FontFamily(
    Font(R.font.plex_arabic_regular, FontWeight.Normal),
    Font(R.font.plex_arabic_medium, FontWeight.Medium),
    Font(R.font.plex_arabic_bold, FontWeight.Bold)
)

object BarqType {
    val Hero = TextStyle(fontFamily = Display, fontSize = 112.sp, lineHeight = 128.sp)
    val Numeral = TextStyle(fontFamily = Display, fontSize = 120.sp, lineHeight = 128.sp)
    val Headline = TextStyle(fontFamily = Display, fontSize = 44.sp, lineHeight = 54.sp)
    val Title = TextStyle(fontFamily = Display, fontSize = 30.sp, lineHeight = 38.sp)
    val Subtitle = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 26.sp)
    val Body = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 24.sp)
    val Label = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 20.sp)
    val Tag = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 16.sp)
}

@Composable
fun BarqTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = BarqColors.Volt, onPrimary = BarqColors.Ink,
            background = BarqColors.Ink, onBackground = BarqColors.Bone,
            surface = BarqColors.Surface1, onSurface = BarqColors.Bone
        ),
        content = content
    )
}

@Composable
fun BText(
    text: String,
    style: TextStyle,
    color: Color = BarqColors.Bone,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE
) {
    Text(
        text = text, style = style, color = color, modifier = modifier, textAlign = textAlign,
        maxLines = maxLines, overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis
    )
}

/** مستطيل بزاويتين مقصوصتين قطرياً (أعلى اليسار وأسفل اليمين) — توقيع برق البصري. */
class SlashShape(private val cut: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { cut.toPx() }.coerceAtMost(min(size.width, size.height) / 2f)
        val p = Path().apply {
            moveTo(c, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height - c)
            lineTo(size.width - c, size.height); lineTo(0f, size.height); lineTo(0f, c); close()
        }
        return Outline.Generic(p)
    }
}

/** مسار الصاعقة (شعار برق) داخل مربع ارتفاعه [height]. العرض = ارتفاع × 56/96. */
fun boltPath(height: Float, left: Float = 0f, top: Float = 0f): Path {
    val s = height / 96f
    fun pt(x: Float, y: Float) = Offset(left + x * s, top + y * s)
    return Path().apply {
        val pts = listOf(pt(36f, 0f), pt(0f, 54f), pt(24f, 54f), pt(18f, 96f), pt(56f, 38f), pt(32f, 38f))
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        close()
    }
}

/** خلفية الحبر مع خطوط قطرية خافتة جداً تعطي ملمساً بدل السطح المسطح. */
@Composable
fun InkBackdrop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .drawWithCache {
                val step = 34.dp.toPx()
                val path = Path()
                var x = -size.height
                while (x < size.width) {
                    path.moveTo(x, size.height)
                    path.lineTo(x + size.height * 0.62f, 0f)
                    x += step
                }
                onDrawBehind {
                    drawRect(BarqColors.Ink)
                    drawPath(path, Color.White.copy(alpha = 0.028f), style = Stroke(1f))
                }
            }
    ) { content() }
}
