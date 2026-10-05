package com.demos.hd25

import java.io.ByteArrayOutputStream

/**
 * Converts one fMP4 media segment per track (one H.264 video, any number of AAC audio tracks)
 * into a self-contained MPEG-TS segment.
 *
 * Segments are produced independently (the downloader fetches several at once) but are later
 * concatenated into one file, so every segment starts with PAT/PMT and every PID emits a multiple
 * of 16 payload packets: continuity counters then run unbroken across segment boundaries.
 * Timestamps come from each fragment's decode time, so consecutive segments join seamlessly.
 */
internal object TsMuxer {
    class Input(val track: Fmp4.Track, val segment: ByteArray, val language: String = "und")

    private const val PACKET = 188
    private const val PMT_PID = 0x1000
    private const val VIDEO_PID = 0x100
    private const val FIRST_AUDIO_PID = 0x101
    /** Keeps timestamps positive when the edit list or composition offsets start before zero. */
    private const val OFFSET_90K = 90_000L
    private const val PCR_LEAD_90K = 27_000L
    private const val AUDIO_FRAMES_PER_PES = 6
    private val AUD = byteArrayOf(0, 0, 0, 1, 9, 0xF0.toByte())
    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    private class Pes(val pid: Int, val dts: Long, val bytes: ByteArray, val pcr: Long?)

    fun mux(video: Input, audio: List<Input>): ByteArray {
        require(video.track.kind == Fmp4.Kind.VIDEO && audio.all { it.track.kind == Fmp4.Kind.AUDIO })
        val pes = ArrayList<Pes>()
        pes += videoPes(video)
        audio.forEachIndexed { index, input -> pes += audioPes(input, FIRST_AUDIO_PID + index) }
        // Stable sort keeps each PID's own order while interleaving tracks by decode time.
        val ordered = pes.sortedBy { it.dts }

        val out = ByteArrayOutputStream(ordered.sumOf { it.bytes.size } * 105 / 100 + 64 * PACKET)
        val pat = pat()
        val pmt = pmt(audio.map { it.language })
        repeat(16) { cc -> section(out, 0, cc, pat) }
        repeat(16) { cc -> section(out, PMT_PID, cc, pmt) }

        val lastOfPid = ordered.withIndex().groupBy { it.value.pid }.mapValues { (_, v) -> v.last().index }
        val packetCount = HashMap<Int, Int>()
        for (p in ordered) packetCount[p.pid] = (packetCount[p.pid] ?: 0) + chunks(p, 0).size
        val counters = HashMap<Int, Int>()
        ordered.forEachIndexed { index, p ->
            val extra = if (lastOfPid[p.pid] == index) (16 - packetCount.getValue(p.pid) % 16) % 16 else 0
            var cc = counters[p.pid] ?: 0
            var at = 0
            chunks(p, extra).forEachIndexed { i, size ->
                writePacket(out, p.pid, cc, i == 0, if (i == 0) p.pcr else null, p.bytes, at, size)
                at += size
                cc = (cc + 1) and 0xF
            }
            counters[p.pid] = cc
        }
        return out.toByteArray()
    }

    private fun to90k(value: Long, track: Fmp4.Track): Long = value * 90_000L / track.timescale

    private fun videoPes(input: Input): List<Pes> {
        val track = input.track
        val b = input.segment
        return Fmp4.samples(b, track).map { s ->
            val es = ByteArrayOutputStream(s.size + 64)
            es.write(AUD)
            if (s.sync) {
                track.sps.forEach { es.write(START_CODE); es.write(it) }
                track.pps.forEach { es.write(START_CODE); es.write(it) }
            }
            var at = s.offset
            val end = s.offset + s.size
            while (at + track.nalLengthSize <= end) {
                var len = 0
                for (i in 0 until track.nalLengthSize) len = (len shl 8) or (b[at + i].toInt() and 0xFF)
                at += track.nalLengthSize
                require(len >= 0 && at + len <= end) { "Invalid NAL length" }
                // Access unit delimiters are already written above.
                if (len > 0 && (b[at].toInt() and 0x1F) != 9) {
                    es.write(START_CODE)
                    es.write(b, at, len)
                }
                at += len
            }
            val dts = to90k(s.decodeTime - track.startOffset, track) + OFFSET_90K
            val pts = to90k(s.decodeTime + s.compositionOffset - track.startOffset, track) + OFFSET_90K
            Pes(VIDEO_PID, dts, pesPacket(0xE0, pts, dts, es.toByteArray(), bounded = false),
                maxOf(0L, dts - PCR_LEAD_90K))
        }
    }

    private fun audioPes(input: Input, pid: Int): List<Pes> {
        val track = input.track
        val b = input.segment
        return Fmp4.samples(b, track).chunked(AUDIO_FRAMES_PER_PES).map { frames ->
            val es = ByteArrayOutputStream(frames.sumOf { it.size + 7 })
            for (f in frames) {
                val length = f.size + 7
                require(length < 8192) { "AAC frame too large for ADTS" }
                es.write(0xFF)
                es.write(0xF1)
                es.write(((track.audioObjectType - 1) shl 6) or (track.samplingIndex shl 2) or (track.channels shr 2))
                es.write(((track.channels and 3) shl 6) or (length shr 11))
                es.write((length shr 3) and 0xFF)
                es.write(((length and 7) shl 5) or 0x1F)
                es.write(0xFC)
                es.write(b, f.offset, f.size)
            }
            val first = frames.first()
            val pts = to90k(first.decodeTime + first.compositionOffset - track.startOffset, track) + OFFSET_90K
            Pes(pid, pts, pesPacket(0xC0, pts, null, es.toByteArray(), bounded = true), null)
        }
    }

    private fun timestamp(out: ByteArrayOutputStream, prefix: Int, value: Long) {
        val v = value and 0x1FFFFFFFFL
        out.write((prefix shl 4) or (((v shr 30) and 7).toInt() shl 1) or 1)
        out.write(((v shr 22) and 0xFF).toInt())
        out.write((((v shr 15) and 0x7F).toInt() shl 1) or 1)
        out.write(((v shr 7) and 0xFF).toInt())
        out.write(((v and 0x7F).toInt() shl 1) or 1)
    }

    private fun pesPacket(streamId: Int, pts: Long, dts: Long?, payload: ByteArray, bounded: Boolean): ByteArray {
        val headerData = if (dts != null && dts != pts) 10 else 5
        val out = ByteArrayOutputStream(payload.size + 19)
        out.write(0); out.write(0); out.write(1); out.write(streamId)
        val length = 3 + headerData + payload.size
        // Video PES may exceed the 16-bit length field; 0 means "unbounded", allowed for video only.
        val field = if (bounded && length <= 0xFFFF) length else 0
        out.write(field shr 8); out.write(field and 0xFF)
        out.write(0x80)
        out.write(if (headerData == 10) 0xC0 else 0x80)
        out.write(headerData)
        if (headerData == 10) {
            timestamp(out, 3, pts)
            timestamp(out, 1, dts!!)
        } else {
            timestamp(out, 2, pts)
        }
        out.write(payload)
        return out.toByteArray()
    }

    /** Payload size of each TS packet for one PES; [extra] adds packets by splitting the tail. */
    private fun chunks(p: Pes, extra: Int): List<Int> {
        val sizes = ArrayList<Int>()
        var remaining = p.bytes.size
        var capacity = if (p.pcr != null) 176 else 184
        while (remaining > 0) {
            val take = minOf(capacity, remaining)
            sizes.add(take)
            remaining -= take
            capacity = 184
        }
        repeat(extra) {
            val i = sizes.indexOfLast { it > 1 }
            require(i >= 0) { "PES too small to align continuity counters" }
            sizes[i] -= 1
            sizes.add(i + 1, 1)
        }
        return sizes
    }

    private fun writePacket(
        out: ByteArrayOutputStream, pid: Int, cc: Int, start: Boolean, pcr: Long?,
        data: ByteArray, offset: Int, size: Int,
    ) {
        out.write(0x47)
        out.write((if (start) 0x40 else 0) or (pid shr 8))
        out.write(pid and 0xFF)
        val adaptation = 184 - size
        out.write((if (adaptation > 0) 0x30 else 0x10) or cc)
        if (adaptation > 0) {
            val afLength = adaptation - 1
            out.write(afLength)
            if (afLength > 0) {
                var written = 1
                if (pcr != null) {
                    require(afLength >= 7)
                    out.write(0x10)
                    out.write(((pcr shr 25) and 0xFF).toInt())
                    out.write(((pcr shr 17) and 0xFF).toInt())
                    out.write(((pcr shr 9) and 0xFF).toInt())
                    out.write(((pcr shr 1) and 0xFF).toInt())
                    out.write((((pcr and 1).toInt()) shl 7) or 0x7E)
                    out.write(0)
                    written += 6
                } else {
                    out.write(0)
                }
                repeat(afLength - written) { out.write(0xFF) }
            }
        }
        out.write(data, offset, size)
    }

    private fun section(out: ByteArrayOutputStream, pid: Int, cc: Int, table: ByteArray) {
        out.write(0x47)
        out.write(0x40 or (pid shr 8))
        out.write(pid and 0xFF)
        out.write(0x10 or cc)
        out.write(0) // pointer_field
        out.write(table)
        repeat(PACKET - 5 - table.size) { out.write(0xFF) }
    }

    private fun withCrc(body: ByteArray): ByteArray {
        val crc = crc32(body)
        return body + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
    }

    private fun pat(): ByteArray = withCrc(byteArrayOf(
        0x00, 0xB0.toByte(), 13, 0x00, 0x01, 0xC1.toByte(), 0, 0,
        0x00, 0x01, (0xE0 or (PMT_PID shr 8)).toByte(), (PMT_PID and 0xFF).toByte(),
    ))

    private fun pmt(languages: List<String>): ByteArray {
        val streams = ByteArrayOutputStream()
        streams.write(0x1B)
        streams.write(0xE0 or (VIDEO_PID shr 8)); streams.write(VIDEO_PID and 0xFF)
        streams.write(0xF0); streams.write(0)
        languages.forEachIndexed { i, language ->
            val pid = FIRST_AUDIO_PID + i
            val code = language.lowercase().padEnd(3, ' ').take(3).toByteArray(Charsets.ISO_8859_1)
            streams.write(0x0F)
            streams.write(0xE0 or (pid shr 8)); streams.write(pid and 0xFF)
            streams.write(0xF0); streams.write(6)
            streams.write(0x0A); streams.write(4); streams.write(code); streams.write(0)
        }
        val s = streams.toByteArray()
        val length = 9 + s.size + 4
        val head = byteArrayOf(
            0x02, (0xB0 or (length shr 8)).toByte(), (length and 0xFF).toByte(),
            0x00, 0x01, 0xC1.toByte(), 0, 0,
            (0xE0 or (VIDEO_PID shr 8)).toByte(), (VIDEO_PID and 0xFF).toByte(), 0xF0.toByte(), 0,
        )
        return withCrc(head + s).also { require(it.size <= PACKET - 5) { "Too many audio tracks" } }
    }

    /** CRC-32/MPEG-2 as required for PSI sections. */
    internal fun crc32(data: ByteArray): Int {
        var crc = -1
        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc
    }
}
