package com.raj.shadowplayer

import java.util.Locale

/**
 * Reads the caption files YouTube hands out (WebVTT, TTML, old / new timed-text XML) and turns them into sentence cues
 * for the sentence player.
 *
 * Voice accuracy comes from three things:
 *  1. Word timing. YouTube's automatic captions carry the exact second of every word (`word<00:00:01.200><c> next</c>`).
 *     The old reader threw that away and only used the cue times, which are "when the text appears", not "when the voice speaks".
 *     Here the words are rebuilt first (rolling repeats removed by their timestamps, not by comparing text), then cut into
 *     sentences at real pauses, so a repeat starts with the first word and stops right after the last one.
 *  2. Padding that never collides. Every cue gets a short lead-in and tail so no consonant is clipped; where two cues are close
 *     the gap between the voices is shared instead of overlapping.
 *  3. Clean text. Sound tags, speaker dashes, double encoded entities and duplicate cues are removed.
 *
 * Pure Kotlin (no Android classes) so it can be tested on a normal JVM.
 */
object YtCaptions {
    /** One spoken word. [start] is real (from the file), [end] is estimated from the speaking rate. */
    class Word(val start: Double, var end: Double, val text: String)

    /** The cues, plus the word timeline when the file carried word timing (automatic captions). */
    class Parsed(val cues: List<Srt.Cue>, val words: List<Word>)

    /**
     * A sentence on its way to becoming a cue. [aStart]..[aEnd] is when the voice is really heard;
     * [start]..[end] is the padded cue that the player uses.
     */
    class Seg(var start: Double, var end: Double, val aStart: Double, val aEnd: Double, var text: String)

    /** Seconds of picture kept before the first word / after the last word. */
    const val LEAD = 0.12
    const val TAIL = 0.22

    private const val HARD_GAP = 0.60      // a pause this long always ends a sentence
    private const val MERGE_GAP = 1.00     // tiny pieces are glued to a neighbour that is at most this close
    private const val MAX_WORDS = 16
    private const val MAX_DUR = 7.0
    private const val MAX_CHARS = 100
    private const val MIN_CUE = 0.40

    private val VTT_TIME = Regex("""((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    private val WORD_TS = Regex("""<((?:\d{1,2}:)?\d{1,2}:\d{2}\.\d{3})>""")
    private val TAGS = Regex("<[^>]*>")
    private val SPACES = Regex("\\s+")
    private val P_TAG = Regex("""<p\b([^>]*)>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val ANY_TEXT_TAG = Regex("""<(?:text|p)\b([^>]*)>(.*?)</(?:text|p)>""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")
    private val NOISE = Regex("""\[[^\]]*\]|[♪♫♬]+""")        // [Music]  [Applause]  music notes
    private val SPEAKER = Regex("""^\s*(?:>>+|>|--?|–|—)\s*""")     // ">> " or "- " at the start of a line
    private val ONLY_PAREN = Regex("""^\(.*\)$""")                            // (laughing)
    private val TERMINAL = Regex("""[.?!…]["'”’)\]]*$""")
    private val I_WORD = Regex("""\bi\b""")
    private val ABBREV = setOf("mr.", "mrs.", "ms.", "dr.", "st.", "vs.", "etc.", "e.g.", "i.e.", "no.", "jr.", "sr.", "prof.")
    private val BREAK_BEFORE = setOf("and", "but", "so", "because", "which", "when", "while", "if", "then", "or", "that", "although", "since", "until")

    // ------------------------------------------------------------------ entry points

    fun parse(body: String): List<Srt.Cue> = parseFull(body).cues

    fun parseFull(body: String): Parsed {
        val t = body.removePrefix("﻿").trim()
        if (t.isEmpty()) return Parsed(emptyList(), emptyList())
        return when {
            t.startsWith("WEBVTT") || (VTT_TIME.containsMatchIn(t) && !t.startsWith("<")) -> parseVtt(t)
            t.contains("<tt") -> Parsed(polish(parseTimedXml(t, ttml = true)), emptyList())
            else -> Parsed(polish(parseTimedXml(t, ttml = false)), emptyList())
        }
    }

    // ------------------------------------------------------------------ text helpers

    private fun clock(s: String): Double {
        val v = s.trim()
        if (v.endsWith("ms")) return (v.removeSuffix("ms").toDoubleOrNull() ?: Double.NaN) / 1000.0
        if (v.endsWith("s")) return v.removeSuffix("s").toDoubleOrNull() ?: Double.NaN
        val parts = v.replace(',', '.').split(':')
        return try {
            when (parts.size) {
                3 -> parts[0].toDouble() * 3600.0 + parts[1].toDouble() * 60.0 + parts[2].toDouble()
                2 -> parts[0].toDouble() * 60.0 + parts[1].toDouble()
                1 -> parts[0].toDouble()
                else -> Double.NaN
            }
        } catch (e: NumberFormatException) {
            Double.NaN
        }
    }

    private fun codePoint(i: Int): String? =
        if (i in 0..0x10FFFF && i !in 0xD800..0xDFFF) String(Character.toChars(i)) else null

    private fun decodeOnce(s: String): String {
        if (s.indexOf('&') < 0) return s
        return ENTITY.replace(s) { m ->
            val g = m.groupValues[1]
            val r: String? = when {
                g.startsWith("#x") || g.startsWith("#X") -> g.substring(2).toIntOrNull(16)?.let { codePoint(it) }
                g.startsWith("#") -> g.substring(1).toIntOrNull()?.let { codePoint(it) }
                else -> when (g) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "nbsp" -> " "
                    "lrm", "rlm" -> ""
                    else -> null
                }
            }
            r ?: m.value
        }
    }

    /** One pass reads `&amp;`, a second reads the `&amp;#39;` that older timed-text files double-encode. */
    private fun decode(s: String): String = decodeOnce(decodeOnce(s))

    /** Removes tags and entities and squeezes the spaces. */
    private fun clean(s: String): String = decode(TAGS.replace(s, "")).replace(SPACES, " ").trim()

    /** One caption line: sound tags and speaker dashes out. Empty when nothing spoken is left. */
    private fun tidyLine(s: String): String {
        var r = NOISE.replace(s, " ").replace(SPACES, " ").trim()
        r = SPEAKER.replace(r, "").trim()
        if (ONLY_PAREN.matches(r)) return ""
        return r
    }

    private fun capitalizeFirst(s: String): String {
        for (i in s.indices) {
            val c = s[i]
            if (Character.isLetter(c)) {
                return if (Character.isLowerCase(c)) s.substring(0, i) + c.titlecaseChar() + s.substring(i + 1) else s
            }
            if (Character.isDigit(c)) return s
        }
        return s
    }

    /** Automatic captions are all lower case: give the sentence a capital and fix the lonely "i". */
    private fun sentenceCase(s: String): String = capitalizeFirst(I_WORD.replace(s, "I"))

    // ------------------------------------------------------------------ WebVTT

    private class Block(val start: Double, val end: Double, val lines: List<String>)
    private class Raw(val start: Double, val end: Double, val lines: List<String>)

    private fun parseVtt(text: String): Parsed {
        val norm = text.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = ArrayList<Block>()
        for (block in norm.split(Regex("\n{2,}"))) {
            val lines = block.split('\n')
            val ti = lines.indexOfFirst { VTT_TIME.containsMatchIn(it) }
            if (ti < 0) continue
            val m = VTT_TIME.find(lines[ti]) ?: continue
            val s = clock(m.groupValues[1])
            val e = clock(m.groupValues[2])
            if (s.isNaN() || e.isNaN() || e <= s) continue
            blocks.add(Block(s, e, lines.drop(ti + 1)))
        }

        // automatic captions: every word has its own timestamp
        if (WORD_TS.findAll(norm).take(3).count() >= 3) {
            val words = extractWords(blocks)
            if (words.size >= 3) {
                val segs = segment(words)
                if (segs.isNotEmpty()) return Parsed(toCues(segs), words)
            }
        }

        // human captions (or automatic ones without word timing)
        val rolling = norm.contains("<c>") || norm.contains("align:start position:0%")
        val raws = ArrayList<Raw>()
        for (b in blocks) {
            val lines = b.lines.map { tidyLine(clean(it)) }.filter { it.isNotEmpty() }
            if (lines.isNotEmpty()) raws.add(Raw(b.start, b.end, lines))
        }
        val cues = if (rolling) fromRolling(raws) else raws.map { Srt.Cue(it.start, it.end, it.lines.joinToString(" ")) }
        return Parsed(polish(cues), emptyList())
    }

    // ------------------------------------------------------------------ word timeline (automatic captions)

    /**
     * Rolling captions show two lines: the old one without timestamps and the new one with them. Only lines with timestamps
     * are read, so a sentence that is really spoken twice ("no no no") is never mistaken for a repeat of the display.
     */
    private fun extractWords(blocks: List<Block>): List<Word> {
        val raw = ArrayList<Word>()
        for (b in blocks) {
            for (line0 in b.lines) {
                if (!WORD_TS.containsMatchIn(line0)) continue
                addLineWords(NOISE.replace(line0, " "), b.start, raw)
            }
        }
        raw.sortBy { it.start }
        val ws = ArrayList<Word>()
        for (w in raw) {
            val p = ws.lastOrNull()
            if (p != null && w.text == p.text && Math.abs(w.start - p.start) < 0.02) continue
            ws.add(w)
        }
        fixEnds(ws)
        return ws
    }

    private fun addLineWords(line: String, blockStart: Double, out: MutableList<Word>) {
        val stamps = ArrayList<Double>()
        val pieces = ArrayList<String>()
        stamps.add(blockStart)
        var pos = 0
        for (m in WORD_TS.findAll(line)) {
            pieces.add(line.substring(pos, m.range.first))
            stamps.add(clock(m.groupValues[1]))
            pos = m.range.last + 1
        }
        pieces.add(line.substring(pos))
        for (i in pieces.indices) {
            val toks = clean(pieces[i]).split(' ').filter { it.isNotEmpty() }
            if (toks.isEmpty()) continue
            val t0 = stamps[i]
            if (t0.isNaN()) continue
            if (toks.size == 1) {
                out.add(Word(t0, t0, toks[0]))
                continue
            }
            // several words share one timestamp: spread them by their length
            val t1 = if (i + 1 < stamps.size) stamps[i + 1] else Double.NaN
            val span = if (!t1.isNaN() && t1 > t0) minOf(t1 - t0, 0.45 * toks.size) else 0.30 * toks.size
            val total = toks.sumOf { it.length + 1 }.toDouble()
            var cum = 0.0
            for (tok in toks) {
                out.add(Word(t0 + span * cum / total, t0, tok))
                cum += (tok.length + 1).toDouble()
            }
        }
    }

    /**
     * The file only says when a word starts. The end of a word is the start of the next one, unless a pause follows:
     * then it is estimated from the speaker's own pace (median seconds per letter of this video).
     */
    private fun fixEnds(ws: List<Word>) {
        val samples = ArrayList<Double>()
        for (i in 0 until ws.size - 1) {
            val d = ws[i + 1].start - ws[i].start
            val n = ws[i].text.length
            if (d > 0.03 && d < 1.0 && n > 0) samples.add(d / n)
        }
        val perChar = if (samples.size >= 20) samples.sorted()[samples.size / 2].coerceIn(0.045, 0.11) else 0.07
        for (i in ws.indices) {
            val est = (ws[i].text.length * perChar + 0.12).coerceIn(0.18, 1.1)
            val next = if (i + 1 < ws.size) ws[i + 1].start else Double.MAX_VALUE
            ws[i].end = minOf(next, ws[i].start + est)
        }
    }

    // ------------------------------------------------------------------ words -> sentences

    private fun endsSentence(w: String): Boolean = TERMINAL.containsMatchIn(w) && w.lowercase(Locale.ROOT) !in ABBREV

    private fun chars(run: List<Word>): Int = run.sumOf { it.text.length + 1 }

    private fun fits(run: List<Word>): Boolean =
        run.size <= MAX_WORDS && (run.last().end - run.first().start) <= MAX_DUR && chars(run) <= MAX_CHARS

    private fun bare(w: String): String = w.lowercase(Locale.ROOT).trim { !it.isLetterOrDigit() }

    /** Cuts a run that is too long at its best natural break: a pause, a comma, a joining word; kept near the middle. */
    private fun splitRun(run: List<Word>, out: MutableList<List<Word>>) {
        if (fits(run) || run.size < 4) {
            out.add(run)
            return
        }
        var bestK = -1
        var bestScore = -1e9
        for (k in 2..run.size - 2) {
            val gap = run[k].start - run[k - 1].end
            var sc = gap * 3.0
            val prev = run[k - 1].text
            if (prev.endsWith(",") || prev.endsWith(";") || prev.endsWith(":")) sc += 0.5
            if (bare(run[k].text) in BREAK_BEFORE) sc += 0.25
            sc -= Math.abs(k.toDouble() / run.size - 0.5) * 0.8
            if (sc > bestScore) {
                bestScore = sc
                bestK = k
            }
        }
        splitRun(run.subList(0, bestK), out)
        splitRun(run.subList(bestK, run.size), out)
    }

    private fun isTiny(p: List<Word>): Boolean {
        val dur = p.last().end - p.first().start
        return p.size < 2 || dur < 0.8 || (p.size < 3 && !endsSentence(p.last().text))
    }

    private fun segment(ws: List<Word>): List<Seg> {
        // 1. hard breaks: a real pause, or the end of a sentence when the captions have punctuation
        val runs = ArrayList<List<Word>>()
        var cur = ArrayList<Word>()
        for (i in ws.indices) {
            cur.add(ws[i])
            val last = i == ws.size - 1
            val gap = if (last) 0.0 else ws[i + 1].start - ws[i].end
            if (last || gap >= HARD_GAP || endsSentence(ws[i].text)) {
                runs.add(cur)
                cur = ArrayList()
            }
        }
        // 2. too long: split at the best natural break
        val pieces = ArrayList<List<Word>>()
        for (r in runs) splitRun(r, pieces)
        // 3. too short: glue to the closest neighbour when the result still fits
        var changed = true
        var guard = 0
        while (changed && guard++ < 50) {
            changed = false
            var i = 0
            while (i < pieces.size) {
                val p = pieces[i]
                if (pieces.size > 1 && isTiny(p)) {
                    val gp = if (i > 0) p.first().start - pieces[i - 1].last().end else Double.MAX_VALUE
                    val gn = if (i + 1 < pieces.size) pieces[i + 1].first().start - p.last().end else Double.MAX_VALUE
                    val usePrev = gp <= gn
                    val gap = if (usePrev) gp else gn
                    if (gap <= MERGE_GAP) {
                        val merged = if (usePrev) pieces[i - 1] + p else p + pieces[i + 1]
                        if (fits(merged)) {
                            if (usePrev) {
                                pieces[i - 1] = merged
                                pieces.removeAt(i)
                            } else {
                                pieces[i] = merged
                                pieces.removeAt(i + 1)
                            }
                            changed = true
                            continue
                        }
                    }
                }
                i++
            }
        }
        val segs = ArrayList<Seg>()
        for ((pi, p) in pieces.withIndex()) {
            val a = p.first().start
            val b = p.last().end
            val joined = p.joinToString(" ") { it.text }
            // a capital only where a sentence really begins (after a pause or a full stop), not where a long sentence was cut
            val prev = if (pi > 0) pieces[pi - 1] else null
            val begins = prev == null || a - prev.last().end >= HARD_GAP || endsSentence(prev.last().text)
            val text = if (begins) sentenceCase(joined) else I_WORD.replace(joined, "I")
            segs.add(Seg(maxOf(0.0, a - LEAD), b + TAIL, a, b, text))
        }
        resolveOverlaps(segs)
        return segs
    }

    /** Two neighbours that would overlap share the gap between their voices (a little more of it goes to the tail). */
    fun resolveOverlaps(segs: List<Seg>) {
        for (i in 0 until segs.size - 1) {
            val a = segs[i]
            val b = segs[i + 1]
            if (a.end <= b.start) continue
            val lo = a.aEnd
            val hi = b.aStart
            var cut = if (hi > lo) lo + (hi - lo) * TAIL / (LEAD + TAIL) else (lo + hi) / 2.0
            val minCut = a.start + 0.1
            val maxCut = b.end - 0.1
            if (minCut < maxCut) cut = cut.coerceIn(minCut, maxCut)
            a.end = cut
            b.start = cut
        }
        // a cue that became very short may grow into free space
        for (i in segs.indices) {
            val s = segs[i]
            if (s.end - s.start >= MIN_CUE) continue
            val limit = if (i + 1 < segs.size) segs[i + 1].start else Double.MAX_VALUE
            if (limit > s.end) s.end = minOf(s.start + MIN_CUE, limit)
        }
    }

    fun toCues(segs: List<Seg>): List<Srt.Cue> = segs.map { Srt.Cue(maxOf(0.0, it.start), it.end, it.text) }

    // ------------------------------------------------------------------ cue clean-up (all sources)

    /** Sound tags out, duplicates merged, overlaps trimmed, tiny cues given a little time. Never reorders speech. */
    fun polish(input: List<Srt.Cue>): List<Srt.Cue> {
        val list = ArrayList<Srt.Cue>()
        for (c in input) {
            if (c.end <= c.start || c.start.isNaN() || c.end.isNaN()) continue
            val t = tidyLine(c.text)
            if (t.isEmpty()) continue
            list.add(Srt.Cue(maxOf(0.0, c.start), c.end, t))
        }
        list.sortBy { it.start }
        val out = ArrayList<Srt.Cue>()
        for (c in list) {
            val p = out.lastOrNull()
            if (p != null && p.text == c.text && c.start < p.end) {
                if (c.end > p.end) p.end = c.end
                continue
            }
            out.add(c)
        }
        for (i in 0 until out.size - 1) {
            val a = out[i]
            val b = out[i + 1]
            if (a.end > b.start && b.start > a.start + 0.2) a.end = b.start
        }
        for (i in out.indices) {
            val c = out[i]
            if (c.end - c.start >= 0.3) continue
            val limit = if (i + 1 < out.size) out[i + 1].start else Double.MAX_VALUE
            if (limit > c.end) c.end = minOf(c.start + 0.3, limit)
        }
        return out
    }

    // ------------------------------------------------------------------ fall-backs for files without word timing

    /** Rolling captions without word timing: every cue repeats the previous line and adds one. */
    private fun fromRolling(raws: List<Raw>): List<Srt.Cue> {
        val out = ArrayList<Srt.Cue>()
        var prev: List<String> = emptyList()
        for (r in raws) {
            if (r.end - r.start < 0.12) continue      // 10 ms filler cues between the real ones
            val fresh = r.lines.filter { it !in prev }
            prev = r.lines
            if (fresh.isEmpty()) {
                if (out.isNotEmpty() && r.end > out.last().end) out.last().end = r.end
                continue
            }
            out.add(Srt.Cue(r.start, r.end, fresh.joinToString(" ")))
        }
        for (i in 0 until out.size - 1) {
            if (out[i].end > out[i + 1].start && out[i + 1].start > out[i].start) out[i].end = out[i + 1].start
        }
        val merged = ArrayList<Srt.Cue>()
        for (c in out) {
            val last = merged.lastOrNull()
            if (last != null && c.start - last.end <= 0.6 && (last.end - last.start) < 2.5 && (last.text.length + c.text.length) < 90) {
                last.end = c.end
                last.text = last.text + " " + c.text
            } else {
                merged.add(Srt.Cue(c.start, c.end, c.text))
            }
        }
        return merged
    }

    // ------------------------------------------------------------------ TTML / timed-text XML

    private fun attr(attrs: String, name: String): String? =
        Regex("""\b$name\s*=\s*"([^"]*)"""").find(attrs)?.groupValues?.get(1)

    private fun parseTimedXml(xml: String, ttml: Boolean): List<Srt.Cue> {
        val out = ArrayList<Srt.Cue>()
        val tags = if (ttml) P_TAG else ANY_TEXT_TAG
        for (m in tags.findAll(xml)) {
            val attrs = m.groupValues[1]
            var s = Double.NaN
            var e = Double.NaN
            val begin = attr(attrs, "begin")
            val end = attr(attrs, "end")
            val tMs = attr(attrs, "t")          // newer timed text: milliseconds
            val dMs = attr(attrs, "d")
            val start = attr(attrs, "start")    // older timed text: seconds
            val dur = attr(attrs, "dur")
            when {
                begin != null && end != null -> { s = clock(begin); e = clock(end) }
                tMs != null && dMs != null -> {
                    s = (tMs.toDoubleOrNull() ?: Double.NaN) / 1000.0
                    e = s + (dMs.toDoubleOrNull() ?: Double.NaN) / 1000.0
                }
                start != null && dur != null -> {
                    s = start.toDoubleOrNull() ?: Double.NaN
                    e = s + (dur.toDoubleOrNull() ?: Double.NaN)
                }
            }
            if (s.isNaN() || e.isNaN() || e <= s) continue
            val txt = clean(m.groupValues[2].replace(BR, " "))
            if (txt.isNotEmpty()) out.add(Srt.Cue(s, e, txt))
        }
        out.sortBy { it.start }
        return out
    }
}
