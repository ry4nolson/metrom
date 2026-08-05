package com.metrom.app.engine

import android.content.Context
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Loads 16-bit PCM WAV into ShortArrays.
 *
 * Sample tones use [loadToneWav] — strict mono / 16-bit / [ClickSynthesizer.SAMPLE_RATE],
 * no resample. Legacy [loadChug] may still mixdown/resample for the old CHUG path.
 */
object WavSampleLoader {

    /**
     * Strict asset load for sample tones. Requires mono, 16-bit PCM at
     * [ClickSynthesizer.SAMPLE_RATE]. Failure (missing / mismatch) is a Result — no resample.
     */
    fun loadToneWav(context: Context, assetPath: String): Result<ShortArray> =
        runCatching {
            context.assets.open(assetPath).use { input ->
                decodeWavStrict(input.readBytes(), assetPath)
            }
        }

    fun toneAssetExists(context: Context, assetPath: String): Boolean =
        runCatching {
            context.assets.open(assetPath).use { true }
        }.getOrDefault(false)

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
        return decodeWavLegacy(bytes)
    }

    /**
     * Mono 16-bit PCM at [ClickSynthesizer.SAMPLE_RATE] only — no resample, no mixdown.
     */
    private fun decodeWavStrict(bytes: ByteArray, label: String): ShortArray {
        val parsed = parseWav(bytes)
        require(parsed.channels == 1) {
            "$label: expected mono, got ${parsed.channels} channel(s)"
        }
        require(parsed.bitsPerSample == 16) {
            "$label: expected 16-bit PCM, got ${parsed.bitsPerSample}-bit"
        }
        require(parsed.sampleRate == ClickSynthesizer.SAMPLE_RATE) {
            "$label: expected ${ClickSynthesizer.SAMPLE_RATE} Hz, got ${parsed.sampleRate} Hz (no resample)"
        }
        val pcm = ShortArray(parsed.frameCount)
        val buf = ByteBuffer.wrap(bytes, parsed.dataOffset, parsed.dataSize)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until parsed.frameCount) {
            pcm[i] = buf.short
        }
        require(pcm.isNotEmpty()) { "$label: empty PCM" }
        return pcm
    }

    /** Legacy path: stereo→mono mixdown + resample (CHUG drop-in only). */
    private fun decodeWavLegacy(bytes: ByteArray): ShortArray {
        val parsed = parseWav(bytes)
        require(parsed.bitsPerSample == 16) { "Only 16-bit PCM supported" }
        val mono = ShortArray(parsed.frameCount)
        val buf = ByteBuffer.wrap(bytes, parsed.dataOffset, parsed.dataSize)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until parsed.frameCount) {
            var sum = 0
            repeat(parsed.channels) { sum += buf.short.toInt() }
            mono[i] = (sum / parsed.channels).toShort()
        }
        return if (parsed.sampleRate == ClickSynthesizer.SAMPLE_RATE) {
            mono
        } else {
            resample(mono, parsed.sampleRate, ClickSynthesizer.SAMPLE_RATE)
        }
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
        return WavParsed(
            channels = channels,
            sampleRate = sampleRate,
            bitsPerSample = bitsPerSample,
            dataOffset = dataOffset,
            dataSize = dataSize,
            frameCount = frameCount,
        )
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
