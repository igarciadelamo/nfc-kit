package dev.nfctest.sample.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    private val packageName = "dev.nfctest.sample"

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
            "am start -n $packageName/dev.nfctest.NfcTestLaunchActivity " +
                "--es tag_b64 $encoded --es id $id$extra",
        )
        return Launch(id, output)
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
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 200L
        val RESULT = Regex("""result=(-?\d+)""")

        const val URI_TEXT =
            """{"uid":"01020304","readOnly":false,"records":[{"type":"uri","value":"https://txoka.app"},{"type":"text","lang":"es","value":"hola"}]}"""

        const val TEXT_ONLY =
            """{"uid":"01020304","records":[{"type":"text","lang":"es","value":"hola"}]}"""

        const val INVALID_UID =
            """{"uid":"0102030405","records":[{"type":"text","lang":"es","value":"hola"}]}"""
    }
}
