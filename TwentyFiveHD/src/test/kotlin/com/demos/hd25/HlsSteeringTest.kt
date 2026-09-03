package com.demos.hd25

import org.junit.Assert.*
import org.junit.Test

class HlsSteeringTest {
    private val master = "https://g.example.net/hls/abc/t.PRIVATE/_master?gw_enc=o1"

    @Test fun resolvesTheDeclaredSteeringDocumentRelativeToTheMaster() {
        val body = """
            #EXTM3U
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXT-X-CONTENT-STEERING:SERVER-URI="/hls/playback-routing.json?gw_enc=o1",PATHWAY-ID="."
        """.trimIndent()
        assertEquals(
            "https://g.example.net/hls/playback-routing.json?gw_enc=o1",
            HlsSteering.serverUrl(master, body),
        )
    }

    @Test fun keepsAnAbsoluteSteeringUriAndRejectsUnusableOnes() {
        assertEquals(
            "https://steering.example.net/routing.json",
            HlsSteering.serverUrl(master, """#EXT-X-CONTENT-STEERING:SERVER-URI="https://steering.example.net/routing.json""""),
        )
        assertNull(HlsSteering.serverUrl(master, "#EXTM3U\n#EXT-X-VERSION:11"))
        assertNull(HlsSteering.serverUrl(master, """#EXT-X-CONTENT-STEERING:SERVER-URI="""""))
        assertNull(HlsSteering.serverUrl(master, """#EXT-X-CONTENT-STEERING:SERVER-URI="data:application/json,{}""""))
        assertNull(HlsSteering.serverUrl(master, """#EXT-X-CONTENT-STEERING:SERVER-URI="https://user:pass@h.example.net/r.json""""))
    }

    @Test fun ordersReplacementHostsByDeclaredPathwayPriority() {
        val body = """
            {"VERSION":1,"TTL":300,
             "PATHWAY-CLONES":[
               {"BASE-ID":".","ID":"cdn-001","URI-REPLACEMENT":{"HOST":"lb1.example.net"}},
               {"BASE-ID":".","ID":"cdn-002","URI-REPLACEMENT":{"HOST":"lb2.example.net"}}],
             "PATHWAY-PRIORITY":["cdn-002","cdn-001","."]}
        """.trimIndent()
        assertEquals(listOf("lb2.example.net", "lb1.example.net"), HlsSteering.hosts(body))
    }

    @Test fun keepsUnprioritisedClonesAsAFallbackAndDropsUnusableHosts() {
        val body = """
            {"PATHWAY-CLONES":[
               {"ID":"good","URI-REPLACEMENT":{"HOST":"lb1.example.net"}},
               {"ID":"noHost","URI-REPLACEMENT":{}},
               {"ID":"withPort","URI-REPLACEMENT":{"HOST":"lb2.example.net:8443"}},
               {"ID":"withScheme","URI-REPLACEMENT":{"HOST":"https://lb3.example.net"}},
               {"ID":"withPath","URI-REPLACEMENT":{"HOST":"lb5.example.net/hls"}},
               {"ID":"spare","URI-REPLACEMENT":{"HOST":"lb4.example.net"}}],
             "PATHWAY-PRIORITY":["missing","good"]}
        """.trimIndent()
        assertEquals(listOf("lb1.example.net", "lb4.example.net"), HlsSteering.hosts(body))
    }

    @Test fun returnsNoHostsForAnUnusableSteeringDocument() {
        assertTrue(HlsSteering.hosts("").isEmpty())
        assertTrue(HlsSteering.hosts("not json").isEmpty())
        assertTrue(HlsSteering.hosts("""{"PATHWAY-CLONES":{}}""").isEmpty())
        assertTrue(HlsSteering.hosts("""{"PATHWAY-PRIORITY":["cdn-001"]}""").isEmpty())
    }
}
