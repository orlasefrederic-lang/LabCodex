package lab.netstrazh

import android.app.Notification
import android.app.NotificationManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import lab.netstrazh.data.Rules
import lab.netstrazh.vpn.FirewallService
import lab.netstrazh.vpn.Live
import lab.netstrazh.vpn.ProxyConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Local counterpart of original t19. Verifies settled capture after app-list rebuilds,
 * not absence of a transient routing window while Android replaces the VPN. */
@RunWith(AndroidJUnit4::class)
class AppListReviewTest {
    @Test fun appListChangesRestoreCaptureAndVerifiedNotification() {
        val r = RuntimeFixture()
        val other = InstrumentationRegistry.getInstrumentation().context.packageName
        try {
            r.start(ProxyConfig.OFF)
            RuntimeFixture.waitFor(30_000, "initial verified capture") {
                Live.startedAt > 0 && FirewallService.captureVerifiedAt >= Live.startedAt
            }
            assertNotEquals(r.ctx.packageName, other)
            val before = FirewallService.captureRecoveries
            for (round in 1..2) for (exclude in listOf(true, false)) {
                Thread.sleep(2)
                val since = System.currentTimeMillis()
                Rules.set(other, if (exclude) Rules.EXCLUDE else 0)
                RuntimeFixture.waitFor(30_000, "fresh verified engine after app-list change $round/$exclude") {
                    Live.startedAt >= since && FirewallService.captureVerifiedAt >= Live.startedAt
                }
                val marker = r.mark()
                val response = r.requireHttp(RuntimeFixture.LAN, "app-list-$round-$exclude")
                r.expectSeen(marker, RuntimeFixture.LAN, "TCP captured after app-list change $round/$exclude") {
                    it.proto == "TCP" && it.port == RuntimeFixture.HTTP_PORT && !it.blocked && it.rx >= response.length && it.tx > 0
                }
                r.requireUdp(RuntimeFixture.LAN, "app-list-$round-$exclude")
                r.expectSeen(marker, RuntimeFixture.LAN, "UDP captured after app-list change $round/$exclude") {
                    it.proto == "UDP" && it.port == RuntimeFixture.UDP_PORT && !it.blocked && it.rx > 0 && it.tx > 0
                }
                RuntimeFixture.waitFor(5_000, "verified notification after app-list change") {
                    r.ctx.getSystemService(NotificationManager::class.java).activeNotifications
                        .firstOrNull { it.packageName == r.ctx.packageName }?.notification?.extras
                        ?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == r.ctx.getString(R.string.notif_on)
                }
                Log.i(RuntimeFixture.TAG, "APP_LIST round=$round exclude=$exclude verified=${FirewallService.captureVerifiedAt} started=${Live.startedAt} recoveries=${FirewallService.captureRecoveries - before}")
            }
        } finally { r.close() }
    }
}
