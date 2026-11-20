package dev.nfctest.sample

import android.nfc.NfcAdapter
import android.os.ParcelFileDescriptor
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end: sends the same command a developer would send with adb, from the
 * shell identity, and checks what the app received.
 */
@RunWith(AndroidJUnit4::class)
class NfcTapEndToEndTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val packageName = instrumentation.targetContext.packageName
    private val scenarios = mutableListOf<ActivityScenario<*>>()

    @Before
    fun clearDeliveries() {
        finishAllActivities()
        ReceivedTags.clear()
    }

    @After
    fun closeActivities() {
        // Finish first: an activity launched by the dispatcher may sit on top of
        // the scenario activity and keep it paused, so close() would time out.
        finishAllActivities()
        scenarios.reversed().forEach { runCatching { it.close() } }
        scenarios.clear()
        finishAllActivities()
        ReceivedTags.clear()
    }

    /** Also finishes activities started by the dispatcher itself. */
    private fun finishAllActivities() {
        instrumentation.runOnMainSync {
            val registry = ActivityLifecycleMonitorRegistry.getInstance()
            val stages = listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED)
            stages.flatMap { registry.getActivitiesInStage(it) }
                .distinct()
                .forEach { it.finish() }
        }
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline && resumedCount() > 0) {
            Thread.sleep(POLL_MS)
        }
    }

    private fun resumedCount(): Int {
        var count = 0
        instrumentation.runOnMainSync {
            count = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).size
        }
        return count
    }

    // 1. foreground -----------------------------------------------------------

    @Test
    fun foregroundDeliversToOnNewIntent() {
        launch(IntentActivity::class.java)

        val result = broadcast(URI_TEXT, "foreground")

        assertEquals(result.output, 1, result.code)
        val delivery = await { it.source == "intent" }
        assertEquals(NfcAdapter.ACTION_NDEF_DISCOVERED, delivery.action)
        assertEquals("https://txoka.app", delivery.data)
        assertEquals("01020304", delivery.uid)
        assertEquals(listOf("uri: https://txoka.app", "text: hola"), delivery.records)
    }

    // 2. reader ---------------------------------------------------------------

    @Test
    fun readerDeliversOnBackgroundThread() {
        launch(ReaderActivity::class.java)

        val result = broadcast(URI_TEXT, "reader")

        assertEquals(result.output, 1, result.code)
        val delivery = await { it.source == "reader" }
        assertNotEquals("The injected callback must not run on the main thread", "main", delivery.thread)
        assertEquals("01020304", delivery.uid)
        assertEquals(listOf("uri: https://txoka.app", "text: hola"), delivery.records)

        // Reader mode must not also dispatch an Intent for the same tag.
        Thread.sleep(300)
        assertEquals(1, ReceivedTags.all.size)
    }

    @Test
    fun readerSurvivesActivityRecreation() {
        val scenario = launch(ReaderActivity::class.java)
        scenario.recreate()

        assertEquals(1, broadcast(URI_TEXT, "reader").code)
        await { it.source == "reader" }
    }

    // 3. implicit: NDEF filter -------------------------------------------------

    @Test
    fun implicitUriResolvesNdefFilter() {
        launch(ReaderActivity::class.java)

        val result = broadcast(URI_TEXT, "implicit")

        assertEquals(result.output, 1, result.code)
        val delivery = await { it.source == "intent" }
        assertEquals(NfcAdapter.ACTION_NDEF_DISCOVERED, delivery.action)
        assertEquals("https://txoka.app", delivery.data)
        assertEquals("01020304", delivery.uid)
        assertEquals(listOf("uri: https://txoka.app", "text: hola"), delivery.records)
    }

    // 4. implicit: TAG_DISCOVERED fallback ------------------------------------

    @Test
    fun implicitTextFallsBackToTagDiscovered() {
        launch(ReaderActivity::class.java)

        val result = broadcast(TEXT_ONLY, "implicit")

        assertEquals(result.output, 1, result.code)
        val delivery = await { it.source == "intent" }
        assertEquals(NfcAdapter.ACTION_TAG_DISCOVERED, delivery.action)
        assertEquals(listOf("text: hola"), delivery.records)
    }

    // 5. auto -----------------------------------------------------------------

    @Test
    fun autoUsesReaderWhenReaderModeIsActive() {
        launch(ReaderActivity::class.java)

        assertEquals(1, broadcast(URI_TEXT, "auto").code)
        await { it.source == "reader" }
    }

    @Test
    fun autoUsesForegroundWhenOnlyIntentActivityIsUp() {
        launch(IntentActivity::class.java)

        assertEquals(1, broadcast(URI_TEXT, "auto").code)
        val delivery = await { it.source == "intent" }
        assertEquals(NfcAdapter.ACTION_NDEF_DISCOVERED, delivery.action)
    }

    // 6. errors ---------------------------------------------------------------

    @Test
    fun invalidJsonReturnsResult2() {
        assertEquals(2, broadcast("""{"uid":"nope","records":[]}""", "auto").code)
        assertEquals(2, broadcast("{not json", "auto").code)
    }

    @Test
    fun readerWithoutActiveReaderReturnsResult4() {
        assertEquals(4, broadcast(URI_TEXT, "reader").code)
    }

    @Test
    fun foregroundWithoutActivityReturnsResult3() {
        assertEquals(3, broadcast(URI_TEXT, "foreground").code)
    }

    // 7. lifecycle ------------------------------------------------------------

    @Test
    fun readerStopsAfterPause() {
        val scenario = launch(ReaderActivity::class.java)
        assertEquals(1, broadcast(URI_TEXT, "reader").code)
        await { it.source == "reader" }

        ReceivedTags.clear()
        scenario.moveToState(Lifecycle.State.CREATED)

        assertEquals(4, broadcast(URI_TEXT, "reader").code)
        assertTrue("No delivery expected after disable", ReceivedTags.all.isEmpty())
    }

    // 8. edge cases -----------------------------------------------------------

    @Test
    fun sevenByteUidAndThreeRecords() {
        launch(IntentActivity::class.java)

        assertEquals(1, broadcast(MULTI, "foreground").code)
        val delivery = await { it.uid == "01020304050607" }
        assertEquals(3, delivery.records.size)
        assertEquals("uri: https://txoka.app", delivery.records[0])
        assertTrue(delivery.records[1].startsWith("mime text/vcard:"))
        assertTrue(delivery.records[2].startsWith("external example.com:t:"))
    }

    // helpers ----------------------------------------------------------------

    private fun <A : android.app.Activity> launch(activity: Class<A>): ActivityScenario<A> {
        val scenario = ActivityScenario.launch(activity)
        scenarios += scenario
        return scenario
    }

    private data class BroadcastResult(val code: Int, val data: String, val output: String)

    private fun broadcast(json: String, mode: String): BroadcastResult {
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val command = "am broadcast -a dev.nfctest.TAP -p $packageName -f 32 " +
            "--es tag_b64 $encoded --es mode $mode"
        val output = exec(command)
        val code = RESULT.find(output)?.groupValues?.get(1)?.toInt() ?: -1
        val data = DATA.find(output)?.groupValues?.get(1) ?: ""
        return BroadcastResult(code, data, output)
    }

    private fun exec(command: String): String {
        val descriptor: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun await(predicate: (ReceivedTags.Delivery) -> Boolean): ReceivedTags.Delivery {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            ReceivedTags.all.firstOrNull(predicate)?.let { return it }
            Thread.sleep(POLL_MS)
        }
        fail("No delivery matched; got ${ReceivedTags.all}")
        error("unreachable")
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 50L
        val RESULT = Regex("""result=(-?\d+)""")
        val DATA = Regex("""data="([^"]*)"""")

        const val URI_TEXT =
            """{"uid":"01020304","readOnly":false,"records":[{"type":"uri","value":"https://txoka.app"},{"type":"text","lang":"es","value":"hola"}]}"""

        const val TEXT_ONLY =
            """{"uid":"01020304","records":[{"type":"text","lang":"es","value":"hola"}]}"""

        const val MULTI =
            """{"uid":"01020304050607","records":[{"type":"uri","value":"https://txoka.app"},{"type":"mime","mime":"text/vcard","valueBase64":"QkVHSU46VkNBUkQ="},{"type":"external","domain":"example.com","typeName":"t","valueBase64":"AAEC"}]}"""
    }
}
