package lab.netstrazh

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import lab.netstrazh.data.Db
import lab.netstrazh.vpn.FirewallService
import lab.netstrazh.vpn.Live
import lab.netstrazh.vpn.ProxyConfig
import lab.netstrazh.vpn.ProxyType
import lab.netstrazh.vpn.VIA_PROXY
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Official v2rayNG 2.3.11, kept inside the VPN, using the local review profile.
 * The profile maps TEST-NET addresses to the host LAN fixture. This proves that
 * both UIDs' traffic can traverse the VPN and that exact payloads return. Since
 * the final destination is LAN, it does not independently prove the non-LAN
 * proxy-UID exception or operation with a remote VLESS/VMess/Trojan server.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class InsideRealV2rayTest {
    companion object {
        private var fixture: IncludedV2rayFixture? = null
        @BeforeClass @JvmStatic fun start() {
            fixture = IncludedV2rayFixture(lockdownRequired = false)
            fixture!!.start()
        }
        @AfterClass @JvmStatic fun stop() { fixture?.close() }
    }
    @Test fun t01_includedListenerHasTheOfficialAppsUid() = fixture!!.assertConfiguration()
    @Test fun t02_tcpPayloadAndBothApplicationUidsAreCaptured() = fixture!!.tcp()
    @Test fun t03_udpPayloadAndBothApplicationUidsAreCaptured() = fixture!!.udp()
}

/** Run only after enabling Android's Always-on VPN + Block connections without
 * VPN for lab.netstrazh through stock Settings. The test never changes those
 * settings. It requests bypassApp=true: the product must override that request
 * under lockdown so the real proxy can reach the controlled host fixture.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LockdownRealV2rayTest {
    companion object {
        private var fixture: IncludedV2rayFixture? = null
        @BeforeClass @JvmStatic fun start() {
            fixture = IncludedV2rayFixture(lockdownRequired = true)
            fixture!!.start()
        }
        @AfterClass @JvmStatic fun stop() { fixture?.close() }
    }
    @Test fun t01_lockdownOverridesAutomaticProxyExclusion() = fixture!!.assertConfiguration()
    @Test fun t02_tcpPayloadAndBothApplicationUidsAreCaptured() = fixture!!.tcp()
    @Test fun t03_udpPayloadAndBothApplicationUidsAreCaptured() = fixture!!.udp()
}

private class IncludedV2rayFixture(private val lockdownRequired: Boolean) {
    private val runtime = RuntimeFixture()
    private var externalUid = -1
    private val proxy = ProxyConfig(
        type = ProxyType.SOCKS5,
        host = "127.0.0.1",
        port = 10808,
        dnsViaProxy = false,
        udpDirect = false,
        directIfDown = false,
        appPkg = PROXY_PACKAGE,
        bypassApp = lockdownRequired,
    )

    fun start() {
        @Suppress("DEPRECATION")
        val installed = runtime.ctx.packageManager.getPackageInfo(PROXY_PACKAGE, 0)
        assertEquals("official fixture version", "2.3.11", installed.versionName)
        externalUid = runtime.ctx.packageManager.getApplicationInfo(PROXY_PACKAGE, 0).uid
        assertTrue("proxy is a separate application UID", externalUid >= 0 && externalUid != runtime.myUid)
        runtime.start(proxy)
        if (lockdownRequired) {
            assertTrue("Enable stock Android Block connections without VPN before running this class",
                FirewallService.instance?.isLockdownEnabled == true)
        }
        RuntimeFixture.waitFor(60_000, "included v2rayNG owner and fresh capture") {
            val service = FirewallService.instance
            val upstream = service?.upstream
            upstream?.ensureProxyUid()
            FirewallService.running && service != null && upstream?.cfg == proxy &&
                upstream.proxyUid == externalUid && PROXY_PACKAGE !in excluded() &&
                !FirewallService.captureBroken && Live.startedAt > 0 &&
                FirewallService.captureVerifiedAt >= Live.startedAt
        }
        assertConfiguration()
    }

    fun assertConfiguration() {
        val service = checkNotNull(FirewallService.instance)
        assertEquals("persisted requested proxy settings", proxy, Prefs.proxy)
        assertEquals("current engine proxy settings", proxy, service.upstream?.cfg)
        assertEquals("listener owner is official v2rayNG UID", externalUid, service.upstream?.proxyUid)
        assertFalse("proxy must be inside the actual VPN interface", PROXY_PACKAGE in excluded())
        assertEquals("system lockdown condition", lockdownRequired, service.isLockdownEnabled)
        assertEquals("reported lockdown condition", lockdownRequired, FirewallService.lockdown)
        assertTrue("current capture verified", FirewallService.captureVerifiedAt >= Live.startedAt)
        Log.i(RuntimeFixture.TAG, "included real v2ray ready: lockdown=$lockdownRequired, " +
            "requestedBypass=${proxy.bypassApp}, appliedExcluded=${excluded()}, appUid=${runtime.myUid}, " +
            "proxyUid=$externalUid, engine=${Live.startedAt}, verified=${FirewallService.captureVerifiedAt}")
    }

    fun tcp() {
        assertConfiguration()
        val since = runtime.mark()
        val response = runtime.requireHttp(DESTINATION, "included-v2ray-lockdown-$lockdownRequired")
        runtime.expectSeen(since, DESTINATION, "origin app TCP through included real proxy") {
            it.proto == "TCP" && it.port == RuntimeFixture.HTTP_PORT && !it.blocked &&
                it.tx > 0 && it.rx >= response.length && it.via == VIA_PROXY
        }
        expectProxyCapture(since, "TCP", RuntimeFixture.HTTP_PORT)
    }

    fun udp() {
        assertConfiguration()
        val since = runtime.mark()
        runtime.requireUdp(DESTINATION, "included-v2ray-lockdown-$lockdownRequired")
        runtime.expectSeen(since, DESTINATION, "origin app UDP through included real proxy") {
            it.proto == "UDP" && it.port == RuntimeFixture.UDP_PORT && !it.blocked &&
                it.tx > 0 && it.rx > 0 && it.via == VIA_PROXY
        }
        expectProxyCapture(since, "UDP", RuntimeFixture.UDP_PORT)
    }

    private fun expectProxyCapture(since: Long, protocol: String, port: Int) {
        var evidence = ""
        RuntimeFixture.waitFor(30_000, "included proxy UID $protocol direct fixture payload") {
            // UDP associations may be reused, so require recent activity and bytes;
            // the exact unique reply above proves this exchange traversed the proxy.
            val active = Live.active.values.firstOrNull {
                it.uid == externalUid && it.proto == protocol && it.ip == RuntimeFixture.LAN &&
                    it.port == port && !it.blocked && it.tx > 0 && it.rx > 0 && it.via == "" &&
                    it.lastAt >= since && (protocol == "UDP" || it.start >= since)
            }
            val saved = Db.recent(Db.F_ALL, null, null, 1500).firstOrNull {
                it.uid == externalUid && it.proto == protocol && it.ip == RuntimeFixture.LAN &&
                    it.port == port && !it.blocked && it.tx > 0 && it.rx > 0 && it.via == "" &&
                    it.ts >= since
            }
            evidence = active?.let { "active uid=${it.uid} ${it.ip}:${it.port} tx=${it.tx} rx=${it.rx} via=${it.via}" }
                ?: saved?.toString() ?: ""
            active != null || saved != null
        }
        Log.i(RuntimeFixture.TAG, "included proxy own $protocol capture (LAN destination): $evidence")
    }

    private fun excluded(): Set<*> {
        val service = FirewallService.instance ?: return emptySet<String>()
        return FirewallService::class.java.getDeclaredField("appliedExcluded")
            .apply { isAccessible = true }.get(service) as Set<*>
    }

    fun close() = runtime.close()

    companion object {
        private const val PROXY_PACKAGE = "com.v2ray.ang"
        private const val DESTINATION = "198.51.100.10"
    }
}
