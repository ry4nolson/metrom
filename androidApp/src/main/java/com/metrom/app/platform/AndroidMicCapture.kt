package com.metrom.app.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import com.metrom.shared.platform.MicCapture

class AndroidMicCapture(context: Context) : MicCapture {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun capture(
        seconds: Float,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): FloatArray? {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val sampleRate = 44100
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
        if (minBuf <= 0) return null
        val bufferBytes = minBuf * 4
        val record = try {
            AudioRecord(chooseAudioSource(), sampleRate, channel, encoding, bufferBytes)
        } catch (_: Exception) {
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            return null
        }
        val shortBuf = ShortArray(bufferBytes / 2)
        val discardSamples = (sampleRate * 0.3f).toInt()
        val captureSamples = (sampleRate * seconds).toInt()
        val pcm = FloatArray(captureSamples)
        try {
            record.startRecording()
            var discarded = 0
            var written = 0
            while (!isCancelled() && written < captureSamples) {
                val n = record.read(shortBuf, 0, shortBuf.size)
                if (n <= 0) {
                    if (isCancelled()) break
                    if (n < 0) break
                    continue
                }
                var i = 0
                while (i < n && discarded < discardSamples) {
                    discarded++
                    i++
                }
                while (i < n && written < captureSamples) {
                    pcm[written] = shortBuf[i] / 32768f
                    written++
                    i++
                }
                if (written > 0) onProgress((written.toFloat() / captureSamples).coerceIn(0f, 1f))
            }
            if (isCancelled() || written < captureSamples) return null
            return pcm
        } catch (_: Exception) {
            return null
        } finally {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            } catch (_: Exception) {
            }
            try {
                record.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun chooseAudioSource(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val supported = audioManager.getProperty(
                AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED,
            )
            if (supported == "true") return MediaRecorder.AudioSource.UNPROCESSED
        }
        return MediaRecorder.AudioSource.MIC
    }
}
