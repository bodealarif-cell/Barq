package sa.barq.app.lan

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * بروتوكول برق للشبكة المحلية (الإصدار 1) — متطابق مع برنامج الحاسوب (src/core/protocol.js).
 * الاكتشاف: UDP 8989 — BARQ1|PROBE|id  /  BARQ1|HERE|id|name|platform|tcpPort
 * النقل: TCP 8990 — المرسل: MAGIC,VERSION,UTF id,UTF name,UTF platform,count,(UTF name,long size)*
 *        المستقبل: 1 قبول / 0 رفض / 2 مشغول ← البيانات ← المستقبل: 7 (اكتمل).
 * هذا الملف Kotlin صرف (JDK فقط) كي يُختبر على الحاسوب بدون أندرويد.
 */
object LanProtocol {
    const val DISCOVERY_PORT = 8989
    const val TRANSFER_PORT = 8990
    const val MAGIC = 0x42415251
    const val VERSION = 1
    const val MAX_FILES = 10_000
    const val REPLY_REJECT = 0
    const val REPLY_ACCEPT = 1
    const val REPLY_BUSY = 2
    const val ACK = 7

    fun cleanLabel(raw: String, max: Int = 40): String {
        val s = raw.filter { !it.isISOControl() && it != '|' }.trim()
        return s.take(max).ifBlank { "جهاز" }
    }

    private val reserved = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    /** اسم ملف آمن على ويندوز وأندرويد: بلا مسارات ولا محارف ممنوعة ولا أسماء محجوزة. */
    fun safeFileName(raw: String): String {
        var n = raw.substringAfterLast('/').substringAfterLast('\\')
        n = n.filter { !it.isISOControl() && it !in "<>:\"|?*" }.trim().trim('.')
        if (n.length > 150) {
            val ext = n.substringAfterLast('.', "")
            n = if (ext.isNotEmpty() && ext.length <= 10) n.take(140) + "." + ext else n.take(150)
        }
        if (n.isBlank()) n = "file"
        if (n.substringBefore('.').uppercase() in reserved) n = "_$n"
        return n
    }
}

/** جهاز يستقبل ملفات على الشبكة المحلية. */
data class Peer(val id: String, val name: String, val platform: String, val host: String, val port: Int)

data class FileInfo(val name: String, val size: Long)

/** طلب إرسال وارد ينتظر موافقة المستخدم. size = -1 تعني حجماً غير معروف. */
data class Offer(
    val peerId: String,
    val peerName: String,
    val platform: String,
    val host: String,
    val files: List<FileInfo>
) {
    val totalBytes: Long get() = if (files.any { it.size < 0L }) -1L else files.sumOf { it.size }
}

/** ملف جاهز للإرسال؛ [open] تفتح مجرى القراءة عند الحاجة. */
class OutFile(val name: String, val size: Long, val open: () -> InputStream)

interface TransferEvents {
    fun onFile(index: Int, count: Int, name: String, size: Long) {}
    fun onBytes(n: Long) {}
}

interface ReceiveSink {
    fun open(index: Int, name: String, size: Long): OutputStream
    /** تُستدعى دائماً بعد كل ملف؛ ok=false إذا انقطع قبل اكتماله ليحذف المنفّذ الناقص. */
    fun close(index: Int, ok: Boolean, bytes: Long) {}
}

class TransferRejected(val code: Int) : IOException(
    if (code == LanProtocol.REPLY_BUSY) "الجهاز الآخر مشغول بنقل آخر" else "رفض الجهاز الآخر الاستقبال"
)

class TransferCancelled : IOException("تم إلغاء النقل")

/** نقل محتوى ملف واحد (حجم معروف: بايتات خام؛ غير معروف: قطع تنتهي بصفر). */
object LanPayload {
    private const val CHUNK = 64 * 1024

    fun write(input: InputStream, out: DataOutputStream, size: Long, onBytes: (Long) -> Unit) {
        require(size >= -1L) { "Invalid file size" }
        val buf = ByteArray(256 * 1024)
        if (size >= 0L) {
            var remaining = size
            while (remaining > 0) {
                val c = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (c < 0) throw EOFException("File ended before its declared size")
                if (c == 0) continue
                out.write(buf, 0, c); remaining -= c; onBytes(c.toLong())
            }
            return
        }
        while (true) {
            val c = input.read(buf, 0, CHUNK)
            if (c < 0) break
            if (c == 0) continue
            out.writeInt(c); out.write(buf, 0, c); onBytes(c.toLong())
        }
        out.writeInt(0)
    }

    fun read(input: DataInputStream, out: OutputStream, size: Long, onBytes: (Long) -> Unit) {
        require(size >= -1L) { "Invalid file size" }
        val buf = ByteArray(256 * 1024)
        if (size >= 0L) {
            var remaining = size
            while (remaining > 0) {
                val c = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (c < 0) throw EOFException("File ended before its declared size")
                if (c == 0) continue
                out.write(buf, 0, c); remaining -= c; onBytes(c.toLong())
            }
            return
        }
        while (true) {
            val c = input.readInt()
            if (c == 0) return
            if (c !in 1..buf.size) throw EOFException("Invalid transfer chunk")
            input.readFully(buf, 0, c)
            out.write(buf, 0, c); onBytes(c.toLong())
        }
    }
}

/** قائمة الأجهزة المرئية الآن؛ يسقط الجهاز إذا لم يُسمع منه خلال [ttlMs]. */
class PeerBook(private val ttlMs: Long = 7000L, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val map = LinkedHashMap<String, Pair<Peer, Long>>()
    @Synchronized fun seen(p: Peer) { map[p.id] = p to clock() }
    @Synchronized fun snapshot(): List<Peer> {
        val now = clock()
        map.entries.removeAll { now - it.value.second > ttlMs }
        return map.values.map { it.first }.sortedBy { it.name.lowercase() }
    }
    @Synchronized fun clear() = map.clear()
}

/** اكتشاف الأجهزة على نفس الشبكة بدون إنترنت. */
class LanDiscovery(
    private val selfId: String,
    selfName: String,
    private val platform: String,
    private val discoveryPort: Int = LanProtocol.DISCOVERY_PORT,
    private val onPeer: (Peer) -> Unit
) {
    @Volatile private var selfName = LanProtocol.cleanLabel(selfName)
    /** منفذ TCP الذي نستقبل عليه؛ 0 = لا نستقبل الآن فلا نرد ولا نعلن. */
    @Volatile var listeningPort: Int = 0
    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    private var rx: Thread? = null
    private var tx: Thread? = null

    fun setName(name: String) { selfName = LanProtocol.cleanLabel(name) }

    @Synchronized fun start() {
        if (running) return
        var s = DatagramSocket(null as SocketAddress?)
        try {
            s.reuseAddress = true
            s.broadcast = true
            s.bind(InetSocketAddress(discoveryPort))
        } catch (e: SocketException) {
            s.close()
            s = DatagramSocket()
            s.broadcast = true
        }
        s.soTimeout = 1000
        socket = s
        running = true
        rx = Thread({ receiveLoop(s) }, "barq-lan-rx").apply { isDaemon = true; start() }
        tx = Thread({ announceLoop() }, "barq-lan-tx").apply { isDaemon = true; start() }
    }

    @Synchronized fun stop() {
        running = false
        socket?.close(); socket = null
        rx?.interrupt(); tx?.interrupt()
        rx = null; tx = null
    }

    /** يبث "من هناك؟" على كل الشبكات المتصلة. */
    fun probe() {
        val msg = "BARQ1|PROBE|$selfId"
        Thread {
            broadcastTargets().forEach { sendTo(msg, InetSocketAddress(it, discoveryPort)) }
        }.apply { isDaemon = true }.start()
    }

    fun probeTo(address: InetSocketAddress) = sendTo("BARQ1|PROBE|$selfId", address)

    fun announceTo(address: InetSocketAddress) = sendTo(hereMessage(), address)

    private fun hereMessage() = "BARQ1|HERE|$selfId|$selfName|$platform|$listeningPort"

    private fun sendTo(text: String, to: InetSocketAddress) {
        val s = socket ?: return
        val data = text.toByteArray(Charsets.UTF_8)
        try { s.send(DatagramPacket(data, data.size, to)) } catch (_: Exception) { }
    }

    private fun announceLoop() {
        while (running) {
            if (listeningPort > 0) {
                val msg = hereMessage()
                broadcastTargets().forEach { sendTo(msg, InetSocketAddress(it, discoveryPort)) }
            }
            try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
        }
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(1024)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                if (!running) return
                try { Thread.sleep(200) } catch (_: InterruptedException) { return }
                continue
            }
            handle(String(p.data, 0, p.length, Charsets.UTF_8), InetSocketAddress(p.address, p.port))
        }
    }

    private fun handle(text: String, from: InetSocketAddress) {
        val parts = text.split('|')
        if (parts.size < 3 || parts[0] != "BARQ1") return
        val id = parts[2]
        if (id == selfId || id.isBlank() || id.length > 64) return
        when (parts[1]) {
            "PROBE" -> if (listeningPort > 0) sendTo(hereMessage(), from)
            "HERE" -> {
                if (parts.size < 6) return
                val port = parts[5].toIntOrNull() ?: return
                if (port !in 1..65535) return
                onPeer(
                    Peer(
                        id = id,
                        name = LanProtocol.cleanLabel(parts[3]),
                        platform = parts[4].take(12),
                        host = from.address.hostAddress ?: return,
                        port = port
                    )
                )
            }
        }
    }

    companion object {
        /** عناوين البث لكل واجهة شبكة فعّالة (تشمل نقطة اتصال الجوال) + البث العام. */
        fun broadcastTargets(): List<InetAddress> {
            val out = LinkedHashSet<InetAddress>()
            try {
                val ifs = NetworkInterface.getNetworkInterfaces()
                while (ifs != null && ifs.hasMoreElements()) {
                    val ni = ifs.nextElement()
                    try {
                        if (!ni.isUp || ni.isLoopback) continue
                        ni.interfaceAddresses.forEach { ia ->
                            val b = ia.broadcast
                            if (b is Inet4Address) out.add(b)
                        }
                    } catch (_: SocketException) { }
                }
            } catch (_: SocketException) { }
            try { out.add(InetAddress.getByName("255.255.255.255")) } catch (_: IOException) { }
            return out.toList()
        }
    }
}

interface ReceiverHandler {
    /** تُستدعى على خيط الاتصال وتنتظر قرار المستخدم (تعود خلال دقيقة تقريباً). */
    fun decide(offer: Offer): Boolean
    fun sinkFor(offer: Offer): ReceiveSink
    fun events(offer: Offer): TransferEvents
    /** error = null عند النجاح. لا تُستدعى عند الرفض. */
    fun onDone(offer: Offer, error: Throwable?)
}

/** خادم الاستقبال: يقبل اتصالاً واحداً نشطاً في كل مرة. */
class LanReceiver(private val handler: ReceiverHandler) {
    @Volatile private var server: ServerSocket? = null
    private val busy = AtomicBoolean(false)
    @Volatile private var active: Socket? = null

    /** يبدأ الاستماع ويرجع المنفذ الفعلي. */
    @Synchronized fun start(preferredPort: Int = LanProtocol.TRANSFER_PORT): Int {
        server?.let { return it.localPort }
        val ss = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(preferredPort)) }
        } catch (_: IOException) {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(0)) }
        }
        server = ss
        Thread({ acceptLoop(ss) }, "barq-lan-accept").apply { isDaemon = true; start() }
        return ss.localPort
    }

    @Synchronized fun stop() {
        try { server?.close() } catch (_: IOException) { }
        server = null
        cancelActive()
    }

    fun cancelActive() { try { active?.close() } catch (_: IOException) { } }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = try { ss.accept() } catch (_: IOException) { return }
            Thread({ handle(s) }, "barq-lan-conn").apply { isDaemon = true; start() }
        }
    }

    private fun handle(s: Socket) {
        var offer: Offer? = null
        var holdsBusy = false
        try {
            s.use {
                s.soTimeout = 15_000
                s.tcpNoDelay = true
                val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 18))
                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 1 shl 10))
                if (inp.readInt() != LanProtocol.MAGIC) return
                if (inp.readUnsignedByte() != LanProtocol.VERSION) {
                    out.writeByte(LanProtocol.REPLY_REJECT); out.flush(); return
                }
                val id = inp.readUTF().take(64)
                val name = LanProtocol.cleanLabel(inp.readUTF())
                val platform = inp.readUTF().take(12)
                val count = inp.readInt()
                if (count !in 1..LanProtocol.MAX_FILES) throw IOException("Invalid file count")
                val files = List(count) {
                    val n = LanProtocol.safeFileName(inp.readUTF())
                    val sz = inp.readLong()
                    if (sz < -1L) throw IOException("Invalid file size")
                    FileInfo(n, sz)
                }
                val o = Offer(id, name, platform, s.inetAddress.hostAddress ?: "", files)
                offer = o

                if (!busy.compareAndSet(false, true)) {
                    out.writeByte(LanProtocol.REPLY_BUSY); out.flush(); return
                }
                holdsBusy = true
                active = s

                val accepted = try { handler.decide(o) } catch (_: Exception) { false }
                out.writeByte(if (accepted) LanProtocol.REPLY_ACCEPT else LanProtocol.REPLY_REJECT)
                out.flush()
                if (!accepted) return

                s.soTimeout = 30_000
                val sink = handler.sinkFor(o)
                val ev = handler.events(o)
                files.forEachIndexed { i, f ->
                    ev.onFile(i, files.size, f.name, f.size)
                    val stream = sink.open(i, f.name, f.size)
                    var bytes = 0L
                    var ok = false
                    try {
                        stream.use { w -> LanPayload.read(inp, w, f.size) { n -> bytes += n; ev.onBytes(n) } }
                        ok = true
                    } finally {
                        sink.close(i, ok, bytes)
                    }
                }
                out.writeByte(LanProtocol.ACK); out.flush()
                handler.onDone(o, null)
            }
        } catch (t: Throwable) {
            offer?.let { if (holdsBusy) handler.onDone(it, t) }
        } finally {
            if (holdsBusy) { active = null; busy.set(false) }
        }
    }
}

/** جلسة إرسال واحدة إلى [peer]. */
class LanSendSession(
    private val peer: Peer,
    private val files: List<OutFile>,
    private val selfId: String,
    selfName: String,
    private val platform: String,
    private val events: TransferEvents
) {
    private val selfName = LanProtocol.cleanLabel(selfName)
    @Volatile private var sock: Socket? = null
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true; try { sock?.close() } catch (_: IOException) { } }

    /** يرمي [TransferRejected] أو [TransferCancelled] أو IOException عند الفشل. */
    fun run() {
        require(files.isNotEmpty()) { "No files" }
        val s = Socket()
        sock = s
        try {
            s.use {
                s.connect(InetSocketAddress(peer.host, peer.port), 5_000)
                s.tcpNoDelay = true
                s.sendBufferSize = 1 shl 20
                s.soTimeout = 70_000 // ينتظر قرار المستخدم على الجهاز الآخر
                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 1 shl 18))
                val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 10))
                out.writeInt(LanProtocol.MAGIC)
                out.writeByte(LanProtocol.VERSION)
                out.writeUTF(selfId)
                out.writeUTF(selfName)
                out.writeUTF(platform)
                out.writeInt(files.size)
                files.forEach { out.writeUTF(LanProtocol.safeFileName(it.name)); out.writeLong(it.size) }
                out.flush()

                val reply = inp.readUnsignedByte()
                if (reply != LanProtocol.REPLY_ACCEPT) throw TransferRejected(reply)

                s.soTimeout = 30_000
                files.forEachIndexed { i, f ->
                    if (cancelled) throw TransferCancelled()
                    events.onFile(i, files.size, f.name, f.size)
                    f.open().use { LanPayload.write(it, out, f.size) { n -> events.onBytes(n) } }
                }
                out.flush()
                if (inp.readUnsignedByte() != LanProtocol.ACK) throw IOException("Receiver did not confirm")
            }
        } catch (e: IOException) {
            if (cancelled) throw TransferCancelled() else throw e
        }
    }
}
