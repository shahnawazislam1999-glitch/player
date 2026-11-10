package com.raj.shadowplayer

import java.util.Locale

/**
 * Voice alignment for captions that were written by a person.
 *
 * A human caption has the best text but loose timing: it appears a little early, lingers, or is cut by eye.
 * YouTube's automatic caption of the same language has poor text but the exact second of every word.
 * This takes the best of both: every human cue is matched to the words the speech recognizer heard (a local sequence
 * alignment, tolerant to wrong or missing words) and its start / end are moved to the first / last matched word.
 *
 * Safety rules, so a bad match can never make things worse:
 *  - a cue is only moved when at least half of its words are found, and by at most [MAX_SHIFT] seconds;
 *  - cues are matched in order, so one wrong match cannot jump backwards;
 *  - a cue that does not match keeps its own timing;
 *  - the caller drops the whole result when too few cues matched (see [MIN_RATIO]).
 *
 * Pure Kotlin (no Android classes).
 */
object CaptionAligner {
    class Result(val cues: List<Srt.Cue>, val matched: Int, val total: Int) {
        val ratio: Double get() = if (total == 0) 0.0 else matched.toDouble() / total
    }

    /** Below this share of matched cues the automatic track is not the same speech (or the wrong language): keep the original timing. */
    const val MIN_RATIO = 0.55

    private const val WINDOW = 2.5          // seconds around a cue where its words are searched
    private const val MAX_SHIFT = 3.0       // never move a start or end further than this
    private const val WORD_SEC = 0.30       // average length of an unmatched word at the edge of a cue
    private const val MAX_TOKENS = 45
    private const val MAX_WINDOW_WORDS = 90

    private const val S_EXACT = 4
    private const val S_CLOSE = 2
    private const val S_MISS = -3
    private const val S_GAP = -2

    private fun norm(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase(Locale.ROOT)) if (Character.isLetterOrDigit(ch)) sb.append(ch)
        return sb.toString()
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val la = a.length
        val lb = b.length
        if (la < 4 || lb < 4 || Math.abs(la - lb) > 2) return 0.0
        var prev = IntArray(lb + 1) { it }
        var cur = IntArray(lb + 1)
        for (i in 1..la) {
            cur[0] = i
            for (j in 1..lb) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + c)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return 1.0 - prev[lb].toDouble() / maxOf(la, lb).toDouble()
    }

    private fun score(a: String, b: String): Int = when {
        a == b -> S_EXACT
        similarity(a, b) >= 0.72 -> S_CLOSE
        else -> S_MISS
    }

    /** Index of the first word that starts at or after [t]. */
    private fun lowerBound(ws: List<YtCaptions.Word>, t: Double): Int {
        var lo = 0
        var hi = ws.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ws[mid].start < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun align(manual: List<Srt.Cue>, words: List<YtCaptions.Word>): Result {
        val aw = words.filter { norm(it.text).isNotEmpty() }
        val an = aw.map { norm(it.text) }
        val segs = ArrayList<YtCaptions.Seg>()
        val moved = ArrayList<Boolean>()
        var prevLast = -1
        var matchedCues = 0

        for (c in manual) {
            val original = YtCaptions.Seg(c.start, c.end, c.start, c.end, c.text)
            segs.add(original)
            moved.add(false)
            val toks = c.text.split(' ').map { norm(it) }.filter { it.isNotEmpty() }
            val n = toks.size
            if (n == 0 || n > MAX_TOKENS || aw.isEmpty()) continue

            val lo = maxOf(lowerBound(aw, c.start - WINDOW), prevLast + 1)
            var hi = lowerBound(aw, c.end + WINDOW)
            if (hi - lo > MAX_WINDOW_WORDS) hi = lo + MAX_WINDOW_WORDS
            val k = hi - lo
            if (k < 1) continue

            // local alignment (Smith-Waterman) of the cue's words against the recognized words around it
            val h = Array(n + 1) { IntArray(k + 1) }
            val t = Array(n + 1) { ByteArray(k + 1) }
            var best = 0
            var bi = 0
            var bj = 0
            for (i in 1..n) {
                for (j in 1..k) {
                    val sc = score(toks[i - 1], an[lo + j - 1])
                    var v = 0
                    var dir: Byte = 0
                    val d = h[i - 1][j - 1] + sc
                    if (d > v) { v = d; dir = 1 }
                    val u = h[i - 1][j] + S_GAP
                    if (u > v) { v = u; dir = 2 }
                    val l = h[i][j - 1] + S_GAP
                    if (l > v) { v = l; dir = 3 }
                    h[i][j] = v
                    t[i][j] = dir
                    if (v > best) { best = v; bi = i; bj = j }
                }
            }
            if (best <= 0) continue

            var i = bi
            var j = bj
            var matched = 0
            var firstTok = -1
            var firstWord = -1
            var lastTok = -1
            var lastWord = -1
            while (i > 0 && j > 0 && h[i][j] > 0) {
                val dir = t[i][j].toInt()
                if (dir == 1) {
                    if (score(toks[i - 1], an[lo + j - 1]) > 0) {
                        matched++
                        if (lastWord == -1) { lastTok = i - 1; lastWord = lo + j - 1 }
                        firstTok = i - 1
                        firstWord = lo + j - 1
                    }
                    i--
                    j--
                } else if (dir == 2) {
                    i--
                } else if (dir == 3) {
                    j--
                } else {
                    break
                }
            }
            if (matched == 0 || firstWord < 0 || lastWord < 0) continue

            // enough of the cue must be found
            val need = if (n <= 2) n else maxOf(2, (n + 1) / 2)
            if (matched < need) continue
            // a lone short cue is too easy to match with the wrong "yes": it must be an exact, nearby match
            if (n <= 2 && Math.abs(aw[firstWord].start - c.start) > 1.5) continue
            val headMissing = firstTok
            val tailMissing = n - 1 - lastTok
            if (headMissing > n * 0.4 || tailMissing > n * 0.4) continue

            val aStart = aw[firstWord].start - minOf(headMissing, 3) * WORD_SEC
            val aEnd = aw[lastWord].end + minOf(tailMissing, 3) * WORD_SEC
            if (aEnd <= aStart) continue
            if (Math.abs(aStart - c.start) > MAX_SHIFT || Math.abs(aEnd - c.end) > MAX_SHIFT) continue
            if ((aEnd - aStart) > (c.end - c.start) + 4.0) continue

            segs[segs.size - 1] = YtCaptions.Seg(
                maxOf(0.0, aStart - YtCaptions.LEAD), aEnd + YtCaptions.TAIL, aStart, aEnd, c.text
            )
            moved[moved.size - 1] = true
            prevLast = lastWord
            matchedCues++
        }

        YtCaptions.resolveOverlaps(segs)

        // last safety net: a moved cue that came out empty or backwards goes back to its own timing
        val out = ArrayList<Srt.Cue>()
        var lastStart = -1.0
        for ((idx, s) in segs.withIndex()) {
            var st = maxOf(0.0, s.start)
            var en = s.end
            if (moved[idx] && (en - st < 0.25 || st < lastStart)) {
                st = manual[idx].start
                en = manual[idx].end
                matchedCues--
            }
            lastStart = st
            out.add(Srt.Cue(st, en, s.text))
        }
        return Result(out, matchedCues, manual.size)
    }
}
