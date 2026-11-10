package com.raj.shadowplayer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory

/**
 * Reads a YouTube file the way YouTube's own apps do: in pieces of a few MB, each asked for with a "range" in the
 * address. One long request is slowed down (throttled) or refused by YouTube; many short ones are not. Seeking just
 * starts the next piece at the new place, so "repeat this sentence" stays instant.
 */
@UnstableApi
class YtDataSource : BaseDataSource(true) {
    private var upstream: DataSource? = null
    private var baseUri: Uri? = null
    private var spec: DataSpec? = null
    private var pos = 0L
    private var endExclusive = UNSET
    private var received = 0L          // bytes taken from the current piece
    private var pieceNo = 0
    private var emptyPieces = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        baseUri = dataSpec.uri
        transferInitializing(dataSpec)
        pos = dataSpec.position
        val total = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull() ?: UNSET
        endExclusive = when {
            dataSpec.length != UNSET -> dataSpec.position + dataSpec.length
            total > 0L -> total
            else -> UNSET
        }
        pieceNo = 0
        emptyPieces = 0
        openPiece()
        opened = true
        transferStarted(dataSpec)
        return if (endExclusive != UNSET) endExclusive - pos else UNSET
    }

    private fun pieceUri(from: Long, to: Long): Uri {
        val b = baseUri!!
        val nb = b.buildUpon().clearQuery()
        for (name in b.queryParameterNames) {
            if (name == "range" || name == "rn") continue
            for (v in b.getQueryParameters(name)) nb.appendQueryParameter(name, v)
        }
        nb.appendQueryParameter("range", "$from-$to")
        nb.appendQueryParameter("rn", pieceNo.toString())
        return nb.build()
    }

    private fun openPiece() {
        closeUpstream()
        pieceNo++
        var last = pos + PIECE - 1
        if (endExclusive != UNSET && last > endExclusive - 1) last = endExclusive - 1
        val up = DefaultHttpDataSource.Factory()
            .setUserAgent(YouTube.userAgentFor(baseUri!!))
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
            .createDataSource()
        up.open(DataSpec.Builder().setUri(pieceUri(pos, last)).build())
        upstream = up
        received = 0L
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (endExclusive != UNSET && pos >= endExclusive) return C.RESULT_END_OF_INPUT
            val up = upstream ?: return C.RESULT_END_OF_INPUT
            val n = up.read(buffer, offset, length)
            if (n == C.RESULT_END_OF_INPUT) {
                if (received == 0L) {
                    emptyPieces++
                    if (emptyPieces >= 2) return C.RESULT_END_OF_INPUT
                } else {
                    emptyPieces = 0
                }
                // size unknown: a short piece means the file is over
                if (endExclusive == UNSET && received < PIECE) return C.RESULT_END_OF_INPUT
                try {
                    openPiece()
                } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    if (e.responseCode == 416) return C.RESULT_END_OF_INPUT   // asked past the end of the file
                    throw e
                }
                continue
            }
            pos += n
            received += n
            bytesTransferred(n)
            return n
        }
    }

    override fun getUri(): Uri? = baseUri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream?.responseHeaders ?: emptyMap()

    override fun close() {
        closeUpstream()
        baseUri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun closeUpstream() {
        try {
            upstream?.close()
        } catch (e: Exception) {
            // already closed
        }
        upstream = null
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = YtDataSource()
    }

    companion object {
        private const val UNSET = -1L
        private const val PIECE = 4L * 1024L * 1024L
    }
}

/** Turns what [YouTube.resolve] found into something ExoPlayer can play. */
@UnstableApi
object YtMedia {
    fun build(plan: YouTube.Plan): MediaSource {
        val extractors = DefaultExtractorsFactory()
        fun progressive(url: String): MediaSource =
            ProgressiveMediaSource.Factory(YtDataSource.Factory(), extractors).createMediaSource(MediaItem.fromUri(url))

        return when (plan.kind) {
            YouTube.KIND_MERGED -> MergingMediaSource(progressive(plan.url), progressive(plan.audioUrl ?: plan.url))
            YouTube.KIND_HLS -> HlsMediaSource.Factory(webFactory()).createMediaSource(MediaItem.fromUri(plan.url))
            YouTube.KIND_DASH -> DashMediaSource.Factory(webFactory()).createMediaSource(MediaItem.fromUri(plan.url))
            else -> progressive(plan.url)
        }
    }

    /** A video saved by [Offline]: plays from the phone, no internet. */
    fun buildLocal(videoFile: java.io.File, audioFile: java.io.File?): MediaSource {
        val extractors = DefaultExtractorsFactory()
        fun local(f: java.io.File): MediaSource =
            ProgressiveMediaSource.Factory(androidx.media3.datasource.FileDataSource.Factory(), extractors)
                .createMediaSource(MediaItem.fromUri(Uri.fromFile(f)))
        return if (audioFile != null) MergingMediaSource(local(videoFile), local(audioFile)) else local(videoFile)
    }

    private fun webFactory(): DataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setUserAgent(YouTube.userAgentFor(Uri.parse("https://youtube.com/?c=WEB")))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
}
