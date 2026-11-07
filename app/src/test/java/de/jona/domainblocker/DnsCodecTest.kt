package de.jona.domainblocker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xbill.DNS.Message
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.Type

/** Builds a minimal DNS query (header + question) with the given name/type. */
fun buildQuery(name: String, type: Int = Type.A, id: Int = 0x1234): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    out.write((id ushr 8) and 0xFF); out.write(id and 0xFF)
    out.write(0x01); out.write(0x00) // RD
    out.write(0x00); out.write(0x01) // QDCOUNT
    out.write(0x00); out.write(0x00)
    out.write(0x00); out.write(0x00)
    out.write(0x00); out.write(0x00)
    for (label in name.trimEnd('.').split('.')) {
        out.write(label.length)
        out.write(label.toByteArray(Charsets.US_ASCII))
    }
    out.write(0x00)
    out.write((type ushr 8) and 0xFF); out.write(type and 0xFF)
    out.write(0x00); out.write(0x01) // class IN
    return out.toByteArray()
}

class DnsCodecTest {

    // ---- parseQuestion -------------------------------------------------

    @Test
    fun parsesSimpleAQuery() {
        val q = DnsCodec.parseQuestion(buildQuery("example.com", Type.A))!!
        assertEquals("example.com", q.domain)
        assertEquals(Type.A, q.qtype)
    }

    @Test
    fun parsesAAAAAndHttpsTypes() {
        assertEquals(Type.AAAA, DnsCodec.parseQuestion(buildQuery("example.com", Type.AAAA))!!.qtype)
        assertEquals(Type.HTTPS, DnsCodec.parseQuestion(buildQuery("example.com", Type.HTTPS))!!.qtype)
    }

    @Test
    fun parsesSubdomain() {
        val q = DnsCodec.parseQuestion(buildQuery("sub.Example.COM", Type.A))!!
        assertEquals("sub.example.com", q.domain)
    }

    @Test
    fun lowercasesDomain() {
        val q = DnsCodec.parseQuestion(buildQuery("WWW.GOOGLE.COM"))!!
        assertEquals("www.google.com", q.domain)
    }

    @Test
    fun rejectsTruncatedPacket() {
        assertNull(DnsCodec.parseQuestion(byteArrayOf(0, 1, 2)))
        // header ok but question truncated mid-label
        assertNull(DnsCodec.parseQuestion(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0, 5, 'a'.code.toByte())))
    }

    @Test
    fun rejectsCompressionPointer() {
        val data = buildQuery("example.com").toMutableList()
        data[12] = 0xC0.toByte(); data[13] = 0x0C.toByte()
        assertNull(DnsCodec.parseQuestion(data.toByteArray()))
    }

    @Test
    fun rejectsEmptyPacket() {
        assertNull(DnsCodec.parseQuestion(ByteArray(0)))
    }

    // ---- blockedReply ---------------------------------------------------

    @Test
    fun blockedReplyA_hasSaneHeadersAnd127001() {
        val query = buildQuery("instagram.com", Type.A, id = 0xBEEF)
        val q = DnsCodec.parseQuestion(query)!!
        val resp = DnsCodec.blockedReply(query, q)

        assertEquals(query[0], resp[0]) // transaction id
        assertEquals(query[1], resp[1])
        assertEquals(0x81, resp[2].toInt() and 0xFF)
        assertEquals(0x80, resp[3].toInt() and 0xFF)
        assertEquals(0, resp[4].toInt() and 0xFF)
        assertEquals(1, resp[5].toInt() and 0xFF)   // QDCOUNT
        assertEquals(0, resp[6].toInt() and 0xFF)
        assertEquals(1, resp[7].toInt() and 0xFF)   // ANCOUNT = 1
    }

    @Test
    fun blockedReplyA_isParseableByDnsjava() {
        val query = buildQuery("instagram.com", Type.A, id = 0xCAFE)
        val q = DnsCodec.parseQuestion(query)!!
        val resp = DnsCodec.blockedReply(query, q)

        val msg = Message(resp) // throws on malformed
        assertEquals(0xCAFE, msg.header.id)
        assertEquals(org.xbill.DNS.Rcode.NOERROR, msg.header.rcode)
        val question = msg.getSection(Section.QUESTION)[0]
        assertEquals("instagram.com", question.name.toString(true))
        assertEquals(Type.A, question.type)
        val answers = msg.getSection(Section.ANSWER)
        assertEquals(1, answers.size)
        val a = answers[0]
        assertEquals(Type.A, a.type)
        assertEquals("127.0.0.1", a.rdataToString())
    }

    @Test
    fun blockedReplyAaaa_hasEmptyAnswer() {
        val query = buildQuery("instagram.com", Type.AAAA, id = 0x0007)
        val q = DnsCodec.parseQuestion(query)!!
        val resp = DnsCodec.blockedReply(query, q)
        val msg = Message(resp)
        assertEquals(0x0007, msg.header.id)
        assertEquals(0, msg.getSection(Section.ANSWER).size)
        assertEquals(0, msg.header.getCount(Section.ANSWER))
    }

    @Test
    fun blockedReplyHttps_hasEmptyAnswer() {
        val query = buildQuery("instagram.com", Type.HTTPS, id = 0x0008)
        val q = DnsCodec.parseQuestion(query)!!
        val resp = DnsCodec.blockedReply(query, q)
        assertEquals(0, Message(resp).getSection(Section.ANSWER).size)
    }

    // ---- wrapIp --------------------------------------------------------

    @Test
    fun wrapIp_producesValidIpv4UdpPacket() {
        val dns = byteArrayOf(0x11, 0x22, 0x33)
        val pkt = DnsCodec.wrapIp(dns, "8.8.8.8", "10.42.0.3", 53, 55555)

        assertEquals(0x45, pkt[0].toInt() and 0xFF) // IPv4, IHL 5
        val totalLen = ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF)
        assertEquals(20 + 8 + 3, totalLen)
        assertEquals(17, pkt[9].toInt() and 0xFF) // UDP
        // Reply semantics: source = the DNS server (8.8.8.8), destination = the client.
        assertEquals("8.8.8.8", ipAt(pkt, 12))
        assertEquals("10.42.0.3", ipAt(pkt, 16))

        // verified IPv4 header checksum
        assertEquals(0, checksumVerify(pkt, 0, 20))

        val udpLen = ((pkt[24].toInt() and 0xFF) shl 8) or (pkt[25].toInt() and 0xFF)
        assertEquals(8 + 3, udpLen)
        assertEquals(53, ((pkt[20].toInt() and 0xFF) shl 8) or (pkt[21].toInt() and 0xFF))
        assertEquals(55555, ((pkt[22].toInt() and 0xFF) shl 8) or (pkt[23].toInt() and 0xFF))
        // UDP checksum (with pseudo-header) must verify to 0
        assertEquals(0, checksumVerify(pkt, 20, udpLen, "8.8.8.8", "10.42.0.3"))
        assertArrayEquals(dns, pkt.copyOfRange(28, 31))
    }

    private fun ipAt(buf: ByteArray, off: Int): String =
        "${buf[off].toInt() and 0xFF}.${buf[off + 1].toInt() and 0xFF}." +
            "${buf[off + 2].toInt() and 0xFF}.${buf[off + 3].toInt() and 0xFF}"

    private fun checksumVerify(buf: ByteArray, offset: Int, len: Int, srcIp: String? = null, dstIp: String? = null): Int {
        var sum = 0
        if (srcIp != null && dstIp != null) {
            val s = srcIp.split('.').map { it.toInt() and 0xFF }
            val d = dstIp.split('.').map { it.toInt() and 0xFF }
            // RFC 768 pseudo-header, 16-bit words: src addr, dst addr,
            // protocol, UDP length (mirrors DnsCodec.writeUdpChecksum).
            for (i in 0..3 step 2) sum += (s[i] shl 8) or s[i + 1]
            for (i in 0..3 step 2) sum += (d[i] shl 8) or d[i + 1]
            sum += 17
            sum += len
        }
        var i = offset
        while (i < offset + len - 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < offset + len) sum += (buf[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF // 0 == valid
    }

    @Test
    fun fullBlockFlow_wirePacketOk() {
        // The wire path: parse -> blockedReply -> wrapIp -> payload survives.
        val query = buildQuery("tiktok.com", Type.A, id = 0x0A0A)
        val q = DnsCodec.parseQuestion(query)!!
        val dns = DnsCodec.blockedReply(query, q)
        val wire = DnsCodec.wrapIp(dns, "8.8.8.8", "10.42.0.5", 53, 4321)
        val udpLen = ((wire[24].toInt() and 0xFF) shl 8) or (wire[25].toInt() and 0xFF)
        assertEquals(dns.size + 8, udpLen)
        assertTrue(udpLen > 8)
    }
}
