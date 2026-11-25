package com.stagemaster.pro.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.stagemaster.pro.model.AudioTrackChannel
import com.stagemaster.pro.model.SongItem

class MultitrackEngine(
    private val context: Context,
    private val onTrackFinished: () -> Unit,
    private val onProgressUpdate: (Long, Long) -> Unit
) {
    private val players = mutableMapOf<String, ExoPlayer>()
    private var masterPlayer: ExoPlayer? = null
    private var currentSong: SongItem? = null
    private val handler = Handler(Looper.getMainLooper())

    private val progressRunnable = object : Runnable {
        override fun run() {
            masterPlayer?.let { player ->
                if (player.isPlaying) {
                    onProgressUpdate(player.currentPosition, player.duration.coerceAtLeast(1L))
                }
            }
            handler.postDelayed(this, 50L) // Atualiza a cada 50ms para sincronia precisa
        }
    }

    fun loadSong(song: SongItem, autoPlay: Boolean = false) {
        releaseAll()
        currentSong = song
        if (song.tracks.isEmpty()) return

        val speedRatio = song.targetBpm.toFloat() / song.originalBpm.coerceAtLeast(1).toFloat()

        song.tracks.forEachIndexed { index, track ->
            val player = ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(track.uri))
                playbackParameters = PlaybackParameters(speedRatio, 1.0f) // Muda BPM mantendo o tom
                prepare()
            }

            if (index == 0) {
                masterPlayer = player
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            handler.removeCallbacks(progressRunnable)
                            onTrackFinished()
                        }
                    }
                })
            }
            players[track.id] = player
        }

        applyMix(song.tracks)
        if (autoPlay) play()
    }

    fun play() {
        // Prepara e dispara todas as faixas no mesmo ciclo de clock
        players.values.forEach { it.playWhenReady = true }
        handler.post(progressRunnable)
    }

    fun pause() {
        players.values.forEach { it.playWhenReady = false }
        handler.removeCallbacks(progressRunnable)
    }

    fun stop() {
        pause()
        seekTo(0L)
    }

    fun seekTo(positionMs: Long) {
        players.values.forEach { it.seekTo(positionMs) }
        masterPlayer?.let { onProgressUpdate(positionMs, it.duration.coerceAtLeast(1L)) }
    }

    fun setBpm(originalBpm: Int, newBpm: Int) {
        val ratio = (newBpm.toFloat() / originalBpm.coerceAtLeast(1).toFloat()).coerceIn(0.5f, 2.0f)
        players.values.forEach { player ->
            player.playbackParameters = PlaybackParameters(ratio, 1.0f)
        }
    }

    fun applyMix(tracks: List<AudioTrackChannel>) {
        val anySolo = tracks.any { it.isSolo }
        tracks.forEach { track ->
            val player = players[track.id] ?: return@forEach
            val effectiveVolume = when {
                anySolo && !track.isSolo -> 0f
                track.isMuted -> 0f
                else -> track.volume
            }
            player.volume = effectiveVolume
        }
    }

    fun isPlaying(): Boolean = masterPlayer?.isPlaying == true

    fun releaseAll() {
        handler.removeCallbacks(progressRunnable)
        players.values.forEach { it.release() }
        players.clear()
        masterPlayer = null
    }
}