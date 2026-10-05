package com.demos.hd25

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.util.Locale
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Makes ZMDB titles downloadable with Cloudstream's built-in HLS downloader.
 *
 * That downloader concatenates the segments of one media playlist and ignores `EXT-X-MAP`,
 * separate audio renditions and the provider's video interceptor. ZMDB serves fMP4 video and
 * audio as separate renditions whose media only the steered CDN hosts deliver, so none of it
 * downloads as-is. Links under [HOST] are answered by [Hook] inside the app's HTTP client: a
 * media playlist whose segments are muxed on request into self-contained MPEG-TS (video plus
 * every audio language), and subtitles as plain WebVTT. URLs only carry the video ID, quality and
 * segment number; signed playlists and CDN hosts are resolved again when needed, so a download
 * can resume after the original links have expired.
 */
internal object ZmdbDownload {
    const val HOST = "zmdb-download.invalid"
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    /** Variant playlists are signed for about five minutes after the master is read. */
    private const val SESSION_TTL_MS = 4 * 60 * 1000L
    private val videoId = Regex("[a-fA-F0-9]{24}")
    private val json = ObjectMapper()

    class Fetched(val status: Int, val body: ByteArray) {
        val ok get() = status in 200..299
        val text get() = String(body, Charsets.UTF_8)
    }

    class Served(val status: Int, val contentType: String, val body: ByteArray)

    class Rendition(val uri: String, val name: String, val language: String, val isDefault: Boolean, val forced: Boolean)
    class Variant(val height: Int, val uri: String, val codecs: String, val audioGroup: String?, val subtitleGroup: String?)
    class Master(
        val url: String, val variants: List<Variant>, val audio: Map<String, List<Rendition>>,
        val subtitles: List<Rendition>, val steering: String?,
    ) {
        /** Variants this downloader can mux: H.264 video with AAC audio. */
        val downloadable get() = variants.filter { it.codecs.startsWith("avc1") || it.codecs.isEmpty() }
    }
    class Media(val init: String?, val segments: List<Pair<Double, String>>)

    private class Session(
        val video: Media, val audio: List<Pair<Rendition, Media>>, val hosts: List<String>, val created: Long,
    )

    private val sessions = LinkedHashMap<String, Session>()
    private val tracks = LinkedHashMap<String, Fmp4.Track>()

    fun playlistUrl(id: String, height: Int) = "https://$HOST/v/$id/$height.m3u8"
    fun subtitleUrl(id: String, index: Int) = "https://$HOST/sub/$id/$index.vtt"

    // ---- Playlist parsing --------------------------------------------------------------------

    private val attribute = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    private fun attributes(line: String): Map<String, String> =
        attribute.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    private fun resolve(base: String, uri: String): String = URI(base).resolve(uri.trim()).toString()

    fun parseMaster(url: String, text: String): Master {
        require(text.trimStart().startsWith("#EXTM3U")) { "ZMDB master is not an HLS playlist" }
        val lines = text.lines().map { it.trim() }
        val variants = ArrayList<Variant>()
        val audio = LinkedHashMap<String, MutableList<Rendition>>()
        val subtitles = ArrayList<Rendition>()
        lines.forEachIndexed { i, line ->
            when {
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = attributes(line)
                    val uri = a["URI"] ?: return@forEachIndexed
                    val rendition = Rendition(resolve(url, uri), a["NAME"].orEmpty(), a["LANGUAGE"].orEmpty(),
                        a["DEFAULT"] == "YES", a["FORCED"] == "YES")
                    when (a["TYPE"]) {
                        "AUDIO" -> audio.getOrPut(a["GROUP-ID"].orEmpty()) { ArrayList() }.add(rendition)
                        "SUBTITLES" -> subtitles.add(rendition)
                    }
                }
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val a = attributes(line)
                    val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@forEachIndexed
                    val height = a["RESOLUTION"]?.substringAfter('x')?.toIntOrNull() ?: 0
                    variants.add(Variant(height, resolve(url, uri), a["CODECS"].orEmpty(), a["AUDIO"], a["SUBTITLES"]))
                }
            }
        }
        // Defaults first so players and downloads start with the site's primary language.
        val sortedAudio = audio.mapValues { (_, list) -> list.sortedByDescending { it.isDefault } }
        return Master(url, variants, sortedAudio, subtitles, HlsSteering.serverUrl(url, text))
    }

    fun parseMedia(url: String, text: String): Media {
        val lines = text.lines().map { it.trim() }
        val init = lines.firstOrNull { it.startsWith("#EXT-X-MAP:") }?.let { attributes(it)["URI"] }?.let { resolve(url, it) }
        val segments = ArrayList<Pair<Double, String>>()
        lines.forEachIndexed { i, line ->
            if (!line.startsWith("#EXTINF:")) return@forEachIndexed
            val duration = line.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull() ?: return@forEachIndexed
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@forEachIndexed
            segments.add(duration to resolve(url, uri))
        }
        require(segments.isNotEmpty()) { "ZMDB media playlist has no segments" }
        return Media(init, segments)
    }

    // ---- Resolution --------------------------------------------------------------------------

    fun master(hlsUrl: String, fetch: (String) -> Fetched): Master {
        val response = fetch(hlsUrl)
        require(response.ok) { "ZMDB master: HTTP ${response.status}" }
        return parseMaster(hlsUrl, response.text)
    }

    private fun masterFor(id: String, fetch: (String) -> Fetched): Master {
        require(videoId.matches(id)) { "Invalid ZMDB video ID" }
        val api = fetch("https://zmdb.net/api/video/$id")
        require(api.ok) { "ZMDB video API: HTTP ${api.status}" }
        return master(ZmdbPayload.video(api.text).hlsUrl, fetch)
    }

    private fun session(id: String, height: Int, fetch: (String) -> Fetched, fresh: Boolean = false): Session {
        val key = "$id/$height"
        val now = System.currentTimeMillis()
        if (!fresh) synchronized(sessions) {
            sessions[key]?.takeIf { now - it.created < SESSION_TTL_MS }?.let { return it }
        }
        val master = masterFor(id, fetch)
        val candidates = master.downloadable.ifEmpty { throw IllegalStateException("ZMDB: no downloadable video") }
        val variant = if (height <= 0) candidates.maxBy { it.height }
            else candidates.minBy { kotlin.math.abs(it.height - height) }
        fun load(url: String): Media {
            val r = fetch(url)
            require(r.ok) { "ZMDB playlist: HTTP ${r.status}" }
            return parseMedia(url, r.text)
        }
        val video = load(variant.uri)
        val audio = master.audio[variant.audioGroup].orEmpty().map { it to load(it.uri) }
        val gateway = URI(master.url).host
        val steered = master.steering?.let { url -> fetch(url).takeIf { it.ok }?.let { HlsSteering.hosts(it.text) } }.orEmpty()
        val session = Session(video, audio, (steered + gateway).distinct(), now)
        synchronized(sessions) {
            sessions[key] = session
            while (sessions.size > 8) sessions.remove(sessions.keys.first())
        }
        return session
    }

    /** Fetches media from the steered hosts, which serve what the playlist gateway refuses. */
    private fun media(url: String, hosts: List<String>, fetch: (String) -> Fetched): ByteArray? {
        val uri = URI(url)
        for (host in hosts) {
            val moved = URI(uri.scheme, uri.userInfo, host, uri.port, uri.path, uri.query, null).toString()
            val r = fetch(moved)
            if (r.ok && r.body.isNotEmpty()) return r.body
        }
        return null
    }

    private fun track(url: String, hosts: List<String>, fetch: (String) -> Fetched): Fmp4.Track {
        val key = URI(url).path
        synchronized(tracks) { tracks[key]?.let { return it } }
        val bytes = media(url, hosts, fetch) ?: throw IllegalStateException("ZMDB: init segment unavailable")
        val parsed = Fmp4.parseInit(bytes)
        synchronized(tracks) {
            tracks[key] = parsed
            while (tracks.size > 32) tracks.remove(tracks.keys.first())
        }
        return parsed
    }

    private fun iso639(language: String): String = try {
        Locale(language.substringBefore('-')).isO3Language.ifBlank { "und" }
    } catch (_: Exception) { "und" }

    private fun segment(id: String, height: Int, index: Int, fetch: (String) -> Fetched): ByteArray {
        fun attempt(session: Session): ByteArray? {
            require(index in session.video.segments.indices) { "Segment out of range" }
            val videoInit = session.video.init ?: throw IllegalStateException("ZMDB video has no init segment")
            val video = media(session.video.segments[index].second, session.hosts, fetch) ?: return null
            val inputs = session.audio.mapNotNull { (rendition, playlist) ->
                if (index !in playlist.segments.indices) return@mapNotNull null
                val init = playlist.init ?: return@mapNotNull null
                val bytes = media(playlist.segments[index].second, session.hosts, fetch) ?: return null
                TsMuxer.Input(track(init, session.hosts, fetch), bytes, iso639(rendition.language))
            }
            return TsMuxer.mux(TsMuxer.Input(track(videoInit, session.hosts, fetch), video), inputs)
        }
        return attempt(session(id, height, fetch))
            // Hosts or signatures may have changed since this session was resolved: resolve again once.
            ?: attempt(session(id, height, fetch, fresh = true))
            ?: throw IllegalStateException("ZMDB: segment unavailable on every host")
    }

    private fun playlist(id: String, height: Int, fetch: (String) -> Fetched): String {
        val session = session(id, height, fetch)
        val target = kotlin.math.ceil(session.video.segments.maxOf { it.first }).toInt().coerceAtLeast(1)
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:$target\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n")
            session.video.segments.forEachIndexed { i, (duration, _) ->
                append("#EXTINF:").append(String.format(Locale.ROOT, "%.6f", duration)).append(",\n")
                append("https://").append(HOST).append("/s/").append(id).append('/').append(height).append('/').append(i).append(".ts\n")
            }
            append("#EXT-X-ENDLIST\n")
        }
    }

    private fun subtitle(id: String, index: Int, fetch: (String) -> Fetched): String {
        val master = masterFor(id, fetch)
        val rendition = master.subtitles.getOrNull(index) ?: throw IllegalArgumentException("Subtitle not found")
        val playlist = fetch(rendition.uri)
        require(playlist.ok) { "ZMDB subtitle playlist: HTTP ${playlist.status}" }
        val gateway = URI(master.url).host
        val hosts = (master.steering?.let { url -> fetch(url).takeIf { it.ok }?.let { HlsSteering.hosts(it.text) } }.orEmpty() + gateway).distinct()
        val parts = parseMedia(rendition.uri, playlist.text).segments.map { (_, url) ->
            String(media(url, hosts, fetch) ?: throw IllegalStateException("ZMDB: subtitle unavailable"), Charsets.UTF_8)
        }
        // Join segmented WebVTT into one file: keep only the first header.
        return parts.mapIndexed { i, part -> if (i == 0) part else part.substringAfter("\n\n", "") }.joinToString("\n\n")
    }

    /** Answers a request under [HOST]. Paths: /v/<id>/<height>.m3u8, /s/<id>/<height>/<n>.ts, /sub/<id>/<n>.vtt */
    fun serve(path: String, fetch: (String) -> Fetched): Served {
        val parts = path.trim('/').split('/')
        return when {
            parts.size == 3 && parts[0] == "v" && parts[2].endsWith(".m3u8") -> Served(200, "application/vnd.apple.mpegurl",
                playlist(parts[1], parts[2].removeSuffix(".m3u8").toInt(), fetch).toByteArray())
            parts.size == 4 && parts[0] == "s" && parts[3].endsWith(".ts") -> Served(200, "video/mp2t",
                segment(parts[1], parts[2].toInt(), parts[3].removeSuffix(".ts").toInt(), fetch))
            parts.size == 3 && parts[0] == "sub" && parts[2].endsWith(".vtt") -> Served(200, "text/vtt",
                subtitle(parts[1], parts[2].removeSuffix(".vtt").toInt(), fetch).toByteArray())
            else -> Served(404, "text/plain", "Not found".toByteArray())
        }
    }

    /** Fetches with the client's own chain so app-wide settings (DNS, proxies) still apply. */
    fun chainFetch(chain: Interceptor.Chain): (String) -> Fetched = { url ->
        val request = Request.Builder().url(url).header("User-Agent", UA).header("Referer", "https://zmdb.net/").build()
        chain.proceed(request).use { Fetched(it.code, it.body?.bytes() ?: ByteArray(0)) }
    }

    class Hook : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (!request.url.host.equals(HOST, ignoreCase = true)) return chain.proceed(request)
            val served = try {
                serve(request.url.encodedPath, chainFetch(chain))
            } catch (e: Exception) {
                Served(502, "text/plain", (e.message ?: "ZMDB download failed").toByteArray())
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

    /** Adds [Hook] to a client once, replacing a copy left by an older version of this plugin. */
    fun withHook(client: OkHttpClient): OkHttpClient {
        if (client.interceptors.any { it is Hook }) return client
        return client.newBuilder().apply {
            interceptors().removeAll { it.javaClass.name == Hook::class.java.name }
            interceptors().add(0, Hook())
        }.build()
    }
}
