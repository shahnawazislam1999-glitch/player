package com.raj.shadowplayer

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * YouTube thumbnails for the "Play a YouTube link" window.
 * Memory cache + small disk cache. Every failure is silent: the view just keeps its placeholder icon.
 */
object YtThumbs {
    private val ID_OK = Regex("^[A-Za-z0-9_-]{11}$")
    private val pool = Executors.newFixedThreadPool(2)
    private val mem = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(act: Activity, id: String, target: ImageView) {
        target.tag = id
        target.setImageDrawable(null)
        if (!ID_OK.matches(id)) return
        val hit = mem.get(id)
        if (hit != null) {
            target.setImageBitmap(hit)
            return
        }
        try {
            pool.execute {
                val bmp = try { fetch(act, id) } catch (e: Throwable) { null }
                if (bmp != null) {
                    mem.put(id, bmp)
                    try {
                        act.runOnUiThread {
                            if (target.tag == id) target.setImageBitmap(bmp)
                        }
                    } catch (e: Throwable) {
                        // screen already closed
                    }
                }
            }
        } catch (e: Throwable) {
            // could not start the loader: placeholder stays
        }
    }

    private fun fetch(act: Activity, id: String): Bitmap? {
        val dir = File(act.cacheDir, "ytthumbs")
        dir.mkdirs()
        val f = File(dir, "$id.jpg")
        if (f.isFile && f.length() > 0) {
            val b = BitmapFactory.decodeFile(f.absolutePath)
            if (b != null) return b
        }
        val c = URL("https://i.ytimg.com/vi/$id/mqdefault.jpg").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            if (c.responseCode != 200) return null
            val bytes = c.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return null
            try { f.writeBytes(bytes) } catch (e: Exception) { }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            c.disconnect()
        }
    }
}
