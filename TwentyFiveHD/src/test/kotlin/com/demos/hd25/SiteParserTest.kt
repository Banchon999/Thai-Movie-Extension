package com.demos.hd25

import org.junit.Assert.*
import org.junit.Test

/** Synthetic fixtures test parser behavior, not the current 25-HD website. */
class SiteParserTest {
    private val base = "https://25-hd.com/title/"

    @Test fun cardsUseLazyPostersAndDiscardExternalAds() {
        val doc = SiteParser.document("""
            <article class="item"><h3><a href="/movie/">หนังไทย (2026)</a></h3>
              <img src="data:image/gif;base64,AA" data-src="//cdn.example/poster.jpg"></article>
            <article class="item"><h3><a href="/movie/">Duplicate</a></h3></article>
            <article class="item"><a href="https://ads.example/">Ad</a></article>
        """.trimIndent(), base)
        val cards = SiteParser.cards(doc)
        assertEquals(1, cards.size)
        assertEquals("https://25-hd.com/movie/", cards.single().url)
        assertEquals("https://cdn.example/poster.jpg", cards.single().poster)
    }

    @Test fun followsActualNextLink() {
        val doc = SiteParser.document("""<a rel="next" href="/?page=2">Next</a>""", base)
        assertEquals("https://25-hd.com/?page=2", SiteParser.nextPage(doc))
        assertNull(SiteParser.nextPage(SiteParser.document("""<a rel="next" href="https://ads.example/">Next</a>""", base)))
    }

    @Test fun seriesSortsNumericallyAndKeepsUnknownEpisodeNumbersUnknown() {
        val doc = SiteParser.document("""
          <h1>ซีรีส์ (2025)</h1><div class="episode-list">
            <a href="/ep10/">ตอนที่ 10</a><a href="/ep2/">ตอนที่ 2</a>
          </div>
        """.trimIndent(), base)
        val detail = SiteParser.detail(doc)!!
        assertTrue(detail.series)
        assertEquals(listOf(2, 10), detail.episodes.map { it.number })
        assertEquals(2025, detail.year)
        val unknown = SiteParser.detail(SiteParser.document("""<h1>Series</h1><div class="episodes"><a href="/special/">Special</a></div>""", base))!!
        assertNull(unknown.episodes.single().number)
    }

    @Test fun embedsStayInsidePlayerAndRejectInvalidSchemes() {
        val doc = SiteParser.document("""
            <iframe src="https://ads.example/"></iframe>
            <div id="player"><iframe data-src="//player.example/embed/1"></iframe>
              <iframe src="javascript:alert(1)"></iframe></div>
        """.trimIndent(), base)
        assertEquals(listOf("https://player.example/embed/1"), SiteParser.embeds(doc))
        assertNull(SiteParser.absolute(base, "javascript:alert(1)"))
        assertNull(SiteParser.absolute(base, "<iframe src='x'>"))
        assertNull(SiteParser.absolute(base, "https://name:password@example.com/"))
    }

    @Test fun mediaKeepsSignedQueryAndCaptions() {
        val doc = SiteParser.document("""
            <video><source src="https://cdn.example/video.m3u8?token=abc&amp;expires=123" label="1080p">
              <track kind="subtitles" src="/thai.vtt" label="ไทย"></video>
            <script>player.setup({file: "https://cdn.example/backup.mp4?key=123"});</script>
        """.trimIndent(), base)
        assertEquals(2, SiteParser.media(doc).size)
        assertEquals("https://cdn.example/video.m3u8?token=abc&expires=123", SiteParser.media(doc).first().url)
        assertEquals("ไทย", SiteParser.captions(doc).single().label)
        assertTrue(SiteParser.isMedia("https://cdn.example/movie.M3U8?x=y"))
        assertFalse(SiteParser.isMedia("https://player.example/embed/1"))
    }

    @Test fun ajaxRequiresExplicitDooPlayAttributes() {
        val doc = SiteParser.document("""
          <li data-post="42" data-nume="1" data-type="movie"></li>
          <li data-post="bad" data-nume="2" data-type="movie"></li>
        """.trimIndent(), base)
        assertEquals(listOf(SiteParser.AjaxPlayer("42", "1", "movie")), SiteParser.ajaxPlayers(doc))
    }

    @Test fun emptySearchMustBeExplicit() {
        assertFalse(SiteParser.hasExplicitNoResults(SiteParser.document("<h1>Just a moment</h1>", base)))
        assertTrue(SiteParser.hasExplicitNoResults(SiteParser.document("<div class='no-results'>ไม่พบผลการค้นหา</div>", base)))
    }
}
