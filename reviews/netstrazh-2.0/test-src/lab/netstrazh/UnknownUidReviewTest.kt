package lab.netstrazh

import android.content.Intent
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import lab.netstrazh.data.Rules
import lab.netstrazh.ui.AppActivity
import lab.netstrazh.vpn.FirewallService
import lab.netstrazh.vpn.Live
import lab.netstrazh.vpn.NetType
import lab.netstrazh.vpn.ProxyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Local-fixture counterpart of original E2eTest.t18, which uses example.com.
 * Exercises explicit UNKNOWN_UID rule storage/lookup and preservation of known
 * app traffic. It does not force Android's owner lookup to return UNKNOWN_UID.
 */
@RunWith(AndroidJUnit4::class)
class UnknownUidReviewTest {
    private fun startStable(runtime: RuntimeFixture) {
        runtime.start(ProxyConfig.OFF)
        RuntimeFixture.waitFor(30_000, "current engine capture verified before unknown-UID policy checks") {
            val started = Live.startedAt
            FirewallService.running && !FirewallService.captureBroken && started > 0 &&
                FirewallService.captureVerifiedAt >= started
        }
    }

    @Test
    fun t01_unknownRulePreservesKnownTcpUdpAndCardOpens() {
        val runtime = RuntimeFixture()
        val key = Rules.keyForUid(Rules.UNKNOWN_UID)
        try {
            startStable(runtime)
            assertEquals("unknown rule key", "uid:-1", key)
            Rules.set(key, Rules.BLOCK_ALL)
            assertTrue("unknown owner blocked on Wi-Fi", Rules.isBlocked(Rules.UNKNOWN_UID, NetType.WIFI))
            assertTrue("unknown owner blocked on mobile", Rules.isBlocked(Rules.UNKNOWN_UID, NetType.MOBILE))
            assertFalse("known app allowed on Wi-Fi", Rules.isBlocked(runtime.myUid, NetType.WIFI))
            assertFalse("known app allowed on mobile", Rules.isBlocked(runtime.myUid, NetType.MOBILE))
            assertFalse("UID key must not become a VPN exclusion", key in Rules.excludedPackages())

            val tcpSince = runtime.mark()
            val response = runtime.requireHttp(RuntimeFixture.LAN, "unknown-rule-known-tcp")
            runtime.expectSeen(tcpSince, RuntimeFixture.LAN, "known TCP captured while unknown owner blocked") {
                it.proto == "TCP" && it.port == RuntimeFixture.HTTP_PORT && !it.blocked &&
                    it.tx > 0 && it.rx >= response.length && it.via == ""
            }
            val udpSince = runtime.mark()
            runtime.requireUdp(RuntimeFixture.LAN, "unknown-rule-known-udp")
            runtime.expectSeen(udpSince, RuntimeFixture.LAN, "known UDP captured while unknown owner blocked") {
                it.proto == "UDP" && it.port == RuntimeFixture.UDP_PORT && !it.blocked &&
                    it.tx > 0 && it.rx > 0 && it.via == ""
            }

            val intent = Intent(runtime.ctx, AppActivity::class.java).putExtra("uid", Rules.UNKNOWN_UID)
            ActivityScenario.launch<AppActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    assertFalse("unknown owner card remains open", activity.isFinishing)
                    assertEquals("card UID", Rules.UNKNOWN_UID, activity.intent.getIntExtra("uid", 0))
                }
            }
            Rules.set(key, 0)
            assertFalse("unknown owner can be unblocked", Rules.isBlocked(Rules.UNKNOWN_UID, NetType.WIFI))
            Log.i(RuntimeFixture.TAG, "UNKNOWN_UID policy/card/local known-UID TCP+UDP passed; owner lookup failure not simulated")
        } finally {
            runtime.close()
        }
    }

    @Test
    fun t02_unknownHostRulesAreSeparateFromKnownAppAndGlobalRules() {
        val runtime = RuntimeFixture()
        val key = Rules.keyForUid(Rules.UNKNOWN_UID)
        try {
            startStable(runtime)
            Rules.setHostBlocked(key, "unknown-only.example.test", true)
            Rules.setHostBlocked(runtime.ctx.packageName, "known-only.example.test", true)
            Rules.setHostBlocked(Rules.ALL, "all-apps.example.test", true)

            assertTrue("unknown-specific parent covers subdomain", Rules.hostBlocked(Rules.UNKNOWN_UID, "sub.unknown-only.example.test"))
            assertFalse("unknown-specific rule must not affect known UID", Rules.hostBlocked(runtime.myUid, "sub.unknown-only.example.test"))
            assertTrue("known-app rule applies to known UID", Rules.hostBlocked(runtime.myUid, "known-only.example.test"))
            assertFalse("known-app rule must not affect unknown UID", Rules.hostBlocked(Rules.UNKNOWN_UID, "known-only.example.test"))
            assertTrue("global rule covers unknown UID", Rules.hostBlocked(Rules.UNKNOWN_UID, "sub.all-apps.example.test"))
            assertTrue("global rule covers known UID", Rules.hostBlocked(runtime.myUid, "sub.all-apps.example.test"))
            Rules.setHostBlocked(key, "unknown-only.example.test", false)
            assertFalse("unknown-specific rule can be removed", Rules.hostBlocked(Rules.UNKNOWN_UID, "sub.unknown-only.example.test"))
            assertTrue("removing unknown rule preserves known rule", Rules.hostBlocked(runtime.myUid, "known-only.example.test"))
            assertTrue("removing unknown rule preserves global rule", Rules.hostBlocked(Rules.UNKNOWN_UID, "all-apps.example.test"))
            Log.i(RuntimeFixture.TAG, "UNKNOWN_UID host-policy scope checks passed; no DNS request or owner-race simulation")
        } finally {
            runtime.close()
        }
    }
}
