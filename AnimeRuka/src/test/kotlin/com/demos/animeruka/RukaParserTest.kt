package com.demos.animeruka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markup follows the DooPlay theme and the animeruka contract documented by an independent
 * scraper; it is synthetic because animeruka.com refuses the development network (Cloudflare block).
 */
class RukaParserTest {
    private val base = "https://animeruka.com/"

    @Test
    fun readsTitleCardsAndSkipsEpisodeCards() {
        val doc = RukaParser.document("""
            <div class="items">
              <article id="post-1" class="item tvshows">
                <div class="poster"><img src="data:image/gif;base64,R0" data-src="/wp-content/uploads/a.jpg" alt="Alt">
                  <a href="https://animeruka.com/anime/megami-no-cafe-terrace-2nd-season/"></a></div>
                <div class="data"><h3><a href="https://animeruka.com/anime/megami-no-cafe-terrace-2nd-season/">Megami no Café Terrace 2</a></h3><span>2024</span></div>
              </article>
              <article id="post-2" class="item movies">
                <div class="poster"><img src="https://animeruka.com/b.jpg" alt="Movie"></div>
                <div class="data"><h3><a href="https://animeruka.com/movies/suzume/">Suzume</a></h3></div>
              </article>
              <article class="item se episodes"><div class="data"><h3><a href="https://animeruka.com/ep/x-1/">EP 1</a></h3></div></article>
              <article class="item tvshows"><div class="data"><h3><a href="https://elsewhere.example/anime/x/">Off site</a></h3></div></article>
            </div>
            <div class="pagination"><span class="current">1</span><a class="arrow_pag" href="https://animeruka.com/page/2/"><i id="nextpagination"></i></a></div>
        """.trimIndent(), base)
        val cards = RukaParser.cards(doc)
        assertEquals(listOf("Megami no Café Terrace 2", "Suzume"), cards.map { it.title })
        assertEquals("https://animeruka.com/wp-content/uploads/a.jpg", cards[0].poster)
        assertEquals(listOf(false, true), cards.map { it.movie })
        assertEquals("https://animeruka.com/page/2/", RukaParser.nextPage(doc))
    }

    @Test
    fun readsSearchResults() {
        val doc = RukaParser.document("""
            <div class="search-page"><div class="result-item"><article>
              <div class="image"><div class="thumbnail animation-2"><a href="https://animeruka.com/anime/shangri-la-frontier/">
                <img src="https://animeruka.com/s.jpg" alt="Shangri-La Frontier"></a></div></div>
              <div class="details"><div class="title"><a href="https://animeruka.com/anime/shangri-la-frontier/">Shangri-La Frontier</a></div></div>
            </article></div></div>
        """.trimIndent(), "https://animeruka.com/?s=shangri")
        val cards = RukaParser.cards(doc)
        assertEquals(1, cards.size)
        assertEquals("https://animeruka.com/anime/shangri-la-frontier/", cards[0].url)
        assertEquals("https://animeruka.com/s.jpg", cards[0].poster)
    }

    @Test
    fun splitsDubAndSubSectionsAndNumbersEpisodes() {
        val doc = RukaParser.document("""
            <html><head><meta property="og:image" content="https://animeruka.com/og.jpg"></head><body>
            <div class="sheader"><div class="data"><h1>Shangri-La Frontier</h1><div class="extra"><span class="date">Oct. 01, 2023</span></div>
              <div class="sgeneros"><a href="/genre/action/">Action</a><a href="/genre/game/">Game</a></div></div></div>
            <div id="info"><div class="wp-content"><p>เรื่องย่อ</p></div></div>
            <div id="seasons">
              <div class="se-c"><div class="se-q"><span class="se-t se-o">ภาค1th</span><span class="title">พากย์ไทย</span></div>
                <div class="se-a"><ul class="episodios">
                  <li><div class="imagen"><img src="https://animeruka.com/e2.jpg"></div><div class="numerando">2</div>
                    <div class="episodiotitle"><a href="https://animeruka.com/ep/slf-th-2/">ตอนที่ 2</a></div></li>
                  <li><div class="numerando">1</div><div class="episodiotitle"><a href="https://animeruka.com/ep/slf-th-1/">ตอนที่ 1</a></div></li>
                </ul></div></div>
              <div class="se-c"><div class="se-q"><span class="se-t">ภาค2</span></div>
                <div class="se-a"><ul class="episodios">
                  <li><div class="numerando">2 - 5</div><div class="episodiotitle"><a href="https://animeruka.com/ep/slf2-5/">EP5</a></div></li>
                </ul></div></div>
            </div></body></html>
        """.trimIndent(), "https://animeruka.com/anime/shangri-la-frontier/")
        val detail = RukaParser.detail(doc)!!
        assertEquals("Shangri-La Frontier", detail.title)
        assertEquals("https://animeruka.com/og.jpg", detail.poster)
        assertEquals("เรื่องย่อ", detail.plot)
        assertEquals(2023, detail.year)
        assertEquals(listOf("Action", "Game"), detail.tags)
        assertEquals(listOf(true, false), detail.sections.map { it.dubbed })
        assertEquals(listOf(1, 2), detail.sections.map { it.season })
        assertEquals(listOf(1, 2), detail.sections[0].episodes.map { it.number })
        assertEquals("https://animeruka.com/e2.jpg", detail.sections[0].episodes[1].thumb)
        // DooPlay "season - episode" numbering keeps the episode number.
        assertEquals(5, detail.sections[1].episodes.single().number)
        assertFalse(detail.hasPlayer)
    }

    @Test
    fun readsPlayerOptionsAndApiEmbeds() {
        val doc = RukaParser.document("""
            <ul id="playeroptionsul">
              <li id="player-option-1" class="dooplay_player_option" data-type="tv" data-post="12345" data-nume="1"><span class="title">พากย์ไทย</span></li>
              <li id="player-option-2" class="dooplay_player_option" data-type="tv" data-post="12345" data-nume="2"><span class="title">สำรอง</span></li>
              <li id="player-option-trailer" class="dooplay_player_option" data-type="tv" data-post="12345" data-nume="trailer"><span class="title">Trailer</span></li>
            </ul>
        """.trimIndent(), "https://animeruka.com/ep/slf-th-1/")
        val options = RukaParser.playerOptions(doc)
        assertEquals(listOf("1", "2"), options.map { it.nume })
        assertEquals("พากย์ไทย", options[0].label)
        assertEquals("https://animeruka.com/wp-json/dooplayer/v2/12345/tv/1", RukaParser.playerApi(base, options[0]))
        assertEquals("https://animemami.xyz/v/abcDEF123",
            RukaParser.embedUrl("""{"embed_url":"https:\/\/animemami.xyz\/v\/abcDEF123","type":"iframe"}""", base))
        assertEquals("https://ok.ru/videoembed/1",
            RukaParser.embedUrl("""{"embed_url":"<iframe src=\"https://ok.ru/videoembed/1\" frameborder=\"0\"></iframe>","type":"iframe"}""", base))
        assertNull(RukaParser.embedUrl("""{"embed_url":"","type":false}""", base))
        assertTrue(RukaParser.isAnimemami("https://animemami.xyz/v/abc"))
        assertFalse(RukaParser.isAnimemami("https://animemami.xyz.evil.example/v/abc"))
    }

    @Test
    fun readsStreamFromInertiaPage() {
        val page = """{&quot;component&quot;:&quot;Player&quot;,&quot;props&quot;:{&quot;video&quot;:{&quot;url&quot;:&quot;https:\/\/cdn2.maimeorder.com\/hls\/SXNDR0==.txt&quot;,&quot;title&quot;:&quot;EP1&quot;}},&quot;url&quot;:&quot;\/v\/abc&quot;}"""
        val doc = RukaParser.document("""<html><body><div id="app" data-page="$page"></div></body></html>""", "https://animemami.xyz/v/abc")
        assertEquals("https://cdn2.maimeorder.com/hls/SXNDR0==.txt", RukaParser.streamFromEmbed(doc))
        assertNull(RukaParser.streamFromEmbed(RukaParser.document("<div id=app></div>", "https://animemami.xyz/v/abc")))
    }
}
