package com.metrom.shared.audio

import kotlin.math.roundToInt

/** Pure WAV decode from bytes — no platform I/O. */
object WavDecoder {
    const val SAMPLE_RATE = 44_100

    fun decodeStrict(bytes: ByteArray, label: String): ShortArray {
        val parsed = parseWav(bytes)
        require(parsed.channels == 1) { "$label: expected mono, got ${parsed.channels} channel(s)" }
        require(parsed.bitsPerSample == 16) {
            "$label: expected 16-bit PCM, got ${parsed.bitsPerSample}-bit"
        }
        require(parsed.sampleRate == SAMPLE_RATE) {
            "$label: expected $SAMPLE_RATE Hz, got ${parsed.sampleRate} Hz (no resample)"
        }
        val pcm = ShortArray(parsed.frameCount)
        var o = parsed.dataOffset
        for (i in 0 until parsed.frameCount) {
            pcm[i] = leShort(bytes, o)
            o += 2
        }
        require(pcm.isNotEmpty()) { "$label: empty PCM" }
        return pcm
    }

    fun decodeLegacy(bytes: ByteArray): ShortArray {
        val parsed = parseWav(bytes)
        require(parsed.bitsPerSample == 16) { "Only 16-bit PCM supported" }
        val mono = ShortArray(parsed.frameCount)
        var o = parsed.dataOffset
        for (i in 0 until parsed.frameCount) {
            var sum = 0
            repeat(parsed.channels) {
                sum += leShort(bytes, o).toInt()
                o += 2
            }
            mono[i] = (sum / parsed.channels).toShort()
        }
        return if (parsed.sampleRate == SAMPLE_RATE) mono
        else resample(mono, parsed.sampleRate, SAMPLE_RATE)
    }

    private data class WavParsed(
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val dataOffset: Int,
        val dataSize: Int,
        val frameCount: Int,
    )

    private fun parseWav(bytes: ByteArray): WavParsed {
        require(bytes.size >= 44) { "WAV too small" }
        require(bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte()) { "Not RIFF" }

        var offset = 12
        var channels = 1
        var sampleRate = SAMPLE_RATE
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0

        while (offset + 8 <= bytes.size) {
            val id = bytes.decodeToString(offset, offset + 4)
            val size = leInt(bytes, offset + 4)
            val chunkStart = offset + 8
            when (id) {
                "fmt " -> {
                    val format = leShort(bytes, chunkStart).toInt() and 0xffff
                    require(format == 1) { "Only PCM WAV supported (got format $format)" }
                    channels = leShort(bytes, chunkStart + 2).toInt() and 0xffff
                    sampleRate = leInt(bytes, chunkStart + 4)
                    bitsPerSample = leShort(bytes, chunkStart + 14).toInt() and 0xffff
                }
                "data" -> {
                    dataOffset = chunkStart
                    dataSize = size.coerceAtMost(bytes.size - chunkStart)
                    break
                }
            }
            offset = chunkStart + size + (size and 1)
        }
        require(dataOffset >= 0) { "No data chunk" }
        val frameCount = dataSize / (channels * 2)
        return WavParsed(channels, sampleRate, bitsPerSample, dataOffset, dataSize, frameCount)
    }

    private fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (input.isEmpty()) return input
        val ratio = fromRate.toDouble() / toRate
        val outLen = ((input.size / ratio).roundToInt()).coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in out.indices) {
            val src = i * ratio
            val i0 = src.toInt().coerceIn(0, input.lastIndex)
            val i1 = (i0 + 1).coerceAtMost(input.lastIndex)
            val frac = src - i0
            val a = input[i0].toDouble()
            val b = input[i1].toDouble()
            out[i] = (a + (b - a) * frac).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return out
    }

    private fun leInt(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xff) or
            ((b[i + 1].toInt() and 0xff) shl 8) or
            ((b[i + 2].toInt() and 0xff) shl 16) or
            ((b[i + 3].toInt() and 0xff) shl 24)

    private fun leShort(b: ByteArray, i: Int): Short =
        ((b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)).toShort()
}
