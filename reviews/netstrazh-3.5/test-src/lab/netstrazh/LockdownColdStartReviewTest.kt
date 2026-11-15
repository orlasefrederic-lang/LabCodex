package lab.netstrazh

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import lab.netstrazh.data.Db
import lab.netstrazh.vpn.FirewallService
import lab.netstrazh.vpn.Live
import lab.netstrazh.vpn.ProxyConfig
import lab.netstrazh.vpn.ProxyType
import lab.netstrazh.vpn.VIA_PROXY
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Characterization: PASS means cold-start wrong exclusion was reproduced and
 * the product's existing UI-resume recheck restored the local proxy data path.
 * Requires stock Android lockdown already enabled and official v2rayNG running.
 * Does not change system settings or application source. */
@RunWith(AndroidJUnit4::class)
class LockdownColdStartReviewTest {
    private val runtime = RuntimeFixture()
    private val report = JSONObject()
    private val proxyPackage = "com.v2ray.ang"
    private val destination = "198.51.100.10"
    private var proxyUid = -1

    private fun excluded(): Set<*> {
        val service = FirewallService.instance ?: return emptySet<String>()
        return FirewallService::class.java.getDeclaredField("appliedExcluded")
            .apply { isAccessible = true }.get(service) as Set<*>
    }

    private fun state(): JSONObject {
        val service = FirewallService.instance
        return JSONObject().put("time", System.currentTimeMillis())
            .put("running", FirewallService.running)
            .put("systemLockdown", service?.isLockdownEnabled)
            .put("reportedLockdown", FirewallService.lockdown)
            .put("appliedExcluded", JSONArray(excluded().toList()))
            .put("engineStartedAt", Live.startedAt)
            .put("captureVerifiedAt", FirewallService.captureVerifiedAt)
            .put("captureBroken", FirewallService.captureBroken)
            .put("captureRecoveries", FirewallService.captureRecoveries)
            .put("observedProxyUid", service?.upstream?.proxyUid)
            .put("installedProxyUid", proxyUid)
            .put("lastError", service?.upstream?.lastError ?: JSONObject.NULL)
    }

    private fun save() {
        runtime.ctx.openFileOutput("lockdown-cold-start-review.json", 0).use {
            it.write((report.toString(2) + "\n").toByteArray())
        }
        Log.i(RuntimeFixture.TAG, "LOCKDOWN_COLD_START $report")
    }

    private fun coldHttp(): JSONObject {
        val marker = "lockdown-cold-${SystemClock.elapsedRealtime()}"
        val output = JSONObject().put("marker", marker).put("exactPayload", false)
        val started = SystemClock.elapsedRealtime()
        try {
            Socket().use { socket ->
                socket.soTimeout = 5_000
                socket.connect(InetSocketAddress(InetAddress.getByName(destination), RuntimeFixture.HTTP_PORT), 5_000)
                output.put("localAddress", socket.localAddress.hostAddress).put("localPort", socket.localPort)
                socket.getOutputStream().write(("GET /echo/$marker HTTP/1.1\r\nHost: $destination\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                val response = socket.getInputStream().readBytes().toString(Charsets.US_ASCII)
                val body = response.substringAfter("\r\n\r\n", "")
                output.put("statusLine", response.substringBefore("\r\n"))
                    .put("body", body).put("exactPayload", body == "netstrazh-http:$marker")
            }
        } catch (failure: Exception) {
            output.put("error", failure.toString())
        }
        return output.put("durationMs", SystemClock.elapsedRealtime() - started)
    }

    private fun proxyCapture(since: Long, protocol: String, port: Int): JSONObject {
        var evidence: JSONObject? = null
        RuntimeFixture.waitFor(30_000, "proxy UID $protocol payload after lockdown recheck") {
            val live = Live.active.values.firstOrNull {
                it.uid == proxyUid && it.proto == protocol && it.ip == RuntimeFixture.LAN && it.port == port &&
                    !it.blocked && it.tx > 0 && it.rx > 0 && it.via == "" && it.lastAt >= since &&
                    (protocol == "UDP" || it.start >= since)
            }
            val saved = Db.recent(Db.F_ALL, null, null, 1500).firstOrNull {
                it.uid == proxyUid && it.proto == protocol && it.ip == RuntimeFixture.LAN && it.port == port &&
                    !it.blocked && it.tx > 0 && it.rx > 0 && it.via == "" && it.ts >= since
            }
            if (live != null) evidence = JSONObject().put("uid", live.uid).put("protocol", live.proto)
                .put("destination", live.ip).put("port", live.port).put("tx", live.tx).put("rx", live.rx)
                .put("via", live.via).put("lastAt", live.lastAt)
            else if (saved != null) evidence = JSONObject().put("uid", saved.uid).put("protocol", saved.proto)
                .put("destination", saved.ip).put("port", saved.port).put("tx", saved.tx).put("rx", saved.rx)
                .put("via", saved.via).put("startedAt", saved.ts)
            evidence != null
        }
        return checkNotNull(evidence)
    }

    @Test fun coldStartMisreadsLockdownAndUiRecheckRestoresProxy() {
        val config = ProxyConfig(type = ProxyType.SOCKS5, host = "127.0.0.1", port = 10808,
            dnsViaProxy = false, udpDirect = false, directIfDown = false,
            appPkg = proxyPackage, bypassApp = true)
        report.put("characterization", true).put("requestedBypass", true)
        try {
            @Suppress("DEPRECATION")
            val installed = runtime.ctx.packageManager.getPackageInfo(proxyPackage, 0)
            assertEquals("official proxy fixture", "2.3.11", installed.versionName)
            proxyUid = runtime.ctx.packageManager.getApplicationInfo(proxyPackage, 0).uid
            runtime.start(config)
            RuntimeFixture.waitFor(20_000, "cold engine capture verified") {
                FirewallService.running && Live.startedAt > 0 && FirewallService.captureVerifiedAt >= Live.startedAt
            }
            report.put("coldState", state())
            save()
            assertTrue("stock Android lockdown must already be enabled", FirewallService.instance!!.isLockdownEnabled)
            assertEquals(config, FirewallService.instance!!.upstream!!.cfg)
            assertTrue("characterization: proxy wrongly excluded after cold startup", proxyPackage in excluded())
            assertFalse("characterization: cached lockdown disagrees with active system API", FirewallService.lockdown)
            val response = coldHttp()
            report.put("coldHttp", response)
            save()
            assertFalse("wrongly excluded proxy must not return exact controlled payload", response.getBoolean("exactPayload"))
            val oldEngine = Live.startedAt
            report.put("beforeRecheck", state())
            InstrumentationRegistry.getInstrumentation().runOnMainSync { FirewallService.recheck() }
            RuntimeFixture.waitFor(40_000, "UI-resume recheck includes proxy and verifies new engine") {
                val service = FirewallService.instance
                val upstream = service?.upstream
                upstream?.ensureProxyUid()
                FirewallService.running && service?.isLockdownEnabled == true && FirewallService.lockdown &&
                    upstream?.cfg == config && upstream.proxyUid == proxyUid && proxyPackage !in excluded() &&
                    Live.startedAt > oldEngine && FirewallService.captureVerifiedAt >= Live.startedAt &&
                    !FirewallService.captureBroken
            }
            report.put("recoveredState", state())
            val tcpSince = runtime.mark()
            val tcp = runtime.requireHttp(destination, "lockdown-recheck-http")
            runtime.expectSeen(tcpSince, destination, "origin app TCP through proxy after lockdown recheck") {
                it.proto == "TCP" && it.port == RuntimeFixture.HTTP_PORT && !it.blocked &&
                    it.tx > 0 && it.rx >= tcp.length && it.via == VIA_PROXY
            }
            report.put("tcpExactPayloadAfterRecheck", true)
                .put("proxyTcpCapture", proxyCapture(tcpSince, "TCP", RuntimeFixture.HTTP_PORT))
            val udpSince = runtime.mark()
            runtime.requireUdp(destination, "lockdown-recheck-udp")
            runtime.expectSeen(udpSince, destination, "origin app UDP through proxy after lockdown recheck") {
                it.proto == "UDP" && it.port == RuntimeFixture.UDP_PORT && !it.blocked &&
                    it.tx > 0 && it.rx > 0 && it.via == VIA_PROXY
            }
            report.put("udpExactPayloadAfterRecheck", true)
                .put("proxyUdpCapture", proxyCapture(udpSince, "UDP", RuntimeFixture.UDP_PORT))
                .put("result", "cold-start-defect-reproduced-and-existing-recheck-restores-local-proxy")
        } catch (failure: Throwable) {
            report.put("failure", failure.toString()).put("failureState", state())
            throw failure
        } finally {
            save()
            runtime.close()
        }
    }
}
