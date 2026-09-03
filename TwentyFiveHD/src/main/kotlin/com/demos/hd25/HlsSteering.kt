package com.demos.hd25

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI

/**
 * The ZMDB playback gateway answers playlists but returns HTTP 403 for media (`hdr.bin`,
 * `seg_*.bin`); only the hosts named by the master playlist's `#EXT-X-CONTENT-STEERING` document
 * serve them. A player that ignores content steering keeps asking the gateway and fails with a bad
 * HTTP status, so the declared replacement hosts are read from the actual documents here.
 */
internal object HlsSteering {
    private val json = ObjectMapper()
    private val serverUri = Regex("""SERVER-URI\s*=\s*"([^"]+)"""")
    private val hostName = Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")

    /** Absolute steering document URL declared by a master playlist, or null when it declares none. */
    fun serverUrl(master: String, body: String): String? {
        val tag = body.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("#EXT-X-CONTENT-STEERING:", ignoreCase = true) } ?: return null
        val declared = serverUri.find(tag)?.groupValues?.get(1)?.trim().orEmpty()
        if (declared.isBlank()) return null
        return try {
            val uri = URI(master).resolve(declared)
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() ||
                uri.userInfo != null
            ) null else uri.toString()
        } catch (_: Exception) { null }
    }

    /** Replacement hosts declared by a steering document, most preferred first. */
    fun hosts(body: String): List<String> {
        val root = try { json.readTree(body) } catch (_: Exception) { null } ?: return emptyList()
        val clones = root.path("PATHWAY-CLONES").takeIf { it.isArray } ?: return emptyList()
        val byPathway = LinkedHashMap<String, String>()
        for (clone in clones) {
            val id = clone.path("ID").takeIf { it.isTextual }?.textValue()?.trim().orEmpty()
            val host = clone.path("URI-REPLACEMENT").path("HOST").takeIf { it.isTextual }
                ?.textValue()?.trim().orEmpty()
            // A replacement host must be a plain hostname: no scheme, port, credentials or path.
            if (id.isBlank() || !hostName.matches(host)) continue
            byPathway.putIfAbsent(id, host)
        }
        val priority = root.path("PATHWAY-PRIORITY").takeIf { it.isArray }
            ?.filter { it.isTextual }?.map { it.textValue().trim() }.orEmpty()
        return (priority.mapNotNull(byPathway::get) + byPathway.values).distinct()
    }
}
