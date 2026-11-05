package de.jona.domainblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local VPN that filters DNS: only UDP/53 traffic to the routed DNS servers
 * enters the tunnel. Blocked domains are answered with A=127.0.0.1 (the
 * loopback redirect server then turns plain-HTTP into a 302; HTTPS is refused
 * and thus blocked). Everything else on those routed IPs is only DNS anyway
 * and is relayed untouched. All non-DNS traffic of the device bypasses the
 * tunnel entirely, so there is no packet-relay engine and hence no slow
 * data path.
 */
class BlockVpnService : VpnService() {

    private val running = AtomicBoolean(false)
    private var tunFd: ParcelFileDescriptor? = null
    private var redirectServer: RedirectServer? = null

    override fun onCreate() {
        super.onCreate()
        DomainStore.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafe()
        if (running.compareAndSet(false, true)) {
            val server = RedirectServer { DomainStore.redirectUrl() }
            server.start()
            redirectServer = server
            Thread { runTunnel() }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        redirectServer?.stop()
        redirectServer = null
        tunFd?.close()
        tunFd = null
        super.onDestroy()
    }

    override fun onRevoke() {
        // User revoked the VPN permission. The system tears the tunnel down;
        // stop the service so the notification disappears too.
        stopSelf()
        super.onRevoke()
    }

    /** Establishes the tunnel and loops over DNS packets until stopped. */
    private fun runTunnel() {
        try {
            val builder = Builder()
                .addAddress("10.42.0.1", 24)
                .addDnsServer(DNS_1)
                .addDnsServer(DNS_2)
                .addDnsServer(DNS_3)
                .addRoute(DNS_1, 32)
                .addRoute(DNS_2, 32)
                .addRoute(DNS_3, 32)
                .setBlocking(true)
            val fd = builder.establish() ?: run {
                stopSelf()
                return
            }
            tunFd = fd
            val input = FileInputStream(fd.fileDescriptor)
            val output = FileOutputStream(fd.fileDescriptor)
            val buffer = ByteArray(65_535)

            while (running.get()) {
                val len = input.read(buffer)
                if (len < 0) break
                handlePacket(buffer, len, output)
            }
        } catch (_: Exception) {
            // Tunnel teardown / revoked permission — service stops itself.
        } finally {
            running.set(false)
            redirectServer?.stop()
            tunFd?.close()
            tunFd = null
            stopSelf()
        }
    }

    /**
     * Dispatches one IPv4 packet. DNS queries addressed to a routed DNS
     * server are answered (blocked) or relayed; any other packet that somehow
     * reaches the tunnel is dropped.
     */
    private fun handlePacket(buf: ByteArray, len: Int, output: FileOutputStream) {
        if (len < 20) return
        val version = (buf[0].toInt() ushr 4) and 0x0F
        val ihl = (buf[0].toInt() and 0x0F) * 4
        val totalLen = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        if (version != 4 || ihl < 20 || totalLen < ihl + 8 || totalLen > len) return

        val protocol = buf[9].toInt() and 0xFF
        if (protocol != PROTO_UDP) return // only DNS (UDP) is routed/captured

        val srcIp = readIp(buf, 12)
        val dstIp = readIp(buf, 16)

        val udpLen = ((buf[ihl + 4].toInt() and 0xFF) shl 8) or (buf[ihl + 5].toInt() and 0xFF)
        if (udpLen < 8 || ihl + udpLen > totalLen) return
        val srcPort = ((buf[ihl].toInt() and 0xFF) shl 8) or (buf[ihl + 1].toInt() and 0xFF)
        val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return

        val payload = buf.copyOfRange(ihl + 8, ihl + udpLen)
        val parsed = parseDnsQuery(payload) ?: return
        val reply = if (DomainStore.isBlocked(parsed.domain)) {
            val dns = dnsBlockedReply(payload, parsed)
            wrapIpResponse(dns, dns.size, dstIp, srcIp, 53, srcPort)
        } else {
            relayDns(payload, dstIp, srcIp, srcPort, dstPort)
        }
        if (reply != null) output.write(reply)
    }

    /**
     * Forwards a plain DNS query over a real socket and wraps the response.
     * The socket MUST be protect()ed: the DNS server IPs are routed through
     * the tunnel, so an unprotected socket would send its query back into
     * the tunnel -> infinite loop, no site resolves.
     */
    private fun relayDns(query: ByteArray, dnsIp: String, clientIp: String, clientPort: Int, dnsPort: Int): ByteArray? {
        return try {
            val socket = DatagramSocket()
            try {
                protect(socket)
                socket.soTimeout = 4000
                val remote = InetAddress.getByName(dnsIp)
                socket.send(DatagramPacket(query, query.size, remote, dnsPort))
                val resp = ByteArray(4096)
                val pkt = DatagramPacket(resp, resp.size)
                socket.receive(pkt)
                wrapIpResponse(pkt.data, pkt.length, dnsIp, clientIp, 53, clientPort)
            } finally {
                socket.close()
            }
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // DNS parsing / response crafting
    // ------------------------------------------------------------------

    private class DnsQuery(val domain: String, val qtype: Int, val questionEnd: Int)

    /** Extracts the question name (no compression in queries) + QTYPE. */
    private fun parseDnsQuery(data: ByteArray): DnsQuery? {
        if (data.size < 12) return null
        val qdcount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        if (qdcount < 1) return null
        var pos = 12
        val sb = StringBuilder()
        while (true) {
            if (pos >= data.size) return null
            val labelLen = data[pos].toInt() and 0xFF
            if (labelLen == 0) {
                pos++
                break
            }
            if (labelLen and 0xC0 == 0xC0) return null // compression not expected
            if (pos + 1 + labelLen > data.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 1..labelLen) sb.append((data[pos + i].toInt() and 0xFF).toChar())
            pos += 1 + labelLen
        }
        if (pos + 4 > data.size) return null
        val qtype = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        return DnsQuery(sb.toString().lowercase(), qtype, pos + 4)
    }

    /**
     * Builds a DNS response for a blocked query:
     * - A (1):         127.0.0.1  -> loopback, so the redirect server can 302 it
     * - AAAA (28):     empty answer -> client falls back to A
     * - anything else: empty answer
     */
    private fun dnsBlockedReply(query: ByteArray, q: DnsQuery): ByteArray {
        val answer = if (q.qtype == 1) {
            byteArrayOf(
                0xC0.toByte(), 0x0C,               // pointer to question name
                0x00, 0x01,            // type A
                0x00, 0x01,            // class IN
                0x00, 0x00, 0x01, 0x2C, // TTL 300
                0x00, 0x04,            // rdlength
                0x7F, 0x00, 0x00, 0x01 // 127.0.0.1
            )
        } else {
            ByteArray(0)
        }

        val resp = ByteArray(12 + q.questionEnd + answer.size)
        // DNS header
        resp[0] = query[0]; resp[1] = query[1]                     // ID
        resp[2] = 0x81.toByte(); resp[3] = 0x80.toByte()             // QR|RD|RA, rcode 0
        resp[4] = 0x00; resp[5] = 0x01                             // QDCOUNT 1
        resp[6] = 0x00; resp[7] = if (answer.isEmpty()) 0x00 else 0x01 // ANCOUNT
        resp[8] = 0x00; resp[9] = 0x00                             // NSCOUNT
        resp[10] = 0x00; resp[11] = 0x00                           // ARCOUNT
        // question: bytes 0..questionEnd of the query (header offset 12)
        System.arraycopy(query, 12, resp, 12, q.questionEnd)
        if (answer.isNotEmpty()) System.arraycopy(answer, 0, resp, 12 + q.questionEnd, answer.size)

        return resp
    }

    /**
     * Wraps a DNS payload into an IPv4+UDP packet from (fromPort@fromIp) back
     * to the original client (toPort@toIp), with correct checksums.
     */
    private fun wrapIpResponse(dns: ByteArray, dnsLen: Int, fromIp: String, toIp: String, fromPort: Int, toPort: Int): ByteArray {
        val udpLen = 8 + dnsLen
        val totalLen = 20 + udpLen
        val out = ByteArray(totalLen)

        // IPv4 header
        out[0] = 0x45
        out[1] = 0x00
        out[2] = (totalLen ushr 8).toByte(); out[3] = totalLen.toByte()
        out[6] = 0x00; out[7] = 0x00 // no fragmentation
        out[8] = 0x40                 // TTL 64
        out[9] = PROTO_UDP.toByte()
        writeIp(out, 12, toIp)
        writeIp(out, 16, fromIp)
        writeChecksum(out, 0, 20)

        // UDP header
        out[20] = (fromPort ushr 8).toByte(); out[21] = fromPort.toByte()
        out[22] = (toPort ushr 8).toByte(); out[23] = toPort.toByte()
        out[24] = (udpLen ushr 8).toByte(); out[25] = udpLen.toByte()
        System.arraycopy(dns, 0, out, 28, dnsLen)
        writeUdpChecksum(out, 20, udpLen, fromIp, toIp)

        return out
    }

    private fun readIp(buf: ByteArray, offset: Int): String =
        "${buf[offset].toInt() and 0xFF}.${buf[offset + 1].toInt() and 0xFF}." +
            "${buf[offset + 2].toInt() and 0xFF}.${buf[offset + 3].toInt() and 0xFF}"

    private fun writeIp(buf: ByteArray, offset: Int, ip: String) {
        val parts = ip.split('.').map { it.toInt() and 0xFF }
        for (i in 0..3) buf[offset + i] = parts[i].toByte()
    }

    private fun writeChecksum(buf: ByteArray, offset: Int, len: Int) {
        var sum = 0
        var i = offset
        while (i < offset + len - 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < offset + len) sum += (buf[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        val cs = (sum.inv() and 0xFFFF)
        buf[offset + 10] = (cs ushr 8).toByte()
        buf[offset + 11] = cs.toByte()
    }

    /** UDP checksum including the IPv4 pseudo-header. */
    private fun writeUdpChecksum(buf: ByteArray, udpOffset: Int, udpLen: Int, srcIp: String, dstIp: String) {
        val src = srcIp.split('.').map { it.toInt() and 0xFF }
        val dst = dstIp.split('.').map { it.toInt() and 0xFF }
        var sum = 0
        for (i in 0..3) {
            sum += (src[i] shl 8) or dst[i]
        }
        sum += PROTO_UDP
        sum += udpLen
        var i = udpOffset
        while (i < udpOffset + udpLen - 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < udpOffset + udpLen) sum += (buf[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        val cs = (sum.inv() and 0xFFFF)
        buf[udpOffset + 6] = (cs ushr 8).toByte()
        buf[udpOffset + 7] = cs.toByte()
    }

    // ------------------------------------------------------------------
    // Foreground notification
    // ------------------------------------------------------------------

    /**
     * API 34+ normally requires the manifest-declared foreground service
     * type; this SDK no longer knows the `vpn` type, so fall back to
     * FOREGROUND_SERVICE_TYPE_SPECIAL_USE when a plain startForeground is
     * rejected.
     */
    private fun startForegroundSafe() {
        val notif = buildNotification()
        try {
            startForeground(NOTIF_ID, notif)
        } catch (_: Exception) {
            try {
                startForeground(
                    NOTIF_ID, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } catch (_: Exception) {
                // Give up silently; tunnel thread also dies with the service.
            }
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "blocker_status"
        private const val NOTIF_ID = 1
        private const val PROTO_UDP = 17

        // Routed DNS servers (IPv4 only on purpose — see README limitations).
        private const val DNS_1 = "8.8.8.8"
        private const val DNS_2 = "1.1.1.1"
        private const val DNS_3 = "9.9.9.9"
    }
}
