package sa.barq.app

import android.annotation.SuppressLint
import android.content.*
import android.net.Uri
import android.net.wifi.p2p.*
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import java.io.*
import java.net.*

enum class Mode { SEND, RECEIVE }

data class TransferItem(val uri: Uri?, val name: String, val size: Long, val startedAt: Long)

@SuppressLint("MissingPermission")
class BarqEngine(private val ctx: Context) {
    private val mgr = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = mgr.initialize(ctx, Looper.getMainLooper(), null)

    var mode by mutableStateOf(Mode.SEND)
    var peers by mutableStateOf(listOf<WifiP2pDevice>())
    var myName by mutableStateOf("")
    var status by mutableStateOf("")
    var connected by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var progressIndeterminate by mutableStateOf(false)
    var speed by mutableStateOf("")
    var currentFile by mutableStateOf("")
    var done by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var filesCount by mutableIntStateOf(0)
    var displayName by mutableStateOf("")
    var senderName by mutableStateOf("")
    var transferStartedAt by mutableLongStateOf(0L)
    var transferItems by mutableStateOf(listOf<TransferItem>())
    var connectionQuality by mutableStateOf("")

    private var files: List<Uri> = emptyList()
    private var localName = ""
    private var started = false
    private var p2pDisabledObserved = false
    @Volatile private var socket: Socket? = null
    @Volatile private var server: ServerSocket? = null
    private var worker: Thread? = null
    @Volatile private var generation = 0
    @Volatile private var active = false

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION ->
                    mgr.requestPeers(channel) { peers = it.deviceList.toList() }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val d = i.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    myName = d?.deviceName ?: ""
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = i.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!enabled) {
                        p2pDisabledObserved = true
                        peers = emptyList()
                        if (active) status = "شغّل Wi-Fi على الهاتفين ثم اضغط تحديث"
                    } else {
                        if (active && p2pDisabledObserved) discover()
                        p2pDisabledObserved = false
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION ->
                    mgr.requestConnectionInfo(channel) { info -> if (info.groupFormed) onLinked(info) }
            }
        }
    }
    val filter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
    }

    fun begin(m: Mode, uris: List<Uri>, name: String = localName) {
        reset(); active = true; mode = m; files = uris; filesCount = uris.size
        localName = name.trim().ifBlank { "مستخدم برق" }
        displayName = localName
        status = if (m == Mode.SEND) "جاري البحث عن الأجهزة القريبة…" else "جاهز للاستقبال، خلّ المرسل يختارك"
        discover()
    }

    fun updateDisplayName(name: String) {
        localName = name.trim().ifBlank { "مستخدم برق" }
        displayName = localName
    }

    fun retry() {
        begin(mode, if (mode == Mode.SEND) files else emptyList(), localName)
    }

    fun discover() {
        try {
            mgr.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { status = "جاري البحث عن الأجهزة القريبة…" }
                override fun onFailure(r: Int) {
                    status = when (r) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "هذا الهاتف لا يدعم Wi-Fi Direct"
                        WifiP2pManager.BUSY -> "خدمة Wi-Fi مشغولة، انتظر قليلًا ثم اضغط تحديث"
                        WifiP2pManager.ERROR -> "تعذر بدء البحث؛ تأكد من تشغيل Wi-Fi ثم أعد المحاولة"
                        else -> "تعذر بدء البحث (رمز $r)، تحقق من Wi-Fi والأذونات"
                    }
                }
            })
        } catch (_: SecurityException) {
            status = "إذن الأجهزة القريبة غير متاح؛ اسمح به من إعدادات التطبيق"
        }
    }

    fun connect(d: WifiP2pDevice) {
        status = "جاري الاتصال بـ ${d.deviceName}…"
        val cfg = WifiP2pConfig().apply { deviceAddress = d.deviceAddress }
        mgr.connect(channel, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(r: Int) { status = "ما قدرنا نتصل، جرّب مرة ثانية" }
        })
    }

    private fun onLinked(info: WifiP2pInfo) {
        if (!active || started) return
        started = true; connected = true; status = "تم الاتصال، أبشر…"
        val transferGeneration = generation
        val transferMode = mode
        val transferFiles = files
        worker = Thread {
            try {
                val s = if (info.isGroupOwner) {
                    server = ServerSocket(8988).also { it.soTimeout = 60000 }
                    server!!.accept().also { socket = it }
                } else connectWithRetry(info.groupOwnerAddress.hostAddress!!)
                if (!isCurrent(transferGeneration)) return@Thread
                if (transferMode == Mode.SEND) send(s, transferFiles, transferGeneration)
                else receive(s, transferGeneration)
                if (isCurrent(transferGeneration)) {
                    done = true; progress = 1f; status = "تم بحمد الله"
                }
            } catch (e: Exception) {
                if (isCurrent(transferGeneration)) {
                    failed = true; status = "انقطع الاتصال، حاول من جديد"
                }
            } finally {
                if (generation == transferGeneration) closeSockets()
            }
        }.also { it.start() }
    }

    private fun connectWithRetry(host: String): Socket {
        var last: Exception? = null
        repeat(10) {
            if (!active) throw IOException("Transfer cancelled")
            val candidate = Socket()
            socket = candidate
            if (!active) {
                candidate.close()
                throw IOException("Transfer cancelled")
            }
            try {
                candidate.connect(InetSocketAddress(host, 8988), 4000)
                return candidate
            } catch (e: Exception) {
                last = e
                try { candidate.close() } catch (_: Exception) {}
                if (socket === candidate) socket = null
                if (active) Thread.sleep(700)
            }
        }
        throw last ?: IOException()
    }

    private class Meta(val uri: Uri, val name: String, val size: Long)

    private fun meta(u: Uri): Meta {
        var n = "file"
        ctx.contentResolver.query(u, null, null, null, null)?.use {
            if (it.moveToFirst()) { val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) n = it.getString(i) }
        }
        val sz = ctx.contentResolver.openAssetFileDescriptor(u, "r")?.use { it.length } ?: -1L
        return Meta(u, n, sz)
    }

    private fun send(s: Socket, files: List<Uri>, transferGeneration: Int) {
        val list = files.map { meta(it) }
        val total = if (list.any { it.size < 0L }) -1L else list.sumOf { it.size }.coerceAtLeast(1)
        val startedAt = System.currentTimeMillis()
        transferStartedAt = startedAt
        senderName = localName
        transferItems = list.map { TransferItem(it.uri, it.name, it.size, startedAt) }
        progressIndeterminate = total < 0L
        val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 1 shl 16))
        out.writeInt(list.size); out.writeLong(total); out.writeUTF(localName); out.writeLong(startedAt)
        var sent = 0L; val t0 = System.nanoTime()
        for (m in list) {
            if (!isCurrent(transferGeneration)) return
            currentFile = m.name
            out.writeUTF(m.name); out.writeLong(m.size)
            ctx.contentResolver.openInputStream(m.uri)!!.use { inp ->
                TransferPayload.write(inp, out, m.size) { n ->
                    sent += n
                    tick(sent, total, t0, transferGeneration)
                }
            }
        }
        out.flush()
    }

    private fun receive(s: Socket, transferGeneration: Int) {
        val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
        val n = inp.readInt(); val total = inp.readLong()
        if (n !in 0..10000) throw IOException("Invalid file count")
        senderName = inp.readUTF()
        transferStartedAt = inp.readLong()
        progressIndeterminate = total < 0L
        filesCount = n
        var got = 0L; val t0 = System.nanoTime()
        repeat(n) {
            if (!isCurrent(transferGeneration)) return
            val name = inp.readUTF(); val size = inp.readLong()
            if (size < -1L) throw IOException("Invalid file size")
            currentFile = name
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name.substringAfterLast('/').substringAfterLast('\\'))
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Barq")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
            var fileBytes = 0L
            ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                TransferPayload.read(inp, out, size) { k ->
                    got += k
                    fileBytes += k
                    tick(got, total, t0, transferGeneration)
                }
            }
            transferItems = transferItems + TransferItem(uri, name, if (size >= 0L) size else fileBytes, transferStartedAt)
        }
    }

    private fun tick(done: Long, total: Long, t0: Long, transferGeneration: Int) {
        if (!isCurrent(transferGeneration)) return
        if (total > 0L) progress = (done.toFloat() / total).coerceIn(0f, 1f)
        val sec = (System.nanoTime() - t0) / 1e9
        if (sec > 0.5) {
            val mbps = done * 8 / sec / 1e6
            speed = "%.1f ميجابت/ث".format(mbps)
            connectionQuality = if (mbps >= 8.0) {
                "جودة الاتصال ممتازة (تقدير حسب سرعة النقل)"
            } else {
                "حاول تقرّب الجهازين من بعض شويه (تقدير حسب سرعة النقل)"
            }
        }
    }

    private fun isCurrent(transferGeneration: Int) = active && generation == transferGeneration

    private fun closeSockets() {
        try { socket?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        socket = null
        server = null
    }

    private fun reset() {
        generation++
        started = false; connected = false; done = false; failed = false
        progress = 0f; progressIndeterminate = false; speed = ""; currentFile = ""; peers = emptyList()
        transferItems = emptyList(); senderName = ""; transferStartedAt = 0L; connectionQuality = ""
    }

    fun stop() {
        active = false
        worker?.interrupt()
        worker = null
        closeSockets()
        mgr.stopPeerDiscovery(channel, null)
        mgr.removeGroup(channel, null)
        reset()
    }
}
