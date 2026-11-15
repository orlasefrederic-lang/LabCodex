package lab.netstrazh

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import lab.netstrazh.data.Rules
import lab.netstrazh.net.Dns
import lab.netstrazh.ui.MainActivity
import lab.netstrazh.vpn.FirewallService
import lab.netstrazh.vpn.ProxyConfig
import lab.netstrazh.vpn.ProxyType
import lab.netstrazh.vpn.TunConfig
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Regression probes for the unchanged archive's hostname rules. All DNS answers are generated
 * in-process by FakeDnsProxy; it never opens an outbound socket. The address 1.1.1.1 appears only
 * as a SOCKS request target and is never contacted. No external DNS/network is needed.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DomainRegressionTest {
    companion object {
        private const val TAG = "NetStrazhDomainTest"
        private lateinit var ctx: Context
        private lateinit var proxy: FakeDnsProxy
        private var activity: ActivityScenario<MainActivity>? = null

        private fun shell(command: String): String {
            val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            return ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
        }

        private fun waitFor(timeoutMs: Long, description: String, check: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + timeoutMs
            while (SystemClock.uptimeMillis() < end) {
                if (check()) return
                Thread.sleep(150)
            }
            throw AssertionError("Timeout waiting for $description")
        }

        @BeforeClass @JvmStatic
        fun startVpn() {
            ctx = InstrumentationRegistry.getInstrumentation().targetContext
            Rules.set(ctx.packageName, 0)
            Rules.setBlockDns(false)
            Prefs.ipv6 = false
            proxy = FakeDnsProxy()
            Prefs.proxy = ProxyConfig(
                type = ProxyType.SOCKS5, host = "127.0.0.1", port = proxy.port,
                dnsViaProxy = true, dnsServer = "1.1.1.1", udpDirect = false,
                directIfDown = false, bypassApp = false, appPkg = "",
            )
            shell("appops set ${ctx.packageName} ACTIVATE_VPN allow")
            if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
            assertNull("VPN consent required", android.net.VpnService.prepare(ctx))
            activity = ActivityScenario.launch(MainActivity::class.java)
            activity!!.onActivity { FirewallService.start(it) }
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            waitFor(90_000, "VPN service and default VPN network") {
                FirewallService.running && cm.getNetworkCapabilities(cm.activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            waitFor(30_000, "controlled DNS proxy configured") {
                FirewallService.instance?.upstream?.cfg?.port == proxy.port
            }
        }

        @AfterClass @JvmStatic
        fun stopVpn() {
            if (::ctx.isInitialized) {
                Rules.set(ctx.packageName, 0)
                Rules.setBlockDns(false)
                Prefs.proxy = ProxyConfig.OFF
                InstrumentationRegistry.getInstrumentation().runOnMainSync { FirewallService.stop(ctx) }
            }
            activity?.close()
            activity = null
            if (::proxy.isInitialized) proxy.close()
        }
    }

    private fun query(name: String, id: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeShort(id)
            d.writeShort(0x0100)
            d.writeShort(1)
            repeat(3) { d.writeShort(0) }
            for (label in name.split('.')) {
                val b = label.toByteArray(Charsets.US_ASCII)
                d.writeByte(b.size)
                d.write(b)
            }
            d.writeByte(0)
            d.writeShort(1)
            d.writeShort(1)
        }
        return out.toByteArray()
    }

    private fun udpSocket(): DatagramSocket = DatagramSocket().apply {
        soTimeout = 10_000
        connect(InetSocketAddress(InetAddress.getByName(TunConfig.DNS4), 53))
    }

    private fun udp(socket: DatagramSocket, name: String, id: Int): ByteArray {
        val q = query(name, id)
        socket.send(DatagramPacket(q, q.size))
        val packet = DatagramPacket(ByteArray(4096), 4096)
        socket.receive(packet)
        val response = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
        assertTrue("DNS header required", response.size >= 12)
        assertEquals("response transaction ID", id, ((response[0].toInt() and 255) shl 8) or (response[1].toInt() and 255))
        return response
    }

    private fun tcp(name: String, id: Int): ByteArray {
        Socket().use { socket ->
            socket.soTimeout = 10_000
            socket.connect(InetSocketAddress(InetAddress.getByName(TunConfig.DNS4), 53), 10_000)
            val q = query(name, id)
            val out = DataOutputStream(socket.getOutputStream())
            out.writeShort(q.size)
            out.write(q)
            out.flush()
            val input = DataInputStream(socket.getInputStream())
            val response = ByteArray(input.readUnsignedShort())
            input.readFully(response)
            assertTrue("DNS header required", response.size >= 12)
            assertEquals("response transaction ID", id, ((response[0].toInt() and 255) shl 8) or (response[1].toInt() and 255))
            return response
        }
    }

    private fun rcode(b: ByteArray): Int = b[3].toInt() and 15

    private fun drainRuleChanges() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { }
    }

    private fun assertControlledAnswer(name: String, response: ByteArray) {
        assertEquals("controlled DNS response for $name", 0, rcode(response))
        assertTrue("fake proxy must have handled $name", proxy.queries.contains(name))
        assertTrue("fixed TEST-NET answer required", response.takeLast(4).map { it.toInt() and 255 } == listOf(203, 0, 113, 9))
    }

    @Test
    fun t01_blockedDnsMustNotPoisonOtherNamesOnSameUdpSocket() {
        val suffix = "${SystemClock.uptimeMillis()}.netstrazh.invalid"
        val blocked = "blocked.$suffix"
        val allowed = "allowed.$suffix"
        val control = "control.$suffix"
        Rules.setHostBlocked(Rules.ALL, blocked, true)
        try {
            udpSocket().use { assertControlledAnswer(control, udp(it, control, 0x6101)) }
            val second: ByteArray
            udpSocket().use { socket ->
                assertEquals("blocked hostname must be refused", 5, rcode(udp(socket, blocked, 0x6102)))
                assertFalse("blocked UDP query must not leave the firewall", proxy.queries.contains(blocked))
                second = udp(socket, allowed, 0x6103)
                Log.i(TAG, "same UDP socket: blocked=$blocked then allowed=$allowed, allowedRcode=${rcode(second)}, " +
                    "allowedForwarded=${proxy.queries.contains(allowed)}")
            }
            // Show that the allowed name and test resolver work when a fresh source port is used.
            udpSocket().use { assertControlledAnswer(allowed, udp(it, allowed, 0x6104)) }
            assertEquals("A hostname rule must not refuse an unrelated allowed name on the same UDP socket; " +
                "the same name resolves on a fresh socket", 0, rcode(second))
        } finally {
            Rules.setHostBlocked(Rules.ALL, blocked, false)
        }
    }

    @Test
    fun t02_blockedHostnameMustNotReachResolverOverTcpDns() {
        val suffix = "${SystemClock.uptimeMillis()}.netstrazh.invalid"
        val blocked = "tcp-blocked.$suffix"
        val control = "tcp-control.$suffix"
        Rules.setHostBlocked(Rules.ALL, blocked, true)
        try {
            assertControlledAnswer(control, tcp(control, 0x6201))
            udpSocket().use { assertEquals("rule is active for UDP DNS", 5, rcode(udp(it, blocked, 0x6202))) }
            assertFalse("blocked UDP did not reach resolver", proxy.queries.contains(blocked))
            var response: ByteArray? = null
            var refused: IOException? = null
            try { response = tcp(blocked, 0x6203) } catch (error: IOException) { refused = error }
            val forwarded = proxy.queries.contains(blocked)
            Log.i(TAG, "TCP DNS: name=$blocked forwarded=$forwarded rcode=${response?.let { rcode(it) }} " +
                "ioError=${refused?.javaClass?.simpleName}")
            assertFalse("Blocked hostname was sent to the controlled DNS resolver over TCP; " +
                "responseRcode=${response?.let { rcode(it) }}", forwarded)
            if (response != null) assertEquals("blocked TCP DNS response must be refused", 5, rcode(response))
            else assertNotNull("TCP DNS must either refuse/reset or return REFUSED", refused)
        } finally {
            Rules.setHostBlocked(Rules.ALL, blocked, false)
        }
    }

    @Test
    fun t03_existingPendingUdpDnsMustApplyNewAppDnsPolicy() {
        val suffix = "${SystemClock.uptimeMillis()}.netstrazh.invalid"
        val heldName = "held.$suffix"
        val blockedByApp = "after-policy.$suffix"
        val freshControl = "fresh-policy.$suffix"
        val warmup = "warmup.$suffix"
        val oldFlags = Rules.flags(ctx.packageName)
        val oldBlockDns = Rules.blockDns
        val hold = proxy.holdResponse(heldName)
        try {
            Rules.set(ctx.packageName, 0)
            Rules.setBlockDns(false)
            drainRuleChanges()
            udpSocket().use { assertControlledAnswer(warmup, udp(it, warmup, 0x6301)) }
            udpSocket().use { socket ->
                socket.soTimeout = 2_000
                val q = query(heldName, 0x6302)
                val heldAt = SystemClock.uptimeMillis()
                socket.send(DatagramPacket(q, q.size))
                assertTrue("first DNS query reached controlled resolver before policy change", hold.seen.await(2, TimeUnit.SECONDS))
                assertFalse("first response remains held", hold.sent)
                Rules.set(ctx.packageName, Rules.BLOCK_ALL)
                Rules.setBlockDns(true)
                drainRuleChanges()
                udpSocket().use { fresh ->
                    fresh.soTimeout = 1_000
                    assertEquals("new app/DNS policy is active on fresh flow", 5, rcode(udp(fresh, freshControl, 0x6303)))
                }
                assertFalse("fresh blocked query must not reach resolver", proxy.queries.contains(freshControl))
                assertFalse("existing flow still has a held first response", hold.sent)
                assertTrue("fixture must change policy before the 3-second pipe deadline",
                    SystemClock.uptimeMillis() - heldAt < 2_000)
                val response = udp(socket, blockedByApp, 0x6304)
                val forwarded = proxy.queries.contains(blockedByApp)
                Log.i(TAG, "pending UDP flow after app DNS policy change: name=$blockedByApp forwarded=$forwarded rcode=${rcode(response)}")
                assertFalse("new app/DNS policy must apply to existing UDP flow; responseRcode=${rcode(response)}", forwarded)
                assertEquals("existing UDP flow must return REFUSED under new app/DNS policy", 5, rcode(response))
            }
        } finally {
            hold.release.countDown()
            proxy.releaseResponse(heldName)
            Rules.set(ctx.packageName, oldFlags)
            Rules.setBlockDns(oldBlockDns)
            drainRuleChanges()
        }
    }

    @Test
    fun t04_existingRefusedUdpDnsMustApplyNewBlockDnsSetting() {
        val suffix = "${SystemClock.uptimeMillis()}.netstrazh.invalid"
        val refusedHost = "host-refused.$suffix"
        val blockedByApp = "new-app-policy.$suffix"
        val freshControl = "fresh-app-policy.$suffix"
        val oldFlags = Rules.flags(ctx.packageName)
        val oldBlockDns = Rules.blockDns
        try {
            Rules.set(ctx.packageName, Rules.BLOCK_ALL)
            Rules.setBlockDns(false)
            Rules.setHostBlocked(Rules.ALL, refusedHost, true)
            drainRuleChanges()
            udpSocket().use { socket ->
                assertEquals("hostname query creates locally refused flow", 5, rcode(udp(socket, refusedHost, 0x6401)))
                assertFalse("hostname-refused query must not leave firewall", proxy.queries.contains(refusedHost))
                Rules.setBlockDns(true)
                drainRuleChanges()
                udpSocket().use { fresh ->
                    assertEquals("block DNS setting applies on fresh flow", 5, rcode(udp(fresh, freshControl, 0x6402)))
                }
                assertFalse("fresh app-refused query must not leave firewall", proxy.queries.contains(freshControl))
                val response = udp(socket, blockedByApp, 0x6403)
                val forwarded = proxy.queries.contains(blockedByApp)
                Log.i(TAG, "locally refused UDP flow after enabling blockDns: name=$blockedByApp forwarded=$forwarded rcode=${rcode(response)}")
                assertFalse("enabling blockDns must prevent an existing NOPATH flow from starting an upstream path; " +
                    "responseRcode=${rcode(response)}", forwarded)
                assertEquals("existing locally refused flow must honor newly enabled blockDns", 5, rcode(response))
            }
        } finally {
            Rules.setHostBlocked(Rules.ALL, refusedHost, false)
            Rules.set(ctx.packageName, oldFlags)
            Rules.setBlockDns(oldBlockDns)
            drainRuleChanges()
        }
    }

    /** Sends one frame in separated writes on an existing TCP connection. */
    private fun tcpFragmented(socket: Socket, name: String, id: Int): ByteArray {
        val q = query(name, id)
        val out = socket.getOutputStream()
        // TCP may coalesce writes, but TCP_NODELAY plus pauses gives the reader an opportunity to
        // encounter the single-byte length prefix and incomplete body before their remainders arrive.
        out.write(q.size ushr 8)
        out.flush()
        Thread.sleep(40)
        out.write(q.size and 255)
        out.flush()
        Thread.sleep(40)
        val split = q.size / 2
        out.write(q, 0, split)
        out.flush()
        Thread.sleep(40)
        out.write(q, split, q.size - split)
        out.flush()
        val input = DataInputStream(socket.getInputStream())
        val response = ByteArray(input.readUnsignedShort())
        input.readFully(response)
        assertTrue("DNS header required for $name", response.size >= 12)
        assertEquals("response transaction ID for $name", id,
            ((response[0].toInt() and 255) shl 8) or (response[1].toInt() and 255))
        assertTrue("DNS response bit required for $name", response[2].toInt() and 0x80 != 0)
        assertEquals("response question for $name", name, Dns.parse(response, 0, response.size)?.qname)
        return response
    }

    @Test
    fun t05_tcpDnsAppliesHostRulesToFragmentedFramesOnPersistentConnection() {
        val suffix = "${SystemClock.uptimeMillis()}.netstrazh.invalid"
        val blockedParent = "tcp-fragment-blocked.$suffix"
        val firstBlocked = "first.$blockedParent"
        val lastBlocked = "last.$blockedParent"
        val allowed = "tcp-fragment-allowed.$suffix"
        Rules.setHostBlocked(Rules.ALL, blockedParent, true)
        try {
            Socket().use { socket ->
                socket.soTimeout = 10_000
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(InetAddress.getByName(TunConfig.DNS4), 53), 10_000)
                val first = tcpFragmented(socket, firstBlocked, 0x6501)
                assertEquals("first blocked hostname is refused on the persistent TCP connection", 5, rcode(first))
                assertFalse("first blocked hostname must not reach the controlled resolver", proxy.queries.contains(firstBlocked))

                val second = tcpFragmented(socket, allowed, 0x6502)
                assertControlledAnswer(allowed, second)

                val third = tcpFragmented(socket, lastBlocked, 0x6503)
                assertEquals("later blocked hostname remains refused after an allowed response", 5, rcode(third))
                assertFalse("later blocked hostname must not reach the controlled resolver", proxy.queries.contains(lastBlocked))
                // Compare unique test names, so earlier fixture traffic cannot affect this assertion.
                val observed = proxy.queries.filter { it == firstBlocked || it == allowed || it == lastBlocked }
                Log.i(TAG, "persistent fragmented TCP DNS: firstRcode=${rcode(first)}, allowedRcode=${rcode(second)}, " +
                    "lastRcode=${rcode(third)}, observed=$observed")
                assertEquals("only the allowed hostname reaches the controlled resolver", listOf(allowed), observed)
            }
        } finally {
            Rules.setHostBlocked(Rules.ALL, blockedParent, false)
        }
    }

    /** Minimal SOCKS5 + DNS-over-TCP fixture. No outgoing connections, forwarding, or credentials. */
    private class FakeDnsProxy : Closeable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = listener.localPort
        val queries = CopyOnWriteArrayList<String>()
        class ResponseHold {
            val seen = CountDownLatch(1)
            val release = CountDownLatch(1)
            @Volatile var sent = false
        }
        private val responseHolds = ConcurrentHashMap<String, ResponseHold>()
        fun holdResponse(name: String): ResponseHold = ResponseHold().also { responseHolds[name] = it }
        fun releaseResponse(name: String) { responseHolds.remove(name)?.release?.countDown() }
        private val clients = ConcurrentHashMap.newKeySet<Socket>()
        private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(16),
            { r -> Thread(r, "ns-fake-dns-worker").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
        @Volatile private var closed = false
        private val acceptor = thread(name = "ns-fake-dns-accept", isDaemon = true) {
            while (!closed) {
                val client = try { listener.accept() } catch (_: IOException) { break }
                clients.add(client)
                try { workers.execute { serve(client) } }
                catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(client); client.close() }
            }
        }

        private fun serve(client: Socket) {
            try {
                client.use { socket ->
                    socket.soTimeout = 20_000
                    val input = DataInputStream(socket.getInputStream())
                    val out = DataOutputStream(socket.getOutputStream())
                    if (input.readUnsignedByte() != 5) return
                    val methods = ByteArray(input.readUnsignedByte())
                    input.readFully(methods)
                    if (methods.none { it.toInt() == 0 }) return
                    out.write(byteArrayOf(5, 0)); out.flush()
                    if (input.readUnsignedByte() != 5) return
                    val command = input.readUnsignedByte()
                    input.readUnsignedByte()
                    val address = when (input.readUnsignedByte()) {
                        1 -> ByteArray(4).also { input.readFully(it) }
                        4 -> ByteArray(16).also { input.readFully(it) }
                        3 -> ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                        else -> return
                    }
                    val targetPort = input.readUnsignedShort()
                    val allowed = command == 1 && targetPort == 53 && address.contentEquals(byteArrayOf(1, 1, 1, 1))
                    out.write(byteArrayOf(5, if (allowed) 0 else 7, 0, 1, 127, 0, 0, 1, 0, 53)); out.flush()
                    if (!allowed) return
                    while (!closed) {
                        val length = try { input.readUnsignedShort() } catch (_: EOFException) { break }
                        if (length !in 12..4096) return
                        val q = ByteArray(length)
                        input.readFully(q)
                        val parsed = Dns.parse(q, 0, q.size) ?: return
                        val name = parsed.qname ?: return
                        queries.add(name)
                        // Tests issue one A/IN question, without extra sections.
                        val answer = q.copyOf()
                        answer[2] = 0x81.toByte(); answer[3] = 0x80.toByte()
                        answer[6] = 0; answer[7] = 1
                        answer[8] = 0; answer[9] = 0; answer[10] = 0; answer[11] = 0
                        val rr = byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4,
                            203.toByte(), 0, 113, 9)
                        fun respond() {
                            synchronized(out) {
                                out.writeShort(answer.size + rr.size)
                                out.write(answer); out.write(rr); out.flush()
                            }
                        }
                        val hold = responseHolds[name]
                        if (hold == null) {
                            respond()
                        } else {
                            hold.seen.countDown()
                            // Keep reading this pipelined connection while this one response is held.
                            // The wait is bounded, and every test releases its hold in finally.
                            workers.execute {
                                try {
                                    if (hold.release.await(12, TimeUnit.SECONDS) && !closed) {
                                        respond()
                                        hold.sent = true
                                    }
                                } catch (_: IOException) {
                                } catch (_: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                }
                            }
                        }
                    }
                }
            } catch (_: IOException) {
                // UID probes connect and close without speaking SOCKS; shutdown also closes sockets.
            } finally {
                clients.remove(client)
            }
        }

        override fun close() {
            closed = true
            for (hold in responseHolds.values) hold.release.countDown()
            responseHolds.clear()
            listener.close()
            for (client in clients) try { client.close() } catch (_: IOException) {}
            workers.shutdownNow()
            acceptor.join(1000)
        }
    }
}
