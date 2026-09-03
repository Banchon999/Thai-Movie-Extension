package com.demos.hd25

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.TreeMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class PlaybackDiagnosticsTest {
    @Test fun capturesChildFailureWithoutExtraRequestsOrChangingHeadersAndBodies() {
        LocalServer().use { server ->
            server.enqueue(body = "#EXTM3U\nchild.m3u8?secret=PRIVATE")
            server.enqueue(status = 403, body = "PRIVATE failure body")
            val master = server.url("/signed-PRIVATE/_master?token=PRIVATE").toString()
            val diagnostics = PlaybackDiagnostics()
            val client = OkHttpClient.Builder().addInterceptor(diagnostics.interceptor(master)).build()
            client.newCall(Request.Builder().url(master).header("Referer", "https://example.org/embed?id=PRIVATE").build()).execute().use {
                assertEquals("#EXTM3U\nchild.m3u8?secret=PRIVATE", it.body!!.string())
            }
            client.newCall(Request.Builder().url(server.url("/PRIVATE/child.m3u8?secret=PRIVATE")).build()).execute().use {
                assertEquals(403, it.code)
                assertEquals("PRIVATE failure body", it.body!!.string())
            }
            assertEquals(2, server.requestCount)
            val first = server.takeRequest()
            assertEquals("https://example.org/embed?id=PRIVATE", first.headers["Referer"]?.firstOrNull())
            assertTrue(first.path.contains("token=PRIVATE"))
            val report = diagnostics.report()
            assertTrue(report.contains("HTTP 403 | playlist | 127.0.0.1"))
            assertTrue(report.contains("คำขอ 2, HTTP สำเร็จ 1"))
            assertFalse(report.contains("PRIVATE"))
        }
    }

    @Test fun recordsFinalRedirectHostAndPreservesRedirectBehavior() {
        LocalServer().use { server ->
            val master = server.url("/master?PRIVATE").toString()
            server.enqueue(status = 302, location = server.url("/PRIVATE/seg.bin?PRIVATE"))
            server.enqueue(status = 404)
            val diagnostics = PlaybackDiagnostics()
            OkHttpClient.Builder().addInterceptor(diagnostics.interceptor(master)).build()
                .newCall(Request.Builder().url(master).build()).execute().use { assertEquals(404, it.code) }
            assertEquals(2, server.requestCount)
            assertTrue(diagnostics.report().contains("HTTP 404 | media/bin | 127.0.0.1"))
            assertFalse(diagnostics.report().contains("PRIVATE"))
        }
    }

    @Test fun recordsKeyAndMasterFailuresAndAcceptsPartialResponses() {
        LocalServer().use { server ->
            val master = server.url("/_master?PRIVATE").toString()
            server.enqueue(status = 503)
            server.enqueue(status = 403)
            server.enqueue(status = 206, body = "chunk")
            val diagnostics = PlaybackDiagnostics()
            val client = OkHttpClient.Builder().addInterceptor(diagnostics.interceptor(master)).build()
            listOf(master, server.url("/PRIVATE.key").toString(), server.url("/seg.bin").toString()).forEach { url ->
                client.newCall(Request.Builder().url(url).build()).execute().close()
            }
            val report = diagnostics.report()
            assertTrue(report.contains("HTTP 503 | master"))
            assertTrue(report.contains("HTTP 403 | key"))
            assertTrue(report.contains("คำขอ 3, HTTP สำเร็จ 1"))
        }
    }

    @Test fun networkErrorDoesNotLeakExceptionMessage() {
        val diagnostics = PlaybackDiagnostics()
        val client = OkHttpClient.Builder().addInterceptor(diagnostics.interceptor("https://example.org/PRIVATE"))
            .addInterceptor { throw java.io.IOException("PRIVATE token and headers") }.build()
        try {
            client.newCall(Request.Builder().url("https://example.org/PRIVATE").build()).execute()
            fail("Expected IOException")
        } catch (expected: java.io.IOException) {
            assertEquals("PRIVATE token and headers", expected.message)
        }
        assertTrue(diagnostics.report().contains("NETWORK | master | example.org"))
        assertFalse(diagnostics.report().contains("PRIVATE"))
    }

    @Test fun boundsSessionsAndReportsNewestFirst() {
        val diagnostics = PlaybackDiagnostics()
        repeat(10) { diagnostics.interceptor("https://example.org/PRIVATE-$it") }
        val report = diagnostics.report()
        assertFalse(report.contains("รอบ 6:"))
        assertTrue(report.contains("รอบ 7:"))
        assertTrue(report.indexOf("รอบ 10:") < report.indexOf("รอบ 9:"))
        assertFalse(report.contains("PRIVATE"))
    }

    @Test fun emptyReportDoesNotInventAnHttpFailure() {
        assertTrue(PlaybackDiagnostics().report().contains("ยังไม่มีคำขอจากตัวเล่น"))
        assertFalse(PlaybackDiagnostics().report().contains("HTTP 403"))
    }
}

/** Minimal GET-only local server; uses Java APIs also exposed by the Android compile SDK. */
private class LocalServer : Closeable {
    data class Recorded(val path: String, val headers: Map<String, List<String>>)
    private data class Reply(val status: Int, val body: String, val location: String?)
    private val replies = ConcurrentLinkedQueue<Reply>()
    private val requests = ConcurrentLinkedQueue<Recorded>()
    private val count = AtomicInteger()
    val requestCount: Int get() = count.get()
    private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newSingleThreadExecutor()
    init {
        executor.submit {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val requestLine = reader.readLine() ?: return@use
                        val headers = TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER)
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            val name = line.substringBefore(':')
                            headers[name] = listOf(line.substringAfter(':').trim())
                        }
                        requests.add(Recorded(requestLine.split(' ')[1], headers))
                        count.incrementAndGet()
                        val reply = replies.poll() ?: Reply(500, "Unexpected test request", null)
                        val bytes = reply.body.toByteArray()
                        val head = buildString {
                            append("HTTP/1.1 ${reply.status} Test\r\n")
                            append("Content-Length: ${bytes.size}\r\nConnection: close\r\n")
                            reply.location?.let { append("Location: $it\r\n") }
                            append("\r\n")
                        }
                        socket.getOutputStream().apply {
                            write(head.toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                } catch (e: java.io.IOException) {
                    if (!server.isClosed) throw e
                }
            }
        }
    }
    fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"
    fun enqueue(status: Int = 200, body: String = "", location: String? = null) {
        replies.add(Reply(status, body, location))
    }
    fun takeRequest(): Recorded = checkNotNull(requests.poll())
    override fun close() {
        server.close()
        executor.shutdownNow()
    }
}
