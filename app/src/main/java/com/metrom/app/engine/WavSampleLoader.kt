package com.metrom.app.engine

import android.content.Context
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Loads 16-bit PCM WAV into mono ShortArrays at [ClickSynthesizer.SAMPLE_RATE].
 * Supports mono/stereo PCM WAVs (common guitar sample exports).
 */
object WavSampleLoader {

    fun loadChug(context: Context, accent: Boolean): ShortArray? {
        val primary = if (accent) "chug_accent.wav" else "chug.wav"
        val fallback = "chug.wav"
        val names = if (accent) listOf(primary, fallback) else listOf(primary)

        for (name in names) {
            val file = File(context.filesDir, "chug/$name")
            if (file.isFile) {
                runCatching { loadFile(file) }.getOrNull()?.let { return it }
            }
        }
        for (name in names) {
            runCatching {
                context.assets.open("chug/$name").use { loadStream(it) }
            }.getOrNull()?.let { return it }
        }
        val rawName = if (accent) "chug_accent" else "chug"
        val ids = buildList {
            add(context.resources.getIdentifier(rawName, "raw", context.packageName))
            if (accent) add(context.resources.getIdentifier("chug", "raw", context.packageName))
        }.filter { it != 0 }
        for (id in ids) {
            runCatching {
                context.resources.openRawResource(id).use { loadStream(it) }
            }.getOrNull()?.let { return it }
        }
        return null
    }

    fun loadFile(file: File): ShortArray =
        file.inputStream().use { loadStream(it) }

    fun loadStream(input: InputStream): ShortArray {
        val bytes = input.readBytes()
        return decodeWav(bytes)
    }

    private fun decodeWav(bytes: ByteArray): ShortArray {
        require(bytes.size >= 44) { "WAV too small" }
        require(bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte()) { "Not RIFF" }

        var offset = 12
        var channels = 1
        var sampleRate = ClickSynthesizer.SAMPLE_RATE
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0

        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = leInt(bytes, offset + 4)
            val chunkStart = offset + 8
            when (id) {
                "fmt " -> {
                    val format = leShort(bytes, chunkStart).toInt() and 0xffff
                    require(format == 1) { "Only PCM WAV supported (got format $format)" }
                    channels = leShort(bytes, chunkStart + 2).toInt() and 0xffff
                    sampleRate = leInt(bytes, chunkStart + 4)
                    bitsPerSample = leShort(bytes, chunkStart + 14).toInt() and 0xffff
                    require(bitsPerSample == 16) { "Only 16-bit PCM supported" }
                }
                "data" -> {
                    dataOffset = chunkStart
                    dataSize = size.coerceAtMost(bytes.size - chunkStart)
                    break
                }
            }
            offset = chunkStart + size + (size and 1) // word align
        }
        require(dataOffset >= 0) { "No data chunk" }

        val frameCount = dataSize / (channels * 2)
        val mono = ShortArray(frameCount)
        val buf = ByteBuffer.wrap(bytes, dataOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            var sum = 0
            repeat(channels) { sum += buf.short.toInt() }
            mono[i] = (sum / channels).toShort()
        }
        return if (sampleRate == ClickSynthesizer.SAMPLE_RATE) {
            mono
        } else {
            resample(mono, sampleRate, ClickSynthesizer.SAMPLE_RATE)
        }
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
