package lab.netstrazh

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import lab.netstrazh.data.Db
import lab.netstrazh.data.Rules
import lab.netstrazh.ui.MainActivity
import lab.netstrazh.vpn.FirewallService
import lab.netstrazh.vpn.Live
import lab.netstrazh.vpn.ProxyConfig
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Real VPN relay tests using only the HTTP/UDP fixture on the emulator host. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LocalRuntimeTest {
    companion object {
        private lateinit var runtime: RuntimeFixture

        @BeforeClass @JvmStatic
        fun startVpn() {
            runtime = RuntimeFixture()
            runtime.start(ProxyConfig.OFF)
        }

        @AfterClass @JvmStatic
        fun stopVpn() {
            if (::runtime.isInitialized) runtime.close()
        }
    }

    @After
    fun restorePolicy() = runtime.allowTestApp()

    @Test
    fun t01_localHttpIsRelayedAndAttributed() {
        val since = runtime.mark()
        val response = runtime.requireHttp(RuntimeFixture.LAN, "direct-http")
        runtime.expectSeen(since, RuntimeFixture.LAN, "attributed direct TCP payload") {
            it.proto == "TCP" && it.port == RuntimeFixture.HTTP_PORT && !it.blocked &&
                it.tx > 0 && it.rx >= response.length && it.via == ""
        }
    }

    @Test
    fun t02_localUdpIsRelayedAndAttributed() {
        val since = runtime.mark()
        runtime.requireUdp(RuntimeFixture.LAN, "direct-udp")
        runtime.expectSeen(since, RuntimeFixture.LAN, "attributed direct UDP payload") {
            it.proto == "UDP" && it.port == RuntimeFixture.UDP_PORT && !it.blocked &&
                it.tx > 0 && it.rx > 0 && it.via == ""
        }
    }

    @Test
    fun t03_tcpRuleBlocksAndUnblockingRestoresTraffic() =
        runtime.assertTcpBlockAndUnblock(RuntimeFixture.LAN, "")

    @Test
    fun t04_udpRuleBlocksAndUnblockingRestoresTraffic() =
        runtime.assertUdpBlockAndUnblock(RuntimeFixture.LAN, "")

    @Test
    fun t05_powerToggleStopsAndRestartsProtection() {
        val firstStartedAt = Live.startedAt
        runtime.onActivity { it.onPowerToggle(false) }
        runtime.awaitStopped()
        assertFalse("power off must clear the saved enabled preference", Prefs.enabled)
        runtime.requireHttp(RuntimeFixture.LAN, "while-power-off")
        runtime.requireUdp(RuntimeFixture.LAN, "while-power-off")
        runtime.onActivity { it.onPowerToggle(true) }
        runtime.awaitRunning()
        assertTrue("power on must save the enabled preference", Prefs.enabled)
        assertTrue("power on must create a new engine", Live.startedAt > firstStartedAt)
        val since = runtime.mark()
        val response = runtime.requireHttp(RuntimeFixture.LAN, "after-power-on")
        runtime.expectSeen(since, RuntimeFixture.LAN, "TCP captured after UI power cycle") {
            it.proto == "TCP" && it.port == RuntimeFixture.HTTP_PORT && !it.blocked &&
                it.rx >= response.length && it.via == ""
        }
    }
}

/** Shared only by these outside-source tests; application code remains unchanged. */
internal class RuntimeFixture {
    data class Seen(
        val started: Long, val uid: Int, val proto: String, val ip: String, val port: Int,
        val tx: Long, val rx: Long, val blocked: Boolean, val via: String,
    )

    val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    val myUid: Int = Process.myUid()
    private var activity: ActivityScenario<MainActivity>? = null
    private val snapshots = listOf("prefs", "rules", "hostrules").associateWith { name ->
        ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()
    }

    fun start(proxy: ProxyConfig) {
        // The emulator is dedicated to this review. Restore every saved entry in close().
        InstrumentationRegistry.getInstrumentation().runOnMainSync { FirewallService.stop(ctx) }
        waitFor(30_000, "previous service destroyed") { FirewallService.instance == null }
        for (name in listOf("rules", "hostrules")) {
            assertTrue(ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
        }
        Rules.reload()
        Prefs.enabled = false
        Prefs.proxy = proxy
        Prefs.ipv6 = false
        shell("appops set ${ctx.packageName} ACTIVATE_VPN allow")
        if (Build.VERSION.SDK_INT >= 33) {
            shell("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
        }
        assertNull("VPN consent must be granted", VpnService.prepare(ctx))
        activity = ActivityScenario.launch(MainActivity::class.java)
        onActivity { it.onPowerToggle(true) }
        try {
            awaitRunning()
        } catch (failure: Throwable) {
            throw AssertionError("VPN startup failed\n" +
                shell("logcat -d -t 200 -s NetStrazh:V AndroidRuntime:E Vpn:V"), failure)
        }
    }

    fun close() {
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { FirewallService.stop(ctx) }
            awaitStopped()
        } finally {
            try { activity?.close() } finally {
                activity = null
                for ((name, values) in snapshots) {
                    val editor = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                    for ((key, value) in values) {
                        when (value) {
                            is Boolean -> editor.putBoolean(key, value)
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is Float -> editor.putFloat(key, value)
                            is String -> editor.putString(key, value)
                            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                        }
                    }
                    assertTrue("restore $name", editor.commit())
                }
                Rules.reload()
            }
        }
    }

    fun onActivity(action: (MainActivity) -> Unit) {
        val scenario = checkNotNull(activity)
        // AndroidJUnitRunner finishes activities between test methods, while our
        // class-level VPN fixture intentionally remains alive. Reopen only for
        // that explicit terminal lifecycle state; propagate all launch/action
        // failures so application crashes cannot be mistaken for this cleanup.
        if (scenario.state == Lifecycle.State.DESTROYED) {
            Log.i(TAG, "Reopening MainActivity after test-runner activity cleanup")
            scenario.close()
            activity = ActivityScenario.launch(MainActivity::class.java)
        }
        checkNotNull(activity).onActivity { action(it) }
    }

    private fun defaultIsVpn(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    fun awaitRunning() = waitFor(90_000, "running VPN as the default network") {
        FirewallService.running && FirewallService.instance?.upstream != null && defaultIsVpn()
    }

    fun awaitStopped() = waitFor(30_000, "VPN stopped and default network released") {
        !FirewallService.running && FirewallService.instance == null && !defaultIsVpn()
    }

    fun allowTestApp() = Rules.set(ctx.packageName, 0)

    fun mark(): Long {
        // Keep preceding connections outside a new assertion's millisecond timestamp window.
        Thread.sleep(2)
        return System.currentTimeMillis()
    }

    private fun seen(): List<Seen> = Live.active.values.map {
        Seen(it.start, it.uid, it.proto, it.ip, it.port, it.tx, it.rx, it.blocked, it.via)
    } + Db.recent(Db.F_ALL, null, null, 1500).map {
        Seen(it.ts, it.uid, it.proto, it.ip, it.port, it.tx, it.rx, it.blocked, it.via)
    }

    fun expectSeen(since: Long, host: String, description: String, match: (Seen) -> Boolean): Seen {
        var found: Seen? = null
        waitFor(30_000, description) {
            found = seen().firstOrNull {
                it.started >= since && it.uid == myUid && it.ip == host && match(it)
            }
            found != null
        }
        Log.i(TAG, "$description: $found")
        return checkNotNull(found)
    }

    private fun token(label: String) = "$label-${SystemClock.uptimeMillis()}"

    private fun http(host: String, marker: String): String = Socket().use { socket ->
        socket.soTimeout = 15_000
        socket.connect(InetSocketAddress(InetAddress.getByName(host), HTTP_PORT), 15_000)
        val request = "GET /echo/$marker HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
        socket.getInputStream().readBytes().toString(Charsets.US_ASCII)
    }

    fun requireHttp(host: String, label: String): String {
        val marker = token(label)
        val response = http(host, marker)
        assertTrue("HTTP 200 required: ${response.take(160)}", response.startsWith("HTTP/1.1 200"))
        assertEquals("netstrazh-http:$marker", response.substringAfter("\r\n\r\n", ""))
        return response
    }

    private fun udp(host: String, marker: String, timeoutMs: Int = 15_000): String? {
        val request = "ns-test:$marker".toByteArray(Charsets.US_ASCII)
        return DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(InetAddress.getByName(host), UDP_PORT))
            socket.send(DatagramPacket(request, request.size))
            val packet = DatagramPacket(ByteArray(2048), 2048)
            try {
                socket.receive(packet)
                String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
            } catch (_: SocketTimeoutException) {
                null
            }
        }
    }

    fun requireUdp(host: String, label: String) {
        val marker = token(label)
        assertEquals("netstrazh-udp:ns-test:$marker", udp(host, marker))
    }

    fun assertTcpBlockAndUnblock(host: String, via: String) {
        requireHttp(host, "tcp-before-block")
        Rules.set(ctx.packageName, Rules.BLOCK_ALL)
        try {
            val since = mark()
            val response = try { http(host, token("tcp-must-block")) } catch (_: IOException) { "" }
            assertEquals("blocked TCP must deliver no fixture response", "", response)
            expectSeen(since, host, "TCP rejected by actual app rule") {
                it.proto == "TCP" && it.port == HTTP_PORT && it.blocked && it.rx == 0L
            }
        } finally {
            allowTestApp()
        }
        val since = mark()
        val response = requireHttp(host, "tcp-after-unblock")
        expectSeen(since, host, "TCP allowed and attributed after unblock") {
            it.proto == "TCP" && it.port == HTTP_PORT && !it.blocked &&
                it.rx >= response.length && it.via == via
        }
    }

    fun assertUdpBlockAndUnblock(host: String, via: String) {
        requireUdp(host, "udp-before-block")
        Rules.set(ctx.packageName, Rules.BLOCK_ALL)
        try {
            val since = mark()
            assertNull("blocked UDP must deliver no fixture response", udp(host, token("udp-must-block"), 3000))
            expectSeen(since, host, "UDP rejected by actual app rule") {
                it.proto == "UDP" && it.port == UDP_PORT && it.blocked && it.rx == 0L
            }
        } finally {
            allowTestApp()
        }
        val since = mark()
        requireUdp(host, "udp-after-unblock")
        expectSeen(since, host, "UDP allowed and attributed after unblock") {
            it.proto == "UDP" && it.port == UDP_PORT && !it.blocked && it.rx > 0 && it.via == via
        }
    }

    companion object {
        const val TAG = "NetStrazhRuntimeTest"
        const val LAN = "10.0.2.2"
        const val HTTP_PORT = 18080
        const val UDP_PORT = 18081

        fun shell(command: String): String {
            val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            return ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
        }

        fun waitFor(timeoutMs: Long, description: String, check: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (SystemClock.uptimeMillis() < deadline) {
                if (check()) return
                Thread.sleep(150)
            }
            fail("timeout waiting for $description")
        }
    }
}
