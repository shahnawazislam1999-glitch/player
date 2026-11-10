package com.raj.shadowplayer

import android.content.Context

/** Favorites and "recently played" for the library. Stored in the same preferences file as the rest of the app. */
object Library {
    private const val FAV = "fav_keys"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("shadowplayer", Context.MODE_PRIVATE)

    fun favorites(ctx: Context): Set<String> = HashSet(prefs(ctx).getStringSet(FAV, emptySet()) ?: emptySet())

    /** Flips the favorite state of a video; returns the new state. */
    fun toggleFavorite(ctx: Context, key: String): Boolean {
        val p = prefs(ctx)
        val set = HashSet(p.getStringSet(FAV, emptySet()) ?: emptySet())
        val now = if (set.contains(key)) { set.remove(key); false } else { set.add(key); true }
        p.edit().putStringSet(FAV, set).apply()
        return now
    }

    fun markPlayed(ctx: Context, key: String) {
        prefs(ctx).edit().putLong("lp_$key", System.currentTimeMillis()).apply()
    }

    fun lastPlayed(ctx: Context, key: String): Long = prefs(ctx).getLong("lp_$key", 0L)
}
