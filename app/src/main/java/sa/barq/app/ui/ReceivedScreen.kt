package sa.barq.app.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sa.barq.app.files.FileCategory
import sa.barq.app.files.ReceivedFile
import sa.barq.app.files.ReceivedStore
import sa.barq.app.isApkFile
import sa.barq.app.isAppArchiveFile
import java.text.DateFormat
import java.util.Date

@Composable
private fun ReceivedRow(
    f: ReceivedFile,
    onOpen: (Uri, String) -> Unit,
    onInstall: (Uri, String) -> Unit,
    onDelete: () -> Unit
) {
    val shape = SlashShape(14.dp)
    val installable = f.name.isApkFile() || f.name.isAppArchiveFile()
    Row(
        Modifier.fillMaxWidth().clip(shape).background(BarqColors.Surface1)
            .border(1.dp, BarqColors.Line, shape).padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        FileTile(f.uri, f.name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BText(f.name, BarqType.Body, maxLines = 1)
            BText(
                "${formatFileSize(f.size)} • ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(f.addedAt))}",
                BarqType.Tag, BarqColors.Mute, maxLines = 1
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (installable) {
                    MiniAction("تثبيت", BarqIcon.INSTALL, BarqColors.Volt, filled = true) { onInstall(f.uri, f.name) }
                } else {
                    MiniAction("فتح", BarqIcon.OPEN, BarqColors.Arc, filled = false) { onOpen(f.uri, f.name) }
                }
                TextAction("حذف", BarqColors.Danger, onDelete)
            }
        }
    }
}

/** كل الملفات التي وصلت عبر برق (من مجلد Download/Barq) في مكان واحد، بدون فتح إدارة الملفات. */
@Composable
fun ReceivedScreen(
    onBack: () -> Unit,
    onOpen: (Uri, String) -> Unit,
    onInstall: (Uri, String) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<ReceivedFile>?>(null) }
    var category by remember { mutableStateOf(FileCategory.ALL) }
    var toDelete by remember { mutableStateOf<ReceivedFile?>(null) }

    LaunchedEffect(Unit) { files = withContext(Dispatchers.IO) { ReceivedStore.list(ctx) } }

    val all = files.orEmpty()
    val counts = remember(files) { all.groupingBy { ReceivedStore.categoryOf(it.name) }.eachCount() }
    val shownCategories = remember(files) {
        listOf(FileCategory.ALL) + listOf(FileCategory.APPS, FileCategory.IMAGES, FileCategory.VIDEOS, FileCategory.OTHER)
            .filter { (counts[it] ?: 0) > 0 }
    }
    val active = if (category in shownCategories) category else FileCategory.ALL
    val visible = remember(files, active) {
        if (active == FileCategory.ALL) all else all.filter { ReceivedStore.categoryOf(it.name) == active }
    }

    val target = toDelete
    if (target != null) {
        DeleteFileDialog(
            name = target.name,
            onConfirm = {
                toDelete = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { ReceivedStore.delete(ctx, target.uri) }
                    if (ok) files = files?.filterNot { it.uri == target.uri }
                    else Toast.makeText(ctx, "ما قدرنا نحذف الملف", Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { toDelete = null }
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BText("المستلمة", BarqType.Headline, BarqColors.Arc)
                BText(
                    if (files == null) "نجمع ملفاتك…"
                    else "${all.size} ملف • ${formatFileSize(all.sumOf { it.size })}",
                    BarqType.Body, BarqColors.Mute
                )
            }
            CloseButton(onBack)
        }
        BText("من مجلد Download/Barq في جوالك", BarqType.Tag, BarqColors.Mute, Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(14.dp))

        if (all.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                shownCategories.forEach { c ->
                    val n = if (c == FileCategory.ALL) all.size else (counts[c] ?: 0)
                    FilterChip("${c.label} $n", active == c, { category = c })
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        when {
            files == null -> Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Radar(BarqColors.Arc, Modifier.size(150.dp)) { BoltMark(40.dp, tile = BarqColors.Arc) }
            }
            all.isEmpty() -> Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                BoltMark(64.dp, tile = BarqColors.Surface2, bolt = BarqColors.Mute)
                Spacer(Modifier.height(16.dp))
                BText("ما وصلك شي عبر برق لحد الحين", BarqType.Subtitle, textAlign = TextAlign.Center)
                BText("أي ملف يوصلك يظهر هنا مباشرة", BarqType.Body, BarqColors.Mute, textAlign = TextAlign.Center)
            }
            else -> LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visible, key = { it.uri.toString() }) { f ->
                    ReceivedRow(f, onOpen, onInstall, onDelete = { toDelete = f })
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
