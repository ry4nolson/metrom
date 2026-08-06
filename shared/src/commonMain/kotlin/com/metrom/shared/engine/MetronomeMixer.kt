package com.metrom.shared.engine

import com.metrom.shared.audio.CachedSampleBuffers
import com.metrom.shared.audio.ClickSynthesizer
import com.metrom.shared.audio.PcmPitch
import com.metrom.shared.domain.AccentNote
import com.metrom.shared.domain.BeatAccent
import com.metrom.shared.domain.BeatEvent
import com.metrom.shared.domain.ClickTone
import com.metrom.shared.domain.MetronomeLimits
import com.metrom.shared.domain.MetronomeTone
import com.metrom.shared.domain.Subdivision
import com.metrom.shared.domain.SwingFeel
import com.metrom.shared.domain.TimeSignature
import com.metrom.shared.platform.AudioSink
import com.metrom.shared.platform.LatencyPad
import com.metrom.shared.platform.UiClock
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Sample-accurate metronome mixer. Fills PCM buffers and reports pending beats;
 * does not own AudioTrack / AVAudioEngine.
 */
class MetronomeMixer {
    private var bpm: Int = 120
    private var beatsPerBar: Int = 4
    private var noteValue: Int = 4
    private var groupTempo: Boolean = false
    private var subdivision: Subdivision = Subdivision.QUARTER
    private var swing: SwingFeel = SwingFeel.OFF
    private var tone: MetronomeTone = MetronomeTone.DEFAULT
    private var sampleBuffers: CachedSampleBuffers? = null
    private var accentNote: AccentNote = AccentNote.DEFAULT
    private var restNote: AccentNote = AccentNote.OFF
    private var beatAccents: IntArray = intArrayOf(2, 1, 1, 1)
    private var volume: Float = 1f
    private var muted: Boolean = false
    private var clicksEnabled: Boolean = true

    fun setBpm(value: Int) {
        bpm = value.coerceIn(MetronomeLimits.MIN_BPM, MetronomeLimits.MAX_BPM)
    }

    fun getBpm(): Int = bpm

    fun setTimeSignature(signature: TimeSignature) {
        val beats = signature.beats.coerceIn(1, 16)
        beatsPerBar = beats
        noteValue = signature.noteValue.coerceIn(1, 16)
        val current = beatAccents
        if (current.size != beats) {
            beatAccents = IntArray(beats) { i ->
                current.getOrElse(i) { if (i == 0) 2 else 1 }
            }
        }
        if (!signature.isCompound) {
            groupTempo = false
        }
    }

    fun setGroupTempo(enabled: Boolean) {
        groupTempo = enabled && noteValue == 8 && beatsPerBar % 3 == 0
    }

    fun setBeatAccents(levels: List<BeatAccent>) {
        val beats = beatsPerBar.coerceIn(1, 16)
        beatAccents = IntArray(beats) { i ->
            when (levels.getOrElse(i) { if (i == 0) BeatAccent.STRONG else BeatAccent.NORMAL }) {
                BeatAccent.MUTE -> 0
                BeatAccent.NORMAL -> 1
                BeatAccent.STRONG -> 2
            }
        }
    }

    fun setSubdivision(value: Subdivision) {
        subdivision = value
    }

    fun setSwing(value: SwingFeel) {
        swing = value
    }

    fun setTone(value: MetronomeTone, buffers: CachedSampleBuffers?) {
        when (value) {
            is MetronomeTone.Synth -> {
                sampleBuffers = null
                tone = value
            }
            is MetronomeTone.Sample -> {
                sampleBuffers = buffers
                tone = if (buffers != null) value else MetronomeTone.DEFAULT
            }
        }
    }

    fun setAccentNote(value: AccentNote) {
        accentNote = value
    }

    fun setRestNote(value: AccentNote) {
        restNote = value
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
    }

    fun setMuted(value: Boolean) {
        muted = value
    }

    fun setClicksEnabled(value: Boolean) {
        clicksEnabled = value
    }

    fun resolvePreviewPcm(accent: Boolean): ShortArray {
        return when (val current = tone) {
            is MetronomeTone.Sample -> {
                val buffers = sampleBuffers
                if (buffers != null) {
                    val note = if (accent) accentNote else restNote
                    val source = if (accent) buffers.strong else buffers.normal
                    pitchShiftedSample(source, current.tone.rootHz, note)
                } else {
                    ClickSynthesizer.generate(
                        ClickTone.WOOD,
                        accent,
                        accentNote,
                        restNote,
                    )
                }
            }
            is MetronomeTone.Synth ->
                ClickSynthesizer.generate(
                    current.tone,
                    accent,
                    accentNote,
                    restNote,
                )
        }
    }

    /**
     * Stateful fill loop state owned by the audio thread.
     */
    class LoopState {
        var framesRendered = 0L
        var nextClickFrame = ClickSynthesizer.SAMPLE_RATE * PREROLL_MS / 1000L
        var beatInBar = 0
        var pulseInBeat = 0
        var lastTone: MetronomeTone = MetronomeTone.DEFAULT
        var lastAccent = AccentNote.DEFAULT
        var lastRest = AccentNote.OFF
        var lastSamples: CachedSampleBuffers? = null
        var cachedAccent = ShortArray(0)
        var cachedNormal = ShortArray(0)
        var cachedSubdivision = ShortArray(0)
        var activeClick: ShortArray? = null
        var activeClickIndex = 0
        var activeGain = 0f
        val pendingBeats = ArrayDeque<PendingBeat>()
        var initialized = false
    }

    data class PendingBeat(
        val frame: Long,
        val beatIndex: Int,
        val pulseIndex: Int,
        val isAccent: Boolean,
    )

    fun ensureCaches(state: LoopState) {
        if (!state.initialized) {
            state.lastTone = tone
            state.lastAccent = accentNote
            state.lastRest = restNote
            state.lastSamples = sampleBuffers
            val resolved = resolveCachedClicks(
                state.lastTone,
                state.lastAccent,
                state.lastRest,
                state.lastSamples,
            )
            state.cachedAccent = resolved.first
            state.cachedNormal = resolved.second
            state.cachedSubdivision = resolved.third
            state.initialized = true
        }
        val currentTone = tone
        val currentAccent = accentNote
        val currentRest = restNote
        val currentSamples = sampleBuffers
        if (currentTone != state.lastTone ||
            currentAccent != state.lastAccent ||
            currentRest != state.lastRest ||
            currentSamples !== state.lastSamples
        ) {
            val resolved = resolveCachedClicks(
                currentTone,
                currentAccent,
                currentRest,
                currentSamples,
            )
            state.cachedAccent = resolved.first
            state.cachedNormal = resolved.second
            state.cachedSubdivision = resolved.third
            state.lastTone = currentTone
            state.lastAccent = currentAccent
            state.lastRest = currentRest
            state.lastSamples = currentSamples
        }
    }

    /** Fill [chunk] with mixed PCM; appends beat pulses to [state.pendingBeats]. */
    fun fill(chunk: ShortArray, state: LoopState) {
        ensureCaches(state)
        val sampleRate = ClickSynthesizer.SAMPLE_RATE
        val pitchAccent = state.lastAccent.hz != null

        for (i in chunk.indices) {
            val frame = state.framesRendered + i
            var sample = 0

            if (frame >= state.nextClickFrame) {
                val pulses = subdivision.pulsesPerBeat.coerceAtLeast(1)
                val pulseJustPlayed = state.pulseInBeat
                val accents = beatAccents
                val level = accents.getOrElse(state.beatInBar) { if (state.beatInBar == 0) 2 else 1 }
                val beatMuted = level == 0
                val isAccent = !beatMuted && level >= 2 && state.pulseInBeat == 0
                val isBeatPulse = state.pulseInBeat == 0
                state.activeClick = when {
                    isAccent -> state.cachedAccent
                    isBeatPulse -> state.cachedNormal
                    else -> state.cachedSubdivision
                }
                state.activeClickIndex = 0
                state.activeGain = if (muted || !clicksEnabled || beatMuted) {
                    0f
                } else {
                    val base = volume * OUTPUT_GAIN
                    when {
                        isAccent && pitchAccent -> base
                        isAccent -> base * 0.95f
                        isBeatPulse -> base * 0.85f
                        else -> base * 0.32f
                    }
                }

                if (isBeatPulse) {
                    state.pendingBeats.addLast(
                        PendingBeat(
                            frame = frame,
                            beatIndex = state.beatInBar,
                            pulseIndex = state.pulseInBeat,
                            isAccent = isAccent,
                        )
                    )
                }

                state.pulseInBeat++
                if (state.pulseInBeat >= pulses) {
                    state.pulseInBeat = 0
                    state.beatInBar = (state.beatInBar + 1) % beatsPerBar
                }

                state.nextClickFrame += framesUntilNextPulse(sampleRate, pulseJustPlayed, pulses)
            }

            val click = state.activeClick
            if (click != null && state.activeClickIndex < click.size && state.activeGain > 0f) {
                sample = (click[state.activeClickIndex] * state.activeGain).toInt()
                state.activeClickIndex++
                if (state.activeClickIndex >= click.size) {
                    state.activeClick = null
                }
            }

            chunk[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    fun scheduleAudibleBeats(
        sink: AudioSink,
        clock: UiClock,
        latencyPad: LatencyPad,
        sampleRate: Int,
        framesWritten: Long,
        pending: ArrayDeque<PendingBeat>,
        isPlaying: () -> Boolean,
        onBeat: (BeatEvent) -> Unit,
    ) {
        if (pending.isEmpty()) return
        val head = sink.playbackHeadFrames().coerceAtMost(framesWritten)
        val now = clock.nowMs()
        val beatMs = (60_000L / bpm.coerceIn(MetronomeLimits.MIN_BPM, MetronomeLimits.MAX_BPM))
            .coerceAtLeast(80L)
        val outputPadMs = latencyPad.padMs(sink.routeHint(), 0)
        val tempoCapMs = (beatMs * 3L / 4L).coerceIn(40L, 350L)
        val maxDelayMs = max(tempoCapMs, outputPadMs + 80L).coerceAtMost(450L)

        while (pending.isNotEmpty() && pending.first().frame <= framesWritten) {
            val beat = pending.removeFirst()
            val framesUntilHear = (beat.frame - head).coerceAtLeast(0L)
            val delayMs = (framesUntilHear * 1000L / sampleRate + outputPadMs)
                .coerceIn(0L, maxDelayMs)

            val hearAt = now + delayMs
            val event = BeatEvent(
                beatIndex = beat.beatIndex,
                pulseIndex = beat.pulseIndex,
                isAccent = beat.isAccent,
                timestampMs = hearAt,
            )
            clock.postAt(hearAt) {
                if (!isPlaying()) return@postAt
                val late = clock.nowMs() - hearAt
                if (late > maxDelayMs) return@postAt
                onBeat(event)
            }
        }
    }

    private fun framesPerBeatUnit(sampleRate: Int): Long {
        val currentBpm = bpm.coerceIn(MetronomeLimits.MIN_BPM, MetronomeLimits.MAX_BPM).toDouble()
        var seconds = 60.0 / currentBpm
        if (groupTempo && noteValue == 8 && beatsPerBar % 3 == 0) {
            seconds /= 3.0
        }
        return (sampleRate.toDouble() * seconds).roundToLong().coerceAtLeast(1L)
    }

    private fun framesUntilNextPulse(sampleRate: Int, pulseJustPlayed: Int, pulses: Int): Long {
        val beat = framesPerBeatUnit(sampleRate)
        val ratio = swing.longRatio.coerceIn(0.5f, 0.8f)
        if (pulses <= 1 || ratio <= 0.501f) {
            return (beat.toDouble() / pulses).roundToLong().coerceAtLeast(1L)
        }
        return when (pulses) {
            2 -> {
                val long = (beat * ratio).roundToLong().coerceAtLeast(1L)
                val short = (beat - long).coerceAtLeast(1L)
                if (pulseJustPlayed == 0) long else short
            }
            4 -> {
                val half = beat / 2.0
                val long = (half * ratio).roundToLong().coerceAtLeast(1L)
                val short = (half - long).roundToLong().coerceAtLeast(1L)
                if (pulseJustPlayed % 2 == 0) long else short
            }
            else -> (beat.toDouble() / pulses).roundToLong().coerceAtLeast(1L)
        }
    }

    private fun resolveCachedClicks(
        currentTone: MetronomeTone,
        currentAccent: AccentNote,
        currentRest: AccentNote,
        samples: CachedSampleBuffers?,
    ): Triple<ShortArray, ShortArray, ShortArray> {
        if (currentTone is MetronomeTone.Sample && samples != null) {
            val root = currentTone.tone.rootHz
            val strong = pitchShiftedSample(samples.strong, root, currentAccent)
            val normal = pitchShiftedSample(samples.normal, root, currentRest)
            val subdivisionPcm = pitchShiftedSample(
                source = samples.ghost ?: samples.normal,
                rootHz = root,
                note = currentRest,
            )
            return Triple(strong, normal, subdivisionPcm)
        }
        val synth = (currentTone as? MetronomeTone.Synth)?.tone ?: ClickTone.WOOD
        val accentPcm = ClickSynthesizer.generate(
            synth,
            accent = true,
            accentNote = currentAccent,
            restNote = currentRest,
        )
        val normalPcm = ClickSynthesizer.generate(
            synth,
            accent = false,
            accentNote = currentAccent,
            restNote = currentRest,
        )
        return Triple(accentPcm, normalPcm, normalPcm)
    }

    private fun pitchShiftedSample(
        source: ShortArray,
        rootHz: Double?,
        note: AccentNote,
    ): ShortArray {
        if (rootHz == null || note.hz == null) return source
        val target = PcmPitch.sampleNoteHz(note, rootHz)
        return PcmPitch.shift(source, baseHz = rootHz, targetHz = target)
    }

    companion object {
        const val OUTPUT_GAIN = 1.35f
        const val PREROLL_MS = 80L
        const val BYTES_PER_SAMPLE = 2
    }
}
