package de.jona.domainblocker

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tiny loopback HTTP server on 127.0.0.1:80. Blocked domains resolve to
 * 127.0.0.1, so a plain-HTTP request to a blocked site lands here and gets a
 * 302 redirect to the configured "focus" page. HTTPS connections to a blocked
 * domain reach 127.0.0.1:443, where nothing listens -> connection refused
 * (blocked, but not redirectable without a MITM certificate).
 */
class RedirectServer(
    private val redirectUrlProvider: () -> String,
    private val port: Int = 80
) {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread {
            var server: ServerSocket? = null
            try {
                server = ServerSocket(port, 8, java.net.InetAddress.getByName("127.0.0.1"))
                while (running.get()) {
                    try {
                        handle(server!!.accept())
                    } catch (_: SocketException) {
                        break
                    } catch (_: SocketTimeoutException) {
                        // keep looping
                    } catch (_: IOException) {
                        // per-connection errors are non-fatal
                    }
                }
            } catch (_: IOException) {
                // Port 80 unavailable -> redirects silently disabled, blocking
                // (DNS rewrite) still works.
            } finally {
                try {
                    server?.close()
                } catch (_: IOException) {
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    /** Test/diagnostic helper: whether the accept loop was entered. */
    fun isRunning(): Boolean = running.get()

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 3000
            val input: InputStream = BufferedInputStream(s.getInputStream())
            val output: OutputStream = s.getOutputStream()

            var line = readLine(input) ?: return
            if (line.isEmpty()) line = readLine(input) ?: return
            val requestLine = line.trim().split(Regex("\\s+"), limit = 3)
            if (requestLine.size < 2 || requestLine[0].uppercase() !in setOf("GET", "HEAD")) {
                output.write(
                    "HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray()
                )
                return
            }

            val redirect = redirectUrlProvider()
            val body = buildString {
                append("<html><head><meta charset=\"utf-8\"><title>Blockiert</title>")
                append("<meta http-equiv=\"refresh\" content=\"0; url=$redirect\">")
                append("</head><body style=\"font-family:sans-serif;text-align:center;margin-top:15vh\">")
                append("<h1>Diese Seite ist blockiert.</h1>")
                append("<p>Weiterleitung auf: <a href=\"$redirect\">$redirect</a></p>")
                append("</body></html>")
            }
            val head = buildString {
                append("HTTP/1.1 302 Found\r\n")
                append("Location: $redirect\r\n")
                append("Content-Type: text/html; charset=utf-8\r\n")
                append("Content-Length: ").append(body.toByteArray().size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(head.toByteArray())
            if (requestLine[0].uppercase() == "GET") output.write(body.toByteArray())
            output.flush()
        }
    }

    /** Reads one CRLF-terminated line of the HTTP request head. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code && prev == '\r'.code) {
                if (sb.isEmpty()) return ""
                return sb.toString()
            }
            if (c != '\r'.code) sb.append(c.toChar())
            prev = c
        }
    }
}
