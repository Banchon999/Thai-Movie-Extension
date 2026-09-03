package com.demos.hd25

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * ZMDB serves master and variant playlists from its gateway host but answers HTTP 403 for the media
 * those playlists reference; only the hosts listed by the master's content-steering document deliver
 * them. Cloudstream's player reports that as `ERROR_CODE_IO_BAD_HTTP_STATUS`. This interceptor sends
 * a refused media request again against each declared host and keeps using the one that answers, so
 * playback no longer depends on the player implementing content steering itself.
 */
internal class HlsGateway(
    diagnostics: PlaybackDiagnostics,
    private val master: String,
) : Interceptor {
    private companion object {
        /** Statuses the gateway uses to refuse media it does not serve. */
        val REFUSED = setOf(403, 404)
        /** Playlists and steering documents are small; never buffer a media body to inspect it. */
        const val MAX_TEXT = 512L * 1024L
    }

    private val recorder = diagnostics.open(master)
    private val gateway = master.toHttpUrlOrNull()
    private var steering: String? = null
    private var masterSeen = false
    private var replacements: List<String>? = null
    private var routed: String? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val movable = movable(original)
        val known = synchronized(this) { routed }
        val first = if (movable && known != null) original.withHost(known) else original
        val response = send(chain, first)
        if (isMaster(first)) captureSteering(response)

        if (response.isSuccessful || response.code !in REFUSED || !movable) return response

        // OkHttp refuses another request on this call while the refused response is still open.
        var refused = detach(response)
        for (host in replacements(chain, first)) {
            if (host.equals(first.url.host, ignoreCase = true)) continue
            val retry = first.withHost(host)
            if (retry === first) continue
            val next = send(chain, retry)
            if (next.isSuccessful) {
                synchronized(this) { routed = host }
                recorder.note("สื่อถูกปฏิเสธที่ ${first.url.host} จึงเปลี่ยนไปใช้ $host")
                return next
            }
            if (next.code !in REFUSED) return next
            refused = detach(next)
        }
        synchronized(this) { if (routed == first.url.host) routed = null }
        return refused
    }

    /** Frees the call so another request may run, keeping the refused status and its short body. */
    private fun detach(response: Response): Response {
        val copy = try {
            response.newBuilder().body(response.peekBody(MAX_TEXT)).build()
        } catch (_: IOException) { null }
        response.close()
        return copy ?: response
    }

    private fun send(chain: Interceptor.Chain, request: Request): Response {
        recorder.request()
        return try {
            val response = chain.proceed(request)
            if (response.isSuccessful) recorder.success()
            else recorder.failure(response.code, response.request.url.toString(), response.request.url.host)
            // Leave status, body, redirects and request headers untouched.
            response
        } catch (e: IOException) {
            recorder.network(request.url.toString(), request.url.host)
            throw e
        }
    }

    /**
     * Only requests the gateway itself received may move. The master and the steering document stay
     * where they are declared: the replacement hosts answer 403 for the master.
     */
    private fun movable(request: Request): Boolean {
        val base = gateway ?: return false
        if (!request.url.host.equals(base.host, ignoreCase = true)) return false
        if (isMaster(request) || request.url.encodedPath.lowercase().endsWith(".json")) return false
        return request.url.toString() != synchronized(this) { steering }
    }

    private fun isMaster(request: Request): Boolean =
        request.url.toString() == master || request.url.encodedPath.endsWith("/_master")

    private fun captureSteering(response: Response) {
        synchronized(this) { masterSeen = true }
        if (!response.isSuccessful) return
        synchronized(this) { if (steering != null) return }
        val body = try { response.peekBody(MAX_TEXT).string() } catch (_: IOException) { return }
        val declared = HlsSteering.serverUrl(master, body) ?: return
        synchronized(this) { if (steering == null) steering = declared }
    }

    /** Resolved once per playback attempt; an unavailable steering document yields no hosts. */
    private fun replacements(chain: Interceptor.Chain, like: Request): List<String> {
        synchronized(this) { replacements?.let { return it } }
        val resolved = discover(chain, like)
        synchronized(this) {
            replacements = replacements ?: resolved
            return replacements.orEmpty()
        }
    }

    private fun discover(chain: Interceptor.Chain, like: Request): List<String> {
        val declared = synchronized(this) { steering } ?: run {
            // The master was already read and declared no steering: there is nowhere else to ask.
            if (synchronized(this) { masterSeen }) return emptyList()
            val body = text(chain, master, like) ?: return emptyList()
            synchronized(this) { masterSeen = true }
            HlsSteering.serverUrl(master, body)?.also { url -> synchronized(this) { steering = url } }
        } ?: return emptyList()
        return HlsSteering.hosts(text(chain, declared, like) ?: return emptyList())
    }

    /** Fetches a declared document with the player's own headers; range conditions do not apply. */
    private fun text(chain: Interceptor.Chain, url: String, like: Request): String? {
        val target = url.toHttpUrlOrNull() ?: return null
        val request = like.newBuilder().url(target).get()
            .removeHeader("Range").removeHeader("If-None-Match").removeHeader("If-Modified-Since").build()
        return try {
            send(chain, request).use { if (it.isSuccessful) it.peekBody(MAX_TEXT).string() else null }
        } catch (_: IOException) { null }
    }

    private fun Request.withHost(host: String): Request = try {
        newBuilder().url(url.newBuilder().host(host).build()).build()
    } catch (_: IllegalArgumentException) { this }
}
