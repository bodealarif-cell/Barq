package sa.barq.app.ui

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sa.barq.app.apps.AppBundler
import sa.barq.app.apps.AppCatalog
import sa.barq.app.apps.AppEntry
import java.text.DateFormat
import java.util.Date

private enum class AppFilter { USER, SYSTEM, ALL }

private fun appsWord(n: Int) = when {
    n == 1 -> "تطبيقاً واحداً"
    n == 2 -> "تطبيقين"
    n in 3..10 -> "$n تطبيقات"
    else -> "$n تطبيقاً"
}

@Composable
private fun SelectMark(selected: Boolean) {
    val shape = SlashShape(6.dp)
    Box(
        Modifier.size(26.dp).clip(shape)
            .then(if (selected) Modifier.background(BarqColors.Volt) else Modifier.border(1.5.dp, BarqColors.Line, shape)),
        contentAlignment = Alignment.Center
    ) { if (selected) BarqIconView(BarqIcon.CHECK, BarqColors.Ink, 16.dp) }
}

@Composable
private fun InfoTag(text: String, accent: androidx.compose.ui.graphics.Color = BarqColors.Mute) {
    val shape = SlashShape(5.dp)
    Box(Modifier.clip(shape).border(1.dp, accent.copy(alpha = 0.55f), shape).padding(horizontal = 7.dp, vertical = 1.dp)) {
        BText(text, BarqType.Tag, accent, maxLines = 1)
    }
}

@Composable
private fun AppRow(app: AppEntry, selected: Boolean, onClick: () -> Unit) {
    val shape = SlashShape(14.dp)
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(if (selected) BarqColors.Volt.copy(alpha = 0.10f) else BarqColors.Surface1)
            .border(1.dp, if (selected) BarqColors.Volt else BarqColors.Line, shape)
            .barqClickable(onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app.pkg, 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BText(app.label, BarqType.Subtitle, maxLines = 1)
            BText(
                (if (app.versionName.isNotBlank()) "v${app.versionName} • " else "") + app.pkg,
                BarqType.Tag, BarqColors.Mute, maxLines = 1
            )
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                InfoTag(formatFileSize(app.sizeBytes), BarqColors.Bone)
                if (app.isSplit) InfoTag("مقسّم ${app.apkPaths.size}", BarqColors.Arc)
                if (app.isSystem) InfoTag("نظام")
                BText(
                    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(app.updatedAt)),
                    BarqType.Tag, BarqColors.Mute, maxLines = 1
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        SelectMark(selected)
    }
}

/** صفحة "تطبيقاتي": كل تطبيقات الجوال بالأيقونة والاسم والإصدار والحجم، مع بحث واختيار متعدد. */
@Composable
fun AppsScreen(onBack: () -> Unit, onPrepared: (AppBundler.Result) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AppFilter.USER) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var preparing by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { AppCatalog.load(ctx) } }

    val all = apps.orEmpty()
    val userCount = remember(apps) { all.count { !it.isSystem } }
    val systemCount = remember(apps) { all.count { it.isSystem } }
    val visible = remember(apps, query, filter) {
        val q = query.trim()
        all.filter { a ->
            val inFilter = when (filter) {
                AppFilter.USER -> !a.isSystem
                AppFilter.SYSTEM -> a.isSystem
                AppFilter.ALL -> true
            }
            inFilter && (q.isEmpty() || a.label.contains(q, ignoreCase = true) || a.pkg.contains(q, ignoreCase = true))
        }
    }
    val chosen = remember(apps, selected) { all.filter { it.pkg in selected } }
    val chosenBytes = chosen.sumOf { it.sizeBytes }

    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BText("تطبيقاتي", BarqType.Headline, BarqColors.Volt)
                BText(
                    if (chosen.isEmpty()) "اختر اللي تبي ترسله" else "${appsWord(chosen.size)} • ${formatFileSize(chosenBytes)}",
                    BarqType.Body, BarqColors.Mute
                )
            }
            CloseButton(onBack)
        }
        Spacer(Modifier.height(16.dp))

        // البحث
        Row(verticalAlignment = Alignment.CenterVertically) {
            BarqIconView(BarqIcon.SEARCH, BarqColors.Mute, 20.dp)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = BarqType.Body.copy(color = BarqColors.Bone),
                cursorBrush = SolidColor(BarqColors.Volt),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) BText("ابحث بالاسم أو اسم الحزمة", BarqType.Body, BarqColors.Mute)
                        inner()
                    }
                }
            )
            if (chosen.isNotEmpty()) TextAction("مسح التحديد", BarqColors.Volt, { selected = emptySet() })
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(BarqColors.Line))
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("المثبّتة $userCount", filter == AppFilter.USER, { filter = AppFilter.USER })
            FilterChip("النظام $systemCount", filter == AppFilter.SYSTEM, { filter = AppFilter.SYSTEM })
            FilterChip("الكل", filter == AppFilter.ALL, { filter = AppFilter.ALL })
        }
        Spacer(Modifier.height(12.dp))

        when {
            apps == null -> Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Radar(BarqColors.Volt, Modifier.size(150.dp)) { BoltMark(40.dp) }
                Spacer(Modifier.height(10.dp))
                BText("نجمع تطبيقات جوالك…", BarqType.Label, BarqColors.Mute)
            }
            visible.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                BText("ما لقينا تطبيق بهذا الاسم", BarqType.Body, BarqColors.Mute)
            }
            else -> LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visible, key = { it.pkg }) { app ->
                    val isSel = app.pkg in selected
                    AppRow(app, isSel) { selected = if (isSel) selected - app.pkg else selected + app.pkg }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        val busy = preparing
        SlashButton(
            text = when {
                busy != null -> busy
                chosen.isEmpty() -> "اختر تطبيقاً للإرسال"
                else -> "أرسل ${appsWord(chosen.size)}"
            },
            onClick = {
                if (busy == null && chosen.isNotEmpty()) {
                    preparing = "جارٍ التجهيز…"
                    val toSend = chosen
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            AppBundler.prepare(ctx, toSend) { d, t, l ->
                                preparing = if (l.isBlank()) "جارٍ التجهيز…" else "جارٍ تجهيز $l ($d/$t)"
                            }
                        }
                        preparing = null
                        onPrepared(result)
                    }
                }
            },
            filled = busy == null && chosen.isNotEmpty()
        )
        Spacer(Modifier.height(16.dp))
    }
}
