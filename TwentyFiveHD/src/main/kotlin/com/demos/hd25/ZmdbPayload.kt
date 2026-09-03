package com.demos.hd25

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI

/** Response contracts supplied by the user. No captured tokens or signed URLs are stored. */
internal object ZmdbPayload {
    private val json = ObjectMapper()
    private val videoPath = Regex("/api/video/[a-fA-F0-9]{24}")

    data class Video(val hlsUrl: String, val availableQualities: List<String>)
    data class PlayerLink(val url: String, val language: String, val quality: String, val server: String)
    data class EpisodeLinks(val season: Int?, val episode: Int?, val players: List<PlayerLink>)

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
