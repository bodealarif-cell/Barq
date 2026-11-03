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
    var speed by mutableStateOf("")
    var currentFile by mutableStateOf("")
    var done by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var filesCount by mutableIntStateOf(0)

    private var files: List<Uri> = emptyList()
    private var started = false
    private var socket: Socket? = null
    private var server: ServerSocket? = null

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION ->
                    mgr.requestPeers(channel) { peers = it.deviceList.toList() }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val d = i.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    myName = d?.deviceName ?: ""
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION ->
                    mgr.requestConnectionInfo(channel) { info -> if (info.groupFormed) onLinked(info) }
            }
        }
    }
    val filter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
    }

    fun begin(m: Mode, uris: List<Uri>) {
        reset(); mode = m; files = uris; filesCount = uris.size
        status = if (m == Mode.SEND) "جاري البحث عن الأجهزة القريبة…" else "جاهز للاستقبال، خلّ المرسل يختارك"
        discover()
    }

    fun discover() {
        mgr.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(r: Int) { status = "تأكد أن الواي فاي شغّال وأعد المحاولة" }
        })
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
        if (started) return
        started = true; connected = true; status = "تم الاتصال، أبشر…"
        Thread {
            try {
                val s = if (info.isGroupOwner) {
                    server = ServerSocket(8988).also { it.soTimeout = 60000 }
                    server!!.accept()
                } else connectWithRetry(info.groupOwnerAddress.hostAddress!!)
                socket = s
                if (mode == Mode.SEND) send(s) else receive(s)
                done = true; progress = 1f; status = "تم بحمد الله"
            } catch (e: Exception) {
                failed = true; status = "انقطع الاتصال، حاول من جديد"
            } finally { closeSockets() }
        }.start()
    }

    private fun connectWithRetry(host: String): Socket {
        var last: Exception? = null
        repeat(10) {
            try {
                return Socket().apply { connect(InetSocketAddress(host, 8988), 4000) }
            } catch (e: Exception) { last = e; Thread.sleep(700) }
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

    private fun send(s: Socket) {
        val list = files.map { meta(it) }
        val total = list.sumOf { it.size }.coerceAtLeast(1)
        val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 1 shl 16))
        out.writeInt(list.size); out.writeLong(total)
        var sent = 0L; val t0 = System.nanoTime()
        for (m in list) {
            currentFile = m.name
            out.writeUTF(m.name); out.writeLong(m.size)
            ctx.contentResolver.openInputStream(m.uri)!!.use { inp ->
                copy(inp, out, m.size) { n -> sent += n; tick(sent, total, t0) }
            }
        }
        out.flush()
    }

    private fun receive(s: Socket) {
        val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
        val n = inp.readInt(); val total = inp.readLong().coerceAtLeast(1)
        filesCount = n
        var got = 0L; val t0 = System.nanoTime()
        repeat(n) {
            val name = inp.readUTF(); val size = inp.readLong()
            currentFile = name
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Barq")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                copy(inp, out, size) { k -> got += k; tick(got, total, t0) }
            }
        }
    }

    private fun tick(done: Long, total: Long, t0: Long) {
        progress = (done.toFloat() / total).coerceIn(0f, 1f)
        val sec = (System.nanoTime() - t0) / 1e9
        if (sec > 0.5) speed = "%.1f ميجابت/ث".format(done * 8 / sec / 1e6)
    }

    private fun copy(i: InputStream, o: OutputStream, size: Long, onBytes: (Long) -> Unit) {
        val buf = ByteArray(256 * 1024); var left = size
        while (left > 0) {
            val n = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException()
            o.write(buf, 0, n); left -= n; onBytes(n.toLong())
        }
        o.flush()
    }

    private fun closeSockets() { try { socket?.close() } catch (_: Exception) {}; try { server?.close() } catch (_: Exception) {} }

    private fun reset() {
        started = false; connected = false; done = false; failed = false
        progress = 0f; speed = ""; currentFile = ""; peers = emptyList()
    }

    fun stop() {
        closeSockets()
        mgr.stopPeerDiscovery(channel, null)
        mgr.removeGroup(channel, null)
        reset()
    }
}
