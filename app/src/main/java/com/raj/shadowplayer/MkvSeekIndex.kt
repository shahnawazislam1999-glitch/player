package com.raj.shadowplayer

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Some MKV files have no seek table ("Cues") or a broken one (a single entry at 0:00, or the table sits at the very
 * end with no pointer to it). The normal player then treats the film as "not seekable" and every jump goes back to
 * the beginning. Other players rebuild the table themselves; this does the same: it hops from one Cluster to the
 * next (one tiny read per cluster, a few thousand reads for a 3 GB film, well under a second on normal storage)
 * and remembers the start time and file position of each. Nothing is changed in the file.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object MkvSeekIndex {

    class Index(val timesUs: LongArray, val positions: LongArray, val lastTimeUs: Long)

    private var cacheKey: String? = null
    private var cacheIndex: Index? = null

    /** The index of the film at [uri], or null when it cannot be built. Remembers the last film. */
    @Synchronized
    fun get(ctx: Context, uri: Uri): Index? {
        val key = uri.toString()
        if (key == cacheKey && cacheIndex != null) return cacheIndex
        var result: Index? = null
        try {
            val pfd: ParcelFileDescriptor? = ctx.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    result = build(input.channel)
                }
            }
        } catch (t: Throwable) {
            result = null
        }
        if (result != null) {
            cacheKey = key
            cacheIndex = result
        }
        return result
    }

    fun seekMap(idx: Index, durationUs: Long): SeekMap = IndexSeekMap(idx, durationUs)

    private class IndexSeekMap(private val idx: Index, private val dur: Long) : SeekMap {
        override fun isSeekable(): Boolean = true
        override fun getDurationUs(): Long = dur
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val t = idx.timesUs
            // last cluster that starts at or before timeUs
            var lo = 0
            var hi = t.size - 1
            var ans = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (t[mid] <= timeUs) { ans = mid; lo = mid + 1 } else hi = mid - 1
            }
            val first = SeekPoint(t[ans], idx.positions[ans])
            return if (ans + 1 < t.size && t[ans] < timeUs) {
                SeekMap.SeekPoints(first, SeekPoint(t[ans + 1], idx.positions[ans + 1]))
            } else {
                SeekMap.SeekPoints(first)
            }
        }
    }

    // ------------------------------------------------------------------ the scan

    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMECODE_SCALE = 0x2AD7B1L
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_TIMECODE = 0xE7L

    /** Reads up to [n] bytes at [pos] into [dst]; returns how many were read. */
    private fun readAt(ch: FileChannel, pos: Long, dst: ByteArray, n: Int): Int {
        val bb = ByteBuffer.wrap(dst, 0, n)
        var p = pos
        while (bb.hasRemaining()) {
            val r = ch.read(bb, p)
            if (r < 0) break
            p += r
        }
        return bb.position()
    }

    /** Length of an element id that starts with byte [b0] (1..4), or 0 when invalid. */
    private fun idLen(b0: Int): Int = when {
        b0 >= 0x80 -> 1
        b0 >= 0x40 -> 2
        b0 >= 0x20 -> 3
        b0 >= 0x10 -> 4
        else -> 0
    }

    /**
     * Element header at [off] in [b] (which holds [have] valid bytes). Returns longArrayOf(id, headerLen, dataSize),
     * with dataSize = -1 for "unknown size", or null when the bytes are not a valid header.
     */
    private fun header(b: ByteArray, off: Int, have: Int): LongArray? {
        if (off >= have) return null
        val il = idLen(b[off].toInt() and 0xFF)
        if (il == 0 || off + il >= have) return null
        var id = 0L
        for (i in 0 until il) id = (id shl 8) or (b[off + i].toLong() and 0xFF)
        val s0 = b[off + il].toInt() and 0xFF
        var sl = 1
        var mask = 0x80
        while (sl <= 8 && (s0 and mask) == 0) { sl++; mask = mask shr 1 }
        if (sl > 8 || off + il + sl > have) return null
        var sz = (s0 and (mask - 1)).toLong()
        var allOnes = (s0 and (mask - 1)) == mask - 1
        for (i in 1 until sl) {
            val v = b[off + il + i].toLong() and 0xFF
            sz = (sz shl 8) or v
            if (v != 0xFFL) allOnes = false
        }
        return longArrayOf(id, (il + sl).toLong(), if (allOnes) -1L else sz)
    }

    private fun uint(b: ByteArray, off: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    /** Builds the index, or returns null for a file that is not (hoppable) Matroska. */
    fun build(ch: FileChannel): Index? {
        val size = ch.size()
        if (size < 64) return null
        val buf = ByteArray(64)
        var times = LongArray(1024)
        var poss = LongArray(1024)
        var count = 0
        var scaleNs = 1_000_000L
        var pos = 0L
        val started = System.currentTimeMillis()
        var guard = 0
        var lastTime = -1L

        while (pos < size && guard++ < 5_000_000) {
            if ((guard and 0xFF) == 0 && System.currentTimeMillis() - started > 20_000L) return null
            val have = readAt(ch, pos, buf, 64)
            val h = header(buf, 0, have) ?: break
            val id = h[0]
            val hdrLen = h[1].toInt()
            val sz = h[2]
            val dataPos = pos + hdrLen
            when (id) {
                ID_SEGMENT -> { pos = dataPos; continue }               // walk into the segment
                ID_CLUSTER -> {
                    if (sz < 0) break                                   // unknown size: cannot hop
                    // the cluster's own start time is one of its first children
                    val ch2 = readAt(ch, dataPos, buf, 24)
                    var o = 0
                    var t = -1L
                    var k = 0
                    while (k++ < 4 && o < ch2) {
                        val c = header(buf, o, ch2) ?: break
                        val dl = c[2].toInt()
                        if (c[0] == ID_TIMECODE) {
                            if (dl in 1..8 && o + c[1].toInt() + dl <= ch2) t = uint(buf, o + c[1].toInt(), dl)
                            break
                        }
                        if (dl < 0) break
                        o += c[1].toInt() + dl
                    }
                    if (t >= 0) {
                        val us = t * scaleNs / 1000L
                        if (us >= lastTime) {
                            if (count == times.size) { times = times.copyOf(count * 2); poss = poss.copyOf(count * 2) }
                            times[count] = us
                            poss[count] = pos
                            count++
                            lastTime = us
                        }
                    }
                }
                ID_INFO -> {
                    if (sz in 1..4096) {
                        val info = ByteArray(sz.toInt())
                        val got = readAt(ch, dataPos, info, info.size)
                        var o = 0
                        while (o < got) {
                            val c = header(info, o, got) ?: break
                            val dl = c[2].toInt()
                            if (dl < 0) break
                            if (c[0] == ID_TIMECODE_SCALE && dl in 1..8 && o + c[1].toInt() + dl <= got) {
                                val v = uint(info, o + c[1].toInt(), dl)
                                if (v > 0) scaleNs = v
                            }
                            o += c[1].toInt() + dl
                        }
                    }
                }
                else -> { /* EBML header, SeekHead, Tracks, Cues, Tags, ... : skipped */ }
            }
            if (sz < 0) break
            val next = dataPos + sz
            if (next <= pos) break
            pos = next
        }
        if (count < 2) return null
        return Index(times.copyOf(count), poss.copyOf(count), times[count - 1])
    }
}
