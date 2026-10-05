package com.demos.animeruka

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * animeruka.com runs the DooPlay WordPress theme. Selectors follow DooPlay's markup and the
 * site-specific contract documented by a maintained scraper (natajrak/IPTV-Player, Sep 2026):
 * `div.se-c` sections whose `.se-t` label ends in "th" are Thai dubs, other sections are Thai subs;
 * episodes are `ul.episodios li` with `.numerando` and `.episodiotitle a`; players come from
 * `/wp-json/dooplayer/v2/<post>/<type>/<nume>`, whose `embed_url` points at animemami.xyz, and the
 * embed page carries the stream in an Inertia `data-page` JSON at `props.video.url`.
 */
internal object RukaParser {
    data class Card(val title: String, val url: String, val poster: String?, val movie: Boolean)
    data class Episode(val url: String, val number: Int?, val title: String, val thumb: String?)
    data class Section(val name: String, val dubbed: Boolean, val season: Int?, val episodes: List<Episode>)
    data class Detail(
        val title: String, val poster: String?, val plot: String?, val year: Int?, val tags: List<String>,
        val sections: List<Section>, val hasPlayer: Boolean,
    )
    data class PlayerOption(val post: String, val type: String, val nume: String, val label: String)

    private val json = ObjectMapper()
    private val year = Regex("\\b(?:19|20)\\d{2}\\b")
    private val number = Regex("\\d+")
    private val seasonEpisode = Regex("(\\d+)\\s*-\\s*(\\d+)")

    fun document(html: String, url: String): Document = Jsoup.parse(html, url)

    fun absolute(base: String, raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            val uri = URI(base).resolve(raw.trim().replace(" ", "%20"))
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) null
            else uri.toString()
        } catch (_: Exception) { null }
    }

    private fun sameSite(a: String, b: String): Boolean = try {
        URI(a).host?.removePrefix("www.").equals(URI(b).host?.removePrefix("www."), ignoreCase = true)
    } catch (_: Exception) { false }

    private fun image(element: Element?, base: String): String? {
        if (element == null) return null
        for (attribute in listOf("data-src", "data-lazy-src", "data-original", "src")) {
            val value = element.attr(attribute)
            // Lazy loaders put a data: placeholder in src until the real image is swapped in.
            if (value.isNotBlank() && !value.startsWith("data:")) absolute(base, value)?.let { return it }
        }
        return null
    }

    /** Title cards from the homepage, archives and search. Episode cards are left out. */
    fun cards(doc: Document): List<Card> {
        val base = doc.baseUri()
        val items = doc.select("article.item, .result-item article").filterNot {
            it.hasClass("episodes") || it.hasClass("se")
        }
        return items.mapNotNull { item ->
            val link = item.selectFirst(".data h3 a[href], .title a[href], .poster a[href], .thumbnail a[href], a[href]")
                ?: return@mapNotNull null
            val url = absolute(base, link.attr("href"))?.takeIf { sameSite(base, it) } ?: return@mapNotNull null
            val img = item.selectFirst(".poster img, .thumbnail img, img")
            val title = item.selectFirst(".data h3, .title")?.text()?.takeIf { it.isNotBlank() }
                ?: img?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: link.text().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val movie = item.hasClass("movies") || item.selectFirst(".movies") != null || "/movie" in url
            Card(title, url, image(img, base), movie)
        }.distinctBy { it.url }
    }

    fun nextPage(doc: Document): String? =
        doc.select("a[rel=next], .pagination a.arrow_pag[href]:has(#nextpagination), .pagination a.next, a.next.page-numbers")
            .firstNotNullOfOrNull { absolute(doc.baseUri(), it.attr("href"))?.takeIf { url -> url != doc.baseUri() } }

    private fun episodeNumber(text: String): Int? =
        seasonEpisode.find(text)?.groupValues?.get(2)?.toIntOrNull() ?: number.find(text)?.value?.toIntOrNull()

    fun detail(doc: Document): Detail? {
        val base = doc.baseUri()
        val title = doc.selectFirst(".sheader .data h1, .data h1")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = image(doc.selectFirst(".sheader .poster img"), base)
            ?: absolute(base, doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("#info .wp-content, .wp-content, [itemprop=description]")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.takeIf { it.isNotBlank() }
        val tags = doc.select(".sgeneros a, .genres a, a[rel=tag]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val yearText = doc.selectFirst(".sheader .extra .date, .extra .date, .date")?.text().orEmpty() + " " + title
        val sections = doc.select("div.se-c").mapNotNull { section ->
            val name = section.selectFirst(".se-t")?.text()?.trim().orEmpty()
            val label = section.selectFirst(".se-q .title, .se-q")?.text()?.trim().orEmpty().ifBlank { name }
            val episodes = section.select("ul.episodios li").mapNotNull { li ->
                val link = li.selectFirst(".episodiotitle a[href]") ?: li.selectFirst("a[href]") ?: return@mapNotNull null
                val url = absolute(base, link.attr("href"))?.takeIf { sameSite(base, it) } ?: return@mapNotNull null
                val numbering = li.selectFirst(".numerando")?.text().orEmpty()
                Episode(url, episodeNumber(numbering) ?: episodeNumber(link.text()), link.text().trim(), image(li.selectFirst(".imagen img"), base))
            }.distinctBy { it.url }
            if (episodes.isEmpty()) return@mapNotNull null
            // Site convention: a section label ending in "th" (e.g. "ภาค1th") is the Thai dub.
            val dubbed = Regex("th\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(name) ||
                ("พากย์" in label && "ซับ" !in label)
            Section(label, dubbed,
                number.find(name)?.value?.toIntOrNull(), episodes.sortedBy { it.number ?: Int.MAX_VALUE })
        }
        return Detail(title, poster, plot, year.find(yearText)?.value?.toIntOrNull(), tags, sections, playerOptions(doc).isNotEmpty())
    }

    fun playerOptions(doc: Document): List<PlayerOption> = doc.select("li.dooplay_player_option[data-post][data-nume], [data-post][data-nume][data-type]")
        .mapNotNull {
            val post = it.attr("data-post").takeIf { p -> p.isNotBlank() && p.all(Char::isDigit) } ?: return@mapNotNull null
            val nume = it.attr("data-nume").takeIf { n -> n.isNotBlank() && n.all(Char::isLetterOrDigit) } ?: return@mapNotNull null
            val type = it.attr("data-type").ifBlank { "tv" }.takeIf { t -> t.all(Char::isLetter) } ?: return@mapNotNull null
            PlayerOption(post, type, nume, it.selectFirst(".title")?.text()?.trim().orEmpty().ifBlank { it.text().trim() })
        }.distinctBy { Triple(it.post, it.type, it.nume) }
        // "trailer" options are YouTube trailers, not episodes.
        .filter { it.nume != "trailer" }

    fun playerApi(base: String, option: PlayerOption): String =
        "${base.trimEnd('/')}/wp-json/dooplayer/v2/${option.post}/${option.type}/${option.nume}"

    /** `embed_url` is usually a URL but DooPlay may also return an iframe snippet. */
    fun embedUrl(body: String, base: String): String? {
        val root = try { json.readTree(body) } catch (_: Exception) { null } ?: return null
        val raw = root.path("embed_url").asText("").ifBlank { root.path("data").path("embed_url").asText("") }
        if (raw.isBlank()) return null
        val src = if (raw.trimStart().startsWith("<")) Jsoup.parse(raw).selectFirst("iframe[src]")?.attr("src") else raw
        return absolute(base, src)
    }

    fun isAnimemami(url: String): Boolean = try {
        URI(url).host?.lowercase()?.let { it == "animemami.xyz" || it.endsWith(".animemami.xyz") } == true
    } catch (_: Exception) { false }

    /** Stream URL from the embed page's Inertia `data-page` attribute (jsoup decodes the entities). */
    fun streamFromEmbed(doc: Document): String? {
        val page = doc.selectFirst("#app[data-page], [data-page]")?.attr("data-page")?.takeIf { it.isNotBlank() } ?: return null
        val root = try { json.readTree(page) } catch (_: Exception) { null } ?: return null
        val url = root.path("props").path("video").path("url").asText("")
        return absolute(doc.baseUri(), url)
    }
}
