package de.jona.domainblocker

/**
 * Pure JVM DNS codec used by [BlockVpnService]: parses DNS questions, builds
 * block replies and wraps raw IP packets. Kept free of any Android dependency
 * so it is fully unit-testable on the JVM.
 */
object DnsCodec {

    const val PROTO_UDP = 17

    class Question(val domain: String, val qtype: Int, val questionEnd: Int)

    /** Extracts the question name (no compression in queries) + QTYPE. */
    fun parseQuestion(data: ByteArray): Question? {
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
        return Question(sb.toString().lowercase(), qtype, pos + 4)
    }

    /**
     * Builds a DNS response for a blocked query:
     * - A (1):         127.0.0.1  -> loopback, so the redirect server can 302 it
     * - AAAA (28):     empty answer -> client falls back to A
     * - anything else: empty answer
     */
    fun blockedReply(query: ByteArray, q: Question): ByteArray {
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

        val qLen = q.questionEnd - 12
        val resp = ByteArray(12 + qLen + answer.size)
        // DNS header
        resp[0] = query[0]; resp[1] = query[1]                     // ID
        resp[2] = 0x81.toByte(); resp[3] = 0x80.toByte()             // QR|RD|RA, rcode 0
        resp[4] = 0x00; resp[5] = 0x01                             // QDCOUNT 1
        resp[6] = 0x00; resp[7] = if (answer.isEmpty()) 0x00 else 0x01 // ANCOUNT
        resp[8] = 0x00; resp[9] = 0x00                             // NSCOUNT
        resp[10] = 0x00; resp[11] = 0x00                           // ARCOUNT
        // question: bytes 12..questionEnd of the query; questionEnd is an
        // ABSOLUTE offset in the query, so the section length is -12.
        System.arraycopy(query, 12, resp, 12, qLen)
        if (answer.isNotEmpty()) System.arraycopy(answer, 0, resp, 12 + qLen, answer.size)

        return resp
    }

    /** Wraps a DNS payload into an IPv4+UDP packet (fromPort@fromIp) back to (toPort@toIp). */
    fun wrapIp(dns: ByteArray, fromIp: String, toIp: String, fromPort: Int, toPort: Int): ByteArray {
        val udpLen = 8 + dns.size
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
        System.arraycopy(dns, 0, out, 28, dns.size)
        writeUdpChecksum(out, 20, udpLen, fromIp, toIp)

        return out
    }

    fun readIp(buf: ByteArray, offset: Int): String =
        "${buf[offset].toInt() and 0xFF}.${buf[offset + 1].toInt() and 0xFF}." +
            "${buf[offset + 2].toInt() and 0xFF}.${buf[offset + 3].toInt() and 0xFF}"

    fun writeIp(buf: ByteArray, offset: Int, ip: String) {
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
}
