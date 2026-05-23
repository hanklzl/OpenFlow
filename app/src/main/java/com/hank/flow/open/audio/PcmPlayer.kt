package com.hank.flow.open.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.hank.flow.open.log.OpenFlowLog

/**
 * Minimal AudioTrack wrapper for one-shot PCM-16 mono playback of history recordings.
 *
 * Each call to [play] stops/releases any previous track and starts a new one.
 * Safe to call [stop] / [release] from UI lifecycle hooks.
 */
class PcmPlayer {

    private var track: AudioTrack? = null

    fun play(pcm: ShortArray, sampleRate: Int = 16_000) {
        stop()
        if (pcm.isEmpty()) return
        val byteCount = pcm.size * 2
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val t = AudioTrack(
            attrs,
            format,
            byteCount,
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        val written = t.write(pcm, 0, pcm.size)
        if (written < 0) {
            OpenFlowLog.e(
                OpenFlowLog.Tag.AUDIO,
                "pcm_player_write_failed",
                fields = mapOf("ret" to written),
            )
            t.release()
            return
        }
        t.play()
        track = t
    }

    fun stop() {
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
    }

    fun release() = stop()
}
