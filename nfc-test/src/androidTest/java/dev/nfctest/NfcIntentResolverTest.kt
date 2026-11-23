package dev.nfctest

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Resolves the cascade against the test APK's own package, which has no NFC
 * intent filters. The trampoline maps this null result to the "sin destino"
 * path (result 3), so the case does not depend only on the debug flag.
 */
@RunWith(AndroidJUnit4::class)
class NfcIntentResolverTest {

    @Test
    fun cascadeReturnsNullWithoutNfcFilters() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val spec = TagSpecParser.parse(
            """{"uid":"01020304","records":[{"type":"uri","value":"https://txoka.app"}]}""",
        )

        val resolution = NfcIntentResolver(context).resolve(spec, spec.toTag())

        assertNull(
            "test APK should not have NFC intent filters (package=${context.packageName})",
            resolution,
        )
    }
}
