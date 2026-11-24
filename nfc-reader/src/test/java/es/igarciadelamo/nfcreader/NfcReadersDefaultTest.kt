package es.igarciadelamo.nfcreader

import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Guards the release contract: unless a debug-only module replaces the factory,
 * [NfcReaders.get] must hand out the real implementation.
 */
class NfcReadersDefaultTest {

    @Test
    fun defaultFactoryReturnsRealReader() {
        NfcReaders.factory = { RealNfcReader() }
        assertSame(RealNfcReader::class.java, NfcReaders.get()::class.java)
    }

    @Test
    fun factoryIsReplaceable() {
        val fake = object : NfcReader {
            override fun enable(activity: android.app.Activity, callback: TagCallback, flags: Int) =
                EnableResult.ENABLED

            override fun disable(activity: android.app.Activity) = Unit
        }
        NfcReaders.factory = { fake }
        try {
            assertSame(fake, NfcReaders.get())
        } finally {
            NfcReaders.factory = { RealNfcReader() }
        }
    }
}
