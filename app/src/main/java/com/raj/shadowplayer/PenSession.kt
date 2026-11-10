package com.raj.shadowplayer

import android.content.Context
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession

/**
 * Makes the Xiaomi smart pen (and any headset / Bluetooth media button) work: its NEXT / PREVIOUS buttons arrive as
 * media keys, which Android hands to the app that owns an active media session. Same logic as the web page:
 * next = next playable sentence, previous = previous playable sentence (see SentenceEngine.cmd).
 * Works while the app is minimized as long as [PenService] keeps the process alive.
 */
@UnstableApi
class PenSession(
    ctx: Context,
    base: ExoPlayer,
    private val onNext: () -> Unit,
    private val onPrev: () -> Unit,
    private val isPlaying: () -> Boolean,
    private val onToggle: () -> Unit
) {
    private val forwarding = object : ForwardingPlayer(base) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
        override fun hasNextMediaItem(): Boolean = true
        override fun hasPreviousMediaItem(): Boolean = true
        override fun seekToNext() = onNext()
        override fun seekToNextMediaItem() = onNext()
        override fun seekToPrevious() = onPrev()
        override fun seekToPreviousMediaItem() = onPrev()
        override fun play() { if (!isPlaying()) onToggle() }
        override fun pause() { if (isPlaying()) onToggle() }
    }

    // a unique id: two player screens can briefly exist together, and a session id may only be used once
    private val session: MediaSession = MediaSession.Builder(ctx.applicationContext, forwarding)
        .setId("pen" + System.nanoTime())
        .build()

    fun release() {
        try {
            session.release()
        } catch (e: Exception) {
            // already released
        }
    }
}
