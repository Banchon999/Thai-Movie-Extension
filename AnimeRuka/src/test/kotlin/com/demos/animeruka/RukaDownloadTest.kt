package com.demos.animeruka

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RukaDownloadTest {
    private val ts = ByteArray(188 * 4) { if (it % 188 == 0) 0x47 else 1 }
    private val webpPrefix = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 tiny-image".toByteArray(Charsets.ISO_8859_1)

    private val tsLine = Regex("""#EXTINF:(([0-9]*[.])?[0-9]+|).*\n(.+?\n)""") // Cloudstream's segment regex

    private fun wrapped(text: String) = "{\"p\":\"${Base64.getEncoder().encodeToString(text.toByteArray())}\"}".toByteArray()

    /** Fake CDN that only answers with the embed Referer, as the real one does. */
    private fun cdn(files: Map<String, ByteArray>, requests: MutableList<String> = ArrayList()): (String) -> RukaDownload.Fetched = { url ->
        requests += url
        files[url]?.let { RukaDownload.Fetched(200, it) } ?: RukaDownload.Fetched(404, ByteArray(0))
    }

    private fun path(url: String) = url.substringAfter(RukaDownload.HOST).substringBefore('?')
    private fun query(url: String) = url.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
        .associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun unwrapsJsonWrappedPlaylistAndRewritesSegments() {
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.0,\nseg-0.webp\n#EXTINF:4.0,\n/hls/x/seg-1.webp\n#EXT-X-ENDLIST\n"
        val fetch = cdn(mapOf(
            "https://cdn2.maimeorder.com/hls/x.txt" to wrapped(media),
            "https://cdn2.maimeorder.com/hls/seg-0.webp" to webpPrefix + ts,
            "https://cdn2.maimeorder.com/hls/x/seg-1.webp" to ts,
        ))
        val link = RukaDownload.playlistUrl("https://cdn2.maimeorder.com/hls/x.txt")
        val playlist = String(RukaDownload.serve(path(link), emptyMap(), fetch).body)
        val segments = tsLine.findAll(playlist + "\n").map { it.groupValues[3].trim() }.toList()
        assertEquals(2, segments.size)
        assertTrue(segments.all { it.startsWith("https://${RukaDownload.HOST}/s/") })
        val first = RukaDownload.serve(path(segments[0]), query(segments[0]), fetch)
        assertEquals("video/mp2t", first.contentType)
        assertTrue("image prefix must be dropped", first.body.contentEquals(ts))
        assertTrue(RukaDownload.serve(path(segments[1]), query(segments[1]), fetch).body.contentEquals(ts))
    }

    @Test
    fun choosesHighestVariantWithMuxedAudio() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="th",URI="audio/index.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=1920x1080,AUDIO="aud"
            1080/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720
            720/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=640x360
            360/index.m3u8
        """.trimIndent()
        assertEquals("https://c.example/v/720/index.m3u8", RukaDownload.chooseVariant("https://c.example/v/master.m3u8", master))
        assertEquals(null, RukaDownload.chooseVariant("https://c.example/v/x.m3u8", "#EXTM3U\n#EXTINF:4,\na.ts\n"))
        val requests = ArrayList<String>()
        val fetch = cdn(mapOf(
            "https://c.example/v/master.m3u8" to master.toByteArray(),
            "https://c.example/v/720/index.m3u8" to "#EXTM3U\n#EXTINF:4,\na.ts\n".toByteArray(),
        ), requests)
        val playlist = String(RukaDownload.serve(path(RukaDownload.playlistUrl("https://c.example/v/master.m3u8")), emptyMap(), fetch).body)
        assertTrue(playlist.contains("#EXT-X-ENDLIST"))
        assertEquals(listOf("https://c.example/v/master.m3u8", "https://c.example/v/720/index.m3u8"), requests)
    }

    @Test
    fun prependsFmp4InitToFirstSegmentOnly() {
        val init = ByteArray(16).also { "ftyp".toByteArray().copyInto(it, 4) }
        val frag = ByteArray(16).also { "moof".toByteArray().copyInto(it, 4) }
        val media = "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\n0.m4s\n#EXTINF:4,\n1.m4s\n"
        val fetch = cdn(mapOf(
            "https://c.example/v/index.m3u8" to media.toByteArray(),
            "https://c.example/v/init.mp4" to init,
            "https://c.example/v/0.m4s" to frag,
            "https://c.example/v/1.m4s" to frag,
        ))
        val playlist = String(RukaDownload.serve(path(RukaDownload.playlistUrl("https://c.example/v/index.m3u8")), emptyMap(), fetch).body)
        assertTrue("downloader ignores EXT-X-MAP", "#EXT-X-MAP" !in playlist)
        val segments = tsLine.findAll(playlist + "\n").map { it.groupValues[3].trim() }.toList()
        assertTrue(query(segments[0]).containsKey("init"))
        assertTrue(!query(segments[1]).containsKey("init"))
        assertTrue(RukaDownload.serve(path(segments[0]), query(segments[0]), fetch).body.contentEquals(init + frag))
        assertTrue(RukaDownload.serve(path(segments[1]), query(segments[1]), fetch).body.contentEquals(frag))
    }

    @Test
    fun keepsEncryptedSegmentsRawAndProxiesKey() {
        val media = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://k.example/key.bin\",IV=0x01\n#EXTINF:4,\n0.ts\n"
        val cipher = ByteArray(400) { 3 }
        val fetch = cdn(mapOf(
            "https://c.example/v/index.m3u8" to media.toByteArray(),
            "https://k.example/key.bin" to ByteArray(16) { 9 },
            "https://c.example/v/0.ts" to cipher,
        ))
        val playlist = String(RukaDownload.serve(path(RukaDownload.playlistUrl("https://c.example/v/index.m3u8")), emptyMap(), fetch).body)
        // Cloudstream reads METHOD, URI and IV with this exact shape.
        val key = Regex("#EXT-X-KEY:METHOD=([^,]+),URI=\"([^\"]+)\"(?:,IV=(.*))?").find(playlist)!!.groupValues
        assertEquals("AES-128", key[1])
        assertEquals("0x01", key[3])
        assertEquals(16, RukaDownload.serve(path(key[2]), emptyMap(), fetch).body.size)
        val segment = tsLine.find(playlist + "\n")!!.groupValues[3].trim()
        assertEquals("1", query(segment)["raw"])
        assertTrue(RukaDownload.serve(path(segment), query(segment), fetch).body.contentEquals(cipher))
    }

    private fun png(payload: Int): ByteArray {
        fun chunk(type: String, data: ByteArray) = byteArrayOf(
            (data.size ushr 24).toByte(), (data.size ushr 16).toByte(), (data.size ushr 8).toByte(), data.size.toByte()) +
            type.toByteArray() + data + ByteArray(4)
        // Image data deliberately contains 0x47 bytes 188 apart so a naive scan would cut inside it.
        val idat = ByteArray(payload) { if (it % 188 == 0) 0x47 else 0x10 }
        return byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) +
            chunk("IHDR", ByteArray(13)) + chunk("IDAT", idat) + chunk("IEND", ByteArray(0))
    }

    @Test
    fun stripsLeadingImagesByTheirOwnStructure() {
        // Larger than the 1 MB window the first version scanned.
        assertTrue(RukaDownload.cleanSegment(png(1_200_000) + ts).contentEquals(ts))
        val webpBody = ByteArray(300) { if (it % 188 == 0) 0x47 else 2 }
        val webp = "RIFF".toByteArray() + byteArrayOf((webpBody.size + 4).toByte(), ((webpBody.size + 4) shr 8).toByte(), 0, 0) +
            "WEBP".toByteArray() + webpBody
        assertTrue(RukaDownload.cleanSegment(webp + ts).contentEquals(ts))
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        assertTrue(RukaDownload.cleanSegment(jpeg + ts).contentEquals(ts))
    }

    @Test
    fun cleanSegmentLeavesPlainStreamsAlone() {
        assertTrue(RukaDownload.cleanSegment(ts).contentEquals(ts))
        val unknown = ByteArray(1000) { 5 }
        assertTrue(RukaDownload.cleanSegment(unknown).contentEquals(unknown))
        assertEquals("#EXTM3U\n", RukaDownload.unwrap(wrapped("#EXTM3U\n")))
        assertEquals("#EXTM3U", RukaDownload.unwrap("#EXTM3U".toByteArray()))
    }
}
