package dev.nfctest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TagSpecParserTest {

    private val uriText = """
        {
          "uid": "01020304",
          "readOnly": false,
          "records": [
            {"type": "uri", "value": "https://txoka.app"},
            {"type": "text", "lang": "es", "value": "hola"}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesUriAndText() {
        val spec = TagSpecParser.parse(uriText)
        assertEquals(4, spec.uid.size)
        assertEquals("01020304", spec.uid.joinToString("") { "%02x".format(it) })
        assertEquals(false, spec.readOnly)
        assertEquals(2, spec.records.size)
        assertEquals(TagRecord.Uri("https://txoka.app"), spec.records[0])
        assertEquals(TagRecord.Text("es", "hola"), spec.records[1])
    }

    @Test
    fun parsesMimeAndExternalWithBase64() {
        val json = """
            {
              "uid": "01020304050607",
              "readOnly": true,
              "records": [
                {"type": "mime", "mime": "text/vcard", "valueBase64": "aGVsbG8="},
                {"type": "external", "domain": "example.com", "typeName": "t",
                 "valueBase64": "AAEC"}
              ]
            }
        """.trimIndent()
        val spec = TagSpecParser.parse(json)
        assertEquals(7, spec.uid.size)
        assertTrue(spec.readOnly)
        assertEquals(TagRecord.Mime("text/vcard", "aGVsbG8="), spec.records[0])
        assertEquals(TagRecord.External("example.com", "t", "AAEC"), spec.records[1])
    }

    @Test
    fun rejectsShortUid() {
        assertFails("uid must be 4, 7 or 10 bytes", """{"uid":"0102","records":[{"type":"text","value":"x"}]}""")
    }

    @Test
    fun rejectsNonHexUid() {
        assertFails("uid must be hexadecimal", """{"uid":"0102zz","records":[{"type":"text","value":"x"}]}""")
    }

    @Test
    fun rejectsZeroRecords() {
        assertFails("at least one record", """{"uid":"01020304","records":[]}""")
    }

    @Test
    fun rejectsUnknownRecordType() {
        assertFails(
            "unknown type \"nope\"",
            """{"uid":"01020304","records":[{"type":"nope","value":"x"}]}""",
        )
    }

    @Test
    fun rejectsInvalidBase64() {
        assertFails(
            "not valid base64",
            """{"uid":"01020304","records":[{"type":"mime","mime":"text/plain","valueBase64":"@@@"}]}""",
        )
    }

    @Test
    fun rejectsRelativeUri() {
        assertFails(
            "absolute URI",
            """{"uid":"01020304","records":[{"type":"uri","value":"/relative"}]}""",
        )
    }

    @Test
    fun rejectsInvalidJson() {
        assertFails("invalid JSON", "{ not json")
    }

    @Test
    fun rejectsOversizedMessage() {
        val payload = java.util.Base64.getEncoder().encodeToString(ByteArray(20_000))
        assertFails(
            "message too large",
            """{"uid":"01020304","records":[{"type":"mime","mime":"application/octet-stream","valueBase64":"$payload"}]}""",
        )
    }

    @Test
    fun rejectsMissingUid() {
        assertFails("uid is required", """{"records":[{"type":"text","value":"x"}]}""")
    }

    private fun assertFails(expected: String, json: String) {
        try {
            TagSpecParser.parse(json)
            fail("Expected TagSpecException containing \"$expected\"")
        } catch (e: TagSpecException) {
            assertTrue("Unexpected message: ${e.message}", e.message.orEmpty().contains(expected))
        }
    }
}
