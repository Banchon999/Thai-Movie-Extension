package com.demos.hd25

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PlaybackDiagnosticsTest {
    @Test fun capturesChildFailureWithoutExtraRequestsOrChangingHeadersAndBodies() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("#EXTM3U\nchild.m3u8?secret=PRIVATE"))
            server.enqueue(MockResponse().setResponseCode(403).setBody("PRIVATE failure body"))
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
            assertEquals("https://example.org/embed?id=PRIVATE", first.getHeader("Referer"))
            assertTrue(first.path!!.contains("token=PRIVATE"))
            val report = diagnostics.report()
            assertTrue(report.contains("HTTP 403 | playlist | localhost"))
            assertTrue(report.contains("คำขอ 2, HTTP สำเร็จ 1"))
            assertFalse(report.contains("PRIVATE"))
        }
    }

    @Test fun recordsFinalRedirectHostAndPreservesRedirectBehavior() {
        MockWebServer().use { server ->
            val master = server.url("/master?PRIVATE").toString()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/PRIVATE/seg.bin?PRIVATE")))
            server.enqueue(MockResponse().setResponseCode(404))
            val diagnostics = PlaybackDiagnostics()
            OkHttpClient.Builder().addInterceptor(diagnostics.interceptor(master)).build()
                .newCall(Request.Builder().url(master).build()).execute().use { assertEquals(404, it.code) }
            assertEquals(2, server.requestCount)
            assertTrue(diagnostics.report().contains("HTTP 404 | media/bin | localhost"))
            assertFalse(diagnostics.report().contains("PRIVATE"))
        }
    }

    @Test fun recordsKeyAndMasterFailuresAndAcceptsPartialResponses() {
        MockWebServer().use { server ->
            val master = server.url("/_master?PRIVATE").toString()
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(206).setBody("chunk"))
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
