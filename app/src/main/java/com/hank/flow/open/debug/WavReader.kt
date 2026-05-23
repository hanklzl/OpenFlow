package com.hank.flow.open.debug

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal RIFF WAVE reader for OpenFlow diagnostic use. Accepts 16 kHz mono
 * PCM-16 only; throws [IOException] for anything else. Returns a fully
 * decoded `ShortArray` ready for [com.hank.flow.open.asr.WhisperEngine].
 *
 * Not a general-purpose wav parser — assumes a well-formed canonical RIFF
 * header with `fmt ` chunk before `data` chunk.
 */
object WavReader {

    data class Result(
        val pcm: ShortArray,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
    )

    fun read(stream: InputStream): Result {
        val bytes = stream.readBytes()
        if (bytes.size < 44) throw IOException("wav too short: ${bytes.size}")
        if (!bytes.sliceArray(0..3).contentEquals("RIFF".toByteArray())) {
            throw IOException("not RIFF")
        }
        if (!bytes.sliceArray(8..11).contentEquals("WAVE".toByteArray())) {
            throw IOException("not WAVE")
        }
        // Walk chunks looking for `fmt ` and `data`.
        var pos = 12
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = le32(bytes, pos + 4)
            val payload = pos + 8
            when (id) {
                "fmt " -> {
                    val audioFormat = le16(bytes, payload)
                    channels = le16(bytes, payload + 2)
                    sampleRate = le32(bytes, payload + 4)
                    bitsPerSample = le16(bytes, payload + 14)
                    if (audioFormat != 1) throw IOException("not PCM (audioFormat=$audioFormat)")
                }
                "data" -> {
                    dataOffset = payload
                    dataSize = size
                }
            }
            pos = payload + size + (size and 1) // chunks are word-aligned
        }
        if (dataOffset < 0) throw IOException("missing data chunk")
        if (sampleRate != 16_000) throw IOException("sampleRate=$sampleRate, expected 16000")
        if (channels != 1) throw IOException("channels=$channels, expected 1")
        if (bitsPerSample != 16) throw IOException("bitsPerSample=$bitsPerSample, expected 16")

        val nShorts = dataSize / 2
        val buf = ByteBuffer.wrap(bytes, dataOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN)
        val pcm = ShortArray(nShorts)
        for (i in 0 until nShorts) pcm[i] = buf.short
        return Result(pcm, sampleRate, channels, bitsPerSample)
    }

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)
}
