package com.metrom.shared.engine

import com.metrom.shared.audio.ClickSynthesizer
import com.metrom.shared.audio.SampleToneCache
import com.metrom.shared.domain.AccentNote
import com.metrom.shared.domain.BeatAccent
import com.metrom.shared.domain.BeatEvent
import com.metrom.shared.domain.ClickTone
import com.metrom.shared.domain.MetronomeTone
import com.metrom.shared.domain.Subdivision
import com.metrom.shared.domain.SwingFeel
import com.metrom.shared.domain.TimeSignature
import com.metrom.shared.platform.AudioSink
import com.metrom.shared.platform.LatencyPad
import com.metrom.shared.platform.UiClock

/**
 * Platform-agnostic metronome coordinator.
 * Platforms start a dedicated audio thread and call [runLoop] on it.
 */
class MetronomeEngine(
    private val sink: AudioSink,
    private val clock: UiClock,
    private val latencyPad: LatencyPad,
    private val sampleCache: SampleToneCache,
    var onBeat: (BeatEvent) -> Unit = {},
) {
    val mixer = MetronomeMixer()

    
    var playing: Boolean = false
        private set

    fun setBpm(value: Int) = mixer.setBpm(value)
    fun setTimeSignature(signature: TimeSignature) = mixer.setTimeSignature(signature)
    fun setGroupTempo(enabled: Boolean) = mixer.setGroupTempo(enabled)
    fun setBeatAccents(levels: List<BeatAccent>) = mixer.setBeatAccents(levels)
    fun setSubdivision(value: Subdivision) = mixer.setSubdivision(value)
    fun setSwing(value: SwingFeel) = mixer.setSwing(value)
    fun setAccentNote(value: AccentNote) = mixer.setAccentNote(value)
    fun setRestNote(value: AccentNote) = mixer.setRestNote(value)
    fun setVolume(value: Float) = mixer.setVolume(value)
    fun setMuted(value: Boolean) = mixer.setMuted(value)
    fun setClicksEnabled(value: Boolean) = mixer.setClicksEnabled(value)

    fun setTone(value: ClickTone) = setTone(MetronomeTone.Synth(value))

    fun setTone(value: MetronomeTone): MetronomeTone {
        return when (value) {
            is MetronomeTone.Synth -> {
                mixer.setTone(value, null)
                value
            }
            is MetronomeTone.Sample -> {
                val buffers = sampleCache.get(value.tone)
                if (buffers == null) {
                    mixer.setTone(MetronomeTone.DEFAULT, null)
                    MetronomeTone.DEFAULT
                } else {
                    mixer.setTone(value, buffers)
                    value
                }
            }
        }
    }

    fun resolvePreviewPcm(accent: Boolean): ShortArray = mixer.resolvePreviewPcm(accent)

    fun markPlaying() {
        playing = true
        mixer.setClicksEnabled(true)
    }

    fun markStopped() {
        playing = false
        mixer.setClicksEnabled(true)
        clock.cancelAll()
        // Unblock a pending sink.write only — do not release the track here.
        // Platform runners join the audio thread, then dispose/release.
        try {
            sink.stop()
        } catch (_: Exception) {
        }
    }

    /**
     * Blocking audio loop — call from a dedicated high-priority platform thread.
     * Returns when [playing] becomes false or the sink errors.
     */
    fun runLoop() {
        val sampleRate = ClickSynthesizer.SAMPLE_RATE
        val preferred = sampleRate / 10
        val writeFrames = sink.start(sampleRate, 1, preferred).coerceAtLeast(1)
        val chunk = ShortArray(writeFrames)
        val state = MetronomeMixer.LoopState()

        try {
            while (playing) {
                mixer.fill(chunk, state)
                var offset = 0
                while (offset < chunk.size && playing) {
                    val result = sink.write(chunk, offset, chunk.size - offset)
                    if (result < 0) {
                        playing = false
                        break
                    }
                    if (result == 0) {
                        sleepMillis(1)
                        continue
                    }
                    offset += result
                }
                state.framesRendered += offset
                mixer.scheduleAudibleBeats(
                    sink = sink,
                    clock = clock,
                    latencyPad = latencyPad,
                    sampleRate = sampleRate,
                    framesWritten = state.framesRendered,
                    pending = state.pendingBeats,
                    isPlaying = { playing },
                    onBeat = onBeat,
                )
            }
        } finally {
            state.pendingBeats.clear()
            clock.cancelAll()
            try {
                sink.stop()
            } catch (_: Exception) {
            }
        }
    }
}

/** Tiny sleep helper — expect/actual if needed; Thread.sleep works on JVM + K/N via kotlinx. */
internal expect fun sleepMillis(ms: Long)
