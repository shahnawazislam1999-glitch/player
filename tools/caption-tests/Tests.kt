import com.raj.shadowplayer.*
import java.util.Random

var fails = 0
fun check(name: String, ok: Boolean, extra: String = "") {
    println((if (ok) "PASS " else "FAIL ") + name + (if (extra.isNotEmpty()) "  [$extra]" else ""))
    if (!ok) fails++
}
fun judgeAll(cues: List<Srt.Cue>, truth: List<Pair<Double, Double>>): Pair<Double, Double> {
    var clip = 0; var ex = 0.0
    for ((i, t) in truth.withIndex()) {
        val c = cues[i]
        if (c.start > t.first + 0.03 || c.end < t.second - 0.03) clip++
        ex += maxOf(0.0, t.first - c.start) + maxOf(0.0, c.end - t.second)
    }
    return Pair(clip.toDouble() / truth.size, ex / truth.size)
}
fun near(a: Double, b: Double, tol: Double) = Math.abs(a - b) <= tol
fun f(x: Double) = String.format("%.2f", x)

val ROLLING = """WEBVTT
Kind: captions
Language: en

00:00:00.160 --> 00:00:02.230 align:start position:0%
 
hello<00:00:00.640><c> everyone</c><00:00:01.199><c> welcome</c><00:00:01.560><c> back</c>

00:00:02.230 --> 00:00:02.240 align:start position:0%
hello everyone welcome back
 

00:00:02.240 --> 00:00:05.000 align:start position:0%
hello everyone welcome back
to<00:00:02.480><c> the</c><00:00:02.639><c> channel</c><00:00:03.100><c> today</c><00:00:03.500><c> i</c><00:00:03.600><c> am</c><00:00:03.800><c> going</c>

00:00:05.000 --> 00:00:05.010 align:start position:0%
to the channel today i am going
 

00:00:05.010 --> 00:00:08.000 align:start position:0%
to the channel today i am going
to<00:00:05.200><c> show</c><00:00:05.500><c> you</c><00:00:05.800><c> [Music]</c><00:00:06.100><c> no</c><00:00:06.500><c> no</c><00:00:06.900><c> no</c>

00:00:08.000 --> 00:00:08.010 align:start position:0%
to show you no no no
 

00:00:09.500 --> 00:00:11.000 align:start position:0%
to show you no no no
thanks<00:00:09.800><c> for</c><00:00:10.000><c> watching</c>
"""

fun main() {
    // ---------- 1. rolling auto captions -> word timeline -> sentences
    val p = YtCaptions.parseFull(ROLLING)
    println("words=" + p.words.joinToString(" ") { it.text + "@" + f(it.start) })
    p.cues.forEachIndexed { i, c -> println("  cue ${i+1}: ${f(c.start)} -> ${f(c.end)}  '${c.text}'") }
    check("words rebuilt without rolling repeats", p.words.size == 20, "n=" + p.words.size)
    check("'no no no' spoken three times is kept", p.words.count { it.text == "no" } == 3)
    check("[Music] noise removed", p.words.none { it.text.contains("Music") })
    check("pause of 1.5s splits sentences", p.cues.size >= 2 && p.cues.last().text.startsWith("Thanks"))
    check("first cue starts at first word minus lead", near(p.cues[0].start, 0.64 - 0.0 - 0.0 - 0.48 + 0.0 + 0.0, 1.0) && p.cues[0].start < 0.16 + 0.001)
    check("sentence capitalised", p.cues[0].text.startsWith("Hello"))
    check("lonely i fixed", p.cues.any { it.text.contains(" I am") })
    check("no overlaps", (0 until p.cues.size - 1).all { p.cues[it].end <= p.cues[it + 1].start + 1e-9 })
    check("each cue within limits", p.cues.all { it.end - it.start in 0.4..8.5 })

    // ---------- 2. old behaviour reference: human VTT unchanged in substance
    val human = """WEBVTT

1
00:00:01.000 --> 00:00:03.000
Hello &amp; welcome

2
00:00:03.500 --> 00:00:05.000
- It&amp;#39;s great
- [laughs] Yes

3
00:00:05.000 --> 00:00:05.100
[Music]

4
00:00:06.000 --> 00:00:07.000
Same line

5
00:00:06.000 --> 00:00:07.000
Same line
"""
    val h = YtCaptions.parse(human)
    h.forEachIndexed { i, c -> println("  human ${i+1}: ${f(c.start)} -> ${f(c.end)}  '${c.text}'") }
    check("entity decoded", h[0].text == "Hello & welcome")
    check("double-encoded apostrophe decoded", h[1].text.startsWith("It's great"), h[1].text)
    check("speaker dashes and [laughs] removed", h[1].text == "It's great Yes", h[1].text)
    check("[Music] cue dropped", h.none { it.text.contains("Music") })
    check("duplicate cue merged", h.count { it.text == "Same line" } == 1)
    check("human timing untouched", near(h[0].start, 1.0, 1e-6) && near(h[0].end, 3.0, 1e-6))

    // ---------- 3. TTML + old XML still parse
    val ttml = """<?xml version="1.0"?><tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="00:00:01.000" end="00:00:02.500">Hi<br/>there &amp;amp; you</p></div></body></tt>"""
    val tc = YtCaptions.parse(ttml)
    check("ttml parsed", tc.size == 1 && tc[0].text == "Hi there & you", tc.firstOrNull()?.text ?: "none")
    val xml = """<transcript><text start="1.5" dur="2">It&amp;#39;s fine</text></transcript>"""
    val xc = YtCaptions.parse(xml)
    check("old xml parsed", xc.size == 1 && xc[0].text == "It's fine" && near(xc[0].end, 3.5, 1e-6))
    check("empty input", YtCaptions.parse("").isEmpty() && YtCaptions.parse("garbage").isEmpty())

    // ---------- 4. simulation: truth vs loose human timing vs auto words
    val rnd = Random(7)
    val vocab = "the of and to in is that it was for on are with as his they at be this from have or by one had not but what all were when we there can an your which their said if do will each about how up out them then she many some so these would other into has more her two like him see time could no make than first been its who now people my made over did down only way find use may water long little very after words called just where most know get through back much before go good new write our me man too any day same right look think also around another came come work three word must because does part even place well such here take why help put different away again off went old number great tell men say small every found still between name should home big give air line set own under read last never us left end along while might next sound below saw something thought both few those always looked show large often together asked house don't world going want school important until form food keep children feet land side without boy once animal life enough took sometimes four head above kind began almost live page got earth need far hand high year mother light country father let night picture being study second soon story since white ever paper hard near sentence better best across during today however sure knew try told young sun thing whole hear example heard several change answer room sea against top turned learn point city play toward five using himself usually money seen didn't car morning added".split(" ")
    // ground truth: 60 sentences, 4-12 words, speech 0.30s/word + small noise, pauses 0.7-1.6s between sentences
    class Sent(val words: List<String>, val ws: List<Double>, val we: List<Double>)
    val sents = ArrayList<Sent>()
    var t = 2.0
    for (s in 0 until 60) {
        val n = 4 + rnd.nextInt(9)
        val ws = ArrayList<Double>(); val we = ArrayList<Double>(); val w = ArrayList<String>()
        for (i in 0 until n) {
            val word = vocab[rnd.nextInt(vocab.size)]
            val d = 0.08 + word.length * 0.045 + rnd.nextDouble() * 0.05
            w.add(word); ws.add(t); we.add(t + d)
            t += d + (if (rnd.nextDouble() < 0.1) 0.15 else 0.02)       // tiny gaps inside a sentence
        }
        sents.add(Sent(w, ws, we))
        t += 0.7 + rnd.nextDouble() * 0.9
    }
    // build YouTube-style rolling VTT from ASR (8% wrong words, 4% dropped), line = up to 7 words
    val asrWords = ArrayList<Pair<String, Double>>()
    for (s in sents) for (i in s.words.indices) {
        val r = rnd.nextDouble()
        if (r < 0.04) continue
        val txt = if (r < 0.12) vocab[rnd.nextInt(vocab.size)] else s.words[i]
        asrWords.add(Pair(txt, s.ws[i] + (rnd.nextDouble() - 0.5) * 0.06))
    }
    fun ts(x: Double): String { val ms = Math.round(x * 1000); return String.format("%02d:%02d:%02d.%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000) }
    val sb = StringBuilder("WEBVTT\nKind: captions\nLanguage: en\n\n")
    var prevLine = ""
    var idx = 0
    while (idx < asrWords.size) {
        val chunk = asrWords.subList(idx, minOf(asrWords.size, idx + 7))
        val start = chunk[0].second
        val endT = if (idx + 7 < asrWords.size) asrWords[idx + 7].second else chunk.last().second + 0.6
        val line = StringBuilder(chunk[0].first)
        for (k in 1 until chunk.size) line.append("<").append(ts(chunk[k].second)).append("><c> ").append(chunk[k].first).append("</c>")
        sb.append("${ts(start)} --> ${ts(endT)} align:start position:0%\n${if (prevLine.isEmpty()) " " else prevLine}\n$line\n\n")
        sb.append("${ts(endT)} --> ${ts(endT + 0.01)} align:start position:0%\n${chunk.joinToString(" ") { it.first }}\n \n\n")
        prevLine = chunk.joinToString(" ") { it.first }
        idx += 7
    }
    val auto = YtCaptions.parseFull(sb.toString())
    println("sim: truth sentences=${sents.size}  auto cues=${auto.cues.size}  words=${auto.words.size}/${asrWords.size}")
    check("all ASR words recovered", auto.words.size == asrWords.size, "${auto.words.size} vs ${asrWords.size}")

    // judge against the truth: a cue "clips" when it starts after the first word or ends before the last one;
    // "excess" is the extra picture (silence / neighbouring speech) it includes. Only cues that cover exactly one sentence count.
    fun judge(cues: List<Srt.Cue>): Triple<Double, Double, Int> {
        var clip = 0; var ex = 0.0; var cnt = 0
        for (c in cues) {
            val ov = sents.filter { s -> minOf(c.end, s.we.last()) - maxOf(c.start, s.ws.first()) > 0.05 }
            if (ov.size != 1) continue
            val s = ov[0]
            cnt++
            if (c.start > s.ws.first() + 0.03 || c.end < s.we.last() - 0.03) clip++
            ex += maxOf(0.0, s.ws.first() - c.start) + maxOf(0.0, c.end - s.we.last())
        }
        return Triple(clip.toDouble() / cnt, ex / cnt, cnt)
    }
    val ja = judge(auto.cues)
    println("sim: auto cues (single-sentence ones: ${ja.third}) clipped=${f(ja.first * 100)}%  mean extra picture=${f(ja.second)}s")
    check("auto cues rarely clip speech", ja.first < 0.06)
    check("auto cues carry little extra picture", ja.second < 0.55)

    // ---------- 5. human captions with loose timing, aligned to auto words
    val loose = ArrayList<Srt.Cue>()
    for (s in sents) {
        val ds = -0.9 + rnd.nextDouble() * 1.4          // appears early, or a bit late
        val de = -0.3 + rnd.nextDouble() * 1.6          // lingers
        loose.add(Srt.Cue(s.ws.first() + ds, s.we.last() + de, s.words.joinToString(" ").replaceFirstChar { it.uppercase() } + "."))
    }
    // human cue edges must not overlap for a fair test
    for (i in 0 until loose.size - 1) if (loose[i].end > loose[i + 1].start) loose[i].end = loose[i + 1].start - 0.01
    val res = CaptionAligner.align(loose, auto.words)
    val jb = judgeAll(loose, sents.map { Pair(it.ws.first(), it.we.last()) })
    val jr = judgeAll(res.cues, sents.map { Pair(it.ws.first(), it.we.last()) })
    println("align: matched ${res.matched}/${res.total}  clipped ${f(jb.first * 100)}% -> ${f(jr.first * 100)}%   extra picture ${f(jb.second)}s -> ${f(jr.second)}s")
    check("most cues matched", res.ratio > 0.85, f(res.ratio))
    check("clipping nearly gone", jr.first < 0.06 && jr.first < jb.first)
    check("extra picture smaller and bounded", jr.second < jb.second && jr.second < 0.6)
    check("same count and text", res.cues.size == loose.size && res.cues.indices.all { res.cues[it].text == loose[it].text })
    check("no overlaps after align", (0 until res.cues.size - 1).all { res.cues[it].end <= res.cues[it + 1].start + 1e-9 })
    check("every cue has positive length", res.cues.all { it.end - it.start >= 0.25 })

    // ---------- 6. do-no-harm: unrelated speech must not move anything
    val other = ArrayList<Srt.Cue>()
    for (i in 0 until 40) other.add(Srt.Cue(2.0 + i * 4.0, 4.5 + i * 4.0, "completely different sentence number $i about nothing"))
    val bad = CaptionAligner.align(other, auto.words)
    check("unrelated text: nothing moved", bad.matched == 0 && bad.cues.indices.all { near(bad.cues[it].start, other[it].start, 1e-6) && near(bad.cues[it].end, other[it].end, 1e-6) }, "matched=${bad.matched}")
    check("unrelated text: ratio below gate", bad.ratio < CaptionAligner.MIN_RATIO)
    check("empty inputs safe", CaptionAligner.align(emptyList(), auto.words).cues.isEmpty() && CaptionAligner.align(other, emptyList()).matched == 0)

    // ---------- 7. weird input must not crash
    for (junk in listOf("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<00:00:01.100><00:00:01.200><00:00:01.300>", "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\na<00:00:01.100><c> b</c><00:00:01.200><c> c</c><00:00:01.300><c> d</c>", "<tt></tt>", "00:00:01.000 --> 00:00:00.500\nx")) {
        try { YtCaptions.parseFull(junk); check("no crash on junk", true) } catch (e: Exception) { check("no crash on junk", false, e.toString()) }
    }
    println(if (fails == 0) "\nALL PASSED" else "\n$fails FAILED")
    if (fails > 0) System.exit(1)
}
