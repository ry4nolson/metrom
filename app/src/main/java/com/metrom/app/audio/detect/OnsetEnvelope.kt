package com.metrom.app.audio.detect

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Builds an onset-strength envelope from mono PCM float samples via
 * Hann-windowed STFT spectral flux, then locally normalizes.
 */
object OnsetEnvelope {
    const val SAMPLE_RATE = 44100
    const val FRAME_SIZE = 1024
    const val HOP_SIZE = 512
    /** Envelope frames per second: 44100 / 512. */
    const val ENVELOPE_RATE = SAMPLE_RATE.toFloat() / HOP_SIZE

    private const val NORM_WINDOW = 86 // ~1.0 s at ENVELOPE_RATE
    private const val LOG_COMPRESS = 100f
    private const val EPS = 1e-8f

    private val hann = FloatArray(FRAME_SIZE) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (FRAME_SIZE - 1)))).toFloat()
    }

    // Reused across compute() calls — not thread-safe; TempoDetector is single-worker.
    private val real = FloatArray(FRAME_SIZE)
    private val imag = FloatArray(FRAME_SIZE)
    private val magLog = FloatArray(FRAME_SIZE / 2 + 1)
    private val magLogPrev = FloatArray(FRAME_SIZE / 2 + 1)

    fun compute(samples: FloatArray): FloatArray {
        if (samples.size < FRAME_SIZE) return FloatArray(0)

        val nFrames = 1 + (samples.size - FRAME_SIZE) / HOP_SIZE
        val flux = FloatArray(nFrames)

        magLogPrev.fill(0f)
        var frameIdx = 0
        var offset = 0
        var hasPrev = false

        while (offset + FRAME_SIZE <= samples.size) {
            for (i in 0 until FRAME_SIZE) {
                real[i] = samples[offset + i] * hann[i]
                imag[i] = 0f
            }
            Fft.transform(real, imag)

            val bins = FRAME_SIZE / 2
            for (k in 0..bins) {
                val re = real[k]
                val im = imag[k]
                val mag = sqrt(re * re + im * im)
                magLog[k] = ln(1f + LOG_COMPRESS * mag)
            }

            var f = 0f
            if (hasPrev) {
                for (k in 0..bins) {
                    val d = magLog[k] - magLogPrev[k]
                    if (d > 0f) f += d
                }
            }
            System.arraycopy(magLog, 0, magLogPrev, 0, magLog.size)
            hasPrev = true

            flux[frameIdx++] = f
            offset += HOP_SIZE
        }

        return localNormalize(flux)
    }

    private fun localNormalize(envelope: FloatArray): FloatArray {
        val n = envelope.size
        if (n == 0) return envelope
        val half = NORM_WINDOW / 2
        val out = FloatArray(n)

        for (i in 0 until n) {
            val start = (i - half).coerceAtLeast(0)
            val end = (i + half).coerceAtMost(n - 1)
            val count = end - start + 1
            var sum = 0f
            for (j in start..end) sum += envelope[j]
            val mean = sum / count
            var varSum = 0f
            for (j in start..end) {
                val d = envelope[j] - mean
                varSum += d * d
            }
            val std = sqrt(varSum / count)
            val denom = if (std < EPS) EPS else std
            val v = (envelope[i] - mean) / denom
            out[i] = if (v > 0f) v else 0f
        }
        return out
    }
}
