package com.demos.hd25

import org.junit.Assert.*
import org.junit.Test

class HlsCheckTest {
    private val url = "https://media.example/private-signed-path/_master?token=DO-NOT-PRINT"

    @Test fun validExtensionlessMasterIsAccepted() {
        HlsCheck.validate(200, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\nvariant", url)
    }

    @Test fun httpStatusIsPreservedWithoutLeakingSignedUrlOrBody() {
        for (status in listOf(403, 404, 429, 503)) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                HlsCheck.validate(status, "PRIVATE RESPONSE BODY", url)
            }
            assertEquals("HLS master: HTTP $status (media.example)", error.message)
            assertFalse(error.message!!.contains("DO-NOT-PRINT"))
            assertFalse(error.message!!.contains("private-signed-path"))
        }
    }

    @Test fun http200HtmlCannotMasqueradeAsAPlaylist() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            HlsCheck.validate(200, "<!DOCTYPE html><h1>Access denied</h1>", url)
        }
        assertTrue(error.message!!.contains("HTTP 200"))
        assertTrue(error.message!!.contains("ไม่ใช่ playlist HLS"))
    }

    @Test fun jsonAndEmptyResponsesAreNotPlaylists() {
        for (body in listOf("", "{\"success\":false}", "{\"encrypted\":\"payload\"}")) {
            assertThrows(IllegalArgumentException::class.java) { HlsCheck.validate(200, body, url) }
        }
    }
}
