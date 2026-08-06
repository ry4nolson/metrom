package com.metrom.app.audio.detect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface DetectState {
    data object Idle : DetectState
    data class Listening(val progress: Float) : DetectState // 0f..1f
    data object Analyzing : DetectState
    /** Strong enough beat found — [options] are BPM choices for the user (not auto-applied). */
    data class Success(
        val options: List<Int>,
        val confidence: Float
    ) : DetectState
    data class Failed(val reason: FailReason) : DetectState
}

enum class FailReason {
    NO_CLEAR_BEAT,
    MIC_UNAVAILABLE,
    PERMISSION_DENIED,
    CANCELLED
}

/**
 * One-shot ambient tempo detection. Owns its AudioRecord + worker thread.
 * No knowledge of the metronome engine.
 */
class TempoDetector(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow<DetectState>(DetectState.Idle)
    val state: StateFlow<DetectState> = _state.asStateFlow()

    /** Last completed analysis; kept after Idle so the debug UI can stay open. */
    private val _lastDebug = MutableStateFlow<DetectDebug?>(null)
    val lastDebug: StateFlow<DetectDebug?> = _lastDebug.asStateFlow()

    private val cancelRequested = AtomicBoolean(false)
    private val lock = Any()
    private var worker: Thread? = null

    @Volatile
    private var recorder: AudioRecord? = null

    fun start() {
        synchronized(lock) {
            if (worker?.isAlive == true) return
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                _state.value = DetectState.Failed(FailReason.PERMISSION_DENIED)
                return
            }
            cancelRequested.set(false)
            _lastDebug.value = null
            _state.value = DetectState.Listening(0f)
            worker = Thread({
                runCaptureAndAnalyze()
            }, "TempoDetector").also {
                it.priority = Thread.NORM_PRIORITY
                it.start()
            }
        }
    }

    fun cancel() {
        cancelRequested.set(true)
        val rec = recorder
        if (rec != null) {
            try {
                rec.stop()
            } catch (_: Exception) {
            }
        }
        val current = _state.value
        if (current is DetectState.Listening || current is DetectState.Analyzing) {
            if (worker?.isAlive != true) {
                _state.value = DetectState.Failed(FailReason.CANCELLED)
            }
        }
    }

    fun reset() {
        val current = _state.value
        if (current is DetectState.Listening || current is DetectState.Analyzing) {
            cancel()
            return
        }
        _state.value = DetectState.Idle
    }

    fun clearDebug() {
        _lastDebug.value = null
    }

    private fun runCaptureAndAnalyze() {
        var record: AudioRecord? = null
        try {
            if (cancelRequested.get()) {
                _state.value = DetectState.Failed(FailReason.CANCELLED)
                return
            }

            val sampleRate = SAMPLE_RATE
            val channel = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
            if (minBuf <= 0) {
                _state.value = DetectState.Failed(FailReason.MIC_UNAVAILABLE)
                return
            }
            val bufferBytes = minBuf * 4
            val audioSource = chooseAudioSource()

            record = try {
                AudioRecord(
                    audioSource,
                    sampleRate,
                    channel,
                    encoding,
                    bufferBytes
                )
            } catch (_: Exception) {
                null
            }

            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                record = null
                _state.value = DetectState.Failed(FailReason.MIC_UNAVAILABLE)
                return
            }

            recorder = record
            val shortBuf = ShortArray(bufferBytes / 2)
            val discardSamples = (sampleRate * DISCARD_SECONDS).toInt()
            val captureSamples = (sampleRate * CAPTURE_SECONDS).toInt()
            val pcm = FloatArray(captureSamples)

            try {
                record.startRecording()
            } catch (_: Exception) {
                _state.value = DetectState.Failed(FailReason.MIC_UNAVAILABLE)
                return
            }

            var discarded = 0
            var written = 0

            while (!cancelRequested.get() && written < captureSamples) {
                val n = record.read(shortBuf, 0, shortBuf.size)
                if (n <= 0) {
                    if (cancelRequested.get()) break
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
                if (written > 0) {
                    _state.value = DetectState.Listening(
                        (written.toFloat() / captureSamples).coerceIn(0f, 1f)
                    )
                }
            }

            if (cancelRequested.get()) {
                _state.value = DetectState.Failed(FailReason.CANCELLED)
                return
            }

            if (written < captureSamples) {
                _state.value = DetectState.Failed(FailReason.MIC_UNAVAILABLE)
                return
            }

            // Release mic before heavy CPU work.
            stopAndRelease(record)
            record = null
            recorder = null

            if (cancelRequested.get()) {
                _state.value = DetectState.Failed(FailReason.CANCELLED)
                return
            }

            _state.value = DetectState.Analyzing

            val envelope = OnsetEnvelope.compute(pcm)
            val estimate = TempoEstimator.estimateWithDebug(envelope)
            val durationSec = CAPTURE_SECONDS
            val debug = DetectDebug(
                waveform = DetectDebug.downsampleWaveform(pcm),
                onset = envelope,
                acf = estimate.autocorrelation,
                candidates = estimate.candidates,
                bpm = estimate.bpm,
                confidence = estimate.confidence,
                accepted = estimate.accepted,
                octaveDoubled = estimate.octaveDoubled,
                beatTimesSec = estimate.bpm?.let {
                    DetectDebug.beatTimesForBpm(envelope, it, durationSec)
                } ?: FloatArray(0),
                durationSec = durationSec,
                sampleRate = sampleRate
            )
            _lastDebug.value = debug

            if (DEBUG_DUMP) {
                dumpDebug(envelope, estimate.autocorrelation)
            }

            synchronized(lock) {
                if (cancelRequested.get()) {
                    _state.value = DetectState.Failed(FailReason.CANCELLED)
                } else if (estimate.options.isEmpty()) {
                    _state.value = DetectState.Failed(FailReason.NO_CLEAR_BEAT)
                } else {
                    _state.value = DetectState.Success(
                        options = estimate.options,
                        confidence = estimate.confidence
                    )
                }
            }
        } catch (_: SecurityException) {
            _state.value = DetectState.Failed(FailReason.PERMISSION_DENIED)
        } catch (_: Exception) {
            _state.value = if (cancelRequested.get()) {
                DetectState.Failed(FailReason.CANCELLED)
            } else {
                DetectState.Failed(FailReason.MIC_UNAVAILABLE)
            }
        } finally {
            stopAndRelease(record)
            if (recorder === record) recorder = null
            synchronized(lock) {
                if (worker === Thread.currentThread()) {
                    worker = null
                }
            }
        }
    }

    private fun chooseAudioSource(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val supported = audioManager.getProperty(
                AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED
            )
            if (supported == "true") {
                return MediaRecorder.AudioSource.UNPROCESSED
            }
        }
        return MediaRecorder.AudioSource.MIC
    }

    private fun stopAndRelease(record: AudioRecord?) {
        if (record == null) return
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (_: Exception) {
        }
        try {
            record.release()
        } catch (_: Exception) {
        }
    }

    private fun dumpDebug(envelope: FloatArray, ac: FloatArray) {
        try {
            val dir = appContext.cacheDir
            File(dir, "tempo_onset.csv").printWriter().use { out ->
                for (v in envelope) out.println(v)
            }
            File(dir, "tempo_acf.csv").printWriter().use { out ->
                for (lag in DetectDebug.ACF_MIN_LAG..DetectDebug.ACF_MAX_LAG) {
                    val bpm = 60f * OnsetEnvelope.ENVELOPE_RATE / lag
                    out.println("$lag,${ac[lag]},$bpm")
                }
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val DISCARD_SECONDS = 0.3f
        private const val CAPTURE_SECONDS = 8.0f

        /** When true, writes onset + ACF CSVs to cacheDir (not audio). */
        const val DEBUG_DUMP = false
    }
}
