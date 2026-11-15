package lab.netstrazh

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import lab.netstrazh.data.Db
import lab.netstrazh.data.Rules
import lab.netstrazh.ui.AppActivity
import lab.netstrazh.ui.HostsActivity
import lab.netstrazh.vpn.Engine
import lab.netstrazh.vpn.FirewallService
import lab.netstrazh.vpn.Live
import lab.netstrazh.vpn.ProxyConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Local counterpart of E2eTest.t23; HTTP Host sniffing uses the host fixture, no public DNS/TLS. */
@RunWith(AndroidJUnit4::class)
class HostRulesReviewTest {
    @Test fun hostListPreservesLegacyRulesCountsRealBlockedAttemptAndUnblocks() {
        val r = RuntimeFixture()
        val markerBase = "host-list-${SystemClock.uptimeMillis()}"
        val host = "$markerBase.example.test"
        val oldHost = "old.$host"
        try {
            r.start(ProxyConfig.OFF)
            RuntimeFixture.waitFor(30_000, "current capture before host-list test") {
                Live.startedAt > 0 && FirewallService.captureVerifiedAt >= Live.startedAt
            }
            val pkg = r.ctx.packageName
            val since = System.currentTimeMillis()
            Rules.setHostBlocked(pkg, host, true)
            val rule = Rules.hostRules().single { it.key == pkg && it.host == host }
            assertTrue("first block time", rule.since in since..System.currentTimeMillis())
            assertEquals(r.myUid % 100000, rule.appId)
            assertEquals(listOf(host), Rules.hostRulesCovering(r.myUid, "www.$host").map { it.host })
            assertTrue(Rules.hostRulesCovering(2000, "www.$host").isEmpty())
            Thread.sleep(30)
            Rules.setHostBlocked(pkg, host, true)
            assertEquals(rule.since, Rules.hostRules().single { it.key == pkg && it.host == host }.since)
            val stored = r.ctx.getSharedPreferences("hostrules", Context.MODE_PRIVATE)
            assertTrue(stored.edit().putBoolean("${Rules.ALL}|$oldHost", true).commit())
            Rules.reload()
            assertEquals(0L, Rules.hostRules().single { it.host == oldHost }.since)
            assertTrue(Rules.hostBlocked(r.myUid, "a.$oldHost"))

            fun request(marker: String): String = Socket().use { s ->
                s.soTimeout = 10_000
                s.connect(InetSocketAddress(InetAddress.getByName(RuntimeFixture.LAN), RuntimeFixture.HTTP_PORT), 10_000)
                s.getOutputStream().write(("GET /echo/$marker HTTP/1.1\r\nHost: www.$host\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                s.getInputStream().readBytes().toString(Charsets.US_ASCII)
            }
            val blockedMarker = "blocked-$markerBase"
            val answer = try { request(blockedMarker) } catch (_: IOException) { "" }
            assertFalse("blocked payload must not return", answer.contains("netstrazh-http:$blockedMarker"))
            var item: HostsActivity.Item? = null
            RuntimeFixture.waitFor(15_000, "real blocked attempt counted for host rule") {
                Db.sync()
                item = HostsActivity.items(Rules.hostRules(), Db.stopped(Engine.NOTE_HOST_BLOCKED))
                    .firstOrNull { it.rule.key == pkg && it.rule.host == host }
                (item?.stopped ?: 0) >= 1
            }
            assertTrue(item!!.last >= since)
            assertEquals(r.myUid, item!!.uid)
            assertTrue(item!!.who.contains(r.ctx.getString(R.string.app_name)))
            Log.i(RuntimeFixture.TAG, "HOST_LIST before unblock: ${item!!.rule.host} ${item!!.who}, ${HostsActivity.details(item!!, ", ")}")
            ActivityScenario.launch(HostsActivity::class.java).use { sc -> sc.onActivity { assertFalse(it.isFinishing) } }
            ActivityScenario.launch<AppActivity>(Intent(r.ctx, AppActivity::class.java).putExtra("uid", r.myUid)).use { sc -> sc.onActivity { assertFalse(it.isFinishing) } }
            Rules.setHostBlocked(pkg, host, false)
            Rules.setHostBlocked(Rules.ALL, oldHost, false)
            assertTrue(Rules.hostRules().none { it.host == host || it.host == oldHost })
            assertFalse(Rules.hostBlocked(r.myUid, "www.$host"))
            val marker = "unblocked-$markerBase"
            val unblocked = request(marker)
            val status = unblocked.substringBefore("\r\n")
            assertEquals("same local target and Host must return HTTP 200 after unblock; response=$unblocked",
                "HTTP/1.1 200 OK", status)
            assertTrue("HTTP response must contain a header/body separator; response=$unblocked",
                unblocked.contains("\r\n\r\n"))
            assertEquals("same local target and Host return exact payload after unblock",
                "netstrazh-http:$marker", unblocked.substringAfter("\r\n\r\n"))
        } finally { r.close() }
    }
}
