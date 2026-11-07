package de.jona.domainblocker

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
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
    private val domainsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DomainStore.ACTION_DOMAINS_CHANGED) {
                DomainStore.reload(context)
            }
        }
    }
    // The tunnel read loop must NEVER block on a synchronous relay, or one
    // slow real-DNS server stalls every other lookup (and can make the app
    // look dead). Relays run on worker threads; replies are written to the
    // TUN file descriptor from there (short writes are thread-safe).
    private val relayPool = Executors.newCachedThreadPool()

    override fun onCreate() {
        super.onCreate()
        isRunning = false
        DomainStore.init(this)
        registerReceiver(domainsChangedReceiver, IntentFilter(DomainStore.ACTION_DOMAINS_CHANGED))
        CrashLog.write(this, "== service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CrashLog.write(this, "onStartCommand intent=$intent running=${running.get()}")
        if (intent?.action == ACTION_STOP) {
            DomainStore.setFilterEnabled(false)
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundSafe()
        if (running.compareAndSet(false, true)) {
            isRunning = true
            val server = RedirectServer({ DomainStore.redirectUrl() })
            server.start()
            redirectServer = server
            Thread { runTunnel() }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        CrashLog.write(this, "onDestroy filterEnabled=${DomainStore.isFilterEnabled()} running=${running.get()}")
        stopTunnel()
        unregisterReceiver(domainsChangedReceiver)
        super.onDestroy()
        // Self-heal: if the tunnel died without the user switching the
        // filter off, bring the service back in a few seconds. This covers
        // Android/Samsung killing the service while the user wants it on.
        if (DomainStore.isFilterEnabled()) {
            scheduleRestart()
        }
    }

    private fun scheduleRestart() {
        try {
            val am = getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getService(
                this, 0, Intent(this, BlockVpnService::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RESTART_DELAY_MS, pi)
        } catch (_: Exception) {
        }
    }

    override fun onRevoke() {
        CrashLog.write(this, "onRevoke — user revoked VPN permission")
        DomainStore.setFilterEnabled(false)
        stopSelf()
        super.onRevoke()
    }

    private fun stopTunnel() {
        running.set(false)
        isRunning = false
        redirectServer?.stop()
        redirectServer = null
        tunFd?.close()
        tunFd = null
        relayPool.shutdownNow()
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
                isRunning = false
                stopSelf()
                return
            }
            tunFd = fd
            isRunning = true
            val input = FileInputStream(fd.fileDescriptor)
            val output = FileOutputStream(fd.fileDescriptor)
            val buffer = ByteArray(65_535)

            while (running.get()) {
                val len = input.read(buffer)
                if (len < 0) break
                handlePacket(buffer, len, output)
            }
        } catch (t: Exception) {
            // Tunnel teardown / revoked permission. Record why, so a
            // recurring kill is visible in the UI (crash.log).
            CrashLog.write(this, "tunnel error: $t")
        } finally {
            CrashLog.write(this, "tunnel exit running=${running.get()} filterEnabled=${DomainStore.isFilterEnabled()}")
            running.set(false)
            isRunning = false
            redirectServer?.stop()
            tunFd?.close()
            tunFd = null
            stopSelf()
        }
    }

    /**
     * Dispatches one IPv4 packet. DNS queries addressed to a routed DNS
     * server are answered (blocked) or relayed; any other packet that somehow
     * reaches the tunnel is dropped. This method must never throw: a single
     * malformed packet must not kill the service.
     */
    private fun handlePacket(buf: ByteArray, len: Int, output: FileOutputStream) {
        try {
            if (len < 20) return
            val version = (buf[0].toInt() ushr 4) and 0x0F
            val ihl = (buf[0].toInt() and 0x0F) * 4
            val totalLen = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            if (version != 4 || ihl < 20 || totalLen < ihl + 8 || totalLen > len) return

            val protocol = buf[9].toInt() and 0xFF
            if (protocol != DnsCodec.PROTO_UDP) return // only DNS (UDP) is routed/captured

            val srcIp = DnsCodec.readIp(buf, 12)
            val dstIp = DnsCodec.readIp(buf, 16)

            val udpLen = ((buf[ihl + 4].toInt() and 0xFF) shl 8) or (buf[ihl + 5].toInt() and 0xFF)
            if (udpLen < 8 || ihl + udpLen > totalLen) return
            val srcPort = ((buf[ihl].toInt() and 0xFF) shl 8) or (buf[ihl + 1].toInt() and 0xFF)
            val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
            if (dstPort != 53) return

            val payload = buf.copyOfRange(ihl + 8, ihl + udpLen)
            val parsed = DnsCodec.parseQuestion(payload) ?: return
            if (DomainStore.isBlocked(parsed.domain)) {
                val dns = DnsCodec.blockedReply(payload, parsed)
                val reply = DnsCodec.wrapIp(dns, dstIp, srcIp, 53, srcPort)
                try {
                    output.write(reply)
                } catch (_: Exception) {
                    // Tunnel write failed — drop the packet, keep reading.
                }
            } else {
                // Never block the tunnel read loop on a network relay. Real
                // DNS servers can be slow or unreachable; a synchronous relay
                // here would stall every other lookup. Relay happens on a
                // worker thread; the reply is written to the tunnel from there.
                relayDnsAsync(output, payload, dstIp, srcIp, srcPort, dstPort)
            }
        } catch (t: Throwable) {
            // Malformed packet or transient error: drop it, keep the tunnel alive.
            CrashLog.write(this, "handlePacket dropped: $t")
        }
    }

    /** Asynchronous fan-out relay for one real DNS query. */
    private fun relayDnsAsync(
        output: FileOutputStream,
        query: ByteArray,
        dnsIp: String,
        clientIp: String,
        clientPort: Int,
        dnsPort: Int
    ) {
        if (!running.get()) return
        try {
            relayPool.execute {
                try {
                    for (target in arrayOf(dnsIp, DNS_1, DNS_2, DNS_3)) {
                        if (!running.get()) return@execute
                        val reply = relayDnsTo(query, target, clientIp, clientPort, dnsPort)
                        if (reply != null) {
                            try {
                                output.write(reply)
                            } catch (_: Exception) {
                            }
                            return@execute
                        }
                    }
                } catch (_: Exception) {
                    // Relay failure — this DNS challenge is simply unresolved.
                }
            }
        } catch (_: Exception) {
            // Pool rejected — drop rather than block the tunnel loop.
        }
    }

    /**
     * Sends one DNS query to a real server via a protect()ed socket and
     * wraps the response for the tunnel. Returns null when the server
     * cannot be reached or times out.
     */
    private fun relayDnsTo(query: ByteArray, dnsIp: String, clientIp: String, clientPort: Int, dnsPort: Int): ByteArray? {
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
                DnsCodec.wrapIp(pkt.data.copyOfRange(0, pkt.length), dnsIp, clientIp, 53, clientPort)
            } finally {
                socket.close()
            }
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Foreground notification
    // ------------------------------------------------------------------

    /** API 34+ requires the manifest-declared foreground service type. */
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
                // Give up silently; Android will reap the service, and the
                // self-heal alarm brings it back when the user wants it on.
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
        const val ACTION_STOP = "de.jona.domainblocker.action.STOP"

        @Volatile
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ID = "blocker_status"
        private const val NOTIF_ID = 1

        // Routed DNS servers (IPv4 only on purpose — see README limitations).
        private const val DNS_1 = "8.8.8.8"
        private const val DNS_2 = "1.1.1.1"
        private const val DNS_3 = "9.9.9.9"

        private const val RESTART_DELAY_MS = 5000L
    }
}
