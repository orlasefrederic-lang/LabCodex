package lab.netstrazh

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import lab.netstrazh.data.Db
import lab.netstrazh.vpn.Live
import lab.netstrazh.vpn.FirewallService
import lab.netstrazh.vpn.ProxyConfig
import lab.netstrazh.vpn.TunConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Ordinary external app sockets, independent of the VPN/instrumentation process and UID. */
@RunWith(AndroidJUnit4::class)
class IndependentClientTest {
    private val report = mutableListOf<String>()
    private val failures = mutableListOf<String>()
    private data class Counters(val tx: Long, val rx: Long, val connections: Int)

    private fun record(message: String) {
        report += message
        Log.i(TAG, message)
    }

    private fun captureHealth(label: String) {
        val checkers = Thread.getAllStackTraces().keys.filter { it.name == "ns-capture" }
        record("$label health: engineStartedAt=${Live.startedAt} running=${FirewallService.running} captureVerifiedAt=${FirewallService.captureVerifiedAt} " +
            "captureRecoveries=${FirewallService.captureRecoveries} " +
            "captureBroken=${FirewallService.captureBroken} ns-capture-count=${checkers.size} " +
            "threads=${checkers.map { "${it.id}:${it.state}:alive=${it.isAlive}" }}")
    }

    private fun observe(label: String, assertion: () -> Unit) {
        try {
            assertion()
            record("$label: PASS")
        } catch (failure: AssertionError) {
            recordFailure(label, failure)
        } catch (failure: Exception) {
            recordFailure(label, failure)
        }
    }

    private fun recordFailure(label: String, failure: Throwable) {
        val message = "$label: ${failure.javaClass.simpleName}: ${failure.message}"
        failures += message
        record(message)
    }

    private fun counters(uid: Int): Counters = Live.stats[uid]?.let {
        Counters(it.tx.get(), it.rx.get(), it.conns.get())
    } ?: Counters(0L, 0L, 0)

    private fun seen(): List<RuntimeFixture.Seen> = Live.active.values.map {
        RuntimeFixture.Seen(it.start, it.uid, it.proto, it.ip, it.port, it.tx, it.rx, it.blocked, it.via)
    } + Db.recent(Db.F_ALL, null, null, 1500).map {
        RuntimeFixture.Seen(it.ts, it.uid, it.proto, it.ip, it.port, it.tx, it.rx, it.blocked, it.via)
    }

    private fun fresh(since: Long, uid: Int) = seen().filter {
        it.started >= since && it.uid == uid && it.ip == RuntimeFixture.LAN &&
            (it.port == RuntimeFixture.HTTP_PORT || it.port == RuntimeFixture.UDP_PORT)
    }

    private fun launchClient(label: String): JSONObject {
        val marker = "client-$label-${SystemClock.elapsedRealtime()}"
        val output = RuntimeFixture.shell("am start -W -n $CLIENT/.ProbeActivity --es marker $marker")
        record("$label launch: ${output.trim()}")
        assertFalse("Helper launch failed: $output", output.contains("Error:"))
        var result: JSONObject? = null
        var lastRead = ""
        try {
            RuntimeFixture.waitFor(30_000, "$label independent client result") {
                lastRead = RuntimeFixture.shell("run-as $CLIENT cat files/result.json")
                val candidate = try { JSONObject(lastRead) } catch (_: Exception) { null }
                if (candidate?.optString("marker") == marker) result = candidate
                result != null
            }
        } finally {
            record("$label helper: ${result ?: lastRead}")
        }
        return checkNotNull(result)
    }

    private fun assertClient(result: JSONObject, uid: Int, vpn: Boolean) {
        val marker = result.getString("marker")
        assertEquals("helper package", CLIENT, result.getString("package"))
        assertEquals("independent helper UID", uid, result.getInt("uid"))
        for (snapshot in listOf("networkBefore", "networkAfter")) {
            val network = result.getJSONObject(snapshot)
            assertEquals("$snapshot default network", vpn, network.getBoolean("vpn"))
            assertEquals("helper must not bind its process to any network", "null", network.getString("bound"))
        }
        val tcp = result.getJSONObject("tcp")
        val udp = result.getJSONObject("udp")
        assertTrue("TCP exact payload: $tcp", tcp.getBoolean("ok"))
        assertTrue("UDP exact payload: $udp", udp.getBoolean("ok"))
        assertEquals("HTTP payload", "netstrazh-http:$marker", tcp.getString("payload"))
        assertEquals("UDP payload", "netstrazh-udp:ns-test:$marker", udp.getString("payload"))
        for ((protocol, connection) in listOf("TCP" to tcp, "UDP" to udp)) {
            val local = connection.getString("localAddress")
            assertTrue("$protocol allocated source port", connection.getInt("localPort") > 0)
            if (vpn) {
                assertEquals("$protocol source address must prove VPN routing", TunConfig.T4, local)
            } else {
                assertFalse("$protocol source must leave the VPN while off", local == TunConfig.T4)
                val addresses = result.getJSONObject("networkBefore").getJSONArray("linkAddresses")
                assertTrue("$protocol physical source $local belongs to default network $addresses",
                    (0 until addresses.length()).any { addresses.getString(it) == local })
            }
        }
    }

    private fun assertSameProcess(initial: JSONObject, current: JSONObject) {
        assertEquals("same-process prerequisite: external PID", initial.getInt("pid"), current.getInt("pid"))
        assertEquals("same-process prerequisite: external nonce", initial.getString("processNonce"),
            current.getString("processNonce"))
        assertTrue("fresh invocation in persistent process",
            current.getInt("sequence") > initial.getInt("sequence"))
    }

    private fun assertCaptured(label: String, since: Long, uid: Int, before: Counters, result: JSONObject) {
        var rows = fresh(since, uid)
        var after = counters(uid)
        try {
            val tcp = result.getJSONObject("tcp")
            val udp = result.getJSONObject("udp")
            // Reads of the exact replies are checked separately; these are payload byte minima.
            val tcpTx = tcp.getLong("requestBytes")
            val tcpRx = tcp.getLong("responseBytes")
            val udpTx = udp.getLong("requestBytes")
            val udpRx = udp.getLong("responseBytes")
            RuntimeFixture.waitFor(30_000, "$label new external UID capture and counters") {
                rows = fresh(since, uid)
                after = counters(uid)
                rows.any {
                    it.proto == "TCP" && it.port == RuntimeFixture.HTTP_PORT && !it.blocked &&
                        it.tx >= tcpTx && it.rx >= tcpRx && it.via == ""
                } && rows.any {
                    it.proto == "UDP" && it.port == RuntimeFixture.UDP_PORT && !it.blocked &&
                        it.tx >= udpTx && it.rx >= udpRx && it.via == ""
                } && after.tx - before.tx >= tcpTx + udpTx &&
                    after.rx - before.rx >= tcpRx + udpRx &&
                    after.connections - before.connections >= 2
            }
        } finally {
            record("$label capture since=$since uid=$uid rows=$rows before=$before after=$after")
        }
    }

    private fun powerToggle(runtime: RuntimeFixture, on: Boolean) {
        // The helper is foreground after each probe. Restore the main UI before invoking
        // its control, so Android background-start restrictions do not confound the test.
        val output = RuntimeFixture.shell("am start -W -n ${runtime.ctx.packageName}/lab.netstrazh.ui.MainActivity")
        record("foreground main before power=$on: ${output.trim()}")
        assertFalse("MainActivity launch failed: $output", output.contains("Error:"))
        // RuntimeFixture.onActivity reopens the scenario if runner cleanup destroyed it.
        runtime.onActivity { it.onPowerToggle(on) }
    }

    @Test
    fun independentUidRemainsCapturedAfterPowerCycle() {
        val runtime = RuntimeFixture()
        try {
            val installed = RuntimeFixture.shell("pm list packages -U $CLIENT")
            val uid = Regex("package:${Regex.escape(CLIENT)} uid:(\\d+)")
                .find(installed)?.groupValues?.get(1)?.toInt()
                ?: throw AssertionError("Install client-probe/review-client.apk first: $installed")
            assertTrue("helper must have a UID distinct from the VPN and instrumentation", uid != runtime.myUid)
            RuntimeFixture.shell("am force-stop $CLIENT")
            runtime.start(ProxyConfig.OFF)
            SystemClock.sleep(1000)
            captureHealth("initial before probe")
            val initialSince = runtime.mark()
            val initialCounters = counters(uid)
            val initial = launchClient("initial")
            observe("initial exact payload and VPN source") { assertClient(initial, uid, vpn = true) }
            observe("initial fresh capture") {
                assertCaptured("initial", initialSince, uid, initialCounters, initial)
            }

            captureHealth("initial after probe")
            repeat(3) { index ->
                val round = index + 1
                val previousEngineStarted = Live.startedAt
                val offLabel = "off-$round"
                val restartedLabel = "restarted-$round"
                powerToggle(runtime, false)
                runtime.awaitStopped()
                assertFalse("$offLabel power preference", Prefs.enabled)
                captureHealth("$offLabel before probe")
                val offSince = runtime.mark()
                val off = launchClient(offLabel)
                observe("$offLabel persistent helper process") { assertSameProcess(initial, off) }
                observe("$offLabel exact payload and physical source") { assertClient(off, uid, vpn = false) }
                observe("$offLabel no fresh captured fixture flows") {
                    assertTrue("VPN off must create no captures: ${fresh(offSince, uid)}", fresh(offSince, uid).isEmpty())
                }
                captureHealth("$offLabel after probe")

                powerToggle(runtime, true)
                runtime.awaitRunning()
                SystemClock.sleep(1000)
                assertTrue("$restartedLabel power preference", Prefs.enabled)
                assertTrue("$restartedLabel new engine", Live.startedAt > previousEngineStarted)
                val restartedEngineStarted = Live.startedAt
                captureHealth("$restartedLabel before probe")
                val restartedSince = runtime.mark()
                val restartedCounters = counters(uid)
                val restarted = launchClient(restartedLabel)
                observe("$restartedLabel persistent helper process") { assertSameProcess(off, restarted) }
                observe("$restartedLabel exact payload and VPN source") { assertClient(restarted, uid, vpn = true) }
                observe("$restartedLabel fresh capture") {
                    assertCaptured(restartedLabel, restartedSince, uid, restartedCounters, restarted)
                }
                captureHealth("$restartedLabel after probe")
                val settledLabel = "settled-$round"
                observe("$settledLabel fresh successful capture watchdog") {
                    RuntimeFixture.waitFor(30_000, "$settledLabel fresh watchdog after engine=$restartedEngineStarted") {
                        val currentStarted = Live.startedAt
                        FirewallService.running && !FirewallService.captureBroken &&
                            currentStarted > 0 && currentStarted >= restartedEngineStarted &&
                            FirewallService.captureVerifiedAt >= currentStarted
                    }
                }
                captureHealth("$settledLabel before probe")
                val settledSince = runtime.mark()
                val settledCounters = counters(uid)
                val settled = launchClient(settledLabel)
                observe("$settledLabel persistent helper process") { assertSameProcess(restarted, settled) }
                observe("$settledLabel exact payload and VPN source") { assertClient(settled, uid, vpn = true) }
                observe("$settledLabel fresh capture") {
                    assertCaptured(settledLabel, settledSince, uid, settledCounters, settled)
                }
                captureHealth("$settledLabel after probe")
            }
            record("RESULT failures=${failures.size}")
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally {
            try { runtime.close() } finally {
                captureHealth("after final close")
                File(runtime.ctx.filesDir, "independent-client-v20.txt")
                    .writeText(report.joinToString("\n") + "\n")
            }
        }
    }

    companion object {
        private const val CLIENT = "lab.netstrazh.reviewclient"
        private const val TAG = "NetStrazhExternalTest"
    }
}
