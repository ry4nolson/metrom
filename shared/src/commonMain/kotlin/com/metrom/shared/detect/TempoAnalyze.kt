package com.metrom.shared.detect

sealed interface DetectState {
    data object Idle : DetectState
    data class Listening(val progress: Float) : DetectState
    data object Analyzing : DetectState
    data class Success(val options: List<Int>, val confidence: Float) : DetectState
    data class Failed(val reason: FailReason) : DetectState
}

enum class FailReason {
    NO_CLEAR_BEAT,
    MIC_UNAVAILABLE,
    PERMISSION_DENIED,
    CANCELLED,
}

object TempoAnalyze {
    data class Result(
        val options: List<Int>,
        val confidence: Float,
        val debug: DetectDebug?,
        val accepted: Boolean,
    )

    fun analyze(samples: FloatArray, sampleRate: Int = 44100, durationSec: Float = 8f): Result {
        val envelope = OnsetEnvelope.compute(samples)
        val estimate = TempoEstimator.estimateWithDebug(envelope)
        val debug = DetectDebug(
            waveform = DetectDebug.downsampleWaveform(samples),
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
            sampleRate = sampleRate,
        )
        return Result(
            options = estimate.options,
            confidence = estimate.confidence,
            debug = debug,
            accepted = estimate.accepted,
        )
    }
}
