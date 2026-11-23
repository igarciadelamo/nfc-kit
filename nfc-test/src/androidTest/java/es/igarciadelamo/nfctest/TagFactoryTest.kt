package es.igarciadelamo.nfctest

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.os.Build
import android.os.Parcel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class TagFactoryTest(private val strategy: TagStrategy) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun strategies(): List<TagStrategy> = TagStrategy.values().toList()

        private val UID_4 = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        private val UID_7 = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)
        private val UID_10 = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A)

        private fun twoRecordMessage(): NdefMessage = NdefMessage(
            NdefRecord.createUri("https://example.com"),
            NdefRecord.createTextRecord("en", "hello"),
        )
    }

    @Before
    fun skipReflectionWhenHiddenApiBlocksIt() {
        assumeTrue(
            "Tag.createMockTag is not visible on SDK ${Build.VERSION.SDK_INT}; " +
                "skipping REFLECTION (set hidden_api_policy=1 while the emulator boots)",
            strategy != TagStrategy.REFLECTION || TagFactory.isReflectionAvailable(),
        )
    }

    @Test
    fun tagIdMatchesUidForSupportedLengths() {
        val message = twoRecordMessage()
        for (uid in listOf(UID_4, UID_7, UID_10)) {
            val tag = TagFactory.create(uid, message, strategy = strategy)
            assertArrayEquals(
                "UID of ${uid.size} bytes not preserved ($strategy)",
                uid,
                tag.id,
            )
        }
    }

    @Test
    fun techListContainsNfcAAndNdef() {
        val tag = TagFactory.create(UID_4, twoRecordMessage(), strategy = strategy)
        val techList = tag.techList.toList()
        assertTrue("$strategy techList=$techList", techList.contains(NfcA::class.java.name))
        assertTrue("$strategy techList=$techList", techList.contains(Ndef::class.java.name))
    }

    @Test
    fun techObjectsAreAvailable() {
        val tag = TagFactory.create(UID_4, twoRecordMessage(), strategy = strategy)
        assertNotNull("Ndef.get(tag) is null ($strategy)", Ndef.get(tag))
        assertNotNull("NfcA.get(tag) is null ($strategy)", NfcA.get(tag))
    }

    @Test
    fun cachedNdefMessageMatchesOriginal() {
        val message = twoRecordMessage()
        val tag = TagFactory.create(UID_4, message, strategy = strategy)
        val cached = Ndef.get(tag)!!.cachedNdefMessage

        assertNotNull("cachedNdefMessage is null ($strategy)", cached)
        assertEquals("record count ($strategy)", 2, cached!!.records.size)
        assertArrayEquals(
            "NDEF bytes differ ($strategy)",
            message.toByteArray(),
            cached.toByteArray(),
        )
    }

    @Test
    fun ndefMetadataIsCoherent() {
        val message = twoRecordMessage()
        for (readOnly in listOf(false, true)) {
            val tag = TagFactory.create(UID_4, message, readOnly = readOnly, strategy = strategy)
            val ndef = Ndef.get(tag)!!

            assertTrue(
                "maxSize=${ndef.maxSize} < ${message.toByteArray().size} ($strategy)",
                ndef.maxSize >= message.toByteArray().size,
            )
            assertEquals(
                "isWritable should be ${!readOnly} ($strategy, readOnly=$readOnly)",
                !readOnly,
                ndef.isWritable,
            )
            assertEquals(
                "NDEF type ($strategy)",
                Ndef.NFC_FORUM_TYPE_2,
                ndef.type,
            )
        }
    }

    @Test
    fun nfcAExtrasMatchConfiguredValues() {
        val tag = TagFactory.create(UID_4, twoRecordMessage(), strategy = strategy)
        val nfcA = NfcA.get(tag)!!

        assertEquals("sak ($strategy)", 0.toShort(), nfcA.sak)
        assertArrayEquals("atqa ($strategy)", byteArrayOf(0x44, 0x00), nfcA.atqa)
    }

    @Test
    fun tagSurvivesParcelRoundTrip() {
        val tag = TagFactory.create(UID_7, twoRecordMessage(), strategy = strategy)
        val parcel = Parcel.obtain()
        try {
            tag.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val copy = Tag.CREATOR.createFromParcel(parcel)

            assertArrayEquals("id after parcel ($strategy)", tag.id, copy.id)
            assertEquals("techList after parcel ($strategy)", tag.techList.toSet(), copy.techList.toSet())
            assertEquals(
                "cached NDEF after parcel ($strategy)",
                Ndef.get(tag)!!.cachedNdefMessage!!.toByteArray().toList(),
                Ndef.get(copy)!!.cachedNdefMessage!!.toByteArray().toList(),
            )
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun tagSurvivesIntentRoundTrip() {
        val tag = TagFactory.create(UID_4, twoRecordMessage(), strategy = strategy)
        val intent = Intent().putExtra(NfcAdapter.EXTRA_TAG, tag)

        val restored = intent.tagExtra()
        assertNotNull("EXTRA_TAG not restored ($strategy)", restored)
        assertArrayEquals("id after Intent ($strategy)", tag.id, restored!!.id)
        assertNotNull("Ndef.get after Intent ($strategy)", Ndef.get(restored))
    }

    @Test
    fun techDiscoveredIntentIsReadable() {
        val message = twoRecordMessage()
        val tag = TagFactory.create(UID_4, message, strategy = strategy)
        val intent = Intent(NfcAdapter.ACTION_TECH_DISCOVERED).apply {
            putExtra(NfcAdapter.EXTRA_TAG, tag)
            putExtra(NfcAdapter.EXTRA_ID, UID_4)
            putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, arrayOf(message))
        }

        assertEquals(NfcAdapter.ACTION_TECH_DISCOVERED, intent.action)
        val intentTag = intent.tagExtra()
        assertNotNull("EXTRA_TAG ($strategy)", intentTag)
        assertArrayEquals("EXTRA_ID ($strategy)", UID_4, intent.getByteArrayExtra(NfcAdapter.EXTRA_ID))

        val messages = intent.ndefMessagesExtra()
        assertNotNull("EXTRA_NDEF_MESSAGES ($strategy)", messages)
        assertEquals("NDEF message count ($strategy)", 1, messages!!.size)
        assertArrayEquals(
            "NDEF payload ($strategy)",
            message.toByteArray(),
            messages[0].toByteArray(),
        )
    }

    @Test
    fun singleRecordMessage() {
        val message = NdefMessage(NdefRecord.createUri("https://example.com"))
        val tag = TagFactory.create(UID_4, message, strategy = strategy)
        val cached = Ndef.get(tag)!!.cachedNdefMessage!!

        assertEquals(1, cached.records.size)
        assertArrayEquals(message.toByteArray(), cached.toByteArray())
    }

    @Test
    fun threeRecordsIncludingMimeAndExternal() {
        val vcard = "BEGIN:VCARD\nVERSION:3.0\nFN:Spike\nEND:VCARD".toByteArray()
        val message = NdefMessage(
            NdefRecord.createUri("https://example.com"),
            NdefRecord.createMime("text/vcard", vcard),
            NdefRecord.createExternal("es.igarciadelamo.nfctest", "demo", byteArrayOf(0x01, 0x02)),
        )
        val tag = TagFactory.create(UID_4, message, strategy = strategy)
        val cached = Ndef.get(tag)!!.cachedNdefMessage!!

        assertEquals(3, cached.records.size)
        assertArrayEquals(message.toByteArray(), cached.toByteArray())
    }

    @Test
    fun largeMessageFitsInNdefMaxSize() {
        val message = NdefMessage(NdefRecord.createTextRecord("en", "x".repeat(2048)))
        val tag = TagFactory.create(UID_4, message, strategy = strategy)
        val ndef = Ndef.get(tag)!!

        assertTrue("message should be around 2 KB", message.toByteArray().size > 2048)
        assertTrue(
            "maxSize=${ndef.maxSize} < ${message.toByteArray().size}",
            ndef.maxSize >= message.toByteArray().size,
        )
        assertArrayEquals(message.toByteArray(), ndef.cachedNdefMessage!!.toByteArray())
    }

    @Test
    fun nfcAOnlyTagHasNoNdef() {
        val tag = TagFactory.createNfcAOnly(UID_4, strategy)

        assertNotNull("NfcA.get(tag) is null ($strategy)", NfcA.get(tag))
        assertNull("Ndef.get(tag) should be null ($strategy)", Ndef.get(tag))
        assertFalse("NDEF should not be in techList ($strategy)", tag.techList.contains(Ndef::class.java.name))
        assertEquals("sak ($strategy)", 0.toShort(), NfcA.get(tag)!!.sak)
    }

    @Test
    fun invalidUidIsRejected() {
        val message = twoRecordMessage()
        for (badUid in listOf(byteArrayOf(), byteArrayOf(0x01, 0x02), ByteArray(5), ByteArray(8))) {
            try {
                TagFactory.create(badUid, message, strategy = strategy)
                throw AssertionError("uid of ${badUid.size} bytes should be rejected")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.tagExtra(): Tag? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
    } else {
        getParcelableExtra(NfcAdapter.EXTRA_TAG)
    }

    @Suppress("DEPRECATION")
    private fun Intent.ndefMessagesExtra(): Array<NdefMessage>? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
    } else {
        getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            ?.map { it as NdefMessage }
            ?.toTypedArray()
    }
}
