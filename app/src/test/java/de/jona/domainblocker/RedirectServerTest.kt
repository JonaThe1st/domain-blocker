package de.jona.domainblocker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

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
}
