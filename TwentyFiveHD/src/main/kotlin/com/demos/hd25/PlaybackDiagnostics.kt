package com.demos.hd25

import okhttp3.Interceptor
import java.io.IOException
import java.net.URI

/** Memory-only, bounded report. Never retain URLs, headers, bodies or exception messages. */
internal class PlaybackDiagnostics {
    private class Session(val number: Int) {
        var requests = 0
        var successful = 0
        val failures = linkedSetOf<String>()
    }
    private val sessions = ArrayDeque<Session>()
    private var serial = 0

    @Synchronized
    fun interceptor(master: String): Interceptor {
        val session = Session(++serial)
        sessions.addLast(session)
        while (sessions.size > 4) sessions.removeFirst()
        return Interceptor { chain ->
            val request = chain.request()
            synchronized(this) { session.requests++ }
            try {
                val response = chain.proceed(request)
                synchronized(this) {
                    if (response.isSuccessful) session.successful++
                    else if (session.failures.size < 8) session.failures.add(
                        "HTTP ${response.code} | ${kind(response.request.url.toString(), master)} | ${response.request.url.host}"
                    )
                }
                // Leave status, body, redirects and request headers untouched.
                response
            } catch (e: IOException) {
                synchronized(this) {
                    if (session.failures.size < 8) session.failures.add(
                        "NETWORK | ${kind(request.url.toString(), master)} | ${request.url.host}"
                    )
                }
                throw e
            }
        }
    }

    @Synchronized
    fun report(): String = buildString {
        append("25-HD v6 — รายงานการเล่น\n")
        if (sessions.isEmpty()) {
            append("ยังไม่มีคำขอจากตัวเล่นที่ตรวจได้ในรอบเปิดแอปนี้\nลองเล่นในแอปก่อน แล้วค้นหา 25hd-debug อีกครั้ง\nการ Cast/ดาวน์โหลดอาจไม่เรียกตัวตรวจนี้")
        } else {
            sessions.toList().asReversed().forEach { s ->
                append("\nรอบ ${s.number}: คำขอ ${s.requests}, HTTP สำเร็จ ${s.successful}\n")
                append(s.failures.joinToString("\n").ifBlank { "ยังไม่พบ HTTP/network error ในคำขอที่ตรวจได้" })
                append('\n')
            }
        }
        append("\nประเภทอิงจากชื่อเส้นทาง ไม่ยืนยันเนื้อหาไฟล์\nรายงานอยู่ในหน่วยความจำเท่านั้น ปิดแอปแล้วหาย")
    }

    private fun kind(url: String, master: String): String {
        if (url == master) return "master"
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return when {
            path.endsWith(".m3u8") || path.endsWith("/_master") -> "playlist"
            path.endsWith(".key") -> "key"
            listOf(".ts", ".m4s", ".mp4", ".bin", ".aac").any(path::endsWith) -> "media/bin"
            else -> "other"
        }
    }
}
