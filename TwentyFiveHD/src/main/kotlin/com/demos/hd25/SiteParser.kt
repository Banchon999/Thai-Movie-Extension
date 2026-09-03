package com.demos.hd25

import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** 25-HD selectors verified against browser DOM excerpts captured on 2026-09-03. */
internal object SiteParser {
    data class Card(val title: String, val url: String, val poster: String?, val series: Boolean)
    data class Part(val title: String, val url: String, val season: Int?, val number: Int?)
    data class AjaxPlayer(val post: String, val number: String, val type: String)
    data class Media(val url: String, val label: String = "")
    data class Caption(val url: String, val label: String)
    data class Detail(
        val title: String, val poster: String?, val plot: String?, val year: Int?,
        val tags: List<String>, val episodes: List<Part>, val series: Boolean,
    )

    private const val CARDS = ".movie_box, article.item, article.post, .movie-item, .movie-box, .halim-item, .box-movie, .items > .item"
    private const val EPISODES = "#seasons .episodios a[href], .episodes a[href], .episode-list a[href], .list-episodes a[href], .halim-list-eps a[href]"
    private const val PLAYER = "#box-player, .real-player-container, #player, #dooplay_player_content, .dooplay_player, .player, .player-container, .movieplay, #movie-player, .entry-content"
    private val episodeNumber = Regex("(?:ตอนที่|ตอน|EP(?:ISODE)?[. ]*)\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val seasonNumber = Regex("(?:SEASON|ซีซั่น|S)\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val yearNumber = Regex("\\b(?:19|20)\\d{2}\\b")
    private val mediaExtension = Regex("\\.(?:m3u8|mp4|webm)(?:[?#]|$)", RegexOption.IGNORE_CASE)
    private val literalMedia = Regex("""["'](?:file|src)["']\s*:\s*["']([^"']+)["']|\bfile\s*:\s*["']([^"']+)["']""")

    fun document(html: String, url: String): Document = Jsoup.parse(html, url)

    fun absolute(base: String, raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            val uri = URI(base).resolve(raw.trim().replace("\\/", "/"))
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) null
            else uri.toString()
        } catch (_: Exception) { null }
    }

    fun sameSite(a: String, b: String): Boolean = try {
        URI(a).host?.removePrefix("www.").equals(URI(b).host?.removePrefix("www."), ignoreCase = true)
    } catch (_: Exception) { false }

    private fun image(element: Element?, base: String): String? {
        if (element == null) return null
        for (attribute in listOf("data-src", "data-lazy-src", "data-original", "src")) {
            absolute(base, element.attr(attribute))?.let { return it }
        }
        return null
    }

    fun cards(doc: Document): List<Card> = doc.select(CARDS).mapNotNull { card ->
        val link = card.selectFirst("h2 a[href], h3 a[href], .title a[href], .data a[href], a[href]") ?: return@mapNotNull null
        val url = absolute(doc.baseUri(), link.attr("href")) ?: return@mapNotNull null
        if (!sameSite(doc.baseUri(), url)) return@mapNotNull null
        val img = card.selectFirst("img")
        // Search-result <p> text is shortened; aria-label retains the complete title.
        val title = link.attr("aria-label").takeIf { it.isNotBlank() }
            ?: card.selectFirst("h2, h3, .title, .data h3")?.text()?.takeIf { it.isNotBlank() }
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: img?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: link.text().takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val series = card.hasClass("tvshows") || card.selectFirst(".box-movie-episode, .episodes, .episode") != null || episodeNumber.containsMatchIn(title)
        Card(title, url, image(img, doc.baseUri()), series)
    }.distinctBy { it.url }

    fun nextPage(doc: Document): String? = doc.select("a[rel=next], a.next.page-numbers, .pagination a.next").firstNotNullOfOrNull {
        absolute(doc.baseUri(), it.attr("href"))?.takeIf { url -> sameSite(doc.baseUri(), url) && url != doc.baseUri() }
    }

    fun hasExplicitNoResults(doc: Document): Boolean = doc.selectFirst(".no-results, .not-found, .search-no-results") != null ||
        doc.body().text().let { it.contains("ไม่พบผลการค้นหา") || it.contains("ไม่พบข้อมูล") || it.contains("Nothing Found", true) }

    fun detail(doc: Document): Detail? {
        // A union with plain h1 would still select the site's banner first in DOM order.
        val title = doc.selectFirst(".h1-text h1")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1.entry-title, .sheader h1")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = absolute(doc.baseUri(), doc.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: image(doc.selectFirst(".sheader .poster img, .movie-poster img, .poster img"), doc.baseUri())
        val plot = doc.selectFirst(".movie-excerpt .movie-content")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".wp-content, .entry-content .description, .synopsis, .description")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.takeIf { it.isNotBlank() }
        val tags = doc.select(".movie-tags a, .sgeneros a, a[rel='category tag'], a[rel=category], .genres a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val episodes = doc.select(EPISODES).mapNotNull { link ->
            val href = absolute(doc.baseUri(), link.attr("href")) ?: return@mapNotNull null
            if (!sameSite(doc.baseUri(), href) || link.attr("href").startsWith("#")) return@mapNotNull null
            val text = link.text().trim().ifBlank { link.attr("title") }
            if (text.isBlank()) return@mapNotNull null
            val number = episodeNumber.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: text.toIntOrNull()
            val seasonText = link.closest(".se-c")?.selectFirst(".se-q")?.text().orEmpty()
            val season = seasonNumber.find("$seasonText $text")?.groupValues?.get(1)?.toIntOrNull()
            Part(text, href, season, number)
        }.distinctBy { it.url }.let { parts ->
            // Do not invent episode numbers when a site supplies unnumbered labels.
            if (parts.isNotEmpty() && parts.all { it.number != null }) parts.sortedWith(compareBy({ it.season ?: 1 }, { it.number })) else parts
        }
        val series = episodes.isNotEmpty() || doc.body().hasClass("single-tvshows") || doc.selectFirst("#seasons, .episode-list, .list-episodes") != null ||
            embeds(doc).any { Regex("[?&]type=tv(?:&|$)").containsMatchIn(it) }
        return Detail(title, poster, plot, yearNumber.find(title)?.value?.toIntOrNull(), tags, episodes, series)
    }

    fun ajaxPlayers(doc: Document): List<AjaxPlayer> = doc.select("[data-post][data-nume][data-type]").mapNotNull {
        val post = it.attr("data-post")
        if (!post.all(Char::isDigit) || post.isBlank()) null else AjaxPlayer(post, it.attr("data-nume"), it.attr("data-type"))
    }.distinct()

    fun embeds(doc: Document, embedPage: Boolean = false): List<String> {
        val roots: List<Element> = if (embedPage) listOf(doc.body()) else doc.select(PLAYER).toList()
        return roots.flatMap { it.select("iframe[src], iframe[data-src], iframe[data-original-src], [data-iframe], [data-embed]") }.mapNotNull { e ->
            listOf("data-original-src", "data-src", "src", "data-iframe", "data-embed").firstNotNullOfOrNull { absolute(doc.baseUri(), e.attr(it)) }
        }.distinct()
    }

    fun isMedia(url: String): Boolean = mediaExtension.containsMatchIn(url)

    fun media(doc: Document): List<Media> {
        val tags = doc.select("video[src], video source[src]").mapNotNull {
            absolute(doc.baseUri(), it.attr("src"))?.let { url -> Media(url, it.attr("label")) }
        }
        val scripts = doc.select("script:not([src])").flatMap { script ->
            literalMedia.findAll(script.data()).mapNotNull { match ->
                val raw = match.groupValues[1].ifBlank { match.groupValues[2] }
                absolute(doc.baseUri(), raw)?.takeIf(::isMedia)?.let { Media(it) }
            }.toList()
        }
        return (tags + scripts).distinctBy { it.url }
    }

    fun captions(doc: Document): List<Caption> = doc.select("track[src]").filter {
        it.attr("kind") in listOf("subtitles", "captions", "")
    }.mapNotNull {
        absolute(doc.baseUri(), it.attr("src"))?.let { url -> Caption(url, it.attr("label").ifBlank { it.attr("srclang").ifBlank { "Subtitle" } }) }
    }.distinctBy { it.url }
}
