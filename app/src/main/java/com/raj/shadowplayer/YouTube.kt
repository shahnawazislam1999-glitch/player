package com.raj.shadowplayer

import android.content.Context
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * YouTube links for personal use: the video stream and its captions are looked up with NewPipe Extractor
 * (no Google account, no API key), the stream is then played by the normal ExoPlayer of this app.
 */
object YouTube {
    /** A problem with a message that can be shown to the user as it is. */
    class YtError(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** One caption language that exists for the video. */
    class Track(val label: String, val lang: String, val auto: Boolean, val url: String)

    const val KIND_MERGED = 1      // picture-only stream + sound-only stream played together (up to 1080p)
    const val KIND_PROGRESSIVE = 2 // one file with picture and sound
    const val KIND_HLS = 3
    const val KIND_DASH = 4

    class Plan(val kind: Int, val url: String, val audioUrl: String?, val label: String)

    class Resolved(
        val id: String,
        val title: String,
        val uploader: String,
        val durationSec: Long,
        val plan: Plan,
        val captions: List<Track>
    )

    class HistoryEntry(val id: String, val title: String, val uploader: String, val time: Long)

    private const val UA_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    private const val UA_IOS = "com.google.ios.youtube/20.03.02 (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X;)"
    private const val UA_ANDROID = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"
    private const val UA_VR = "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    private val ID_RE = Regex("""(?:youtu\.be/|youtube(?:-nocookie)?\.com/(?:watch\?(?:[^\s#]*&)?v=|shorts/|embed/|live/|v/))([A-Za-z0-9_-]{11})""")

    /** The 11 character video id inside any YouTube link (watch, youtu.be, shorts, live, embed, mobile, music) or null. */
    fun extractId(text: String): String? = ID_RE.find(text)?.groupValues?.get(1)

    fun pageUrl(id: String): String = "https://www.youtube.com/watch?v=$id"

    /** The player must identify itself like the app that the stream address was made for, otherwise YouTube answers 403. */
    fun userAgentFor(uri: Uri): String = when (uri.getQueryParameter("c")) {
        "IOS" -> UA_IOS
        "ANDROID" -> UA_ANDROID
        "ANDROID_VR" -> UA_VR
        else -> UA_WEB
    }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private class Dl : Downloader() {
        override fun execute(request: Request): Response {
            val method = request.httpMethod()
            val url = request.url()
            val data = request.dataToSend()
            val body = when {
                data != null -> data.toRequestBody(null)
                method == "POST" -> ByteArray(0).toRequestBody(null)
                else -> null
            }
            val b = okhttp3.Request.Builder().method(method, body).url(url).header("User-Agent", UA_WEB)
            for ((k, vs) in request.headers()) {
                b.removeHeader(k)
                for (v in vs) b.addHeader(k, v)
            }
            return http.newCall(b.build()).execute().use { resp ->
                if (resp.code == 429) throw ReCaptchaException("YouTube asked for a captcha (too many requests)", url)
                val text = resp.body?.string()
                Response(resp.code, resp.message, resp.headers.toMultimap(), text, resp.request.url.toString())
            }
        }
    }

    @Volatile private var ready = false

    private fun ensureInit() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            NewPipe.init(Dl(), Localization("en", "US"), ContentCountry("US"))
            ready = true
        }
    }

    // ------------------------------------------------------------------ look up a video

    /**
     * Blocking: call from a background thread. [maxHeight] is the highest picture size wanted (360 / 480 / 720 / 1080).
     * [preferHls] is the second try after a stream address stopped working: the HLS list is more tolerant.
     */
    fun resolve(id: String, maxHeight: Int, preferHls: Boolean = false): Resolved {
        ensureInit()
        val info: StreamInfo = try {
            StreamInfo.getInfo(ServiceList.YouTube, pageUrl(id))
        } catch (e: Exception) {
            throw YtError(friendly(e), e)
        }
        val captions = collectCaptions(info)
        val plan = choosePlan(info, maxHeight, preferHls)
            ?: throw YtError("YouTube gave no playable stream for this video.")
        val title = (info.name ?: "").ifBlank { "YouTube video" }
        val uploader = info.uploaderName ?: ""
        return Resolved(id, title, uploader, info.duration, plan, captions)
    }

    private fun friendly(e: Exception): String {
        val n = e.javaClass.simpleName
        return when {
            n.contains("AgeRestricted") -> "This video is age-restricted, so it cannot be played without a YouTube login."
            n.contains("Geographic") -> "This video is blocked in your country."
            n.contains("Private") -> "This video is private."
            n.contains("Paid") || n.contains("Premium") -> "This video needs a paid YouTube membership."
            n.contains("SignInConfirmNotBot") -> "YouTube wants to confirm you are not a bot. Try again later or on another network."
            n.contains("LiveNotStarted") -> "This live stream has not started yet."
            n.contains("ReCaptcha") -> "YouTube is limiting this connection. Try again in a few minutes."
            n.contains("ContentNotAvailable") || n.contains("ContentNotSupported") -> "This video is not available."
            e is IOException -> "Could not reach YouTube. Check the internet connection."
            n.contains("Parsing") || n.contains("Extraction") ->
                "YouTube changed and the extractor could not read the video. Rebuild the app with a newer NewPipe Extractor version."
            else -> "Could not open the video (" + (e.message ?: n) + ")"
        }
    }

    private fun reflectString(o: Any, getter: String): String? = try {
        o.javaClass.getMethod(getter).invoke(o)?.toString()
    } catch (e: Throwable) {
        null
    }

    private fun choosePlan(info: StreamInfo, maxHeight: Int, preferHls: Boolean): Plan? {
        val hls = info.hlsUrl ?: ""
        val dash = info.dashMpdUrl ?: ""
        if (preferHls && hls.isNotEmpty()) return Plan(KIND_HLS, hls, null, "auto")

        val muxed = (info.videoStreams ?: emptyList()).filter { it.isUrl && !it.isVideoOnly }
        val videoOnly = (info.videoOnlyStreams ?: emptyList()).filter { it.isUrl }
        val audios = (info.audioStreams ?: emptyList()).filter { it.isUrl }

        fun cap(list: List<org.schabi.newpipe.extractor.stream.VideoStream>): List<org.schabi.newpipe.extractor.stream.VideoStream> {
            val within = list.filter { it.height in 1..maxHeight }
            if (within.isNotEmpty()) return within
            // everything is bigger than wanted: take the smallest one
            val smallest = list.filter { it.height > 0 }.minOfOrNull { it.height } ?: return list
            return list.filter { it.height == smallest }
        }

        fun score(s: org.schabi.newpipe.extractor.stream.VideoStream): Int {
            val codec = (s.codec ?: "").lowercase()
            val suffix = s.format?.suffix ?: ""
            var v = s.height * 10
            if (codec.startsWith("avc")) v += 6            // H.264: every phone decodes it in hardware
            if (suffix == "mp4") v += 3
            return v
        }

        val bestMuxed = cap(muxed).maxByOrNull { score(it) }
        val bestVideo = cap(videoOnly).maxByOrNull { score(it) }

        // sound: original language first, AAC (m4a) first, then the biggest bitrate
        val bestAudio = audios.maxWithOrNull(
            compareBy<org.schabi.newpipe.extractor.stream.AudioStream>(
                { if ((reflectString(it, "getAudioTrackType") ?: "ORIGINAL").equals("ORIGINAL", true)) 1 else 0 },
                { if ((it.format?.suffix ?: "") == "m4a") 1 else 0 },
                { it.averageBitrate }
            )
        )

        // a muxed file is only 360p or more; when the wanted size is smaller (144p / 240p), the small picture-only stream + sound wins
        val muxedTooBig = bestMuxed != null && bestMuxed.height > maxHeight && bestVideo != null && bestVideo.height <= maxHeight
        if (bestVideo != null && bestAudio != null && (bestMuxed == null || bestVideo.height > bestMuxed.height || muxedTooBig)) {
            return Plan(KIND_MERGED, bestVideo.content, bestAudio.content, bestVideo.height.toString() + "p")
        }
        if (bestMuxed != null) {
            return Plan(KIND_PROGRESSIVE, bestMuxed.content, null, bestMuxed.height.toString() + "p")
        }
        if (bestAudio != null && bestVideo == null) {
            return Plan(KIND_PROGRESSIVE, bestAudio.content, null, "audio only")
        }
        if (hls.isNotEmpty()) return Plan(KIND_HLS, hls, null, "auto")
        if (dash.isNotEmpty()) return Plan(KIND_DASH, dash, null, "auto")
        return null
    }

    // ------------------------------------------------------------------ captions

    private fun formatRank(suffix: String): Int = when (suffix) {
        "vtt" -> 4
        "ttml" -> 3
        "srt" -> 2
        else -> 1
    }

    private fun collectCaptions(info: StreamInfo): List<Track> {
        val best = LinkedHashMap<String, Pair<Int, Track>>()
        for (s in (info.subtitles ?: emptyList())) {
            if (!s.isUrl) continue
            val lang = s.languageTag ?: continue
            val auto = s.isAutoGenerated
            val name = (s.displayLanguageName ?: lang).ifBlank { lang }
            val t = Track(if (auto) "$name (auto-generated)" else name, lang, auto, s.content)
            val rank = formatRank(s.format?.suffix ?: "")
            val key = lang + "|" + auto
            val old = best[key]
            if (old == null || rank > old.first) best[key] = Pair(rank, t)
        }
        // written by a person first, then automatic ones; alphabetical inside each group
        return best.values.map { it.second }.sortedWith(compareBy<Track>({ it.auto }, { it.label.lowercase() }))
    }

    /** The caption to load without asking: the last chosen language, else English; a human-written one before an automatic one. */
    fun pickDefault(tracks: List<Track>, prefLang: String): Track? {
        if (tracks.isEmpty()) return null
        val want = prefLang.lowercase().ifEmpty { "en" }
        fun matches(t: Track) = t.lang.lowercase() == want || t.lang.lowercase().startsWith("$want-")
        return tracks.firstOrNull { matches(it) && !it.auto }
            ?: tracks.firstOrNull { matches(it) }
            ?: tracks.firstOrNull { !it.auto }
            ?: tracks.first()
    }

    /** Sentences of one caption track, and a short note when their timing was matched to the voice. */
    class Fetched(val cues: List<Srt.Cue>, val note: String?)

    private fun download(url: String): String {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", UA_WEB).build()
        return try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw YtError("YouTube refused the captions (error " + r.code + ").")
                r.body?.string() ?: ""
            }
        } catch (e: YtError) {
            throw e
        } catch (e: Exception) {
            throw YtError("Could not download the captions. Check the internet connection.", e)
        }
    }

    private fun baseLang(l: String): String = l.lowercase().substringBefore('-').substringBefore('_')

    /** Downloads a caption and turns it into sentences. Blocking. */
    fun fetchCaptions(track: Track): List<Srt.Cue> = fetchCaptionsSmart(track, emptyList()).cues

    /**
     * Downloads a caption and turns it into sentences. Blocking.
     * An automatic caption is cut into sentences by the real timing of its words. A caption written by a person has the better
     * text but loose timing: when the video also has an automatic caption in the same language, the person's cues are moved
     * to the exact start and end of the spoken words (see [CaptionAligner]). Any problem on that side keeps the original timing.
     */
    fun fetchCaptionsSmart(track: Track, all: List<Track>): Fetched {
        val parsed = YtCaptions.parseFull(download(track.url))
        if (parsed.cues.isEmpty()) throw YtError("These captions are empty or in a format that cannot be read.")
        if (track.auto || parsed.cues.size < 5) return Fetched(parsed.cues, if (track.auto && parsed.words.isNotEmpty()) "timed to the voice" else null)
        val voice = all.firstOrNull { it.auto && baseLang(it.lang) == baseLang(track.lang) }
            ?: return Fetched(parsed.cues, null)
        return try {
            val words = YtCaptions.parseFull(download(voice.url)).words
            if (words.size < 20) {
                Fetched(parsed.cues, null)
            } else {
                val r = CaptionAligner.align(parsed.cues, words)
                if (r.ratio >= CaptionAligner.MIN_RATIO) Fetched(r.cues, "timed to the voice") else Fetched(parsed.cues, null)
            }
        } catch (e: Exception) {
            Fetched(parsed.cues, null)       // the extra help failed: the caption itself is still fine
        }
    }

    // ------------------------------------------------------------------ history of played links

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("shadowplayer", Context.MODE_PRIVATE)

    fun history(ctx: Context): List<HistoryEntry> {
        val raw = prefs(ctx).getString("yt_history", null) ?: return emptyList()
        val out = ArrayList<HistoryEntry>()
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(HistoryEntry(o.getString("id"), o.optString("title", "YouTube video"), o.optString("up", ""), o.optLong("t", 0L)))
            }
        } catch (e: Exception) {
            // unreadable list: start again
        }
        return out
    }

    fun addHistory(ctx: Context, id: String, title: String, uploader: String) {
        val list = ArrayList<HistoryEntry>(history(ctx))
        list.removeAll { it.id == id }
        list.add(0, HistoryEntry(id, title, uploader, System.currentTimeMillis()))
        saveHistory(ctx, list.take(40))
        try { refreshFavoriteInfo(ctx, id, title, uploader) } catch (e: Exception) { }
    }

    fun removeHistory(ctx: Context, id: String) {
        saveHistory(ctx, history(ctx).filter { it.id != id })
    }

    private fun saveHistory(ctx: Context, list: List<HistoryEntry>) {
        val a = JSONArray()
        for (h in list) {
            val o = JSONObject()
            o.put("id", h.id)
            o.put("title", h.title)
            o.put("up", h.uploader)
            o.put("t", h.time)
            a.put(o)
        }
        prefs(ctx).edit().putString("yt_history", a.toString()).apply()
    }

    // ------------------------------------------------------------------ favourites (YouTube videos the user hearted)

    private fun readList(ctx: Context, key: String): List<HistoryEntry> {
        val raw = prefs(ctx).getString(key, null) ?: return emptyList()
        val out = ArrayList<HistoryEntry>()
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(HistoryEntry(o.getString("id"), o.optString("title", "YouTube video"), o.optString("up", ""), o.optLong("t", 0L)))
            }
        } catch (e: Exception) {
            // unreadable list: treat as empty
        }
        return out
    }

    private fun writeList(ctx: Context, key: String, list: List<HistoryEntry>) {
        try {
            val a = JSONArray()
            for (h in list) {
                val o = JSONObject()
                o.put("id", h.id)
                o.put("title", h.title)
                o.put("up", h.uploader)
                o.put("t", h.time)
                a.put(o)
            }
            prefs(ctx).edit().putString(key, a.toString()).apply()
        } catch (e: Exception) {
            // ignore
        }
    }

    fun favorites(ctx: Context): List<HistoryEntry> = readList(ctx, "yt_favs")

    fun isFavorite(ctx: Context, id: String): Boolean = favorites(ctx).any { it.id == id }

    fun addFavorite(ctx: Context, id: String, title: String, uploader: String) {
        val list = ArrayList<HistoryEntry>(favorites(ctx))
        list.removeAll { it.id == id }
        list.add(0, HistoryEntry(id, title, uploader, System.currentTimeMillis()))
        writeList(ctx, "yt_favs", list.take(200))
    }

    fun removeFavorite(ctx: Context, id: String) {
        writeList(ctx, "yt_favs", favorites(ctx).filter { it.id != id })
    }

    /** A favourite saved from a pasted link only knows a placeholder title: once the video has played, store the real one. */
    private fun refreshFavoriteInfo(ctx: Context, id: String, title: String, uploader: String) {
        val favs = favorites(ctx)
        if (favs.none { it.id == id }) return
        writeList(ctx, "yt_favs", favs.map { if (it.id == id) HistoryEntry(id, title, uploader, it.time) else it })
    }

    fun maxHeight(ctx: Context): Int = prefs(ctx).getInt("yt_max_h", 720)

    fun setMaxHeight(ctx: Context, h: Int) {
        prefs(ctx).edit().putInt("yt_max_h", h).apply()
    }

    fun captionLang(ctx: Context): String = prefs(ctx).getString("yt_cap_lang", "en") ?: "en"

    fun setCaptionLang(ctx: Context, lang: String) {
        prefs(ctx).edit().putString("yt_cap_lang", lang).apply()
    }
}
