package es.igarciadelamo.nfctest.sample.e2e

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start end-to-end tests, in their own process so they can force-stop the
 * sample app without killing the test.
 *
 * The trampoline activity is launched from the shell; it builds the tag and
 * opens the destination Activity with the NFC Intent already in onCreate.
 */
@RunWith(AndroidJUnit4::class)
class ColdStartTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val packageName = "es.igarciadelamo.nfctest.sample"

    @Before
    fun reset() {
        shell("am force-stop $packageName")
        shell("logcat -c")
        device.waitForIdle()
    }

    @Test
    fun coldStartUriOpensIntentActivityOnCreate() {
        val launch = launchCold(URI_TEXT)

        val info = awaitInfo()
        assertTrue("path=onCreate expected, got: $info", info.startsWith("path=onCreate "))
        assertTrue(
            "action=NDEF_DISCOVERED expected, got: $info",
            info.contains("action=android.nfc.action.NDEF_DISCOVERED"),
        )
        assertTrue("records=2 expected, got: $info", info.contains("records=2"))
        assertTrue("uid=01020304 expected, got: $info", info.contains("uid=01020304"))

        assertEquals(1, resultOf(awaitLog(launch.id)))
    }

    @Test
    fun coldStartTextFallsBackToTagDiscovered() {
        val launch = launchCold(TEXT_ONLY)

        val info = awaitInfo()
        assertTrue("path=onCreate expected, got: $info", info.startsWith("path=onCreate "))
        assertTrue(
            "action=TAG_DISCOVERED expected, got: $info",
            info.contains("action=android.nfc.action.TAG_DISCOVERED"),
        )
        assertTrue("records=1 expected, got: $info", info.contains("records=1"))

        assertEquals(1, resultOf(awaitLog(launch.id)))
    }

    @Test
    fun backgroundAppReceivesTag() {
        // Start the app first, then send it to the background without stopping it.
        shell("am start -W -n $packageName/.ReaderActivity")
        device.wait(Until.hasObject(By.pkg(packageName)), TIMEOUT_MS)
        device.pressHome()
        device.waitForIdle()

        val launch = launchCold(URI_TEXT, keepProcess = true)
        val info = awaitInfo()
        // The path is not fixed a priori: the observed value is recorded in the results.
        assertTrue("path=onCreate|onNewIntent expected, got: $info", info.contains("path="))
        assertTrue("records=2 expected, got: $info", info.contains("records=2"))
        assertTrue("uid=01020304 expected, got: $info", info.contains("uid=01020304"))

        assertEquals(1, resultOf(awaitLog(launch.id)))
    }

    @Test
    fun forceStopKillsProcessAndColdStartUsesNewPid() {
        shell("am force-stop $packageName")
        shell("am start -n $packageName/.ReaderActivity")
        val before = awaitPid(present = true)
        assertNotNull("no PID before force-stop", before)
        val beforePid = before!!
        Log.i(TAG, "pid before force-stop=$beforePid")

        shell("am force-stop $packageName")
        assertNull("process should be gone after force-stop", awaitPid(present = false))
        Log.i(TAG, "pid after force-stop=<none>")

        val launch = launchCold(URI_TEXT, keepProcess = true)
        val info = awaitInfo()
        assertTrue("path=onCreate expected, got: $info", info.startsWith("path=onCreate "))
        assertTrue("records=2 expected, got: $info", info.contains("records=2"))
        assertEquals(1, resultOf(awaitLog(launch.id)))

        val after = awaitPid(present = true)
        assertNotNull("no PID after cold start", after)
        val afterPid = after!!
        Log.i(TAG, "pid after cold start=$afterPid")
        assertNotEquals(
            "PID must change after force-stop: before=$beforePid after=$afterPid",
            beforePid,
            afterPid,
        )
        assertFalse("old pid $beforePid must not be running", pidExists(beforePid))
    }

    @Test
    fun backgroundIntentActivityOnTopKeepsSingleInstance() {
        shell("am force-stop $packageName")
        shell("am start -n $packageName/.IntentActivity")
        device.wait(Until.hasObject(By.res(packageName, "received_info")), TIMEOUT_MS)
        device.pressHome()
        device.waitForIdle()

        val launch = launchCold(URI_TEXT, keepProcess = true)
        val info = awaitInfo()
        // The path is not fixed a priori: record the observed one in the results.
        Log.i(TAG, "observed path=$info")
        assertTrue("records=2 expected, got: $info", info.contains("records=2"))
        assertTrue("uid=01020304 expected, got: $info", info.contains("uid=01020304"))
        assertEquals(1, resultOf(awaitLog(launch.id)))

        val (created, live) = awaitInstanceCounts()
        assertEquals(
            "IntentActivity must be created once (created=$created live=$live)",
            1,
            created,
        )
        assertEquals(
            "IntentActivity must keep a single live instance (created=$created live=$live)",
            1,
            live,
        )
    }

    @Test
    fun invalidUidReturnsResult2AndOpensNothing() {
        val launch = launchCold(INVALID_UID)

        assertEquals(2, resultOf(awaitLog(launch.id)))
        Thread.sleep(1_000)
        assertFalse(
            "The sample app must not keep an Activity open",
            device.hasObject(By.pkg(packageName)),
        )
    }

    @Test
    fun noDestinationReturnsResult3() {
        val launch = launchCold(URI_TEXT, noDestination = true)

        assertEquals(3, resultOf(awaitLog(launch.id)))
        Thread.sleep(1_000)
        assertFalse(
            "The sample app must not keep an Activity open",
            device.hasObject(By.pkg(packageName)),
        )
    }

    @Test
    fun logLineMatchesSentId() {
        val launch = launchCold(URI_TEXT)

        val line = awaitLog(launch.id)
        assertTrue("log line must contain the sent id: $line", line.contains("cold id=${launch.id} "))
        assertEquals(1, resultOf(line))
    }

    // helpers ----------------------------------------------------------------

    private data class Launch(val id: String, val output: String)

    private fun launchCold(
        json: String,
        keepProcess: Boolean = false,
        noDestination: Boolean = false,
    ): Launch {
        if (!keepProcess) {
            shell("am force-stop $packageName")
        }
        val id = UUID.randomUUID().toString()
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val extra = if (noDestination) " --ez no_destination true" else ""
        // No -W: when the trampoline closes without opening a destination, -W
        // waits forever for an activity that never becomes idle.
        val output = shell(
            "am start -n $packageName/es.igarciadelamo.nfctest.NfcTestLaunchActivity " +
                "--es tag_b64 $encoded --es id $id$extra",
        )
        return Launch(id, output)
    }

    private fun samplePid(): String? {
        val candidates = shell("pidof $packageName")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        // On API 26 pidof matches the 15-char comm, so the test process (same
        // prefix) can show up too; keep only the PID whose cmdline is the app.
        candidates.firstOrNull { pid ->
            shell("cat /proc/$pid/cmdline").trim('\u0000', '\n') == packageName
        }?.let { return it }
        return shell("ps -A")
            .lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.lastOrNull() == packageName }
            ?.getOrNull(1)
    }

    private fun awaitPid(present: Boolean): String? {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val pid = samplePid()
            if ((pid != null) == present) return pid
            Thread.sleep(POLL_MS)
        }
        return samplePid()
    }

    private fun pidExists(pid: String): Boolean =
        shell("ps -A").lineSequence().any { line ->
            line.trim().split(Regex("\\s+")).getOrNull(1) == pid
        }

    private data class InstanceCounts(val created: Int, val live: Int)

    private fun awaitInstanceCounts(): InstanceCounts {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val line = shell("logcat -d -s NfcSample")
                .lineSequence()
                .lastOrNull { it.contains("created=") && it.contains("live=") }
            if (line != null) {
                val created = CREATED.find(line)?.groupValues?.get(1)?.toInt()
                val live = LIVE.find(line)?.groupValues?.get(1)?.toInt()
                if (created != null && live != null) return InstanceCounts(created, live)
            }
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("No NfcSample created=/live= line found")
    }

    private fun awaitInfo(): String {
        val found = device.wait(
            Until.hasObject(By.res(packageName, "received_info").textContains("path=")),
            TIMEOUT_MS,
        )
        assertTrue("received_info with a path was not shown", found)
        val text = device.findObject(By.res(packageName, "received_info")).text
        assertNotNull("received_info has no text", text)
        return text!!
    }

    private fun awaitLog(id: String): String {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val line = shell("logcat -d -s NfcTest")
                .lineSequence()
                .firstOrNull { it.contains("cold id=$id ") }
            if (line != null) return line
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("No cold log line for id=$id")
    }

    private fun resultOf(line: String): Int =
        RESULT.find(line)?.groupValues?.get(1)?.toInt() ?: -1

    private fun shell(command: String): String =
        device.executeShellCommand(command)

    private companion object {
        const val TAG = "NfcE2E"
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 200L
        val RESULT = Regex("""result=(-?\d+)""")
        val CREATED = Regex("""created=(\d+)""")
        val LIVE = Regex("""live=(\d+)""")

        const val URI_TEXT =
            """{"uid":"01020304","readOnly":false,"records":[{"type":"uri","value":"https://example.com"},{"type":"text","lang":"en","value":"hello"}]}"""

        const val TEXT_ONLY =
            """{"uid":"01020304","records":[{"type":"text","lang":"en","value":"hello"}]}"""

        const val INVALID_UID =
            """{"uid":"0102030405","records":[{"type":"text","lang":"en","value":"hello"}]}"""
    }
}
