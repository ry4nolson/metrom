package com.metrom.app.audio.detect

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Iterative radix-2 Cooley–Tukey FFT, in-place on separate real/imag arrays.
 * Tables are precomputed for [SIZE] = 1024 and reused across onset frames.
 */
object Fft {
    const val SIZE = 1024

    private val bitRev = IntArray(SIZE)
    private val cosTable = FloatArray(SIZE / 2)
    private val sinTable = FloatArray(SIZE / 2)

    init {
        val bits = Integer.numberOfTrailingZeros(SIZE)
        for (i in 0 until SIZE) {
            bitRev[i] = Integer.reverse(i) ushr (32 - bits)
        }
        for (i in 0 until SIZE / 2) {
            val angle = -2.0 * PI * i / SIZE
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }
    }

    /** In-place forward FFT. [real] and [imag] must each have length [SIZE]. */
    fun transform(real: FloatArray, imag: FloatArray) {
        require(real.size >= SIZE && imag.size >= SIZE)

        for (i in 0 until SIZE) {
            val j = bitRev[i]
            if (j > i) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
                val ti = imag[i]
                imag[i] = imag[j]
                imag[j] = ti
            }
        }

        var len = 2
        while (len <= SIZE) {
            val half = len / 2
            val tableStep = SIZE / len
            var i = 0
            while (i < SIZE) {
                var k = 0
                var j = 0
                while (j < half) {
                    val cos = cosTable[k]
                    val sin = sinTable[k]
                    val even = i + j
                    val odd = even + half
                    val tr = cos * real[odd] - sin * imag[odd]
                    val ti = sin * real[odd] + cos * imag[odd]
                    real[odd] = real[even] - tr
                    imag[odd] = imag[even] - ti
                    real[even] += tr
                    imag[even] += ti
                    k += tableStep
                    j++
                }
                i += len
            }
            len *= 2
        }
    }
}
