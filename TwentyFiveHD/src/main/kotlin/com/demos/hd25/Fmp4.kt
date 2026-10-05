package com.demos.hd25

/**
 * Minimal fragmented-MP4 reader for the single-track H.264 / AAC renditions ZMDB serves.
 * It reads codec configuration from an init segment and sample timing/locations from
 * `moof`/`mdat` fragments; it never copies sample data, only records where it lives.
 */
internal object Fmp4 {
    class Box(val type: String, val start: Int, val size: Int, val header: Int) {
        val body get() = start + header
        val end get() = start + size
    }

    enum class Kind { VIDEO, AUDIO }

    class Track(
        val kind: Kind,
        val timescale: Long,
        /** Media time the first presented sample starts at (edit list), in [timescale] units. */
        val startOffset: Long,
        val defaultDuration: Long,
        val defaultSize: Int,
        val defaultFlags: Int,
        // H.264
        val nalLengthSize: Int = 4,
        val sps: List<ByteArray> = emptyList(),
        val pps: List<ByteArray> = emptyList(),
        // AAC
        val audioObjectType: Int = 2,
        val samplingIndex: Int = 4,
        val channels: Int = 2,
    )

    class Sample(val decodeTime: Long, val compositionOffset: Long, val offset: Int, val size: Int, val sync: Boolean)

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun u32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()
    private fun s32(b: ByteArray, i: Int): Int = u32(b, i).toInt()
    private fun u64(b: ByteArray, i: Int): Long = (u32(b, i) shl 32) or u32(b, i + 4)

    fun boxes(b: ByteArray, from: Int = 0, to: Int = b.size): List<Box> {
        val out = ArrayList<Box>()
        var at = from
        while (at + 8 <= to) {
            var size = u32(b, at)
            var header = 8
            if (size == 1L) {
                require(at + 16 <= to) { "Truncated box" }
                size = u64(b, at + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - at).toLong()
            }
            require(size >= header && at + size <= to) { "Invalid MP4 box size" }
            out.add(Box(String(b, at + 4, 4, Charsets.ISO_8859_1), at, size.toInt(), header))
            at += size.toInt()
        }
        return out
    }

    private fun child(b: ByteArray, parent: Box, type: String, skip: Int = 0): Box? =
        boxes(b, parent.body + skip, parent.end).firstOrNull { it.type == type }

    private fun path(b: ByteArray, parent: Box, vararg types: String): Box? {
        var current = parent
        for (type in types) current = child(b, current, type) ?: return null
        return current
    }

    fun parseInit(b: ByteArray): Track {
        val moov = boxes(b).firstOrNull { it.type == "moov" } ?: throw IllegalArgumentException("Init segment has no moov")
        val traks = boxes(b, moov.body, moov.end).filter { it.type == "trak" }
        require(traks.size == 1) { "Expected one track per rendition, found ${traks.size}" }
        val trak = traks[0]
        val mvhd = child(b, moov, "mvhd") ?: throw IllegalArgumentException("Missing mvhd")
        val movieTimescale = if (u8(b, mvhd.body) == 1) u32(b, mvhd.body + 20) else u32(b, mvhd.body + 12)
        val mdhd = path(b, trak, "mdia", "mdhd") ?: throw IllegalArgumentException("Missing mdhd")
        val timescale = if (u8(b, mdhd.body) == 1) u32(b, mdhd.body + 20) else u32(b, mdhd.body + 12)
        require(timescale > 0 && movieTimescale > 0) { "Invalid timescale" }
        val hdlr = path(b, trak, "mdia", "hdlr") ?: throw IllegalArgumentException("Missing hdlr")
        val handler = String(b, hdlr.body + 8, 4, Charsets.ISO_8859_1)

        // Edit list: an empty edit delays presentation; a media edit skips leading media time.
        var startOffset = 0L
        path(b, trak, "edts", "elst")?.let { elst ->
            val v1 = u8(b, elst.body) == 1
            val count = u32(b, elst.body + 4).toInt()
            var at = elst.body + 8
            var delay = 0L
            for (i in 0 until count) {
                val duration = if (v1) u64(b, at) else u32(b, at)
                val mediaTime = if (v1) u64(b, at + 8) else s32(b, at + 4).toLong()
                at += if (v1) 20 else 12
                if (mediaTime == -1L) {
                    delay += duration * timescale / movieTimescale
                } else {
                    startOffset = mediaTime - delay
                    break
                }
            }
        }

        val trex = path(b, moov, "mvex")?.let { mvex ->
            boxes(b, mvex.body, mvex.end).firstOrNull { it.type == "trex" }
        }
        val defaultDuration = trex?.let { u32(b, it.body + 12) } ?: 0L
        val defaultSize = trex?.let { s32(b, it.body + 16) } ?: 0
        val defaultFlags = trex?.let { s32(b, it.body + 20) } ?: 0

        val stsd = path(b, trak, "mdia", "minf", "stbl", "stsd") ?: throw IllegalArgumentException("Missing stsd")
        val entry = boxes(b, stsd.body + 8, stsd.end).firstOrNull() ?: throw IllegalArgumentException("Empty stsd")
        return when (handler) {
            "vide" -> {
                require(entry.type == "avc1" || entry.type == "avc3") { "Unsupported video codec ${entry.type}" }
                val avcC = boxes(b, entry.body + 78, entry.end).firstOrNull { it.type == "avcC" }
                    ?: throw IllegalArgumentException("Missing avcC")
                var at = avcC.body + 4
                val nalLength = (u8(b, at) and 3) + 1
                at++
                val sps = ArrayList<ByteArray>()
                repeat(u8(b, at) and 0x1F) {
                    val len = u16(b, at + 1)
                    sps.add(b.copyOfRange(at + 3, at + 3 + len))
                    at += 2 + len
                }
                at++
                val pps = ArrayList<ByteArray>()
                repeat(u8(b, at)) {
                    val len = u16(b, at + 1)
                    pps.add(b.copyOfRange(at + 3, at + 3 + len))
                    at += 2 + len
                }
                Track(Kind.VIDEO, timescale, startOffset, defaultDuration, defaultSize, defaultFlags,
                    nalLengthSize = nalLength, sps = sps, pps = pps)
            }
            "soun" -> {
                require(entry.type == "mp4a") { "Unsupported audio codec ${entry.type}" }
                val esds = boxes(b, entry.body + 28, entry.end).firstOrNull { it.type == "esds" }
                    ?: throw IllegalArgumentException("Missing esds")
                val config = audioSpecificConfig(b, esds.body + 4, esds.end)
                val aot = (u8(b, config) shr 3).let { if (it == 31) 32 + ((u8(b, config) and 7) shl 3 or (u8(b, config + 1) shr 5)) else it }
                val bits = if ((u8(b, config) shr 3) == 31) 11 else 5
                fun read(bit: Int, count: Int): Int {
                    var value = 0
                    for (i in 0 until count) {
                        val pos = bit + i
                        value = (value shl 1) or ((u8(b, config + pos / 8) shr (7 - pos % 8)) and 1)
                    }
                    return value
                }
                val samplingIndex = read(bits, 4)
                require(samplingIndex != 15) { "Explicit AAC sampling rates are not supported" }
                val channels = read(bits + 4, 4)
                Track(Kind.AUDIO, timescale, startOffset, defaultDuration, defaultSize, defaultFlags,
                    // ADTS can only signal AAC Main/LC/SSR/LTP; SBR/PS streams keep their LC core.
                    audioObjectType = if (aot in 1..4) aot else 2, samplingIndex = samplingIndex, channels = channels)
            }
            else -> throw IllegalArgumentException("Unsupported track handler $handler")
        }
    }

    /** Returns the offset of AudioSpecificConfig inside an ES_Descriptor. */
    private fun audioSpecificConfig(b: ByteArray, from: Int, to: Int): Int {
        var at = from
        fun descriptor(expected: Int): Int {
            require(at < to && u8(b, at) == expected) { "Unexpected esds descriptor" }
            at++
            var length = 0
            do {
                val byte = u8(b, at++)
                length = (length shl 7) or (byte and 0x7F)
            } while (byte and 0x80 != 0 && at < to)
            return length
        }
        descriptor(0x03)
        val flags = u8(b, at + 2)
        at += 3
        if (flags and 0x80 != 0) at += 2
        if (flags and 0x40 != 0) at += 1 + u8(b, at)
        if (flags and 0x20 != 0) at += 2
        descriptor(0x04)
        at += 13
        descriptor(0x05)
        return at
    }

    /** Lists samples of every fragment in a media segment, in decode order. */
    fun samples(b: ByteArray, track: Track): List<Sample> {
        val out = ArrayList<Sample>()
        for (moof in boxes(b).filter { it.type == "moof" }) {
            for (traf in boxes(b, moof.body, moof.end).filter { it.type == "traf" }) {
                val tfhd = child(b, traf, "tfhd") ?: throw IllegalArgumentException("Missing tfhd")
                val flags = s32(b, tfhd.body) and 0xFFFFFF
                var at = tfhd.body + 8
                var base = moof.start.toLong()
                if (flags and 0x01 != 0) { base = u64(b, at); at += 8 }
                if (flags and 0x02 != 0) at += 4
                var duration = track.defaultDuration
                var size = track.defaultSize
                var sampleFlags = track.defaultFlags
                if (flags and 0x08 != 0) { duration = u32(b, at); at += 4 }
                if (flags and 0x10 != 0) { size = s32(b, at); at += 4 }
                if (flags and 0x20 != 0) { sampleFlags = s32(b, at) }
                var time = child(b, traf, "tfdt")?.let { if (u8(b, it.body) == 1) u64(b, it.body + 4) else u32(b, it.body + 4) }
                    ?: (out.lastOrNull()?.let { it.decodeTime } ?: 0L)
                var data = base
                for (trun in boxes(b, traf.body, traf.end).filter { it.type == "trun" }) {
                    val version = u8(b, trun.body)
                    val runFlags = s32(b, trun.body) and 0xFFFFFF
                    val count = u32(b, trun.body + 4).toInt()
                    var p = trun.body + 8
                    if (runFlags and 0x01 != 0) { data = base + s32(b, p); p += 4 }
                    var firstFlags: Int? = null
                    if (runFlags and 0x04 != 0) { firstFlags = s32(b, p); p += 4 }
                    for (i in 0 until count) {
                        var d = duration
                        var s = size
                        var f = if (i == 0 && firstFlags != null) firstFlags else sampleFlags
                        var cto = 0L
                        if (runFlags and 0x100 != 0) { d = u32(b, p); p += 4 }
                        if (runFlags and 0x200 != 0) { s = s32(b, p); p += 4 }
                        if (runFlags and 0x400 != 0) { f = s32(b, p); p += 4 }
                        if (runFlags and 0x800 != 0) { cto = if (version == 0) u32(b, p) else s32(b, p).toLong(); p += 4 }
                        require(s >= 0 && data >= 0 && data + s <= b.size) { "Sample outside segment" }
                        // sample_is_non_sync_sample is bit 16 of the sample flags.
                        out.add(Sample(time, cto, data.toInt(), s, f and 0x10000 == 0))
                        data += s
                        time += d
                    }
                }
            }
        }
        return out
    }
}
