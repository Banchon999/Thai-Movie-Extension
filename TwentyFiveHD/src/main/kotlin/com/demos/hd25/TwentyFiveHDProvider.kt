package com.demos.hd25

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URLEncoder
import java.util.concurrent.CancellationException
import org.jsoup.nodes.Document

class TwentyFiveHDProvider : MainAPI() {
    override var mainUrl = "https://25-hd.com"
    override var name = "25-HD (ทดลอง)"
    override var lang = "th"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf("/" to "อัปเดตล่าสุด")

    private val diagnostics = PlaybackDiagnostics()
    private val diagnosticsUrl = "https://25-hd.com/__25hd_diagnostics__"

    override fun getVideoInterceptor(extractorLink: ExtractorLink): okhttp3.Interceptor =
        diagnostics.interceptor(extractorLink.url)

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "th-TH,th;q=0.9,en;q=0.7",
    )

    data class Playback(val url: String = "", val referer: String = "", val season: Int? = null, val episode: Int? = null)
    private val zmdb = ZmdbClient { url, referer, headers ->
        val response = app.get(url, referer = referer, headers = requestHeaders + headers, timeout = 25)
        ZmdbClient.Response(response.code, response.text)
    }
    private data class Pending(val url: String, val referer: String, val depth: Int)
    // Keyed by homepage section and page number: follow actual next links, not guessed slugs.
    private val pageUrls = mutableMapOf<String, String>()

    private suspend fun fetch(url: String, referer: String = "$mainUrl/"): Document {
        val response = app.get(url, headers = requestHeaders, referer = referer, timeout = 25)
        if (response.code == 403 || response.code == 503) {
            throw ErrorLoadingException("25-HD/ตัวเล่นปฏิเสธคำขอ (${response.code}) ลองเปิดเว็บในเบราว์เซอร์ของ Cloudstream ก่อน")
        }
        if (response.code !in 200..299) throw ErrorLoadingException("โหลดหน้าไม่สำเร็จ: HTTP ${response.code}")
        val doc = SiteParser.document(response.text, response.url)
        if (doc.selectFirst("#challenge-form, #cf-challenge-running") != null || doc.title().contains("Just a moment", true)) {
            throw ErrorLoadingException("เว็บกำลังตรวจสอบเบราว์เซอร์ ให้เปิดเว็บใน Cloudstream ก่อนแล้วลองใหม่")
        }
        return doc
    }

    private fun SiteParser.Card.toSearch(): SearchResponse = if (series) {
        newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            posterUrl = poster
            posterHeaders = requestHeaders + ("Referer" to "$mainUrl/")
        }
    } else {
        newMovieSearchResponse(title, url, TvType.Movie) {
            posterUrl = poster
            posterHeaders = requestHeaders + ("Referer" to "$mainUrl/")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val first = SiteParser.absolute(mainUrl, request.data) ?: throw ErrorLoadingException("URL หน้าหลักไม่ถูกต้อง")
        val key = "${request.data}:$page"
        val url = if (page == 1) first else synchronized(pageUrls) { pageUrls[key] }
            ?: return newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
        val doc = fetch(url)
        val cards = SiteParser.cards(doc)
        if (cards.isEmpty() && !SiteParser.hasExplicitNoResults(doc)) {
            throw ErrorLoadingException("อ่านรายการจาก 25-HD ไม่ได้ โครงสร้างเว็บอาจไม่ตรงกับเวอร์ชันทดลองนี้")
        }
        val next = SiteParser.nextPage(doc)
        synchronized(pageUrls) {
            if (page == 1) pageUrls.keys.removeAll { it.startsWith("${request.data}:") }
            if (next != null) pageUrls["${request.data}:${page + 1}"] = next
        }
        return newHomePageResponse(HomePageList(request.name, cards.map { it.toSearch() }), hasNext = next != null)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.trim().equals("25hd-debug", ignoreCase = true)) {
            return listOf(newMovieSearchResponse("25-HD v6 • รายงานการเล่น", "$diagnosticsUrl?report=${System.nanoTime()}", TvType.Movie))
        }
        if (query.isBlank()) return emptyList()
        val doc = fetch("$mainUrl/?s=${URLEncoder.encode(query.trim(), "UTF-8")}")
        val cards = SiteParser.cards(doc)
        if (cards.isEmpty() && !SiteParser.hasExplicitNoResults(doc)) {
            throw ErrorLoadingException("อ่านผลค้นหาไม่ได้ ต้องตรวจ HTML ของ 25-HD เพื่อปรับตัวอ่านเว็บ")
        }
        return cards.map { it.toSearch() }
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.substringBefore('?') == diagnosticsUrl) {
            return newMovieLoadResponse("25-HD v6 • รายงานการเล่น", url, TvType.Movie, "") {
                plot = diagnostics.report()
                comingSoon = true
            }
        }
        val doc = fetch(url)
        val item = SiteParser.detail(doc) ?: throw ErrorLoadingException("ไม่พบชื่อเรื่องในหน้าเว็บ")
        if (item.series) {
            val embed = SiteParser.embeds(doc).firstOrNull(ZmdbPayload::isEmbed)
            var episodeFailure: String? = null
            val external = if (embed != null) try {
                zmdb.bootstrap(embed, doc.baseUri()).episodes
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                episodeFailure = e.message
                emptyList()
            } else emptyList()
            val episodes = if (external.isNotEmpty()) external.map { part ->
                newEpisode(mapper.writeValueAsString(Playback(embed!!, doc.baseUri(), part.season, part.number)), initializer = {
                    name = part.title
                    season = part.season
                    episode = part.number
                }, fix = false)
            } else item.episodes.map { part ->
                newEpisode(mapper.writeValueAsString(Playback(part.url, doc.baseUri())), initializer = {
                    name = part.title
                    season = part.season
                    episode = part.number
                }, fix = false)
            }
            return newTvSeriesLoadResponse(item.title, url, TvType.TvSeries, episodes) {
                posterUrl = item.poster
                posterHeaders = requestHeaders + ("Referer" to doc.baseUri())
                // Keep details accessible even though external player controls are not yet supported.
                comingSoon = false
                plot = if (episodes.isEmpty()) listOfNotNull(
                    "ยังโหลดรายการตอนจากตัวเล่นไม่ได้" + (episodeFailure?.let { ": $it" } ?: ""),
                    item.plot,
                ).joinToString("\n\n") else item.plot
                year = item.year
                tags = item.tags
            }
        }
        return newMovieLoadResponse(item.title, url, TvType.Movie, mapper.writeValueAsString(Playback(doc.baseUri(), "$mainUrl/"))) {
            posterUrl = item.poster
            posterHeaders = requestHeaders + ("Referer" to doc.baseUri())
            plot = item.plot
            year = item.year
            tags = item.tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val input = if (data.trimStart().startsWith("{")) mapper.readValue<Playback>(data) else Playback(data, "$mainUrl/")
        val start = SiteParser.absolute(mainUrl, input.url) ?: throw ErrorLoadingException("URL ตัวเล่นไม่ถูกต้อง")
        val pending = ArrayDeque<Pending>()
        pending.add(Pending(start, input.referer.ifBlank { "$mainUrl/" }, 0))
        val visited = mutableSetOf<String>()
        val sent = mutableSetOf<String>()
        val subtitles = mutableSetOf<String>()
        var lastFailure: String? = null

        val emit: (ExtractorLink) -> Unit = { link -> if (sent.add(link.url)) callback(link) }
        val emitSubtitle: (SubtitleFile) -> Unit = { sub -> if (subtitles.add(sub.url)) subtitleCallback(sub) }

        suspend fun direct(url: String, referer: String, label: String = "", type: ExtractorLinkType? = null) {
            emit(newExtractorLink(name, label.ifBlank { name }, url, type = type) {
                this.referer = referer
                headers = requestHeaders
                quality = getQualityFromName(label)
            })
        }

        suspend fun inspect(doc: Document, depth: Int, embedPage: Boolean) {
            SiteParser.media(doc).forEach { direct(it.url, doc.baseUri(), it.label) }
            SiteParser.captions(doc).forEach { emitSubtitle(newSubtitleFile(it.label, it.url)) }
            if (depth < 3) SiteParser.embeds(doc, embedPage).take(12).forEach {
                pending.add(Pending(it, doc.baseUri(), depth + 1))
            }
        }

        while (pending.isNotEmpty() && visited.size < 12) {
            val current = pending.removeFirst()
            if (!visited.add(current.url)) continue
            try {
                if (ZmdbPayload.isEmbed(current.url)) {
                    val streams = zmdb.resolve(current.url, current.referer, input.season, input.episode)
                    for (stream in streams) {
                        try {
                            direct(stream.url, stream.referer, "ZMDB ${stream.server} • Auto", ExtractorLinkType.M3U8)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            lastFailure = e.message
                        }
                    }
                    continue
                }
                if (ZmdbPayload.isVideoApi(current.url)) {
                    val response = app.get(current.url, headers = requestHeaders, referer = current.referer, timeout = 25)
                    if (response.code !in 200..299) throw ErrorLoadingException("ZMDB video API: HTTP ${response.code}")
                    val video = ZmdbPayload.video(response.text)
                    direct(video.hlsUrl, current.referer, type = ExtractorLinkType.M3U8)
                    continue
                }
                if (SiteParser.isMedia(current.url)) {
                    direct(current.url, current.referer)
                    continue
                }
                // A matched extractor may return true without yielding a playable link.
                val before = sent.size
                if (current.depth > 0) {
                    try {
                        loadExtractor(current.url, current.referer, emitSubtitle, emit)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        lastFailure = e.message
                        // An extractor failure must not skip HTML media/iframe fallbacks.
                    }
                }
                if (sent.size > before) continue
                val doc = fetch(current.url, current.referer)
                inspect(doc, current.depth, current.depth > 0)
                // Only use DooPlay AJAX when the DOM explicitly declares its identifiers.
                if (SiteParser.sameSite(mainUrl, doc.baseUri())) {
                    for (player in SiteParser.ajaxPlayers(doc).take(8)) {
                        try {
                            val endpoint = SiteParser.absolute(doc.baseUri(), "/wp-admin/admin-ajax.php") ?: continue
                            val response = app.post(endpoint, referer = doc.baseUri(), headers = requestHeaders, timeout = 25,
                                data = mapOf("action" to "doo_player_ajax", "post" to player.post, "nume" to player.number, "type" to player.type))
                            if (response.code !in 200..299) continue
                            val json = mapper.readTree(response.text)
                            val embed = json.path("embed_url").asText("").ifBlank { json.path("data").path("embed_url").asText("") }
                            if (embed.isBlank()) continue
                            val link = SiteParser.absolute(doc.baseUri(), embed)
                            if (link != null && current.depth < 3) pending.add(Pending(link, doc.baseUri(), current.depth + 1))
                            else inspect(SiteParser.document(embed, doc.baseUri()), current.depth, true)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            lastFailure = e.message
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastFailure = e.message
            }
        }
        if (sent.isEmpty()) throw ErrorLoadingException(
            "ยังดึงลิงก์วิดีโอไม่ได้ ตัวเล่นอาจต้องใช้ extractor เพิ่ม" + (lastFailure?.let { ": $it" } ?: "")
        )
        return true
    }
}
