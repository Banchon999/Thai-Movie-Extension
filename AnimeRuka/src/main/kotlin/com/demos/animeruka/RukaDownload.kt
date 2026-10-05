package com.demos.animeruka

import java.net.URI
import java.util.Base64
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Makes AnimeRuka episodes downloadable with Cloudstream's built-in HLS downloader.
 *
 * Playback works because the provider's video interceptor unwraps the CDN's `{"p": base64}`
 * playlists, but the downloader fetches with plain requests and never uses that interceptor, and it
 * cannot handle separate audio renditions or `EXT-X-MAP`. Links under [HOST] are answered by [Hook]
 * inside the app's HTTP client: it fetches with the embed Referer, unwraps the playlist, picks a
 * variant with muxed audio and serves segments the downloader can concatenate as they are.
 */
internal object RukaDownload {
    const val HOST = "animeruka-download.invalid"
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    const val REFERER = "https://animemami.xyz/"

    class Fetched(val status: Int, val body: ByteArray) {
        val ok get() = status in 200..299
    }

    class Served(val status: Int, val contentType: String, val body: ByteArray)

    /** Memory-only report of the last download attempts: shapes and statuses, never URLs or bodies. */
    private val events = ArrayDeque<String>()
    fun note(text: String) = synchronized(events) {
        events.addLast(text)
        while (events.size > 24) events.removeFirst()
    }
    fun report(): String = synchronized(events) {
        if (events.isEmpty()) "ยังไม่มีการดาวน์โหลดในรอบเปิดแอปนี้ ลองกดดาวน์โหลดก่อน แล้วค้นหา animeruka-debug อีกครั้ง"
        else events.joinToString("\n")
    }
    @Volatile private var segmentsNoted = 0
    private fun host(url: String) = try { URI(url).host.orEmpty() } catch (_: Exception) { "?" }
    private fun signature(b: ByteArray): String = when {
        b.isEmpty() -> "empty"
        b[0] == 0x47.toByte() -> "TS"
        String(b, 0, minOf(b.size, 16), Charsets.ISO_8859_1).trimStart().startsWith("#EXTM3U") -> "M3U8"
        b.size >= 8 && String(b, 4, 4, Charsets.ISO_8859_1) in setOf("ftyp", "styp", "moof", "sidx") -> "MP4"
        b.size >= 4 && (b[0].toInt() and 0xFF) == 0x89 && String(b, 1, 3, Charsets.ISO_8859_1) == "PNG" -> "PNG"
        b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" -> "RIFF/" + String(b, 8, 4, Charsets.ISO_8859_1)
        b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8 -> "JPEG"
        b.size >= 4 && String(b, 0, 4, Charsets.ISO_8859_1) == "GIF8" -> "GIF"
        String(b, 0, minOf(b.size, 16), Charsets.ISO_8859_1).trimStart().startsWith("<") -> "HTML"
        String(b, 0, minOf(b.size, 16), Charsets.ISO_8859_1).trimStart().startsWith("{") -> "JSON"
        else -> "other:" + b.take(4).joinToString("") { "%02x".format(it) }
    }

    private fun encode(url: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray())
    private fun decode(token: String) = String(Base64.getUrlDecoder().decode(token))

    fun playlistUrl(stream: String) = "https://$HOST/p/${encode(stream)}.m3u8"

    /** The CDN may answer `{"p":"<base64 playlist>"}` instead of the playlist itself. */
    fun unwrap(body: ByteArray): String {
        val text = String(body, Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t')
        if (text.startsWith("{\"p\":\"")) {
            val payload = text.substringAfter("{\"p\":\"").substringBefore('"').replace("\\/", "/")
            return String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
        }
        return text
    }

    private val attribute = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
    private fun attributes(line: String) =
        attribute.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    private fun resolve(base: String, uri: String): String = URI(base).resolve(uri.trim().replace(" ", "%20")).toString()

    /** Picks the best variant a downloader can use alone: audio muxed in, not an I-frame playlist. */
    fun chooseVariant(url: String, master: String): String? {
        val lines = master.lines().map { it.trim() }
        val separateAudio = lines.filter { it.startsWith("#EXT-X-MEDIA:") }.map { attributes(it) }
            .filter { it["TYPE"] == "AUDIO" && it["URI"] != null }.mapNotNull { it["GROUP-ID"] }.toSet()
        val variants = lines.mapIndexedNotNull { i, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF:")) return@mapIndexedNotNull null
            val a = attributes(line)
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@mapIndexedNotNull null
            Triple(resolve(url, uri), a["BANDWIDTH"]?.toLongOrNull() ?: 0L, a["AUDIO"] in separateAudio)
        }
        if (variants.isEmpty()) return null
        return (variants.filter { !it.third }.ifEmpty { variants }).maxBy { it.second }.first
    }

    /** Rewrites a media playlist so every URI points back at [HOST] and init segments ride on segment 0. */
    fun rewrite(url: String, media: String): String {
        val lines = media.lines().map { it.trim() }
        val init = lines.firstOrNull { it.startsWith("#EXT-X-MAP:") }?.let { attributes(it)["URI"] }?.let { resolve(url, it) }
        val encrypted = lines.any { it.startsWith("#EXT-X-KEY:") && attributes(it)["METHOD"].let { m -> m != null && m != "NONE" } }
        require(!(encrypted && init != null)) { "Encrypted fMP4 is not supported" }
        val out = StringBuilder()
        var index = 0
        for (line in lines) {
            when {
                line.isEmpty() -> continue
                line.startsWith("#EXT-X-MAP:") || line.startsWith("#EXT-X-BYTERANGE") -> continue
                line.startsWith("#EXT-X-KEY:") -> {
                    val uri = attributes(line)["URI"]
                    out.append(if (uri == null) line else line.replace("\"$uri\"", "\"https://$HOST/k/${encode(resolve(url, uri))}.key\"")).append('\n')
                }
                line.startsWith("#") -> out.append(line).append('\n')
                else -> {
                    val segment = resolve(url, line)
                    out.append("https://").append(HOST).append("/s/").append(encode(segment)).append(".ts")
                    val query = listOfNotNull(
                        init?.takeIf { index == 0 }?.let { "init=" + encode(it) },
                        "raw=1".takeIf { encrypted },
                    )
                    if (query.isNotEmpty()) out.append('?').append(query.joinToString("&"))
                    out.append('\n')
                    index++
                }
            }
        }
        require(index > 0) { "Playlist has no segments" }
        if ("#EXT-X-ENDLIST" !in out) out.append("#EXT-X-ENDLIST\n")
        return out.toString()
    }

    /**
     * Segments are served with image extensions and may carry a real image in front of the
     * transport stream. Skip a leading PNG/WebP/JPEG/GIF by its own structure, then fall back to the
     * first position where TS sync bytes repeat every 188 bytes.
     */
    fun cleanSegment(bytes: ByteArray): ByteArray {
        if (bytes.size >= 8 && String(bytes, 4, 4, Charsets.ISO_8859_1) in setOf("ftyp", "styp", "moof", "sidx")) return bytes
        fun synced(at: Int): Boolean {
            if (at < 0 || at >= bytes.size || bytes[at] != 0x47.toByte()) return false
            // Require every packet start we can see, up to five, to carry the sync byte.
            var seen = 0
            var p = at
            while (p < bytes.size && seen < 5) {
                if (bytes[p] != 0x47.toByte()) return false
                p += 188
                seen++
            }
            return seen >= minOf(3, (bytes.size - at + 187) / 188)
        }
        if (synced(0)) return bytes
        imageEnd(bytes)?.let { end ->
            (end until minOf(bytes.size, end + 188)).firstOrNull { synced(it) }?.let { return bytes.copyOfRange(it, bytes.size) }
        }
        for (i in 1 until bytes.size) if (synced(i) && bytes.size - i >= 376) return bytes.copyOfRange(i, bytes.size)
        return bytes
    }

    /** End offset of an image at the start of [b], or null when none is recognised. */
    private fun imageEnd(b: ByteArray): Int? {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun ascii(at: Int, text: String) = at + text.length <= b.size && String(b, at, text.length, Charsets.ISO_8859_1) == text
        if (b.size >= 8 && u8(0) == 0x89 && ascii(1, "PNG")) {
            var at = 8
            while (at + 12 <= b.size) {
                val length = (u8(at) shl 24) or (u8(at + 1) shl 16) or (u8(at + 2) shl 8) or u8(at + 3)
                if (length < 0) return null
                val type = String(b, at + 4, 4, Charsets.ISO_8859_1)
                at += 12 + length
                if (type == "IEND") return at
            }
            return null
        }
        if (ascii(0, "RIFF") && ascii(8, "WEBP")) {
            val size = u8(4) or (u8(5) shl 8) or (u8(6) shl 16) or (u8(7) shl 24)
            return (8 + size + (size and 1)).takeIf { size > 0 && it <= b.size }
        }
        if (b.size >= 4 && u8(0) == 0xFF && u8(1) == 0xD8) {
            for (i in 2 until b.size - 1) if (u8(i) == 0xFF && u8(i + 1) == 0xD9) return i + 2
            return null
        }
        if (ascii(0, "GIF8")) {
            for (i in 6 until b.size) if (u8(i) == 0x3B && i + 1 < b.size && u8(i + 1) == 0x47) return i + 1
        }
        return null
    }

    fun serve(path: String, query: Map<String, String>, fetch: (String) -> Fetched): Served {
        val parts = path.trim('/').split('/')
        require(parts.size == 2) { "Not found" }
        val token = parts[1].substringBeforeLast('.')
        val url = decode(token)
        val scheme = URI(url).scheme?.lowercase()
        require(scheme == "https" || scheme == "http") { "Invalid URL" }
        return when (parts[0]) {
            "p" -> {
                var current = url
                var response = fetch(current)
                note("playlist ${host(current)}: HTTP ${response.status}, ${signature(response.body)}, ${response.body.size} B")
                require(response.ok) { "Playlist: HTTP ${response.status}" }
                var text = unwrap(response.body)
                require(text.startsWith("#EXTM3U")) { "Not an HLS playlist" }
                chooseVariant(current, text)?.let { variant ->
                    current = variant
                    response = fetch(current)
                    note("variant ${host(current)}: HTTP ${response.status}, ${signature(response.body)}")
                    require(response.ok) { "Variant playlist: HTTP ${response.status}" }
                    text = unwrap(response.body)
                }
                val lines = text.lines()
                note("segments=${lines.count { it.startsWith("#EXTINF") }} map=${lines.any { it.startsWith("#EXT-X-MAP") }} " +
                    "key=${lines.any { it.startsWith("#EXT-X-KEY") && !it.contains("NONE") }} byterange=${lines.any { it.startsWith("#EXT-X-BYTERANGE") }} " +
                    "hosts=${lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { host(resolve(current, it)) }.distinct().take(3)}")
                Served(200, "application/vnd.apple.mpegurl", rewrite(current, text).toByteArray())
            }
            "s" -> {
                val response = fetch(url)
                if (!response.ok || segmentsNoted < 3) {
                    segmentsNoted++
                    note("segment ${host(url)}: HTTP ${response.status}, ${signature(response.body)}, ${response.body.size} B" +
                        if (response.ok && query["raw"] != "1") ", TS after cleaning=${cleanSegment(response.body).firstOrNull() == 0x47.toByte()}" else "")
                }
                require(response.ok) { "Segment: HTTP ${response.status}" }
                val init = query["init"]?.let { fetch(decode(it)) }?.also { require(it.ok) { "Init: HTTP ${it.status}" } }?.body
                val body = if (query["raw"] == "1") response.body else cleanSegment(response.body)
                Served(200, if (init != null) "video/mp4" else "video/mp2t", (init ?: ByteArray(0)) + body)
            }
            "k" -> {
                val response = fetch(url)
                require(response.ok) { "Key: HTTP ${response.status}" }
                Served(200, "application/octet-stream", response.body)
            }
            else -> Served(404, "text/plain", "Not found".toByteArray())
        }
    }

    fun chainFetch(chain: Interceptor.Chain): (String) -> Fetched = { url ->
        // The CDN refuses requests carrying an Origin header, so only Referer is sent.
        val request = Request.Builder().url(url).header("User-Agent", UA).header("Referer", REFERER).build()
        chain.proceed(request).use { Fetched(it.code, it.body.bytes()) }
    }

    class Hook : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (!request.url.host.equals(HOST, ignoreCase = true)) return chain.proceed(request)
            val query = request.url.queryParameterNames.associateWith { request.url.queryParameter(it).orEmpty() }
            val served = try {
                serve(request.url.encodedPath, query, chainFetch(chain))
            } catch (e: Exception) {
                note("error: ${e.javaClass.simpleName}: ${e.message}")
                Served(502, "text/plain", (e.message ?: "AnimeRuka download failed").toByteArray())
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(served.status)
                .message(if (served.status == 200) "OK" else "Error")
                .body(served.body.toResponseBody(served.contentType.toMediaType()))
                .build()
        }
    }

    /** Adds [Hook] once, replacing a copy left by an older version of this plugin. */
    fun withHook(client: OkHttpClient): OkHttpClient {
        if (client.interceptors.any { it is Hook }) return client
        return client.newBuilder().apply {
            interceptors().removeAll { it.javaClass.name == Hook::class.java.name }
            interceptors().add(0, Hook())
        }.build()
    }
}
