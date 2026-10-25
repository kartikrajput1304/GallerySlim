package com.galleryslim.share

import com.galleryslim.diag.GsLog
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Answers mDNS "A" queries for [HOSTNAME] (RFC 6762) so the laptop can open
 * `http://galleryslim.local:8765` instead of an IP address. Windows 10+, macOS
 * and Linux resolve `.local` names this way. It answers only that one name,
 * only on the Wi-Fi interface, and only while sharing is active. Some routers
 * isolate clients and block multicast — the IP link stays as the fallback.
 */
class MdnsResponder(private val wifiAddress: Inet4Address, private val iface: NetworkInterface?) {
    private val running = AtomicBoolean(false)
    private var socket: MulticastSocket? = null
    private val nameBytes = encodeName(HOSTNAME)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                val group = InetAddress.getByName(MDNS_GROUP)
                val s = MulticastSocket(MDNS_PORT).apply {
                    reuseAddress = true
                    iface?.let { networkInterface = it }
                    joinGroup(InetSocketAddress(group, MDNS_PORT), iface)
                }
                socket = s
                GsLog.i("share", "mDNS: answering for $HOSTNAME → ${wifiAddress.hostAddress}")
                val buf = ByteArray(1500)
                while (running.get()) {
                    val packet = DatagramPacket(buf, buf.size)
                    try { s.receive(packet) } catch (_: Exception) { if (running.get()) continue else break }
                    val reply = answerIfForUs(packet.data, packet.length) ?: continue
                    // Legacy unicast queries (source port != 5353) get a unicast reply with the query id.
                    val legacy = packet.port != MDNS_PORT
                    if (legacy) { reply[0] = packet.data[0]; reply[1] = packet.data[1] }
                    val target = if (legacy) packet.socketAddress else InetSocketAddress(group, MDNS_PORT)
                    runCatching { s.send(DatagramPacket(reply, reply.size, target)) }
                }
            } catch (e: Exception) {
                GsLog.w("share", "mDNS responder stopped: ${e.message}")
            }
        }, "gs-mdns").apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
    }

    /** Builds a response when the packet is a query containing our name (type A or ANY). */
    internal fun answerIfForUs(data: ByteArray, length: Int): ByteArray? {
        if (length < 12) return null
        val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        if (flags and 0x8000 != 0) return null // a response, not a query
        val qdCount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        var pos = 12
        var asked = false
        repeat(qdCount) {
            val (name, next) = readName(data, pos, length) ?: return null
            if (next + 4 > length) return null
            val type = ((data[next].toInt() and 0xFF) shl 8) or (data[next + 1].toInt() and 0xFF)
            pos = next + 4
            if (name.equals(HOSTNAME, ignoreCase = true) && (type == TYPE_A || type == TYPE_ANY)) asked = true
        }
        if (!asked) return null
        val out = ByteBuffer.allocate(12 + nameBytes.size + 10 + 4)
        out.putShort(0)                 // id (0 for multicast responses)
        out.putShort(0x8400.toShort())  // QR=1, AA=1
        out.putShort(0); out.putShort(1); out.putShort(0); out.putShort(0) // qd=0 an=1 ns=0 ar=0
        out.put(nameBytes)
        out.putShort(TYPE_A.toShort())
        out.putShort(0x8001.toShort())  // class IN + cache-flush
        out.putInt(TTL_SECONDS)
        out.putShort(4)
        out.put(wifiAddress.address)
        return out.array()
    }

    private fun readName(data: ByteArray, start: Int, length: Int): Pair<String, Int>? {
        val labels = ArrayList<String>()
        var pos = start
        var hops = 0
        var end = -1
        while (pos < length) {
            val len = data[pos].toInt() and 0xFF
            when {
                len == 0 -> { if (end < 0) end = pos + 1; break }
                len and 0xC0 == 0xC0 -> { // compression pointer
                    if (pos + 1 >= length || ++hops > 8) return null
                    if (end < 0) end = pos + 2
                    pos = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                }
                else -> {
                    if (pos + 1 + len > length) return null
                    labels += String(data, pos + 1, len, Charsets.US_ASCII)
                    pos += 1 + len
                }
            }
        }
        if (end < 0) return null
        return labels.joinToString(".") to end
    }

    companion object {
        const val HOSTNAME = "galleryslim.local"
        const val MDNS_GROUP = "224.0.0.251"
        const val MDNS_PORT = 5353
        private const val TYPE_A = 1
        private const val TYPE_ANY = 255
        private const val TTL_SECONDS = 120

        fun encodeName(name: String): ByteArray {
            val out = ArrayList<Byte>()
            name.split('.').forEach { label ->
                val b = label.toByteArray(Charsets.US_ASCII)
                out += b.size.toByte(); out += b.toList()
            }
            out += 0
            return out.toByteArray()
        }
    }
}
