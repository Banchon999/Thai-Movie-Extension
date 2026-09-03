package com.demos.hd25

import java.net.URI

/** Memory-only, bounded report. Never retain URLs, headers, bodies or exception messages. */
internal class PlaybackDiagnostics {
    internal class Session(val number: Int) {
        var requests = 0
        var successful = 0
        val failures = linkedSetOf<String>()
        val notes = linkedSetOf<String>()
    }
    private val sessions = ArrayDeque<Session>()
    private var serial = 0

    /** Starts one playback attempt; older attempts fall out of the bounded report. */
    @Synchronized
    fun open(master: String): Recorder {
        val session = Session(++serial)
        sessions.addLast(session)
        while (sessions.size > 4) sessions.removeFirst()
        return Recorder(session, master)
    }

    inner class Recorder internal constructor(private val session: Session, private val master: String) {
        fun request() = synchronized(this@PlaybackDiagnostics) { session.requests++ }

        fun success() = synchronized(this@PlaybackDiagnostics) { session.successful++ }

        fun failure(status: Int, url: String, host: String) = add("HTTP $status | ${kind(url, master)} | $host")

        fun network(url: String, host: String) = add("NETWORK | ${kind(url, master)} | $host")

        /** Short, host-level note about how the request was routed. Never include URLs or headers. */
        fun note(text: String) = synchronized(this@PlaybackDiagnostics) {
            if (session.notes.size < 4) session.notes.add(text)
            Unit
        }

        private fun add(entry: String) = synchronized(this@PlaybackDiagnostics) {
            if (session.failures.size < 8) session.failures.add(entry)
            Unit
        }
    }

    @Synchronized
    fun report(): String = buildString {
        append("25-HD v7 — รายงานการเล่น\n")
        if (sessions.isEmpty()) {
            append("ยังไม่มีคำขอจากตัวเล่นที่ตรวจได้ในรอบเปิดแอปนี้\nลองเล่นในแอปก่อน แล้วค้นหา 25hd-debug อีกครั้ง\nการ Cast/ดาวน์โหลดอาจไม่เรียกตัวตรวจนี้")
        } else {
            sessions.toList().asReversed().forEach { s ->
                append("\nรอบ ${s.number}: คำขอ ${s.requests}, HTTP สำเร็จ ${s.successful}\n")
                if (s.notes.isNotEmpty()) append(s.notes.joinToString("\n")).append('\n')
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
            path.endsWith(".m3u8") || path.endsWith("/_master") || path.endsWith("/_index") -> "playlist"
            path.endsWith(".key") -> "key"
            listOf(".ts", ".m4s", ".mp4", ".bin", ".aac").any(path::endsWith) -> "media/bin"
            else -> "other"
        }
    }
}
