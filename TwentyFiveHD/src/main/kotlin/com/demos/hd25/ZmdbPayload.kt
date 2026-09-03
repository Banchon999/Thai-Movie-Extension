package com.demos.hd25

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLDecoder
import org.jsoup.nodes.Document

/** Response contracts supplied by the user. No captured tokens or signed URLs are stored. */
internal object ZmdbPayload {
    private val json = ObjectMapper()
    private val videoPath = Regex("/api/video/[a-fA-F0-9]{24}")

    data class Video(val hlsUrl: String, val availableQualities: List<String>)
    data class PlayerLink(val url: String, val language: String, val quality: String, val server: String)
    data class EpisodeLinks(val season: Int?, val episode: Int?, val players: List<PlayerLink>)
    data class Episode(val season: Int, val number: Int, val title: String)
    // Not a data class: default toString must not print the temporary bearer token.
    class Bootstrap(val id: String, val type: String, val token: String, val episodes: List<Episode>)

    private fun query(url: String): Map<String, String> = URI(url).rawQuery.orEmpty().split('&')
        .filter { it.isNotBlank() }.associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }

    fun isEmbed(url: String): Boolean = try {
        val uri = URI(url)
        val q = query(url)
        uri.scheme == "https" && uri.host == "zmdb.net" && uri.port == -1 && uri.userInfo == null &&
            uri.path == "/embed" && q["id"]?.matches(Regex("[0-9]+")) == true && q["type"] in setOf("tv", "movie")
    } catch (_: Exception) { false }

    fun bootstrap(doc: Document): Bootstrap {
        require(isEmbed(doc.baseUri())) { "Invalid ZMDB embed URL" }
        val script = doc.selectFirst("script#bootstrap[type=application/json]")
            ?: throw IllegalArgumentException("ไม่พบข้อมูล bootstrap ของ ZMDB (เว็บอาจปฏิเสธคำขอ)")
        val root = try { json.readTree(script.data()) } catch (_: Exception) {
            throw IllegalArgumentException("อ่าน bootstrap ของ ZMDB ไม่สำเร็จ")
        } ?: throw IllegalArgumentException("Empty ZMDB bootstrap")
        val content = root.path("content")
        require(!content.path("isVipOnly").asBoolean(false)) { "ZMDB: เนื้อหานี้จำกัดสิทธิ์ VIP" }
        require(!content.path("isComingSoon").asBoolean(false)) { "ZMDB: เรื่องนี้ยังไม่พร้อมเล่น" }
        val id = root.path("query").string("id")
        val type = root.path("query").string("type")
        val expected = query(doc.baseUri())
        require(id == expected["id"] && type == expected["type"] && content.string("mediaType") == type) {
            "ZMDB bootstrap does not match the requested title"
        }
        val parts = content.path("seasons").takeIf { it.isArray }?.flatMap { season ->
            val number = season.path("seasonNumber").takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue()
            if (number == null || number < 0) return@flatMap emptyList()
            season.path("episodes").takeIf { it.isArray }?.mapNotNull { episode ->
                val ep = episode.path("episodeNumber").takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue()
                if (ep == null || ep < 1 || !episode.path("hasLinks").asBoolean(false) ||
                    episode.path("isVipOnly").asBoolean(false)) return@mapNotNull null
                Episode(number, ep, episode.string("title").ifBlank { "ตอนที่ $ep" })
            }.orEmpty()
        }.orEmpty().distinctBy { it.season to it.number }.sortedWith(compareBy({ it.season }, { it.number }))
        return Bootstrap(id, type, root.string("linkToken"), parts)
    }

    fun linksUrl(data: Bootstrap, season: Int?, episode: Int?): String {
        val base = "https://zmdb.net/api/embed/links?id=${data.id}&type=${data.type}"
        if (data.type == "movie") return base
        require(data.episodes.any { it.season == season && it.number == episode }) { "ไม่พบตอนที่เลือกในข้อมูล ZMDB" }
        return "$base&season=$season&episode=$episode"
    }

    /** Mapping observed in the user's player links and video request; never use content.id here. */
    fun videoApiForPlayer(url: String): String? = try {
        val uri = URI(url)
        val match = Regex("/play/([a-fA-F0-9]{24})").matchEntire(uri.path)
        if (uri.scheme == "https" && uri.host == "stream037.com" && uri.userInfo == null && uri.port == -1 && match != null)
            "https://zmdb.net/api/video/${match.groupValues[1]}" else null
    } catch (_: Exception) { null }

    fun isVideoApi(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "zmdb.net" && uri.userInfo == null &&
            uri.port == -1 && videoPath.matches(uri.path)
    } catch (_: Exception) { false }

    private fun success(body: String): JsonNode {
        val root = json.readTree(body) ?: throw IllegalArgumentException("Empty ZMDB response")
        require(root.path("success").isBoolean && root.path("success").booleanValue()) {
            "ZMDB response is unsuccessful"
        }
        return root
    }

    private fun httpUrl(raw: String): String? = try {
        val uri = URI(raw)
        raw.takeIf {
            uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null
        }
    } catch (_: Exception) { null }

    private fun JsonNode.string(field: String): String = path(field).takeIf { it.isTextual }?.textValue().orEmpty()

    fun video(body: String): Video {
        val data = success(body).path("data")
        val url = httpUrl(data.string("hlsUrl"))
        require(url != null) { "ZMDB response is missing a valid data.hlsUrl" }
        // The master may have no file extension. Preserve it exactly, including its signed query.
        // seekPreview URLs are storyboard assets, not video or dialogue subtitles.
        val qualities = data.path("availableQualities").takeIf { it.isArray }
            ?.filter { it.isTextual }?.map { it.textValue() }?.distinct().orEmpty()
        return Video(url, qualities)
    }

    fun episodeLinks(body: String): EpisodeLinks {
        val root = success(body)
        val players = root.path("playerEmbedLinks").takeIf { it.isArray }?.mapNotNull { item ->
            // Only consume links explicitly marked as publicly accessible by this response.
            if (!item.path("isVipOnly").isBoolean || item.path("isVipOnly").booleanValue()) return@mapNotNull null
            val url = httpUrl(item.string("embedUrl")) ?: return@mapNotNull null
            PlayerLink(url, item.string("language"), item.string("qualityLabel"), item.string("serverLabel"))
        }?.distinctBy { it.url }.orEmpty()
        fun positiveInt(field: String) = root.path(field).takeIf { it.isIntegralNumber && it.canConvertToInt() }
            ?.intValue()?.takeIf { it > 0 }
        // linkToken is intentionally neither persisted nor included in diagnostic output.
        return EpisodeLinks(positiveInt("seasonNumber"), positiveInt("episodeNumber"), players)
    }
}
