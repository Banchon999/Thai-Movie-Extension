package com.demos.animeruka

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import java.util.concurrent.CancellationException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document

class AnimeRukaProvider : MainAPI() {
    override var mainUrl = "https://animeruka.com"
    override var name = "AnimeRuka (ทดลอง)"
    override var lang = "th"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override val mainPage = mainPageOf("/" to "อัปเดตล่าสุด", "/anime/" to "อนิเมะทั้งหมด")

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "th-TH,th;q=0.9,en;q=0.8",
    )
    /** The stream CDN only answers with the embed host as Referer, and refuses an Origin header. */
    private val streamReferer = "https://animemami.xyz/"
    private val pageUrls = mutableMapOf<String, String>()

    private suspend fun fetch(url: String, referer: String = "$mainUrl/"): Document {
        val response = app.get(url, headers = requestHeaders, referer = referer, timeout = 25)
        if (response.code == 403 || response.code == 503) {
            throw ErrorLoadingException("AnimeRuka ปฏิเสธคำขอ (${response.code}) ลองเปิดเว็บในเบราว์เซอร์ของ Cloudstream ก่อน")
        }
        if (response.code !in 200..299) throw ErrorLoadingException("โหลดหน้าไม่สำเร็จ: HTTP ${response.code}")
        return RukaParser.document(response.text, response.url)
    }

    private fun RukaParser.Card.toSearch(): SearchResponse =
        newAnimeSearchResponse(title, url, if (movie) TvType.AnimeMovie else TvType.Anime) {
            posterUrl = poster
            posterHeaders = requestHeaders + ("Referer" to "$mainUrl/")
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val key = "${request.data}:$page"
        val url = if (page == 1) "$mainUrl${request.data}" else synchronized(pageUrls) { pageUrls[key] }
            ?: return newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
        val doc = fetch(url)
        val cards = RukaParser.cards(doc)
        if (cards.isEmpty()) throw ErrorLoadingException("อ่านรายการจาก AnimeRuka ไม่ได้ โครงสร้างเว็บอาจเปลี่ยน")
        val next = RukaParser.nextPage(doc)
        synchronized(pageUrls) { if (next != null) pageUrls["${request.data}:${page + 1}"] = next }
        return newHomePageResponse(HomePageList(request.name, cards.map { it.toSearch() }), hasNext = next != null)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return RukaParser.cards(fetch("$mainUrl/?s=${URLEncoder.encode(query.trim(), "UTF-8")}")).map { it.toSearch() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = fetch(url)
        val item = RukaParser.detail(doc) ?: throw ErrorLoadingException("ไม่พบชื่อเรื่องในหน้าเว็บ")
        val posterHeaders = requestHeaders + ("Referer" to "$mainUrl/")
        if (item.sections.isEmpty()) {
            // Movies keep their players on the title page itself.
            return newMovieLoadResponse(item.title, url, TvType.AnimeMovie, url) {
                posterUrl = item.poster
                this.posterHeaders = posterHeaders
                plot = item.plot
                year = item.year
                tags = item.tags
            }
        }
        return newAnimeLoadResponse(item.title, url, TvType.Anime) {
            posterUrl = item.poster
            this.posterHeaders = posterHeaders
            plot = item.plot
            year = item.year
            tags = item.tags
            for ((dubbed, sections) in item.sections.groupBy { it.dubbed }) {
                val episodes = sections.flatMap { section ->
                    section.episodes.map { ep ->
                        newEpisode(ep.url) {
                            name = ep.title
                            episode = ep.number
                            season = section.season
                            posterUrl = ep.thumb
                        }
                    }
                }
                addEpisodes(if (dubbed) DubStatus.Dubbed else DubStatus.Subbed, episodes)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val page = fetch(data)
        val options = RukaParser.playerOptions(page)
        if (options.isEmpty()) throw ErrorLoadingException("ไม่พบตัวเล่นในหน้านี้")
        var found = false
        var lastFailure: String? = null
        for (option in options.take(6)) {
            try {
                val api = app.get(RukaParser.playerApi(mainUrl, option), headers = requestHeaders, referer = data, timeout = 25)
                val embed = (if (api.code in 200..299) RukaParser.embedUrl(api.text, mainUrl) else null)
                    ?: ajaxEmbed(option, data)
                    ?: throw ErrorLoadingException("ตัวเล่น ${option.nume}: ไม่พบลิงก์ฝัง")
                val label = option.label.ifBlank { "ตัวเล่น ${option.nume}" }
                if (RukaParser.isAnimemami(embed)) {
                    // The embed host checks that it is framed from animeruka.com.
                    val player = app.get(embed, headers = requestHeaders, referer = "$mainUrl/", timeout = 25)
                    if (player.code !in 200..299) throw ErrorLoadingException("animemami: HTTP ${player.code}")
                    val stream = RukaParser.streamFromEmbed(RukaParser.document(player.text, embed))
                        ?: throw ErrorLoadingException("animemami: ไม่พบ video.url")
                    callback(newExtractorLink(name, "AnimeRuka • $label", stream, type = ExtractorLinkType.M3U8) {
                        referer = streamReferer
                        headers = requestHeaders
                        quality = Qualities.Unknown.value
                    })
                    found = true
                } else if (loadExtractor(embed, data, subtitleCallback) { callback(it); found = true }) {
                    // Other mirrors (e.g. ok.ru) go through Cloudstream's own extractors.
                } else {
                    lastFailure = "ไม่รองรับตัวเล่นนี้"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastFailure = e.message
            }
        }
        if (!found) throw ErrorLoadingException("ยังดึงลิงก์วิดีโอไม่ได้" + (lastFailure?.let { ": $it" } ?: ""))
        return true
    }

    /** Older DooPlay setups only expose players through admin-ajax. */
    private suspend fun ajaxEmbed(option: RukaParser.PlayerOption, referer: String): String? = try {
        val response = app.post("$mainUrl/wp-admin/admin-ajax.php", referer = referer, headers = requestHeaders, timeout = 25,
            data = mapOf("action" to "doo_player_ajax", "post" to option.post, "nume" to option.nume, "type" to option.type))
        if (response.code in 200..299) RukaParser.embedUrl(response.text, mainUrl) else null
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }

    /**
     * The stream CDN has been seen wrapping playlists as `{"p":"<base64 m3u8>"}` for some clients;
     * unwrap those so the player still receives HLS. Other responses pass through untouched.
     */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val head = try { response.peekBody(16).string().trimStart() } catch (_: Exception) { "" }
        if (!head.startsWith("{\"p\":\"")) return@Interceptor response
        val playlist = try {
            val wrapped = mapper.readTree(response.body.string()).path("p").asText("")
            base64Decode(wrapped)
        } catch (_: Exception) { null } finally { response.close() }
        if (playlist == null || !playlist.trimStart().startsWith("#EXTM3U")) {
            return@Interceptor response.newBuilder().code(502).message("Bad wrapped playlist")
                .body("".toResponseBody("text/plain".toMediaType())).build()
        }
        response.newBuilder().body(playlist.toResponseBody("application/vnd.apple.mpegurl".toMediaType())).build()
    }
}
