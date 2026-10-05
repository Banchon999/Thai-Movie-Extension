package com.demos.hd25

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic fMP4 matching the observed ZMDB layout: one track per rendition, moof-relative data. */
private object Mp4 {
    fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        return int(8 + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body
    }
    fun full(type: String, version: Int, flags: Int, vararg parts: ByteArray) =
        box(type, byteArrayOf(version.toByte(), (flags shr 16).toByte(), (flags shr 8).toByte(), flags.toByte()), *parts)
    fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    fun long(v: Long) = int((v ushr 32).toInt()) + int(v.toInt())
    fun short(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    fun zeros(n: Int) = ByteArray(n)

    val SPS = byteArrayOf(0x67, 0x4D, 0x40, 0x1F, 0x11)
    val PPS = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0x80.toByte())

    private fun trak(handler: String, timescale: Int, entry: ByteArray, elst: ByteArray?) = box("trak",
        full("tkhd", 0, 3, zeros(80)),
        *(if (elst != null) arrayOf(box("edts", elst)) else emptyArray()),
        box("mdia",
            full("mdhd", 0, 0, int(0), int(0), int(timescale), int(0), zeros(4)),
            full("hdlr", 0, 0, int(0), handler.toByteArray(Charsets.ISO_8859_1), zeros(12), byteArrayOf(0)),
            box("minf", box("stbl", full("stsd", 0, 0, int(1), entry)))))

    private fun moov(trak: ByteArray) = box("moov",
        full("mvhd", 0, 0, int(0), int(0), int(1000), int(0), zeros(80)),
        trak,
        box("mvex", full("trex", 0, 0, int(1), int(1), int(0), int(0), int(0))))

    fun videoInit(): ByteArray {
        val avcC = box("avcC", byteArrayOf(1, 0x4D, 0x40, 0x1F, 0xFF.toByte(), 0xE1.toByte()), short(SPS.size), SPS,
            byteArrayOf(1), short(PPS.size), PPS)
        val avc1 = box("avc1", zeros(6), short(1), zeros(16), short(1280), short(720), zeros(50), avcC)
        // Empty edit of 120 ms then media from 1536: first frame presented at 0.12 s, as ZMDB does.
        val elst = full("elst", 0, 0, int(2), int(120), int(-1), int(0x10000), int(0), int(1536), int(0x10000))
        return box("ftyp", "isom".toByteArray(), int(0)) + moov(trak("vide", 12800, avc1, elst))
    }

    fun audioInit(): ByteArray {
        // AAC LC, 48 kHz (index 3), stereo.
        val asc = byteArrayOf(0x11, 0x90.toByte())
        val esds = full("esds", 0, 0,
            byteArrayOf(0x03, (3 + 2 + 13 + 2 + asc.size).toByte(), 0, 1, 0),
            byteArrayOf(0x04, (13 + 2 + asc.size).toByte(), 0x40, 0x15, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            byteArrayOf(0x05, asc.size.toByte()), asc)
        val mp4a = box("mp4a", zeros(6), short(1), zeros(8), short(2), short(16), zeros(4), int(48000 shl 16), esds)
        return box("ftyp", "isom".toByteArray(), int(0)) + moov(trak("soun", 48000, mp4a, null))
    }

    /** One fragment; each sample is its payload, video samples carrying one length-prefixed NAL. */
    fun segment(baseTime: Long, durations: List<Int>, payloads: List<ByteArray>, ctos: List<Int>?, syncFirstOnly: Boolean): ByteArray {
        val runFlags = 0x001 or 0x004 or 0x100 or 0x200 or (if (ctos != null) 0x800 else 0)
        fun trun(dataOffset: Int): ByteArray {
            val entries = ByteArrayOutputStream()
            payloads.forEachIndexed { i, p ->
                entries.write(int(durations[i])); entries.write(int(p.size))
                if (ctos != null) entries.write(int(ctos[i]))
            }
            val first = if (syncFirstOnly) 0x02000000 else 0x02000000
            return full("trun", 1, runFlags, int(payloads.size), int(dataOffset), int(first), entries.toByteArray())
        }
        val tfhd = full("tfhd", 0, 0x020020, int(1), int(if (syncFirstOnly) 0x01010000 else 0x02000000))
        val tfdt = full("tfdt", 1, 0, long(baseTime))
        fun moof(offset: Int) = box("moof", full("mfhd", 0, 0, int(1)), box("traf", tfhd, tfdt, trun(offset)))
        val size = moof(0).size
        val mdat = box("mdat", payloads.fold(ByteArray(0)) { a, b -> a + b })
        return box("styp", "msdh".toByteArray(), int(0)) + moof(size + 8) + mdat
    }

    fun nal(type: Int, length: Int) = int(length) + byteArrayOf(type.toByte()) + ByteArray(length - 1) { (it % 200).toByte() }
}

class ZmdbDownloadTest {
    private val video = Fmp4.parseInit(Mp4.videoInit())
    private val audio = Fmp4.parseInit(Mp4.audioInit())

    private fun videoSegment(n: Int) = Mp4.segment(n * 51200L, List(4) { 12800 }, listOf(Mp4.nal(0x65, 300)) + List(3) { Mp4.nal(0x41, 120) },
        listOf(1536, 3072, 1536, 512), syncFirstOnly = true)
    private fun audioSegment(n: Int) = Mp4.segment(n * 192000L, List(6) { 1024 * 30 }, List(6) { ByteArray(200) { 7 } }, null, false)

    private fun packets(ts: ByteArray) = (0 until ts.size / 188).map { ts.copyOfRange(it * 188, it * 188 + 188) }
    private fun pid(p: ByteArray) = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)

    @Test
    fun parsesInitConfiguration() {
        assertEquals(Fmp4.Kind.VIDEO, video.kind)
        assertEquals(12800L, video.timescale)
        assertEquals(0L, video.startOffset) // 1536 media time minus the 120 ms (1536 tick) empty edit
        assertEquals(4, video.nalLengthSize)
        assertTrue(video.sps.single().contentEquals(Mp4.SPS))
        assertTrue(video.pps.single().contentEquals(Mp4.PPS))
        assertEquals(Fmp4.Kind.AUDIO, audio.kind)
        assertEquals(2, audio.audioObjectType)
        assertEquals(3, audio.samplingIndex)
        assertEquals(2, audio.channels)
    }

    @Test
    fun readsFragmentSamples() {
        val samples = Fmp4.samples(videoSegment(1), video)
        assertEquals(4, samples.size)
        assertEquals(51200L, samples[0].decodeTime)
        assertEquals(51200L + 3 * 12800, samples[3].decodeTime)
        assertEquals(listOf(1536L, 3072L, 1536L, 512L), samples.map { it.compositionOffset })
        assertEquals(listOf(true, false, false, false), samples.map { it.sync })
        assertEquals(304, samples[0].size)
    }

    @Test
    fun concatenatedSegmentsFormContinuousValidTransportStream() {
        val ts = (0..2).map { n ->
            TsMuxer.mux(TsMuxer.Input(video, videoSegment(n)),
                listOf(TsMuxer.Input(audio, audioSegment(n), "tha"), TsMuxer.Input(audio, audioSegment(n), "eng")))
        }
        ts.forEach { assertEquals(0, it.size % 188) }
        val all = packets(ts.fold(ByteArray(0)) { a, b -> a + b })
        assertTrue(all.all { it[0] == 0x47.toByte() })
        // Continuity counters must advance by one per payload packet across segment boundaries.
        val last = HashMap<Int, Int>()
        for (p in all) {
            val cc = p[3].toInt() and 0xF
            last[pid(p)]?.let { assertEquals("PID ${pid(p)}", (it + 1) and 0xF, cc) }
            last[pid(p)] = cc
        }
        assertEquals(setOf(0, 0x1000, 0x100, 0x101, 0x102), last.keys)
        // PSI sections carry a valid CRC (CRC over section including CRC is zero).
        for (table in listOf(all.first { pid(it) == 0 }, all.first { pid(it) == 0x1000 })) {
            val length = ((table[6].toInt() and 0x0F) shl 8) or (table[7].toInt() and 0xFF)
            assertEquals(0, TsMuxer.crc32(table.copyOfRange(5, 8 + length)))
        }
        val pmt = all.first { pid(it) == 0x1000 }
        assertTrue(String(pmt, Charsets.ISO_8859_1).contains("tha") && String(pmt, Charsets.ISO_8859_1).contains("eng"))
    }

    private fun pts(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0x0E) shl 29) or ((b[at + 1].toLong() and 0xFF) shl 22) or ((b[at + 2].toLong() and 0xFE) shl 14) or
            ((b[at + 3].toLong() and 0xFF) shl 7) or ((b[at + 4].toLong() and 0xFE) shr 1)

    private fun payload(p: ByteArray): ByteArray {
        val start = if (p[3].toInt() and 0x20 != 0) 5 + (p[4].toInt() and 0xFF) else 4
        return p.copyOfRange(start, 188)
    }

    @Test
    fun timestampsFollowFragmentDecodeTimeAndEditList() {
        val ts = TsMuxer.mux(TsMuxer.Input(video, videoSegment(1)), listOf(TsMuxer.Input(audio, audioSegment(1), "tha")))
        val starts = packets(ts).filter { it[1].toInt() and 0x40 != 0 }
        val firstVideo = payload(starts.first { pid(it) == 0x100 })
        assertEquals(0xC0, firstVideo[7].toInt() and 0xC0)
        // Segment 1 starts at 4 s; +0.12 s composition offset, +1 s positive-timestamp offset.
        assertEquals(90_000L + 4 * 90_000L + 10_800L, pts(firstVideo, 9))
        assertEquals(90_000L + 4 * 90_000L, pts(firstVideo, 14))
        val es = firstVideo.copyOfRange(9 + 10, firstVideo.size)
        // Keyframe access unit: AUD, SPS, PPS, then the slice.
        val expected = byteArrayOf(0, 0, 0, 1, 9, 0xF0.toByte(), 0, 0, 0, 1) + Mp4.SPS + byteArrayOf(0, 0, 0, 1) + Mp4.PPS +
            byteArrayOf(0, 0, 0, 1, 0x65)
        assertTrue(es.copyOfRange(0, expected.size).contentEquals(expected))
        val firstAudio = payload(starts.first { pid(it) == 0x101 })
        assertEquals(90_000L + 4 * 90_000L, pts(firstAudio, 9))
        val adts = firstAudio.copyOfRange(14, 21)
        assertEquals(0xFF, adts[0].toInt() and 0xFF)
        assertEquals(0xF1, adts[1].toInt() and 0xFF)
        assertEquals(207, ((adts[3].toInt() and 3) shl 11) or ((adts[4].toInt() and 0xFF) shl 3) or ((adts[5].toInt() and 0xFF) shr 5))
        assertEquals((1 shl 6) or (3 shl 2), adts[2].toInt() and 0xFF) // LC profile, 48 kHz
    }

    private val masterText = """
        #EXTM3U
        #EXT-X-VERSION:11
        #EXT-X-CONTENT-STEERING:SERVER-URI="https://g.example/hls/playback-routing.json?gw_enc=o1",PATHWAY-ID="."
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="group_audio",NAME="English",LANGUAGE="en",DEFAULT=NO,AUTOSELECT=YES,CHANNELS="2",URI="/hls/0123456789abcdef01234567/_a_2/_index?sig=b&exp=1&gw_enc=o1"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="group_audio",NAME="ไทย",LANGUAGE="th",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="2",URI="/hls/0123456789abcdef01234567/_a_1/_index?sig=a&exp=1&gw_enc=o1"
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="ไทย",LANGUAGE="th",DEFAULT=YES,FORCED=NO,AUTOSELECT=YES,URI="/hls/0123456789abcdef01234567/_s/_tha?gw_enc=o1"
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="ไทย (Forced)",LANGUAGE="th",DEFAULT=NO,FORCED=YES,AUTOSELECT=YES,URI="/hls/0123456789abcdef01234567/_s/_tha-forced?gw_enc=o1"
        #EXT-X-STREAM-INF:BANDWIDTH=5853088,RESOLUTION=1920x1080,CODECS="avc1.4d4028,mp4a.40.2",AUDIO="group_audio",SUBTITLES="subs"
        /hls/0123456789abcdef01234567/1080p/_index?sig=c&exp=1&gw_enc=o1
        #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=1014902,RESOLUTION=1920x1080,CODECS="avc1.4d4028",URI="/hls/0123456789abcdef01234567/1080p/_iframe_index"
        #EXT-X-STREAM-INF:BANDWIDTH=2765378,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="group_audio",SUBTITLES="subs"
        /hls/0123456789abcdef01234567/720p/_index?sig=d&exp=1&gw_enc=o1
    """.trimIndent()

    @Test
    fun parsesMasterRenditionsWithDefaultAudioFirst() {
        val master = ZmdbDownload.parseMaster("https://g.example/hls/0123456789abcdef01234567/t.x/_master", masterText)
        assertEquals(listOf(1080, 720), master.downloadable.map { it.height })
        assertEquals(listOf("th", "en"), master.audio.getValue("group_audio").map { it.language })
        assertEquals("https://g.example/hls/0123456789abcdef01234567/720p/_index?sig=d&exp=1&gw_enc=o1", master.variants[1].uri)
        assertEquals(listOf(false, true), master.subtitles.map { it.forced })
        assertEquals("https://g.example/hls/playback-routing.json?gw_enc=o1", master.steering)
    }

    /** Fake network reproducing the live split: the gateway serves playlists, only the CDN serves media. */
    private fun network(requests: MutableList<String>): (String) -> ZmdbDownload.Fetched = { url ->
        requests += url
        val host = java.net.URI(url).host
        val path = java.net.URI(url).path
        fun ok(text: String) = ZmdbDownload.Fetched(200, text.toByteArray())
        fun media(index: Int, video: Boolean) = ZmdbDownload.Fetched(200, if (video) videoSegment(index) else audioSegment(index))
        val segment = Regex("seg_(\\d+)\\.bin").find(path)?.groupValues?.get(1)?.toInt()
        when {
            path == "/api/video/0123456789abcdef01234567" ->
                ok("""{"success":true,"data":{"hlsUrl":"https://g.example/hls/0123456789abcdef01234567/t.x/_master"}}""")
            path.endsWith("/_master") -> ok(masterText)
            path.endsWith("playback-routing.json") ->
                ok("""{"PATHWAY-CLONES":[{"ID":"cdn-001","URI-REPLACEMENT":{"HOST":"cdn.example"}}],"PATHWAY-PRIORITY":["cdn-001","."]}""")
            path.endsWith("/_index") -> ok("#EXTM3U\n#EXT-X-MAP:URI=\"hdr.bin\"\n#EXTINF:4.000000,\nseg_00000.bin\n#EXTINF:4.000000,\nseg_00001.bin\n#EXT-X-ENDLIST\n")
            path.endsWith("/_s/_tha") -> ok("#EXTM3U\n#EXTINF:6000,\ntha.vtt\n#EXT-X-ENDLIST\n")
            host != "cdn.example" -> ZmdbDownload.Fetched(403, "Forbidden".toByteArray())
            path.endsWith("/hdr.bin") -> ZmdbDownload.Fetched(200, if ("/_a_" in path) Mp4.audioInit() else Mp4.videoInit())
            segment != null -> media(segment, "/_a_" !in path)
            path.endsWith("/tha.vtt") -> ok("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nสวัสดี\n")
            else -> ZmdbDownload.Fetched(404, ByteArray(0))
        }
    }

    @Test
    fun servesPlaylistSegmentsAndSubtitlesFromCdnHosts() {
        val requests = ArrayList<String>()
        val fetch = network(requests)
        val playlist = String(ZmdbDownload.serve("/v/0123456789abcdef01234567/720.m3u8", fetch).body)
        // Cloudstream's downloader reads #EXTINF lines followed by the segment URL.
        val segments = Regex("#EXTINF:([0-9.]+),\\n(.+)\\n").findAll(playlist).map { it.groupValues[2] }.toList()
        assertEquals(listOf("https://zmdb-download.invalid/s/0123456789abcdef01234567/720/0.ts",
            "https://zmdb-download.invalid/s/0123456789abcdef01234567/720/1.ts"), segments)
        assertTrue(requests.any { it.contains("/720p/_index") } && requests.none { it.contains("/1080p/_index") })

        val ts = ZmdbDownload.serve("/s/0123456789abcdef01234567/720/1.ts", fetch)
        assertEquals(200, ts.status)
        assertEquals("video/mp2t", ts.contentType)
        val pids = packets(ts.body).map { pid(it) }.toSet()
        assertEquals(setOf(0, 0x1000, 0x100, 0x101, 0x102), pids) // video + Thai + English audio
        assertTrue("media must come from the steered CDN", requests.filter { it.endsWith(".bin") }.all { "cdn.example" in it || "g.example" in it })
        assertTrue(requests.any { it.startsWith("https://cdn.example/") && it.endsWith("seg_00001.bin") })

        val sub = ZmdbDownload.serve("/sub/0123456789abcdef01234567/0.vtt", fetch)
        assertEquals("text/vtt", sub.contentType)
        assertTrue(String(sub.body).startsWith("WEBVTT"))
        assertNotNull(String(sub.body).lines().firstOrNull { "-->" in it })
        assertEquals(404, ZmdbDownload.serve("/other", fetch).status)
    }
}
