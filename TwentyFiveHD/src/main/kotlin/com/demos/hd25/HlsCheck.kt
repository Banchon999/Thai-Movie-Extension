package com.demos.hd25

import java.net.URI

/** Report the actual playlist response without exposing signed paths, query tokens or body text. */
internal object HlsCheck {
    fun validate(status: Int, body: String, url: String) {
        val host = try { URI(url).host ?: "unknown-host" } catch (_: Exception) { "unknown-host" }
        require(status in 200..299) { "HLS master: HTTP $status ($host)" }
        require(body.removePrefix("\uFEFF").trimStart().lineSequence().firstOrNull()?.trim() == "#EXTM3U") {
            "HLS master: HTTP $status แต่ข้อมูลไม่ใช่ playlist HLS ($host)"
        }
    }
}
