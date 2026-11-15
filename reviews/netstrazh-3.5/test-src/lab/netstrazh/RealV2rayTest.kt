package lab.netstrazh

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import lab.netstrazh.vpn.FirewallService
import lab.netstrazh.vpn.Live
import lab.netstrazh.vpn.ProxyConfig
import lab.netstrazh.vpn.ProxyType
import lab.netstrazh.vpn.Upstream
import lab.netstrazh.vpn.VIA_PROXY
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Requires official v2rayNG 2.3.11 already running in proxy-only mode with the
 * review's v2rayNG-fixture-profile.json. Only the two TEST-NET destination ports
 * are mapped to the host fixture; every other proxy destination is blackholed.
 * Payload assertions therefore exercise the external Xray core. No remote VPN
 * server, public HTTP service, DNS query, or synthetic in-process proxy is used.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RealV2rayTest {
    companion object {
        private const val PROXY_PACKAGE = "com.v2ray.ang"
        private const val DESTINATION = "198.51.100.10"
        private lateinit var runtime: RuntimeFixture
        private var externalUid = -1
        private val proxy = ProxyConfig(
            type = ProxyType.SOCKS5,
            host = "127.0.0.1",
            port = 10808,
            dnsViaProxy = false,
            udpDirect = false,
            directIfDown = false,
            appPkg = PROXY_PACKAGE,
            bypassApp = true,
        )

        @BeforeClass @JvmStatic
        fun startVpn() {
            runtime = RuntimeFixture()
            @Suppress("DEPRECATION")
            val installed = runtime.ctx.packageManager.getPackageInfo(PROXY_PACKAGE, 0)
            assertEquals("official fixture version", "2.3.11", installed.versionName)
            externalUid = runtime.ctx.packageManager.getApplicationInfo(PROXY_PACKAGE, 0).uid
            assertTrue("proxy must be a separate app UID", externalUid >= 0 && externalUid != runtime.myUid)
            runtime.start(proxy)
            awaitProxyReady()
        }

        @AfterClass @JvmStatic
        fun stopVpn() {
            if (::runtime.isInitialized) runtime.close()
        }

        private fun appliedProxyExclusion(): Boolean {
            val service = FirewallService.instance ?: return false
            val excluded = FirewallService::class.java.getDeclaredField("appliedExcluded")
                .apply { isAccessible = true }.get(service) as Set<*>
            return PROXY_PACKAGE in excluded
        }

        private fun proxyDiagnostic(): String {
            val up = FirewallService.instance?.upstream
            val cfg = up?.cfg
            return "running=${FirewallService.running}, endpoint=${cfg?.type}:${cfg?.host}:${cfg?.port}, " +
                "configuredApp=${cfg?.appPkg}, bypass=${cfg?.bypassApp}, appliedExcluded=${appliedProxyExclusion()}, " +
                "observedUid=${up?.proxyUid}, installedUid=$externalUid, lastError=${up?.lastError}"
        }

        private fun awaitProxyReady(expected: ProxyConfig = proxy, requireVisibleOwner: Boolean = false) {
            runtime.awaitRunning()
            // Always read the current engine: proxy discovery can rebuild the VPN.
            try {
                RuntimeFixture.waitFor(60_000, "v2rayNG listener and expected app exclusion") {
                    val current = FirewallService.instance?.upstream
                    current?.ensureProxyUid()
                    // Android 10's getConnectionOwnerUid intentionally hides UIDs
                    // excluded from the caller's VPN, so UID_NONE is expected after
                    // bypass=true. Exact owner identity is tested while included.
                    val ownerReady = current?.proxyUid == externalUid ||
                        (!requireVisibleOwner && expected.bypassApp && current?.proxyUid == Upstream.UID_NONE)
                    FirewallService.running && Prefs.proxy == expected && current != null &&
                        current === FirewallService.instance?.upstream && current.cfg == expected &&
                        appliedProxyExclusion() == expected.bypassApp && ownerReady
                }
            } catch (failure: AssertionError) {
                Log.e(RuntimeFixture.TAG, "v2rayNG readiness failed: ${proxyDiagnostic()}")
                throw AssertionError("${failure.message}\n${proxyDiagnostic()}", failure)
            }
            Log.i(RuntimeFixture.TAG, "v2rayNG ready: ${proxyDiagnostic()}")
        }

        private fun assertExcludedProxyConfiguration() {
            assertEquals("proxy endpoint and safety settings", proxy, FirewallService.instance?.upstream?.cfg)
            assertEquals("persisted proxy package", PROXY_PACKAGE, Prefs.proxy.appPkg)
            assertTrue("actual VPN configuration must exclude the proxy application", appliedProxyExclusion())
        }
    }

    @Test
    fun t00_realProxyOwnerIsIdentifiedBeforeExclusion() {
        // AOSP Android 10 only exposes connection owners covered by this VPN.
        // Keep this phase separate so a detection failure does not suppress the
        // actual data-plane tests using the explicitly selected proxy package.
        Prefs.proxy = proxy.copy(bypassApp = false)
        FirewallService.applyProxy()
        try {
            awaitProxyReady(proxy.copy(bypassApp = false), requireVisibleOwner = true)
            assertEquals("included listener must belong to official v2rayNG", externalUid,
                FirewallService.instance?.upstream?.proxyUid)
        } finally {
            Prefs.proxy = proxy
            FirewallService.applyProxy()
            awaitProxyReady()
        }
        assertExcludedProxyConfiguration()
    }

    @After
    fun restorePolicy() {
        runtime.allowTestApp()
        if (Prefs.proxy != proxy) {
            Prefs.proxy = proxy
            FirewallService.applyProxy()
            awaitProxyReady()
        }
    }

    @Test
    fun t01_realV2rayCarriesTcpAndPreservesAppAttribution() {
        val since = runtime.mark()
        val response = runtime.requireHttp(DESTINATION, "v2ray-http")
        runtime.expectSeen(since, DESTINATION, "TCP through actual v2rayNG with app UID and payload") {
            it.proto == "TCP" && it.port == RuntimeFixture.HTTP_PORT && !it.blocked &&
                it.tx > 0 && it.rx >= response.length && it.via == VIA_PROXY
        }
        assertExcludedProxyConfiguration()
    }

    @Test
    fun t02_realV2rayCarriesUdpAndPreservesAppAttribution() {
        val since = runtime.mark()
        runtime.requireUdp(DESTINATION, "v2ray-udp")
        runtime.expectSeen(since, DESTINATION, "UDP through actual v2rayNG with app UID and payload") {
            it.proto == "UDP" && it.port == RuntimeFixture.UDP_PORT && !it.blocked &&
                it.tx > 0 && it.rx > 0 && it.via == VIA_PROXY
        }
        assertExcludedProxyConfiguration()
    }

    @Test
    fun t03_lanTcpAndUdpBypassConfiguredProxy() {
        assertEquals(proxy, FirewallService.instance?.upstream?.cfg)
        val tcpSince = runtime.mark()
        val response = runtime.requireHttp(RuntimeFixture.LAN, "v2ray-lan-http")
        runtime.expectSeen(tcpSince, RuntimeFixture.LAN, "LAN TCP remains direct with proxy enabled") {
            it.proto == "TCP" && it.port == RuntimeFixture.HTTP_PORT && !it.blocked &&
                it.rx >= response.length && it.via == ""
        }
        val udpSince = runtime.mark()
        runtime.requireUdp(RuntimeFixture.LAN, "v2ray-lan-udp")
        runtime.expectSeen(udpSince, RuntimeFixture.LAN, "LAN UDP remains direct with proxy enabled") {
            it.proto == "UDP" && it.port == RuntimeFixture.UDP_PORT && !it.blocked &&
                it.rx > 0 && it.via == ""
        }
    }

    @Test
    fun t04_blockedTcpCannotReachV2rayAndUnblockingRestoresProxy() =
        runtime.assertTcpBlockAndUnblock(DESTINATION, VIA_PROXY)

    @Test
    fun t05_blockedUdpCannotReachV2rayAndUnblockingRestoresProxy() =
        runtime.assertUdpBlockAndUnblock(DESTINATION, VIA_PROXY)

    @Test
    fun t06_proxyAppDiscoveryRebuildsVpnAndRestoresTcpAndUdp() {
        val previousUpstream = FirewallService.instance?.upstream
        val previousStartedAt = Live.startedAt
        // Exercise the actual owner discovery and exclusion path, including any
        // v2rayNG core restart caused by Android's VPN network change callbacks.
        Prefs.proxy = proxy.copy(appPkg = "")
        FirewallService.applyProxy()
        awaitProxyReady()
        assertTrue("changing exclusions must rebuild the engine",
            previousUpstream !== FirewallService.instance?.upstream)
        assertTrue("a newly established VPN must be recorded", Live.startedAt > previousStartedAt)
        assertEquals("discovery must persist the v2rayNG package", PROXY_PACKAGE, Prefs.proxy.appPkg)
        val tcpSince = runtime.mark()
        val response = runtime.requireHttp(DESTINATION, "v2ray-after-discovery-http")
        runtime.expectSeen(tcpSince, DESTINATION, "TCP proxied after automatic app discovery") {
            it.proto == "TCP" && it.port == RuntimeFixture.HTTP_PORT && !it.blocked &&
                it.rx >= response.length && it.via == VIA_PROXY
        }
        val udpSince = runtime.mark()
        runtime.requireUdp(DESTINATION, "v2ray-after-discovery-udp")
        runtime.expectSeen(udpSince, DESTINATION, "UDP proxied after automatic app discovery") {
            it.proto == "UDP" && it.port == RuntimeFixture.UDP_PORT && !it.blocked &&
                it.rx > 0 && it.via == VIA_PROXY
        }
    }
}
