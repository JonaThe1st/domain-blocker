package de.jona.domainblocker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.ServerSocket
import java.nio.charset.StandardCharsets

class RedirectServerTest {

    @Test
    fun startOnFreePort_works() {
        val server = RedirectServer({ "https://example.com" }, port = 0)
        server.start()
        Thread.sleep(300)
        assertTrue(server.isRunning())
        server.stop()
    }

    @Test
    fun startOnBusyPort_doesNotCrashAndServerStaysUsable() {
        // Occupy a port, then ask RedirectServer to bind the same one.
        val occupier = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val busyPort = occupier.localPort
        try {
            val server = RedirectServer({ "https://example.com" }, port = busyPort)
            server.start()
            Thread.sleep(300)
            // No exception thrown, thread alive, but running flag set — the
            // bind failure was swallowed like the port-80 case on Android.
            assertTrue(server.isRunning())
            server.stop()
        } finally {
            occupier.close()
        }
    }

    @Test
    fun stop_isIdempotent() {
        val server = RedirectServer({ "https://example.com" }, port = 0)
        server.start()
        Thread.sleep(200)
        server.stop()
        server.stop() // must not throw
        assertFalse(server.isRunning())
    }

    @Test
    fun get_returns302LocationAndHtmlBody() {
        val port = freePort()
        val server = RedirectServer({ "https://focus.example/path" }, port)
        server.start()
        try {
            val response = request(port, "GET / HTTP/1.1\r\nHost: blocked.example\r\n\r\n")
            assertTrue(response.startsWith("HTTP/1.1 302 Found\r\n"))
            assertTrue(response.contains("Location: https://focus.example/path\r\n"))
            assertTrue(response.contains("Diese Seite ist blockiert."))
        } finally {
            server.stop()
        }
    }

    @Test
    fun head_returnsHeadersWithoutBody() {
        val port = freePort()
        val server = RedirectServer({ "https://focus.example" }, port)
        server.start()
        try {
            val response = request(port, "HEAD /favicon.ico HTTP/1.1\r\nHost: blocked.example\r\n\r\n")
            val body = response.substringAfter("\r\n\r\n")
            assertTrue(response.startsWith("HTTP/1.1 302 Found\r\n"))
            assertTrue(response.contains("Location: https://focus.example\r\n"))
            assertEquals("", body)
        } finally {
            server.stop()
        }
    }

    @Test
    fun faviconAndExtraSpacesAreParsedAsGet() {
        val port = freePort()
        val server = RedirectServer({ "https://focus.example" }, port)
        server.start()
        try {
            val response = request(port, "GET   /favicon.ico   HTTP/1.1\r\nHost: blocked.example\r\n\r\n")
            assertTrue(response.startsWith("HTTP/1.1 302 Found\r\n"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun nonHttpMethod_returns405() {
        val port = freePort()
        val server = RedirectServer({ "https://focus.example" }, port)
        server.start()
        try {
            val response = request(port, "POST / HTTP/1.1\r\nHost: blocked.example\r\n\r\n")
            assertTrue(response.startsWith("HTTP/1.1 405 Method Not Allowed\r\n"))
            assertTrue(response.contains("Content-Length: 0\r\n"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun malformedInput_returns405() {
        val port = freePort()
        val server = RedirectServer({ "https://focus.example" }, port)
        server.start()
        try {
            val response = request(port, "not-http\r\n\r\n")
            assertTrue(response.startsWith("HTTP/1.1 405 Method Not Allowed\r\n"))
        } finally {
            server.stop()
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun request(port: Int, request: String): String {
        repeat(20) {
            try {
                Socket("127.0.0.1", port).use { socket ->
                    socket.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
                    socket.getOutputStream().flush()
                    val out = ByteArrayOutputStream()
                    socket.getInputStream().copyTo(out)
                    return out.toString(StandardCharsets.UTF_8.name())
                }
            } catch (_: java.net.ConnectException) {
                Thread.sleep(25)
            }
        }
        error("server did not accept a connection")
    }
}
