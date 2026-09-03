package com.demos.hd25

import org.junit.Assert.*
import org.junit.Test

class ZmdbPayloadTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/25hd/$name.json")!!
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test fun extensionlessMasterPreservesQueryAndIsNotAStoryboard() {
        val video = ZmdbPayload.video(fixture("zmdb-video-redacted"))
        assertEquals("https://media.example/hls/video-id/t.example/_master?gw_enc=01&signature=a%2Fb%3D", video.hlsUrl)
        assertFalse(SiteParser.isMedia(video.hlsUrl)) // Why extension inference is insufficient.
        assertEquals(listOf("1080p", "720p", "480p", "360p"), video.availableQualities)
    }

    @Test fun episodeAudioAndSubtitleChoicesRemainDistinct() {
        val links = ZmdbPayload.episodeLinks(fixture("zmdb-links-redacted"))
        assertEquals(1, links.season)
        assertEquals(7, links.episode)
        assertEquals(2, links.players.size)
        assertEquals(listOf("พากย์ไทย", "ซับไทย"), links.players.map { it.language })
        assertTrue(links.players[0].url.endsWith("?audio=tha&subtitle=none"))
        assertTrue(links.players[1].url.endsWith("?audio=eng&subtitle=tha"))
        assertFalse(links.toString().contains("REDACTED"))
    }

    @Test fun vipOrUnspecifiedAccessLinksAreNotReturned() {
        assertTrue(ZmdbPayload.episodeLinks("""{"success":true,"playerEmbedLinks":[
            {"embedUrl":"https://example.org/vip","isVipOnly":true},
            {"embedUrl":"https://example.org/unknown"}
        ]}""").players.isEmpty())
    }

    @Test fun missingHlsDoesNotFallBackToPreviewPlaylist() {
        assertThrows(IllegalArgumentException::class.java) {
            ZmdbPayload.video("""{"success":true,"data":{"seekPreview":{"imagePlaylistUrl":"https://example.org/preview.m3u8"}}}""")
        }
    }

    @Test fun failedResponsesAreNotTreatedAsPlayable() {
        assertThrows(IllegalArgumentException::class.java) {
            ZmdbPayload.video("""{"success":false,"data":{"hlsUrl":"https://example.org/master"}}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ZmdbPayload.episodeLinks("""{"success":"true"}""")
        }
    }

    @Test fun invalidVideoUrlsAreRejected() {
        for (url in listOf("javascript:alert(1)", "data:video/mp4;base64,AA", "/relative/master", "https://user:password@example.org/master")) {
            assertThrows(IllegalArgumentException::class.java) {
                ZmdbPayload.video("""{"success":true,"data":{"hlsUrl":"$url"}}""")
            }
        }
    }

    @Test fun apiDispatchIsScopedToTheObservedHostAndPath() {
        assertTrue(ZmdbPayload.isVideoApi("https://zmdb.net/api/video/6a4da13bcf73eed785176d81"))
        assertFalse(ZmdbPayload.isVideoApi("https://zmdb.net/embed?id=108978&type=tv"))
        assertFalse(ZmdbPayload.isVideoApi("https://zmdb.net.evil.example/api/video/6a4da13bcf73eed785176d81"))
        assertFalse(ZmdbPayload.isVideoApi("https://other.example/api/video/6a4da13bcf73eed785176d81"))
        assertFalse(ZmdbPayload.isVideoApi("https://zmdb.net/api/video/not-an-id"))
    }
}
