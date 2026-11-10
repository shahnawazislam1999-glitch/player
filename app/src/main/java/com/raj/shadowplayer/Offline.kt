package com.raj.shadowplayer

import android.content.Context
import android.net.Uri
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Offline copies of YouTube videos: the picture (at the quality chosen in the "Play a YouTube link" window) and the
 * subtitles are saved inside the app's private folder. A saved video plays without internet and can be deleted again.
 * Folder per video: files/offline/<id>/ (video.bin, audio.bin when picture and sound are separate, subtitles.srt, meta.json).
 * meta.json is written last, so a half finished download never shows up as a saved video.
 */
object Offline {
    class Entry(
        val id: String,
        val title: String,
        val uploader: String,
        val height: Int,
        val bytes: Long,
        val time: Long,
        val hasAudioFile: Boolean,
        val hasSubs: Boolean,
        val wroteMainSrt: Boolean
    )

    class Cancelled : Exception("cancelled")

    private const val PIECE = 4L * 1024L * 1024L

    private fun root(ctx: Context): File = File(ctx.applicationContext.filesDir, "offline").also { it.mkdirs() }

    fun dir(ctx: Context, id: String): File = File(root(ctx), id)

    fun videoFile(ctx: Context, id: String): File = File(dir(ctx, id), "video.bin")
    fun audioFile(ctx: Context, id: String): File = File(dir(ctx, id), "audio.bin")
    fun subsFile(ctx: Context, id: String): File = File(dir(ctx, id), "subtitles.srt")

    private fun isSafeId(id: String) = Regex("[A-Za-z0-9_-]{11}").matches(id)

    fun get(ctx: Context, id: String): Entry? {
        if (!isSafeId(id)) return null
        return try {
            val f = File(dir(ctx, id), "meta.json")
            if (!f.isFile || !videoFile(ctx, id).isFile) return null
            val o = JSONObject(f.readText())
            val e = Entry(
                id, o.optString("title", "YouTube video"), o.optString("uploader", ""), o.optInt("height", 0),
                o.optLong("bytes", 0L), o.optLong("time", 0L), o.optBoolean("audio", false),
                o.optBoolean("subs", false), o.optBoolean("wroteSrt", false)
            )
            if (e.hasAudioFile && !audioFile(ctx, id).isFile) null else e
        } catch (ex: Exception) {
            null
        }
    }

    fun list(ctx: Context): List<Entry> {
        val dirs = root(ctx).listFiles() ?: return emptyList()
        return dirs.filter { it.isDirectory }.mapNotNull { get(ctx, it.name) }.sortedByDescending { it.time }
    }

    fun totalBytes(ctx: Context): Long = list(ctx).sumOf { it.bytes }

    fun delete(ctx: Context, id: String): Boolean {
        if (!isSafeId(id)) return false
        val e = get(ctx, id)
        // subtitles that this download put into the video's memory go away with it (the user's own subtitles stay)
        if (e != null && e.wroteMainSrt) {
            try { File(File(ctx.applicationContext.filesDir, "subs"), "yt_$id.srt").delete() } catch (ex: Exception) { }
        }
        return dir(ctx, id).deleteRecursively()
    }

    fun deleteAll(ctx: Context): Int {
        var n = 0
        for (e in list(ctx)) if (delete(ctx, e.id)) n++
        // also remove leftovers of broken downloads
        root(ctx).listFiles()?.forEach { it.deleteRecursively() }
        return n
    }

    // ------------------------------------------------------------------ download (blocking: call from a background thread)

    /**
     * Saves the video at up to [maxHeight] pixels (144 saves the most space) and its default subtitles.
     * [onProgress] gets 0..100 (or -1 when the size is not known). [cancelled] is polled between pieces.
     */
    fun download(
        ctx: Context, id: String, maxHeight: Int, onProgress: (Int) -> Unit, cancelled: () -> Boolean
    ): Entry {
        if (!isSafeId(id)) throw YouTube.YtError("That is not a valid YouTube video.")
        val r = YouTube.resolve(id, maxHeight)
        val plan = r.plan
        if (plan.kind != YouTube.KIND_MERGED && plan.kind != YouTube.KIND_PROGRESSIVE) {
            throw YouTube.YtError("This video is only offered as a live / adaptive stream, so it cannot be saved offline.")
        }
        if (plan.label == "audio only") throw YouTube.YtError("YouTube gave no picture for this video, so it cannot be saved offline.")
        val d = dir(ctx, id)
        d.deleteRecursively()
        if (!d.mkdirs() && !d.isDirectory) throw YouTube.YtError("Could not create the offline folder.")
        try {
            val vTotal = Uri.parse(plan.url).getQueryParameter("clen")?.toLongOrNull() ?: -1L
            val aUrl = plan.audioUrl
            val aTotal = if (aUrl != null) (Uri.parse(aUrl).getQueryParameter("clen")?.toLongOrNull() ?: -1L) else 0L
            val known = vTotal > 0 && aTotal >= 0
            val grand = if (known) vTotal + aTotal else -1L
            var done = 0L
            var lastPct = -2
            fun report(add: Long) {
                done += add
                val pct = if (grand > 0) ((done * 100L) / grand).toInt().coerceIn(0, 99) else -1
                if (pct != lastPct) {
                    lastPct = pct
                    onProgress(pct)
                }
            }
            val vFile = videoFile(ctx, id)
            fetchFile(plan.url, vFile, ::report, cancelled)
            var total = vFile.length()
            if (aUrl != null) {
                val aFile = audioFile(ctx, id)
                fetchFile(aUrl, aFile, ::report, cancelled)
                total += aFile.length()
            }

            // subtitles: the same caption the player would load by itself
            var srtText: String? = null
            try {
                val pick = YouTube.pickDefault(r.captions, YouTube.captionLang(ctx))
                if (pick != null) {
                    val cues = YouTube.fetchCaptionsSmart(pick, r.captions).cues
                    if (cues.isNotEmpty()) srtText = Srt.serialize(cues)
                }
            } catch (e: Exception) {
                srtText = null        // the video is still saved without subtitles
            }
            var wrote = false
            if (srtText != null) {
                subsFile(ctx, id).writeText(srtText)
                // make the player use it offline as well (never replace subtitles the learner already has for this video)
                val vd = VideoData(ctx.applicationContext, "yt_$id")
                if (vd.loadMainSrt() == null) {
                    vd.saveMainSrt(srtText)
                    wrote = true
                }
            }
            val h = plan.label.removeSuffix("p").toIntOrNull() ?: 0
            val meta = JSONObject()
                .put("title", r.title).put("uploader", r.uploader).put("height", h)
                .put("bytes", total).put("time", System.currentTimeMillis())
                .put("audio", aUrl != null).put("subs", srtText != null).put("wroteSrt", wrote)
            File(d, "meta.json").writeText(meta.toString())
            onProgress(100)
            return get(ctx, id) ?: throw YouTube.YtError("The video was saved but could not be read back.")
        } catch (e: Throwable) {
            d.deleteRecursively()
            throw e
        }
    }

    private fun pieceUrl(base: Uri, from: Long, to: Long, no: Int): String {
        val nb = base.buildUpon().clearQuery()
        for (name in base.queryParameterNames) {
            if (name == "range" || name == "rn") continue
            for (v in base.getQueryParameters(name)) nb.appendQueryParameter(name, v)
        }
        nb.appendQueryParameter("range", "$from-$to")
        nb.appendQueryParameter("rn", no.toString())
        return nb.build().toString()
    }

    /** Same way the player reads YouTube files: short ranged pieces (one long request is throttled). */
    private fun fetchFile(url: String, dest: File, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
        val base = Uri.parse(url)
        val total = base.getQueryParameter("clen")?.toLongOrNull() ?: -1L
        val ua = YouTube.userAgentFor(base)
        val tmp = File(dest.path + ".part")
        var pos = 0L
        var no = 0
        FileOutputStream(tmp).use { out ->
            while (true) {
                if (cancelled()) throw Cancelled()
                if (total > 0 && pos >= total) break
                var last = pos + PIECE - 1
                if (total > 0 && last > total - 1) last = total - 1
                no++
                var bytes: ByteArray? = null
                var end = false
                var attempt = 0
                while (bytes == null && !end) {
                    if (cancelled()) throw Cancelled()
                    attempt++
                    try {
                        val req = Request.Builder().url(pieceUrl(base, pos, last, no)).header("User-Agent", ua).build()
                        YouTube.http.newCall(req).execute().use { resp ->
                            if (resp.code == 416) {
                                end = true
                            } else if (!resp.isSuccessful) {
                                throw IOException("YouTube refused the download (error " + resp.code + ").")
                            } else {
                                bytes = resp.body?.bytes() ?: ByteArray(0)
                            }
                        }
                    } catch (e: IOException) {
                        if (attempt >= 3) throw YouTube.YtError(e.message ?: "The download was interrupted. Check the internet connection.", e)
                        try { Thread.sleep(800L * attempt) } catch (ie: InterruptedException) { throw Cancelled() }
                    }
                }
                val b = bytes
                if (end || b == null || b.isEmpty()) break
                out.write(b)
                pos += b.size
                onBytes(b.size.toLong())
                if (total <= 0 && b.size < PIECE) break      // size unknown: a short piece means the file is over
            }
        }
        if (pos <= 0L) {
            tmp.delete()
            throw YouTube.YtError("YouTube sent an empty file.")
        }
        if (total > 0 && pos < total) {
            tmp.delete()
            throw YouTube.YtError("The download stopped early. Try again.")
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw YouTube.YtError("Could not save the file.")
    }
}
