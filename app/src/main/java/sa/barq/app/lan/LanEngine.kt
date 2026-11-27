package sa.barq.app.lan

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

enum class LanPhase { IDLE, ACTIVE, DONE, FAILED, CANCELLED }

/**
 * نقل الملفات بين الجوال والحاسوب (أو جوال آخر) عبر الشبكة المحلية: بدون إنترنت ولا استهلاك باقة.
 * الجهاز يظهر للآخرين ويستقبل ما دامت شاشة الحاسوب مفتوحة ([start] ... [stop]).
 */
class LanEngine(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    // ───── الحالة التي تقرأها الواجهة ─────
    var peers by mutableStateOf<List<Peer>>(emptyList()); private set
    var incoming by mutableStateOf<Offer?>(null); private set
    var phase by mutableStateOf(LanPhase.IDLE); private set
    var sending by mutableStateOf(true); private set
    var peerName by mutableStateOf(""); private set
    var fileName by mutableStateOf(""); private set
    var fileIndex by mutableIntStateOf(0); private set
    var fileCount by mutableIntStateOf(0); private set
    var doneBytes by mutableLongStateOf(0L); private set
    var totalBytes by mutableLongStateOf(-1L); private set
    var speed by mutableLongStateOf(0L); private set
    var error by mutableStateOf(""); private set
    var selfName by mutableStateOf(""); private set
    var running by mutableStateOf(false); private set

    private val book = PeerBook()
    private var discovery: LanDiscovery? = null
    private var receiver: LanReceiver? = null
    private var session: LanSendSession? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val decisions = LinkedBlockingQueue<Boolean>()
    private var ticks = 0
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var selfId = ""

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            peers = book.snapshot()
            if (++ticks % 3 == 0) discovery?.probe()
            if (phase == LanPhase.ACTIVE) speed = currentSpeed()
            main.postDelayed(this, 800)
        }
    }

    // ───── دورة الحياة ─────
    @Synchronized
    fun start(displayName: String) {
        if (running) return
        selfId = app.getSharedPreferences("barq_lan", Context.MODE_PRIVATE).let { p ->
            p.getString("id", null) ?: UUID.randomUUID().toString().replace("-", "").take(16).also { p.edit().putString("id", it).apply() }
        }
        selfName = LanProtocol.cleanLabel(displayName.ifBlank { Build.MODEL ?: "جوال" })
        try {
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("barq-lan")?.apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) { /* نكمل بدونه */ }

        val rx = LanReceiver(receiverHandler())
        val port = try { rx.start() } catch (e: Exception) { error = "تعذّر تشغيل الاستقبال: ${e.message}"; 0 }
        receiver = rx
        val d = LanDiscovery(selfId, selfName, "android") { p -> book.seen(p) }
        d.listeningPort = port
        d.start()
        d.probe()
        discovery = d
        running = true
        ticks = 0
        main.post(tick)
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(tick)
        session?.cancel(); session = null
        decisions.offer(false)
        receiver?.stop(); receiver = null
        discovery?.stop(); discovery = null
        try { multicastLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        multicastLock = null
        book.clear(); peers = emptyList(); incoming = null
        phase = LanPhase.IDLE
    }

    fun refresh() { discovery?.probe() }

    fun dismiss() { if (phase != LanPhase.ACTIVE) phase = LanPhase.IDLE }

    fun cancel() { session?.cancel(); receiver?.cancelActive() }

    /** المستخدم قرر على الطلب الوارد. */
    fun respond(accept: Boolean) { decisions.offer(accept) }

    // ───── التقدم ─────
    private fun begin(isSend: Boolean, name: String, count: Int, total: Long) {
        sending = isSend; peerName = name; fileCount = count; totalBytes = total
        fileIndex = 0; fileName = ""; doneBytes = 0L; speed = 0L; error = ""
        samples.clear(); samples.addLast(System.currentTimeMillis() to 0L)
        phase = LanPhase.ACTIVE
    }

    private fun count(n: Long) {
        val now = System.currentTimeMillis()
        val d = doneBytes + n
        doneBytes = d
        synchronized(samples) {
            samples.addLast(now to d)
            while (samples.size > 2 && now - samples.first().first > 2000) samples.removeFirst()
        }
    }

    private fun currentSpeed(): Long = synchronized(samples) {
        if (samples.size < 2) return 0L
        val a = samples.first(); val b = samples.last()
        val dt = (b.first - a.first) / 1000.0
        if (dt > 0.2) ((b.second - a.second) / dt).toLong() else 0L
    }

    private fun finish(p: LanPhase, message: String = "") {
        speed = 0L; error = message; phase = p
    }

    // ───── الإرسال ─────
    fun send(peer: Peer, uris: List<Uri>) {
        if (phase == LanPhase.ACTIVE || uris.isEmpty()) return
        val files = uris.map { outFile(it) }
        begin(true, peer.name, files.size, if (files.any { it.size < 0 }) -1L else files.sumOf { it.size })
        val s = LanSendSession(peer, files, selfId, selfName, "android", object : TransferEvents {
            override fun onFile(index: Int, count: Int, name: String, size: Long) { fileIndex = index; fileName = name }
            override fun onBytes(n: Long) = count(n)
        })
        session = s
        Thread({
            try {
                s.run()
                finish(LanPhase.DONE)
            } catch (e: TransferCancelled) {
                finish(LanPhase.CANCELLED, e.message ?: "")
            } catch (e: Exception) {
                finish(LanPhase.FAILED, e.message ?: "تعذّر النقل")
            } finally {
                if (session === s) session = null
            }
        }, "barq-lan-send").start()
    }

    private fun outFile(uri: Uri): OutFile {
        val cr = app.contentResolver
        var name = if (uri.scheme == "file") uri.lastPathSegment ?: "file" else "file"
        var size = -1L
        if (uri.scheme != "file") {
            cr.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (size < 0) size = try { cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L } catch (_: Exception) { -1L }
        if (size == android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH) size = -1L
        return OutFile(name, size) { cr.openInputStream(uri) ?: throw java.io.IOException("تعذّر فتح الملف") }
    }

    // ───── الاستقبال (يُحفظ في التنزيلات/Barq) ─────
    private fun receiverHandler() = object : ReceiverHandler {
        override fun decide(offer: Offer): Boolean {
            decisions.clear()
            incoming = offer
            val ok = try { decisions.poll(60, TimeUnit.SECONDS) ?: false } catch (_: InterruptedException) { false }
            incoming = null
            if (ok) begin(false, offer.peerName, offer.files.size, offer.totalBytes)
            return ok
        }

        override fun sinkFor(offer: Offer): ReceiveSink = object : ReceiveSink {
            private var uri: Uri? = null
            override fun open(index: Int, name: String, size: Long): OutputStream {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/Barq")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val u = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw java.io.IOException("تعذّر إنشاء الملف في التنزيلات")
                uri = u
                val out = app.contentResolver.openOutputStream(u) ?: throw java.io.IOException("تعذّر الكتابة في التنزيلات")
                return BufferedOutputStream(out, 256 * 1024)
            }

            override fun close(index: Int, ok: Boolean, bytes: Long) {
                val u = uri ?: return
                try {
                    if (ok) {
                        app.contentResolver.update(u, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                    } else {
                        app.contentResolver.delete(u, null, null)
                    }
                } catch (_: Exception) { }
                uri = null
            }
        }

        override fun events(offer: Offer): TransferEvents = object : TransferEvents {
            override fun onFile(index: Int, count: Int, name: String, size: Long) { fileIndex = index; fileName = name }
            override fun onBytes(n: Long) = count(n)
        }

        override fun onDone(offer: Offer, error: Throwable?) {
            if (error == null) finish(LanPhase.DONE) else finish(LanPhase.FAILED, error.message ?: "انقطع النقل")
        }
    }
}
