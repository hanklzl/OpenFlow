package com.hank.flow.open.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * AudioRecord wrapper producing 16kHz / mono / PCM-16 frames. Emits both
 * raw PCM chunks (for transcription) and RMS levels (for waveform UI).
 */
class AudioRecorder {

    data class Frame(val pcm: ShortArray, val rms: Float)

    private val _frames = MutableSharedFlow<Frame>(replay = 0, extraBufferCapacity = 64)
    val frames: SharedFlow<Frame> = _frames.asSharedFlow()

    private val captured = ArrayDeque<Short>()
    private var recorder: AudioRecord? = null
    private var job: Job? = null

    val sampleRate = 16_000
    private val bufferShorts = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    ).coerceAtLeast(1024)

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        captured.clear()
        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferShorts * 2,
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            return
        }
        recorder = ar
        ar.startRecording()
        job = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(bufferShorts)
            while (isActive) {
                val n = ar.read(buffer, 0, buffer.size)
                if (n > 0) {
                    val chunk = buffer.copyOf(n)
                    for (i in 0 until n) captured.addLast(buffer[i])
                    _frames.tryEmit(Frame(chunk, rms(chunk)))
                }
            }
        }
    }

    fun stop(): ShortArray {
        job?.cancel()
        job = null
        recorder?.let {
            try { it.stop() } catch (_: Throwable) {}
            it.release()
        }
        recorder = null
        val out = ShortArray(captured.size)
        var i = 0
        captured.forEach { out[i++] = it }
        captured.clear()
        return out
    }

    private fun rms(chunk: ShortArray): Float {
        if (chunk.isEmpty()) return 0f
        var acc = 0.0
        for (s in chunk) acc += (s.toDouble() * s.toDouble())
        val rms = kotlin.math.sqrt(acc / chunk.size)
        return (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }
}

internal fun CoroutineScope.cancelSafely() {
    try { this.cancel() } catch (_: Throwable) {}
}
