package com.demos.hd25

import java.util.concurrent.CancellationException

/** Uses an injected transport so the complete request sequence can be tested without the live site. */
internal class ZmdbClient(
    private val get: suspend (url: String, referer: String, headers: Map<String, String>) -> Response,
) {
    data class Response(val status: Int, val body: String)
    data class Stream(val url: String, val referer: String, val server: String)

    private fun checked(response: Response, stage: String): String {
        require(response.status in 200..299) { "ZMDB $stage: HTTP ${response.status}" }
        return response.body
    }

    suspend fun bootstrap(embed: String, referer: String): ZmdbPayload.Bootstrap {
        require(ZmdbPayload.isEmbed(embed)) { "Invalid ZMDB embed URL" }
        val html = checked(get(embed, referer, emptyMap()), "embed")
        return ZmdbPayload.bootstrap(SiteParser.document(html, embed))
    }

    suspend fun resolve(embed: String, referer: String, season: Int?, episode: Int?): List<Stream> {
        // Tokens are short-lived. Fetch a fresh bootstrap per play; serialized episodes hold no token.
        var state = bootstrap(embed, referer)
        suspend fun links(): Response {
            require(state.token.isNotBlank()) { "ZMDB: ไม่พบโทเคนสำหรับโหลดลิงก์" }
            return get(ZmdbPayload.linksUrl(state, season, episode), embed,
                mapOf("Authorization" to "Bearer ${state.token}"))
        }
        var response = links()
        // Only an expired/invalid token (401) gets one refresh. Never loop on access denials (403).
        if (response.status == 401) {
            state = bootstrap(embed, referer)
            response = links()
        }
        val choices = try { ZmdbPayload.episodeLinks(checked(response, "links")) } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (response.status !in 200..299) throw e
            throw IllegalArgumentException("ZMDB: อ่านข้อมูลลิงก์ไม่สำเร็จ")
        }
        if (state.type == "tv") require(choices.season == season && choices.episode == episode) {
            "ZMDB ส่งลิงก์ของตอนอื่นกลับมา"
        }
        val streams = mutableListOf<Stream>()
        val requested = mutableSetOf<String>()
        var failure = "ZMDB: ไม่พบเซิร์ฟเวอร์ที่รองรับ"
        for (player in choices.players.take(12)) {
            val api = ZmdbPayload.videoApiForPlayer(player.url) ?: continue
            // Audio variants share one master; do not present duplicate masters as selected audio tracks.
            if (!requested.add(api)) continue
            try {
                val videoResponse = get(api, embed, emptyMap())
                val body = checked(videoResponse, "video")
                val video = try { ZmdbPayload.video(body) } catch (_: Exception) {
                    throw IllegalArgumentException("ZMDB: ไม่พบ data.hlsUrl ที่ใช้ได้")
                }
                streams.add(Stream(video.hlsUrl, embed, player.server))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure = e.message ?: "ZMDB: โหลดข้อมูลวิดีโอไม่สำเร็จ"
            }
        }
        require(streams.isNotEmpty()) { failure }
        return streams.distinctBy { it.url }
    }
}
