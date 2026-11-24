package es.igarciadelamo.nfcreader

import android.app.Activity
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Bare activity so the tests can call reader mode on a real Activity. */
class TestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(android.widget.TextView(this).apply { text = "nfc-reader" })
    }
}

@RunWith(AndroidJUnit4::class)
class RealNfcReaderTest {

    @Test
    fun emulatorWithoutNfcReportsNoAdapter() {
        ActivityScenario.launch(TestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val result = RealNfcReader().enable(activity, TagCallback { })
                assertEquals(
                    "Emulator images have no NFC adapter; expected NO_ADAPTER",
                    EnableResult.NO_ADAPTER,
                    result,
                )
            }
        }
    }

    @Test
    fun disableWithoutAdapterDoesNotThrow() {
        ActivityScenario.launch(TestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                RealNfcReader().disable(activity)
            }
        }
    }
}
