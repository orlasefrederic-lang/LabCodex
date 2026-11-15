package lab.netstrazh

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import lab.netstrazh.data.Db
import lab.netstrazh.data.Rules
import lab.netstrazh.ui.HostsActivity
import lab.netstrazh.vpn.Conn
import lab.netstrazh.vpn.Engine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Characterizes a review finding; passing means the stale-history defect was reproduced.
 * Inserts one synthetic, uniquely named journal row. It makes no network requests.
 */
@RunWith(AndroidJUnit4::class)
class HostHistoryReviewTest {
    @Test fun recreatedRuleIsCreditedWithPreviousRulesAttempt() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val host = "history-review-${System.nanoTime()}.invalid"
        Rules.setHostBlocked(Rules.ALL, host, true)
        try {
            val c = Conn(android.os.Process.myUid(), "DNS", "", 53, host, "WIFI").apply {
                blocked = true
                note = Engine.NOTE_HOST_BLOCKED
                end = System.currentTimeMillis()
            }
            Db.log(c)
            Db.sync()
            Rules.setHostBlocked(Rules.ALL, host, false)
            Thread.sleep(50)
            Rules.setHostBlocked(Rules.ALL, host, true)
            val rule = Rules.hostRules().single { it.key == Rules.ALL && it.host == host }
            val item = HostsActivity.items(listOf(rule), Db.stopped(Engine.NOTE_HOST_BLOCKED)).single()
            val evidence = "{\"synthetic_journal_row\":true,\"new_rule_since\":${rule.since},\"credited_attempts\":${item.stopped},\"last_attempt\":${item.last},\"attempt_predates_rule\":${item.last < rule.since}}"
            Log.i("HostHistoryReview", evidence)
            ctx.openFileOutput("host-history-review.json", 0).use { it.write((evidence + "\n").toByteArray()) }
            assertEquals("The recreated rule incorrectly inherits the old count", 1, item.stopped)
            assertTrue("The displayed last attempt predates creation of this rule", item.last < rule.since)
        } finally {
            Rules.setHostBlocked(Rules.ALL, host, false)
        }
    }
}
