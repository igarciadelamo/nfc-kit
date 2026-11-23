package es.igarciadelamo.nfctest

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Milestone 1: proves that Gradle Managed Devices boots each configured API. */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @Test
    fun emulatorIsAtLeastApi30() {
        assertTrue("Unexpected SDK_INT=${Build.VERSION.SDK_INT}", Build.VERSION.SDK_INT >= 30)
    }
}
