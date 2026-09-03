package com.demos.hd25

import org.junit.Assert.*
import org.junit.Test

/** Regression tests use DOM excerpts actually captured from 25-HD, not invented markup. */
class LiveMarkupTest {
    private fun fixture(name: String, url: String) = SiteParser.document(
        javaClass.getResourceAsStream("/25hd/$name.html")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }, url
    )

    @Test fun homepageMovieBoxesAreReadAndDeduplicated() {
        val cards = SiteParser.cards(fixture("home", "https://25-hd.com/"))
        assertEquals(3, cards.size)
        assertEquals("Minions & Monsters (2026) มินเนี่ยน & มอนสเตอร์", cards.first().title)
        assertTrue(cards.first().poster!!.endsWith("ai10GoujvMbpWtXWvW0aPqxrDNm-200x300.jpg"))
        assertFalse(cards.first().series)
        assertTrue(cards.single { it.url.endsWith("/reacher-2022/") }.series)
    }

    @Test fun searchUsesFullAriaLabelInsteadOfTruncatedParagraph() {
        val cards = SiteParser.cards(fixture("search", "https://25-hd.com/?s=reacher"))
        assertEquals(3, cards.size)
        assertEquals("Reacher Season 1-4 (2026) แจ็ค รีชเชอร์ ยอดคนสืบระห่ำ ซีซั่น 1-4", cards.first().title)
        assertFalse(cards.first().title.contains("..."))
        assertEquals(1, cards.count { it.series })
    }

    @Test fun detailDoesNotUseGlobalBannerHeadingAsMovieTitle() {
        val detail = SiteParser.detail(fixture("movie", "https://25-hd.com/minions-monsters-2026/"))!!
        assertEquals("Minions & Monsters (2026) มินเนี่ยน & มอนสเตอร์", detail.title)
        assertEquals(2026, detail.year)
        assertTrue(detail.poster!!.endsWith("ai10GoujvMbpWtXWvW0aPqxrDNm-1.jpg"))
        assertTrue(detail.plot!!.startsWith("นี่คือเรื่องราวแสนชุลมุน"))
        assertTrue(detail.tags.contains("Animation"))
        assertFalse(detail.series)
    }

    @Test fun deferredPlayerUsesOriginalSrcEvenWhenSrcIsEmpty() {
        val links = SiteParser.embeds(fixture("movie", "https://25-hd.com/minions-monsters-2026/"))
        assertEquals(listOf("https://zmdb.net/embed?id=1315772&type=movie"), links)
    }

    @Test fun tvEmbedIsRecognizedWithoutInventingAnEpisodeList() {
        val doc = fixture("series", "https://25-hd.com/reacher-2022/")
        assertEquals(listOf("https://zmdb.net/embed?id=108978&type=tv"), SiteParser.embeds(doc))
        val detail = SiteParser.detail(doc)!!
        assertTrue(detail.series)
        assertTrue(detail.episodes.isEmpty())
    }

    @Test fun displayedMoviePosterOverridesDifferentOgImage() {
        val doc = fixture("movie", "https://25-hd.com/minions-monsters-2026/")
        doc.body().append(fixture("movie-poster", doc.baseUri()).body().html())
        assertTrue(SiteParser.detail(doc)!!.poster!!.endsWith("ai10GoujvMbpWtXWvW0aPqxrDNm-683x1024.jpg"))
    }

    @Test fun displayedSeriesPosterOverridesDifferentOgImage() {
        val doc = fixture("series", "https://25-hd.com/reacher-2022/")
        doc.body().append(fixture("series-poster", doc.baseUri()).body().html())
        assertTrue(SiteParser.detail(doc)!!.poster!!.endsWith("jjZYr2F7hOkruaac5fUSMvt77xK-scaled-2-683x1024.jpg"))
    }
}
