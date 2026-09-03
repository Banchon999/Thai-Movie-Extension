package com.demos.hd25

import kotlin.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class ZmdbClientTest {
    private val embed = "https://zmdb.net/embed?id=290193&type=tv"
    private val page = "https://25-hd.com/example-series/"
    private val api = "https://zmdb.net/api/video/000000000000000000000001"
    private fun fixture(name: String) = javaClass.getResourceAsStream("/25hd/$name")!!
        .bufferedReader(Charsets.UTF_8).use { it.readText() }
    private val html get() = fixture("zmdb-bootstrap-redacted.html")
    private val links get() = fixture("zmdb-links-redacted.json")
    private val video get() = fixture("zmdb-video-redacted.json")
    private fun <T> run(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(value: Result<T>) { result = value }
        })
        return checkNotNull(result) { "Test transport must complete synchronously" }.getOrThrow()
    }

    @Test fun suppliedBootstrapListsTenEpisodesAndUsesQueryId() {
        val state = ZmdbPayload.bootstrap(SiteParser.document(html, embed))
        assertEquals((1..10).toList(), state.episodes.map { it.number })
        assertTrue(state.episodes.all { it.season == 1 })
        assertEquals("Episode 7", state.episodes[6].title)
        assertEquals("290193", state.id)
        assertEquals("https://zmdb.net/api/embed/links?id=290193&type=tv&season=1&episode=7",
            ZmdbPayload.linksUrl(state, 1, 7))
        assertFalse(state.toString().contains("TEST-TOKEN"))
    }

    @Test fun episodeSevenResolvesThroughFreshTokenAndOneVideoRequest() {
        val calls = mutableListOf<String>()
        val client = ZmdbClient { url, referer, headers ->
            calls.add(url)
            when (calls.size) {
                1 -> { assertEquals(embed, url); assertEquals(page, referer); assertTrue(headers.isEmpty()); ZmdbClient.Response(200, html) }
                2 -> {
                    assertEquals("https://zmdb.net/api/embed/links?id=290193&type=tv&season=1&episode=7", url)
                    assertEquals(embed, referer)
                    assertEquals("Bearer TEST-TOKEN-INITIAL", headers["Authorization"])
                    ZmdbClient.Response(200, links)
                }
                3 -> { assertEquals(api, url); assertEquals(embed, referer); assertTrue(headers.isEmpty()); ZmdbClient.Response(200, video) }
                else -> error("Unexpected request; shared audio/video ID must not trigger duplicate requests")
            }
        }
        val result = run { client.resolve(embed, page, 1, 7) }
        assertEquals(3, calls.size)
        assertEquals(1, result.size)
        assertEquals(ZmdbPayload.video(video).hlsUrl, result.single().url)
        assertEquals(embed, result.single().referer)
    }

    @Test fun token401RefreshesOnceWithoutReusingOldBearer() {
        var requests = 0
        val client = ZmdbClient { _, _, headers ->
            when (++requests) {
                1 -> ZmdbClient.Response(200, html)
                2 -> { assertEquals("Bearer TEST-TOKEN-INITIAL", headers["Authorization"]); ZmdbClient.Response(401, "") }
                3 -> ZmdbClient.Response(200, html.replace("TEST-TOKEN-INITIAL", "TEST-TOKEN-NEW"))
                4 -> { assertEquals("Bearer TEST-TOKEN-NEW", headers["Authorization"]); ZmdbClient.Response(200, links) }
                5 -> { assertTrue(headers.isEmpty()); ZmdbClient.Response(200, video) }
                else -> error("Retry loop")
            }
        }
        assertEquals(1, run { client.resolve(embed, page, 1, 7) }.size)
        assertEquals(5, requests)
    }

    @Test fun accessDenialDoesNotRetryOrRequestVideo() {
        var requests = 0
        val client = ZmdbClient { _, _, _ ->
            when (++requests) {
                1 -> ZmdbClient.Response(200, html)
                2 -> ZmdbClient.Response(403, "Access denied")
                else -> error("Must stop on 403")
            }
        }
        assertThrows(IllegalArgumentException::class.java) { run { client.resolve(embed, page, 1, 7) } }
        assertEquals(2, requests)
    }

    @Test fun aSecond401StopsAfterOneRefresh() {
        var requests = 0
        val client = ZmdbClient { _, _, _ ->
            requests++
            if (requests % 2 == 1) ZmdbClient.Response(200, html) else ZmdbClient.Response(401, "")
        }
        assertThrows(IllegalArgumentException::class.java) { run { client.resolve(embed, page, 1, 7) } }
        assertEquals(4, requests)
    }

    @Test fun requestingUnknownEpisodeOrWrongReturnedEpisodeCannotPlayAnother() {
        var requests = 0
        val client = ZmdbClient { _, _, _ ->
            requests++
            if (requests == 1) ZmdbClient.Response(200, html) else ZmdbClient.Response(200, links.replace("\"episodeNumber\": 7", "\"episodeNumber\": 1"))
        }
        assertThrows(IllegalArgumentException::class.java) { run { client.resolve(embed, page, 1, 99) } }
        assertEquals(1, requests)
        requests = 0
        assertThrows(IllegalArgumentException::class.java) { run { client.resolve(embed, page, 1, 7) } }
        assertEquals(2, requests)
    }

    @Test fun wrongTitleAndVipBootstrapAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ZmdbPayload.bootstrap(SiteParser.document(html, embed.replace("290193", "108978")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ZmdbPayload.bootstrap(SiteParser.document(html.replaceFirst("\"isVipOnly\": false", "\"isVipOnly\": true"), embed))
        }
    }

    @Test fun seasonsAreSortedAndUnavailableEpisodesAreOmitted() {
        val doc = SiteParser.document("""<script type="application/json" id="bootstrap">{
            "query":{"id":"290193","type":"tv"},"content":{"mediaType":"tv","seasons":[
              {"seasonNumber":2,"episodes":[{"episodeNumber":10,"hasLinks":true},{"episodeNumber":2,"hasLinks":true}]},
              {"seasonNumber":1,"episodes":[{"episodeNumber":1,"hasLinks":false},{"episodeNumber":2,"hasLinks":true}]},
              {"seasonNumber":0,"episodes":[{"episodeNumber":1,"hasLinks":true}]}
            ]}}
        </script>""", embed)
        assertEquals(listOf(0 to 1, 1 to 2, 2 to 2, 2 to 10),
            ZmdbPayload.bootstrap(doc).episodes.map { it.season to it.number })
    }

    @Test fun cancellationPropagatesInsteadOfBecomingMissingLinks() {
        val client = ZmdbClient { _, _, _ -> throw CancellationException("cancel") }
        assertThrows(CancellationException::class.java) { run { client.resolve(embed, page, 1, 7) } }
    }

    @Test fun movieRequestDoesNotInventSeasonAndEpisodeParameters() {
        // Synthetic movie contract; only the TV bootstrap has been captured live by the user.
        val movieEmbed = "https://zmdb.net/embed?id=1315772&type=movie"
        var requests = 0
        val client = ZmdbClient { url, _, _ ->
            when (++requests) {
                1 -> ZmdbClient.Response(200, """<script type="application/json" id="bootstrap">{
                    "content":{"mediaType":"movie"},"query":{"id":"1315772","type":"movie"},"linkToken":"TEST"}
                    </script>""")
                2 -> {
                    assertEquals("https://zmdb.net/api/embed/links?id=1315772&type=movie", url)
                    ZmdbClient.Response(200, links)
                }
                3 -> ZmdbClient.Response(200, video)
                else -> error("Unexpected request")
            }
        }
        assertEquals(1, run { client.resolve(movieEmbed, page, null, null) }.size)
    }
}
