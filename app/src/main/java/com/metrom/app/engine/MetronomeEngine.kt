package com.metrom.app.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToLong

enum class Subdivision(val pulsesPerBeat: Int, val label: String) {
    /** One click per beat. */
    QUARTER(1, "×1"),
    /** Two clicks per beat (eighths). */
    EIGHTH(2, "×2"),
    /** Three clicks per beat (triplets). */
    TRIPLET(3, "×3"),
    /** Four clicks per beat (sixteenths). */
    SIXTEENTH(4, "×4")
}

/** Delay the off-beat in ×2/×4 grids. [longRatio] is the first share of each pair. */
enum class SwingFeel(val label: String, val longRatio: Float) {
    OFF("Off", 0.50f),
    LIGHT("Light", 0.58f),
    MED("Med", 0.67f),
    HEAVY("Heavy", 0.75f)
}

/** Per-beat click weight — tap the beat rail to cycle. */
enum class BeatAccent(val code: Char) {
    MUTE('M'),
    NORMAL('N'),
    STRONG('S');

    fun next(): BeatAccent = when (this) {
        STRONG -> NORMAL
        NORMAL -> MUTE
        MUTE -> STRONG
    }

    companion object {
        fun defaultPattern(beats: Int, noteValue: Int = 4): List<BeatAccent> {
            val n = beats.coerceIn(1, 16)
            // Compound meters (x/8 in groups of 3): accent every dotted beat
            if (noteValue == 8 && n % 3 == 0) {
                return List(n) { if (it % 3 == 0) STRONG else NORMAL }
            }
            return List(n) { if (it == 0) STRONG else NORMAL }
        }

        fun encode(levels: List<BeatAccent>): String =
            levels.joinToString("") { it.code.toString() }

        fun decode(raw: String?, beats: Int, noteValue: Int = 4): List<BeatAccent> {
            val n = beats.coerceIn(1, 16)
            val defaults = defaultPattern(n, noteValue)
            if (raw.isNullOrBlank()) return defaults
            return List(n) { i ->
                when (raw.getOrNull(i)) {
                    'S' -> STRONG
                    'N' -> NORMAL
                    'M' -> MUTE
                    else -> defaults[i]
                }
            }
        }
    }
}

data class TimeSignature(val beats: Int, val noteValue: Int) {
    val label: String get() = "$beats/$noteValue"
    val isCompound: Boolean get() = noteValue == 8 && beats % 3 == 0

    companion object {
        val COMMON = listOf(
            TimeSignature(2, 4),
            TimeSignature(3, 4),
            TimeSignature(4, 4),
            TimeSignature(5, 4),
            TimeSignature(3, 8),
            TimeSignature(5, 8),
            TimeSignature(6, 8),
            TimeSignature(7, 8),
            TimeSignature(9, 8),
            TimeSignature(12, 8),
        )
    }
}

data class BeatEvent(
    val beatIndex: Int,
    val pulseIndex: Int,
    val isAccent: Boolean,
    val timestampMs: Long
)

/**
 * Stable, sample-accurate metronome.
 *
 * Clicks carry across buffer boundaries. UI/haptics are scheduled from the
 * measured audio queue depth after each write, so the rail moves when the
 * click is heard — not when it is rendered into a buffer.
 *
 * [audioManager] is used only to size the *visual* output-latency pad
 * ([VisualOutputLatency]); it never changes when PCM is written.
 */
class MetronomeEngine(
    private val audioManager: AudioManager? = null,
    private val appContext: Context? = null,
    private val onBeat: (BeatEvent) -> Unit = {}
) {
    private val playing = AtomicBoolean(false)
    private val bpm = AtomicInteger(120)
    private val beatsPerBar = AtomicInteger(4)
    private val noteValue = AtomicInteger(4)
    /** When true on compound x/8, BPM is the dotted feel (3 rail pulses per beat). */
    private val groupTempo = AtomicBoolean(false)
    private val subdivision = AtomicReference(Subdivision.QUARTER)
    private val swing = AtomicReference(SwingFeel.OFF)
    private val tone = AtomicReference<MetronomeTone>(MetronomeTone.DEFAULT)
    /**
     * Pre-decoded sample PCM for the active sample tone.
     * Set in [setTone] only — the audio loop never loads or decodes.
     */
    private val sampleBuffers = AtomicReference<CachedSampleBuffers?>(null)
    private val accentNote = AtomicReference(AccentNote.DEFAULT)
    /** 0=MUTE, 1=NORMAL, 2=STRONG — length matches beatsPerBar. */
    private val beatAccents = AtomicReference(intArrayOf(2, 1, 1, 1))
    private val volume = AtomicReference(1f)
    private val muted = AtomicBoolean(false)
    /** Practice gate (count-in / mute-bars). Independent from user mute. */
    private val clicksEnabled = AtomicBoolean(true)

    private val mainHandler = Handler(Looper.getMainLooper())
    /** Serializes start/stop/release so rapid toggles cannot arm two audio threads. */
    private val lifecycleLock = Any()
    private val previewGeneration = AtomicInteger(0)

    @Volatile
    private var audioThread: Thread? = null

    @Volatile
    private var track: AudioTrack? = null

    fun setBpm(value: Int) {
        bpm.set(value.coerceIn(MIN_BPM, MAX_BPM))
    }

    fun setTimeSignature(signature: TimeSignature) {
        val beats = signature.beats.coerceIn(1, 16)
        beatsPerBar.set(beats)
        noteValue.set(signature.noteValue.coerceIn(1, 16))
        val current = beatAccents.get()
        if (current.size != beats) {
            beatAccents.set(
                IntArray(beats) { i ->
                    current.getOrElse(i) { if (i == 0) 2 else 1 }
                }
            )
        }
        if (!signature.isCompound) {
            groupTempo.set(false)
        }
    }

    fun setGroupTempo(enabled: Boolean) {
        groupTempo.set(enabled && noteValue.get() == 8 && beatsPerBar.get() % 3 == 0)
    }

    fun setBeatAccents(levels: List<BeatAccent>) {
        val beats = beatsPerBar.get().coerceIn(1, 16)
        beatAccents.set(
            IntArray(beats) { i ->
                when (levels.getOrElse(i) { if (i == 0) BeatAccent.STRONG else BeatAccent.NORMAL }) {
                    BeatAccent.MUTE -> 0
                    BeatAccent.NORMAL -> 1
                    BeatAccent.STRONG -> 2
                }
            }
        )
    }

    fun setSubdivision(value: Subdivision) {
        subdivision.set(value)
    }

    fun setSwing(value: SwingFeel) {
        swing.set(value)
    }

    /** Legacy synth-only entry point — kept until UI selects [MetronomeTone]. */
    fun setTone(value: ClickTone) {
        setTone(MetronomeTone.Synth(value))
    }

    /**
     * Select the active tone. Sample tones are decoded here (or pulled from
     * [SampleToneCache]); the audio loop only swaps already-cached ShortArrays.
     * Invalid/missing samples fall back to [MetronomeTone.DEFAULT].
     * @return the tone actually applied
     */
    fun setTone(value: MetronomeTone): MetronomeTone {
        when (value) {
            is MetronomeTone.Synth -> {
                sampleBuffers.set(null)
                tone.set(value)
                return value
            }
            is MetronomeTone.Sample -> {
                val ctx = appContext?.applicationContext
                val buffers = if (ctx != null) {
                    SampleToneCache.get(ctx, value.tone)
                } else {
                    SampleToneCache.peek(value.tone.id)
                }
                if (buffers == null) {
                    Log.w(TAG, "Sample tone '${value.tone.id}' unavailable — falling back to ${MetronomeTone.DEFAULT.id}")
                    sampleBuffers.set(null)
                    tone.set(MetronomeTone.DEFAULT)
                    return MetronomeTone.DEFAULT
                }
                sampleBuffers.set(buffers)
                tone.set(value)
                return value
            }
        }
    }

    fun setAccentNote(value: AccentNote) {
        accentNote.set(value)
    }

    fun setVolume(value: Float) {
        volume.set(value.coerceIn(0f, 1f))
    }

    fun setMuted(value: Boolean) {
        muted.set(value)
    }

    fun setClicksEnabled(value: Boolean) {
        clicksEnabled.set(value)
    }

    fun isPlaying(): Boolean = playing.get() && audioThread?.isAlive == true

    /**
     * Start the audio thread. Safe under rapid toggle: if a previous loop is still
     * draining, it is joined before a new thread is armed. [playing] CAS prevents
     * two live loops; [lifecycleLock] serializes start/stop/release.
     */
    fun start() {
        synchronized(lifecycleLock) {
            if (playing.get() && audioThread?.isAlive == true) return
            ensureStoppedLocked()
            if (!playing.compareAndSet(false, true)) return
            clicksEnabled.set(true)
            // Use Android audio priority inside runLoop — not Thread.MAX_PRIORITY,
            // which can starve system_server on emulators left playing overnight.
            val thread = Thread({
                try {
                    runLoop()
                } finally {
                    // Write errors / natural exit — clear flag so a later start can arm.
                    playing.set(false)
                }
            }, "metrom-audio")
            audioThread = thread
            thread.start()
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            ensureStoppedLocked()
            clicksEnabled.set(true)
        }
    }

    fun release() {
        synchronized(lifecycleLock) {
            previewGeneration.incrementAndGet()
            ensureStoppedLocked()
            clicksEnabled.set(true)
            mainHandler.removeCallbacksAndMessages(null)
        }
    }

    /** One-shot preview of the current tone (works while stopped or playing). */
    fun previewClick(accent: Boolean = true) {
        val gen = previewGeneration.incrementAndGet()
        Thread({
            var preview: AudioTrack? = null
            try {
                val pcm = resolvePreviewPcm(accent)
                val bytes = pcm.size * BYTES_PER_SAMPLE
                preview = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(ClickSynthesizer.SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(bytes)
                    .build()
                if (gen != previewGeneration.get()) return@Thread
                preview.setVolume(volume.get().coerceIn(0.2f, 1f))
                preview.write(pcm, 0, pcm.size)
                preview.play()
                Thread.sleep((pcm.size * 1000L / ClickSynthesizer.SAMPLE_RATE) + 40L)
            } catch (_: Exception) {
            } finally {
                try {
                    preview?.stop()
                } catch (_: Exception) {
                }
                try {
                    preview?.release()
                } catch (_: Exception) {
                }
            }
        }, "metrom-preview").start()
    }

    /**
     * Must hold [lifecycleLock]. Signals the loop to exit, joins the audio thread,
     * clears beat Handler callbacks, and releases any orphaned [AudioTrack].
     */
    private fun ensureStoppedLocked() {
        playing.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        val thread = audioThread
        if (thread != null) {
            joinAudioThread(thread)
            if (audioThread === thread) {
                audioThread = null
            }
        }
        // runLoop's finally releases the track on a clean join; this covers orphans
        // if the thread never started or timed out after a failed write teardown.
        if (track != null) {
            releaseTrack()
        }
    }

    private fun joinAudioThread(thread: Thread) {
        try {
            thread.join(JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) {
            // One more buffer's worth — WRITE_BLOCKING can outlive the first join.
            try {
                thread.join(JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val sampleRate = ClickSynthesizer.SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val writeFrames = max(minBuf, sampleRate / 10) // ~100ms chunks
        val trackBufferFrames = (writeFrames * 2).toLong() // ~200ms queued max
        val trackBufferBytes = (trackBufferFrames * BYTES_PER_SAMPLE).toInt()
        val chunk = ShortArray(writeFrames)

        val localTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(trackBufferBytes)
            // NONE = stable queue depth reporting (LOW_LATENCY drifts on many devices/emulators).
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
            .build()

        track = localTrack
        localTrack.setVolume(1f)
        localTrack.play()

        var framesRendered = 0L
        var nextClickFrame = sampleRate * PREROLL_MS / 1000L
        var beatInBar = 0
        var pulseInBeat = 0

        var lastTone = tone.get()
        var lastAccent = accentNote.get()
        var lastSamples = sampleBuffers.get()
        var (cachedAccent, cachedNormal, cachedSubdivision) =
            resolveCachedClicks(lastTone, lastAccent, lastSamples)

        var activeClick: ShortArray? = null
        var activeClickIndex = 0
        var activeGain = 0f

        val pendingBeats = ArrayDeque<PendingBeat>()

        try {
            while (playing.get()) {
                val currentTone = tone.get()
                val currentAccent = accentNote.get()
                val currentSamples = sampleBuffers.get()
                if (currentTone != lastTone ||
                    currentAccent != lastAccent ||
                    currentSamples !== lastSamples
                ) {
                    val resolved = resolveCachedClicks(currentTone, currentAccent, currentSamples)
                    cachedAccent = resolved.first
                    cachedNormal = resolved.second
                    cachedSubdivision = resolved.third
                    lastTone = currentTone
                    lastAccent = currentAccent
                    lastSamples = currentSamples
                }

                val pitchAccent = currentAccent.hz != null

                for (i in chunk.indices) {
                    val frame = framesRendered + i
                    var sample = 0

                    if (frame >= nextClickFrame) {
                        val pulses = subdivision.get().pulsesPerBeat.coerceAtLeast(1)
                        val pulseJustPlayed = pulseInBeat
                        val accents = beatAccents.get()
                        val level = accents.getOrElse(beatInBar) { if (beatInBar == 0) 2 else 1 }
                        val beatMuted = level == 0
                        val isAccent = !beatMuted && level >= 2 && pulseInBeat == 0
                        val isBeatPulse = pulseInBeat == 0
                        activeClick = when {
                            isAccent -> cachedAccent
                            isBeatPulse -> cachedNormal
                            else -> cachedSubdivision
                        }
                        activeClickIndex = 0
                        activeGain = if (muted.get() || !clicksEnabled.get() || beatMuted) {
                            0f
                        } else {
                            val base = volume.get() * OUTPUT_GAIN
                            when {
                                isAccent && pitchAccent -> base
                                isAccent -> base * 0.95f
                                isBeatPulse -> base * 0.85f
                                // In-between subdivisions — quieter so ×2 doesn't feel like 2× tempo
                                else -> base * 0.32f
                            }
                        }

                        if (isBeatPulse) {
                            pendingBeats.addLast(
                                PendingBeat(
                                    frame = frame,
                                    beatIndex = beatInBar,
                                    pulseIndex = pulseInBeat,
                                    isAccent = isAccent
                                )
                            )
                        }

                        pulseInBeat++
                        if (pulseInBeat >= pulses) {
                            pulseInBeat = 0
                            beatInBar = (beatInBar + 1) % beatsPerBar.get()
                        }

                        nextClickFrame += framesUntilNextPulse(sampleRate, pulseJustPlayed, pulses)
                    }

                    val click = activeClick
                    if (click != null && activeClickIndex < click.size && activeGain > 0f) {
                        sample = (click[activeClickIndex] * activeGain).toInt()
                        activeClickIndex++
                        if (activeClickIndex >= click.size) {
                            activeClick = null
                        }
                    }

                    chunk[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }

                var offset = 0
                while (offset < chunk.size && playing.get()) {
                    val result = localTrack.write(
                        chunk,
                        offset,
                        chunk.size - offset,
                        AudioTrack.WRITE_BLOCKING
                    )
                    if (result < 0) {
                        playing.set(false)
                        break
                    }
                    if (result == 0) {
                        Thread.sleep(1)
                        continue
                    }
                    offset += result
                }
                framesRendered += offset

                // Only schedule UI after samples are in the AudioTrack queue
                scheduleAudibleBeats(
                    track = localTrack,
                    sampleRate = sampleRate,
                    framesWritten = framesRendered,
                    bufferFrames = trackBufferFrames,
                    pending = pendingBeats
                )
            }
        } finally {
            pendingBeats.clear()
            mainHandler.removeCallbacksAndMessages(null)
            try {
                localTrack.pause()
                localTrack.flush()
                localTrack.stop()
            } catch (_: Exception) {
            }
            localTrack.release()
            if (track === localTrack) track = null
        }
    }

    /**
     * Schedule UI for when each beat's PCM should leave the speaker.
     * Uses playback-head queue depth only — hardware timestamps are too noisy
     * on emulators and cause early/late flashes.
     *
     * Output-route pad ([VisualOutputLatency]) is added here for visuals only.
     * Sample-frame click placement in [runLoop] is unchanged.
     */
    private fun scheduleAudibleBeats(
        track: AudioTrack,
        sampleRate: Int,
        framesWritten: Long,
        bufferFrames: Long,
        pending: ArrayDeque<PendingBeat>
    ) {
        if (pending.isEmpty()) return
        val head = Integer.toUnsignedLong(track.playbackHeadPosition).coerceAtMost(framesWritten)
        val now = SystemClock.uptimeMillis()
        val beatMs = (60_000L / bpm.get().coerceIn(MIN_BPM, MAX_BPM)).coerceAtLeast(80L)
        // Route-aware pad (Bluetooth ~170ms). Re-queried every buffer so a mid-
        // session route change (earbuds connect, plug headphones) updates the
        // next flash without touching audio write timing.
        val outputPadMs = VisualOutputLatency.padMs(track, audioManager)
        // Tempo cap avoids flashes associating with the wrong click; Bluetooth
        // still needs headroom so the pad is not truncated at high BPM.
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
                timestampMs = hearAt
            )
            // Skip flash if the main looper is already behind — avoids unbounded
            // Handler backlog when the UI/emulator stalls overnight.
            mainHandler.postAtTime({
                if (!playing.get()) return@postAtTime
                val late = SystemClock.uptimeMillis() - hearAt
                if (late > maxDelayMs) return@postAtTime
                onBeat(event)
            }, hearAt)
        }
    }

    /** One numbered rail beat (may be an eighth under compound group tempo). */
    private fun framesPerBeatUnit(sampleRate: Int): Long {
        val currentBpm = bpm.get().coerceIn(MIN_BPM, MAX_BPM).toDouble()
        var seconds = 60.0 / currentBpm
        if (groupTempo.get() && noteValue.get() == 8 && beatsPerBar.get() % 3 == 0) {
            // BPM = dotted feel; each rail slot is one subdivision of that feel.
            seconds /= 3.0
        }
        return (sampleRate.toDouble() * seconds).roundToLong().coerceAtLeast(1L)
    }

    /**
     * Gap after [pulseJustPlayed] until the next click.
     * Swing stretches the first half of each ×2/×4 pair.
     */
    private fun framesUntilNextPulse(sampleRate: Int, pulseJustPlayed: Int, pulses: Int): Long {
        val beat = framesPerBeatUnit(sampleRate)
        val ratio = swing.get().longRatio.coerceIn(0.5f, 0.8f)
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

    private fun releaseTrack() {
        try {
            track?.pause()
            track?.flush()
            track?.stop()
        } catch (_: Exception) {
        }
        try {
            track?.release()
        } catch (_: Exception) {
        }
        track = null
    }

    /**
     * Build the three click caches for the active tone.
     * Sample path: uses pre-decoded buffers only (no I/O / no decode).
     * Synth path: [ClickSynthesizer.generate] as before.
     */
    private fun resolveCachedClicks(
        currentTone: MetronomeTone,
        currentAccent: AccentNote,
        samples: CachedSampleBuffers?,
    ): Triple<ShortArray, ShortArray, ShortArray> {
        if (currentTone is MetronomeTone.Sample && samples != null) {
            val subdivision = samples.ghost ?: samples.normal
            return Triple(samples.strong, samples.normal, subdivision)
        }
        val synth = (currentTone as? MetronomeTone.Synth)?.tone ?: ClickTone.WOOD
        val accentPcm = ClickSynthesizer.generate(synth, accent = true, currentAccent)
        val normalPcm = ClickSynthesizer.generate(synth, accent = false, currentAccent)
        return Triple(accentPcm, normalPcm, normalPcm)
    }

    private fun resolvePreviewPcm(accent: Boolean): ShortArray {
        return when (val current = tone.get()) {
            is MetronomeTone.Sample -> {
                val buffers = sampleBuffers.get()
                if (buffers != null) {
                    if (accent) buffers.strong else buffers.normal
                } else {
                    ClickSynthesizer.generate(ClickTone.WOOD, accent, accentNote.get())
                }
            }
            is MetronomeTone.Synth ->
                ClickSynthesizer.generate(current.tone, accent, accentNote.get())
        }
    }

    private data class PendingBeat(
        val frame: Long,
        val beatIndex: Int,
        val pulseIndex: Int,
        val isAccent: Boolean
    )

    companion object {
        private const val TAG = "MetronomeEngine"
        const val MIN_BPM = 30
        const val MAX_BPM = 300
        private const val OUTPUT_GAIN = 1.35f
        private const val PREROLL_MS = 80L
        private const val BYTES_PER_SAMPLE = 2 // 16-bit mono
        /** Max wait per join attempt for the audio thread to leave runLoop. */
        private const val JOIN_TIMEOUT_MS = 1_500L
    }
}
