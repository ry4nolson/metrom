package com.metrom.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.metrom.app.data.SongPreset
import com.metrom.app.data.SongStore
import com.metrom.app.engine.AccentNote
import com.metrom.app.engine.BeatAccent
import com.metrom.app.engine.BeatEvent
import com.metrom.app.engine.ClickTone
import com.metrom.app.engine.MetronomeEngine
import com.metrom.app.engine.Subdivision
import com.metrom.app.engine.SwingFeel
import com.metrom.app.engine.TimeSignature
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SessionPhase {
    IDLE,
    COUNT_IN,
    PLAYING,
    SILENT,
    TRAINER_DONE
}

data class MutePattern(val playBars: Int, val silentBars: Int) {
    val label: String
        get() = if (silentBars == 0) "Off" else "$playBars+$silentBars"

    companion object {
        val OFF = MutePattern(1, 0)
        val OPTIONS = listOf(
            OFF,
            MutePattern(1, 1),
            MutePattern(2, 2),
            MutePattern(4, 2),
            MutePattern(4, 4)
        )
    }
}

data class MetronomeUiState(
    val bpm: Int = 120,
    val isPlaying: Boolean = false,
    val timeSignature: TimeSignature = TimeSignature(4, 4),
    val subdivision: Subdivision = Subdivision.QUARTER,
    val swing: SwingFeel = SwingFeel.OFF,
    /** Compound meters: BPM counts the dotted feel (3 rail pulses). */
    val groupTempo: Boolean = false,
    val tone: ClickTone = ClickTone.WOOD,
    val accentNote: AccentNote = AccentNote.DEFAULT,
    val beatAccents: List<BeatAccent> = BeatAccent.defaultPattern(4, 4),
    val volume: Float = 1f,
    val muted: Boolean = false,
    val haptics: Boolean = true,
    val activeBeat: Int = -1,
    val beatFlash: Long = 0L,
    /** Uptime millis when this beat should be heard (for visual lock). */
    val beatAtMs: Long = 0L,
    val isAccentBeat: Boolean = false,
    val tapHint: String? = null,
    // Practice
    val countInBars: Int = 1,
    val mutePattern: MutePattern = MutePattern.OFF,
    val trainerEnabled: Boolean = false,
    val trainerStartBpm: Int = 80,
    val trainerTargetBpm: Int = 120,
    val trainerStep: Int = 2,
    val trainerEveryBars: Int = 4,
    val trainerAutoStop: Boolean = true,
    val sessionPhase: SessionPhase = SessionPhase.IDLE,
    val sessionBar: Int = 0,
    val statusLine: String = "READY",
    // Songs
    val songs: List<SongPreset> = emptyList(),
    val activeSongId: String? = null
)

class MetronomeViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("metrom", Context.MODE_PRIVATE)
    private val songStore = SongStore(prefs)
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    /** True after transient focus loss — resume on [AudioManager.AUDIOFOCUS_GAIN]. */
    private var resumeAfterFocusGain = false
    private var noisyReceiverRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // Focus callbacks are not guaranteed on the main thread.
        mainHandler.post {
            when (change) {
                // Call / another media app took over — stop cleanly, do not auto-resume.
                AudioManager.AUDIOFOCUS_LOSS -> {
                    resumeAfterFocusGain = false
                    if (_state.value.isPlaying) stop()
                }
                // Notification, nav prompt, etc. — pause engine, keep focus request, resume on gain.
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    if (_state.value.isPlaying) pauseForTransientFocus()
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (resumeAfterFocusGain && !_state.value.isPlaying) {
                        resumeAfterFocusGain = false
                        start()
                    }
                }
            }
        }
    }

    /**
     * Headphones unplugged / BT audio route gone — stop so clicks don't jump to the speaker.
     */
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            mainHandler.post {
                resumeAfterFocusGain = false
                if (_state.value.isPlaying) stop()
            }
        }
    }

    private val _state = MutableStateFlow(
        run {
            val beats = prefs.getInt("beats", 4)
            val noteValue = prefs.getInt("noteValue", 4)
            MetronomeUiState(
                bpm = prefs.getInt("bpm", 120),
                volume = prefs.getFloat("volume", 0.9f).coerceIn(0f, 1f),
                muted = prefs.getBoolean("muted", false),
                haptics = prefs.getBoolean("haptics", true),
                tone = ClickTone.entries.getOrElse(prefs.getInt("tone", 0)) { ClickTone.WOOD },
                accentNote = AccentNote.entries.getOrElse(prefs.getInt("accentNote", AccentNote.DEFAULT.ordinal)) {
                    AccentNote.DEFAULT
                },
                subdivision = Subdivision.entries.getOrElse(prefs.getInt("subdivision", 0)) { Subdivision.QUARTER }
                    .let { stored ->
                        if (!prefs.contains("subdivision")) Subdivision.QUARTER else stored
                    },
                swing = SwingFeel.entries.getOrElse(prefs.getInt("swing", 0)) { SwingFeel.OFF },
                groupTempo = prefs.getBoolean("groupTempo", false) &&
                    noteValue == 8 && beats % 3 == 0,
                timeSignature = TimeSignature(beats = beats, noteValue = noteValue),
                beatAccents = BeatAccent.decode(
                    raw = prefs.getString("beatAccents", null),
                    beats = beats,
                    noteValue = noteValue
                ),
                countInBars = prefs.getInt("countInBars", 1),
                mutePattern = MutePattern(
                    playBars = prefs.getInt("mutePlayBars", 1),
                    silentBars = prefs.getInt("muteSilentBars", 0)
                ),
                trainerEnabled = prefs.getBoolean("trainerEnabled", false),
                trainerStartBpm = prefs.getInt("trainerStartBpm", 80),
                trainerTargetBpm = prefs.getInt("trainerTargetBpm", 120),
                trainerStep = prefs.getInt("trainerStep", 2),
                trainerEveryBars = prefs.getInt("trainerEveryBars", 4),
                trainerAutoStop = prefs.getBoolean("trainerAutoStop", true),
                songs = songStore.load().dedupeSongs()
            )
        }
    )
    val state: StateFlow<MetronomeUiState> = _state.asStateFlow()

    private val tapTimes = ArrayDeque<Long>()
    private var lastBeatIndex = -1
    private var barIndex = 0
    private var beatSerial = 0L

    private val engine = MetronomeEngine(
        audioManager = audioManager,
        onBeat = { event -> onBeat(event) }
    ).also { eng ->
        // Persist cleaned song list if duplicates were collapsed on load
        val songs = _state.value.songs
        if (songs.size != songStore.load().size) {
            songStore.saveAll(songs)
        }
        val s = _state.value
        eng.setBpm(s.bpm)
        eng.setTimeSignature(s.timeSignature)
        eng.setBeatAccents(s.beatAccents)
        eng.setSubdivision(s.subdivision)
        eng.setSwing(s.swing)
        eng.setGroupTempo(s.groupTempo)
        eng.setTone(s.tone)
        eng.setAccentNote(s.accentNote)
        eng.setVolume(s.volume)
        eng.setMuted(s.muted)
    }

    init {
        PlaybackBridge.onToggle = { togglePlay() }
        PlaybackBridge.onStop = { stop() }
        publishPlayback()
    }

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = application.getSystemService(VibratorManager::class.java)
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        application.getSystemService(Vibrator::class.java)
    }

    fun togglePlay() {
        if (_state.value.isPlaying || engine.isPlaying()) stop() else start()
    }

    fun start() {
        // Already running — keep a single engine session (rotation / duplicate calls).
        if (engine.isPlaying()) {
            resumeAfterFocusGain = false
            registerBecomingNoisy()
            if (!_state.value.isPlaying) {
                _state.update {
                    it.copy(
                        isPlaying = true,
                        statusLine = statusFor(
                            it,
                            it.sessionBar,
                            if (it.sessionPhase == SessionPhase.IDLE) SessionPhase.PLAYING else it.sessionPhase
                        )
                    )
                }
                publishPlayback()
            }
            return
        }

        resumeAfterFocusGain = false
        if (!requestAudioFocus()) {
            _state.update { it.copy(tapHint = "AUDIO BUSY") }
            return
        }
        lastBeatIndex = -1
        barIndex = 0

        val s = _state.value
        if (s.trainerEnabled) {
            // Keep current tempo as start; target may be above or below.
            val startBpm = s.bpm
            engine.setBpm(startBpm)
            _state.update {
                it.copy(
                    trainerStartBpm = startBpm,
                    tapHint = "TRAIN $startBpm→${s.trainerTargetBpm}"
                )
            }
            prefs.edit().putInt("trainerStartBpm", startBpm).apply()
        }

        applyBarGate(0)
        engine.start()
        registerBecomingNoisy()
        _state.update {
            it.copy(
                isPlaying = true,
                activeBeat = -1,
                sessionBar = 0,
                sessionPhase = if (it.countInBars > 0) SessionPhase.COUNT_IN else SessionPhase.PLAYING,
                statusLine = statusFor(it, 0, if (it.countInBars > 0) SessionPhase.COUNT_IN else SessionPhase.PLAYING)
            )
        }
        publishPlayback()
    }

    fun stop() {
        resumeAfterFocusGain = false
        engine.stop()
        unregisterBecomingNoisy()
        abandonAudioFocus()
        lastBeatIndex = -1
        barIndex = 0
        _state.update {
            it.copy(
                isPlaying = false,
                activeBeat = -1,
                sessionBar = 0,
                sessionPhase = SessionPhase.IDLE,
                statusLine = "READY",
                tapHint = null
            )
        }
        publishPlayback()
    }

    /** Pause for a transient focus loss without abandoning focus (so we can resume on gain). */
    private fun pauseForTransientFocus() {
        resumeAfterFocusGain = true
        engine.stop()
        unregisterBecomingNoisy()
        lastBeatIndex = -1
        _state.update {
            it.copy(
                isPlaying = false,
                activeBeat = -1,
                statusLine = "PAUSED",
                tapHint = "AUDIO PAUSED"
            )
        }
        publishPlayback()
    }

    fun setBpm(bpm: Int, persist: Boolean = true) {
        val value = bpm.coerceIn(MetronomeEngine.MIN_BPM, MetronomeEngine.MAX_BPM)
        engine.setBpm(value)
        _state.update { it.copy(bpm = value, tapHint = null) }
        if (persist) prefs.edit().putInt("bpm", value).apply()
        publishPlayback()
    }

    fun nudgeBpm(delta: Int) = setBpm(_state.value.bpm + delta)

    fun setTimeSignature(signature: TimeSignature) {
        val accents = BeatAccent.defaultPattern(signature.beats, signature.noteValue)
        val group = _state.value.groupTempo && signature.isCompound
        engine.setTimeSignature(signature)
        engine.setBeatAccents(accents)
        engine.setGroupTempo(group)
        _state.update {
            it.copy(
                timeSignature = signature,
                beatAccents = accents,
                groupTempo = group,
                activeBeat = -1
            )
        }
        prefs.edit()
            .putInt("beats", signature.beats)
            .putInt("noteValue", signature.noteValue)
            .putBoolean("groupTempo", group)
            .putString("beatAccents", BeatAccent.encode(accents))
            .apply()
    }

    fun setSwing(feel: SwingFeel) {
        engine.setSwing(feel)
        _state.update { it.copy(swing = feel, tapHint = if (feel == SwingFeel.OFF) "STRAIGHT" else "SWING · ${feel.label.uppercase()}") }
        prefs.edit().putInt("swing", feel.ordinal).apply()
    }

    fun toggleGroupTempo() {
        val s = _state.value
        if (!s.timeSignature.isCompound) return
        val enabled = !s.groupTempo
        // Keep the audible pulse rate the same — only change what the BPM number means.
        // Pulse→Dotted: BPM becomes the dotted feel (÷3). Dotted→Pulse: BPM becomes each pulse (×3).
        val converted = if (enabled) {
            (s.bpm / 3).coerceIn(MetronomeEngine.MIN_BPM, MetronomeEngine.MAX_BPM)
        } else {
            (s.bpm * 3).coerceIn(MetronomeEngine.MIN_BPM, MetronomeEngine.MAX_BPM)
        }
        engine.setGroupTempo(enabled)
        engine.setBpm(converted)
        _state.update {
            it.copy(
                groupTempo = enabled,
                bpm = converted,
                tapHint = if (enabled) {
                    "DOTTED · $converted = felt beat"
                } else {
                    "PULSE · $converted = each click"
                }
            )
        }
        prefs.edit()
            .putBoolean("groupTempo", enabled)
            .putInt("bpm", converted)
            .apply()
        publishPlayback()
    }

    fun cycleBeatAccent(index: Int) {
        val s = _state.value
        if (index !in s.beatAccents.indices) return
        val next = s.beatAccents.toMutableList().also { it[index] = it[index].next() }
        engine.setBeatAccents(next)
        _state.update {
            it.copy(
                beatAccents = next,
                tapHint = when (next[index]) {
                    BeatAccent.STRONG -> "BEAT ${index + 1} · STRONG"
                    BeatAccent.NORMAL -> "BEAT ${index + 1} · NORMAL"
                    BeatAccent.MUTE -> "BEAT ${index + 1} · MUTE"
                }
            )
        }
        prefs.edit().putString("beatAccents", BeatAccent.encode(next)).apply()
        if (!s.isPlaying) {
            when (next[index]) {
                BeatAccent.MUTE -> { /* silent */ }
                BeatAccent.STRONG -> engine.previewClick(accent = true)
                BeatAccent.NORMAL -> engine.previewClick(accent = false)
            }
        }
    }

    fun resetBeatAccents() {
        val s = _state.value
        val accents = BeatAccent.defaultPattern(s.timeSignature.beats, s.timeSignature.noteValue)
        engine.setBeatAccents(accents)
        _state.update { it.copy(beatAccents = accents, tapHint = "ACCENTS RESET") }
        prefs.edit().putString("beatAccents", BeatAccent.encode(accents)).apply()
    }

    fun setSubdivision(subdivision: Subdivision) {
        engine.setSubdivision(subdivision)
        _state.update { it.copy(subdivision = subdivision) }
        prefs.edit().putInt("subdivision", subdivision.ordinal).apply()
    }

    fun setTone(tone: ClickTone, preview: Boolean = true) {
        engine.setTone(tone)
        _state.update { it.copy(tone = tone) }
        prefs.edit().putInt("tone", tone.ordinal).apply()
        if (preview && !_state.value.isPlaying) engine.previewClick(accent = true)
    }

    fun setAccentNote(note: AccentNote, preview: Boolean = true) {
        engine.setAccentNote(note)
        _state.update { it.copy(accentNote = note) }
        prefs.edit().putInt("accentNote", note.ordinal).apply()
        if (preview && !_state.value.isPlaying) engine.previewClick(accent = true)
    }

    fun previewTone() {
        engine.previewClick(accent = true)
    }

    fun setVolume(volume: Float) {
        val value = volume.coerceIn(0f, 1f)
        engine.setVolume(value)
        _state.update { it.copy(volume = value, muted = false) }
        engine.setMuted(false)
        prefs.edit().putFloat("volume", value).putBoolean("muted", false).apply()
    }

    fun toggleMute() {
        val muted = !_state.value.muted
        engine.setMuted(muted)
        _state.update { it.copy(muted = muted) }
        prefs.edit().putBoolean("muted", muted).apply()
    }

    fun toggleHaptics() {
        val enabled = !_state.value.haptics
        _state.update { it.copy(haptics = enabled) }
        prefs.edit().putBoolean("haptics", enabled).apply()
    }

    fun setCountInBars(bars: Int) {
        val value = bars.coerceIn(0, 4)
        _state.update { it.copy(countInBars = value) }
        prefs.edit().putInt("countInBars", value).apply()
    }

    fun setMutePattern(pattern: MutePattern) {
        _state.update { it.copy(mutePattern = pattern) }
        prefs.edit()
            .putInt("mutePlayBars", pattern.playBars)
            .putInt("muteSilentBars", pattern.silentBars)
            .apply()
    }

    fun toggleTrainer() {
        val enabled = !_state.value.trainerEnabled
        _state.update {
            it.copy(
                trainerEnabled = enabled,
                trainerStartBpm = if (enabled) it.bpm else it.trainerStartBpm
            )
        }
        prefs.edit()
            .putBoolean("trainerEnabled", enabled)
            .putInt("trainerStartBpm", _state.value.trainerStartBpm)
            .apply()
    }

    fun setTrainerTarget(bpm: Int) {
        val value = bpm.coerceIn(MetronomeEngine.MIN_BPM, MetronomeEngine.MAX_BPM)
        _state.update { it.copy(trainerTargetBpm = value) }
        prefs.edit().putInt("trainerTargetBpm", value).apply()
    }

    fun cycleTrainerTarget() {
        val s = _state.value
        // Cycle through common targets relative to current tempo
        val options = listOf(
            (s.bpm - 20).coerceAtLeast(MetronomeEngine.MIN_BPM),
            (s.bpm - 10).coerceAtLeast(MetronomeEngine.MIN_BPM),
            (s.bpm + 10).coerceAtMost(MetronomeEngine.MAX_BPM),
            (s.bpm + 20).coerceAtMost(MetronomeEngine.MAX_BPM),
            100, 120, 140, 160
        ).distinct().sorted()
        val idx = options.indexOf(s.trainerTargetBpm)
        val next = if (idx < 0) options.first() else options[(idx + 1) % options.size]
        setTrainerTarget(next)
    }

    fun setTrainerStep(step: Int) {
        val value = step.coerceIn(1, 10)
        _state.update { it.copy(trainerStep = value) }
        prefs.edit().putInt("trainerStep", value).apply()
    }

    fun setTrainerEveryBars(bars: Int) {
        val value = bars.coerceIn(1, 16)
        _state.update { it.copy(trainerEveryBars = value) }
        prefs.edit().putInt("trainerEveryBars", value).apply()
    }

    fun toggleTrainerAutoStop() {
        val enabled = !_state.value.trainerAutoStop
        _state.update { it.copy(trainerAutoStop = enabled) }
        prefs.edit().putBoolean("trainerAutoStop", enabled).apply()
    }

    fun saveCurrentSong(name: String? = null) {
        val s = _state.value
        val existing = s.songs.firstOrNull {
            it.sameSetupAs(
                s.bpm,
                s.timeSignature,
                s.subdivision,
                s.tone,
                s.accentNote,
                s.beatAccents,
                s.swing,
                s.groupTempo,
                s.countInBars,
                s.mutePattern
            )
        }
        if (existing != null) {
            _state.update {
                it.copy(activeSongId = existing.id, tapHint = "ALREADY SAVED")
            }
            return
        }
        val song = SongPreset(
            name = name?.trim().orEmpty().ifEmpty {
                SongPreset.autoName(s.bpm, s.timeSignature, s.subdivision)
            },
            bpm = s.bpm,
            timeSignature = s.timeSignature,
            subdivision = s.subdivision,
            tone = s.tone,
            accentNote = s.accentNote,
            beatAccents = s.beatAccents,
            swing = s.swing,
            groupTempo = s.groupTempo,
            countInBars = s.countInBars,
            mutePattern = s.mutePattern
        )
        val next = s.songs + song
        songStore.saveAll(next)
        _state.update { it.copy(songs = next, activeSongId = song.id, tapHint = "SAVED · ${song.name}") }
    }

    fun loadSong(song: SongPreset) {
        setBpm(song.bpm)
        engine.setTimeSignature(song.timeSignature)
        engine.setBeatAccents(song.beatAccents)
        setSubdivision(song.subdivision)
        setSwing(song.swing)
        setTone(song.tone, preview = false)
        setAccentNote(song.accentNote, preview = false)
        setCountInBars(song.countInBars)
        setMutePattern(song.mutePattern)
        val group = song.groupTempo && song.timeSignature.isCompound
        engine.setGroupTempo(group)
        _state.update {
            it.copy(
                timeSignature = song.timeSignature,
                beatAccents = song.beatAccents,
                groupTempo = group,
                activeBeat = -1,
                activeSongId = song.id,
                tapHint = song.name.uppercase()
            )
        }
        prefs.edit()
            .putInt("beats", song.timeSignature.beats)
            .putInt("noteValue", song.timeSignature.noteValue)
            .putBoolean("groupTempo", group)
            .putString("beatAccents", BeatAccent.encode(song.beatAccents))
            .apply()
    }

    fun deleteSong(song: SongPreset) {
        val next = _state.value.songs.filterNot { it.id == song.id }
        songStore.saveAll(next)
        _state.update {
            it.copy(
                songs = next,
                activeSongId = if (it.activeSongId == song.id) null else it.activeSongId
            )
        }
    }

    fun renameSong(song: SongPreset, name: String) {
        val trimmed = name.trim().ifEmpty { return }
        val next = _state.value.songs.map {
            if (it.id == song.id) it.copy(name = trimmed) else it
        }
        songStore.saveAll(next)
        _state.update { it.copy(songs = next, tapHint = "RENAMED") }
    }

    fun updateActiveSong() {
        val s = _state.value
        val id = s.activeSongId ?: return
        val next = s.songs.map { song ->
            if (song.id != id) song
            else song.copy(
                bpm = s.bpm,
                timeSignature = s.timeSignature,
                subdivision = s.subdivision,
                tone = s.tone,
                accentNote = s.accentNote,
                beatAccents = s.beatAccents,
                swing = s.swing,
                groupTempo = s.groupTempo,
                countInBars = s.countInBars,
                mutePattern = s.mutePattern,
                name = if (song.name.contains("·")) {
                    SongPreset.autoName(s.bpm, s.timeSignature, s.subdivision)
                } else {
                    song.name
                }
            )
        }
        songStore.saveAll(next)
        _state.update { it.copy(songs = next, tapHint = "UPDATED") }
    }

    fun tapTempo() {
        val now = System.currentTimeMillis()
        while (tapTimes.isNotEmpty() && now - tapTimes.first() > 3000) {
            tapTimes.removeFirst()
        }
        tapTimes.addLast(now)
        if (tapTimes.size >= 2) {
            val intervals = tapTimes.zipWithNext { a, b -> b - a }
            val avg = intervals.average()
            if (avg in 200.0..2000.0) {
                val bpm = (60_000.0 / avg).toInt()
                setBpm(bpm)
                _state.update { it.copy(tapHint = "TAP → $bpm") }
            }
        } else {
            _state.update { it.copy(tapHint = "TAP") }
        }
    }

    private fun onBeat(event: BeatEvent) {
        // Detect bar boundaries (beat index wrapped back to 0)
        if (event.beatIndex == 0 && lastBeatIndex > 0) {
            barIndex++
            onBarAdvanced(barIndex)
        } else if (event.beatIndex == 0 && lastBeatIndex == -1) {
            applyBarGate(0)
        }
        lastBeatIndex = event.beatIndex

        val phase = _state.value.sessionPhase
        val clicksOn = phase != SessionPhase.SILENT && phase != SessionPhase.IDLE

        beatSerial += 1L
        _state.update {
            it.copy(
                activeBeat = event.beatIndex,
                beatFlash = beatSerial,
                beatAtMs = event.timestampMs,
                isAccentBeat = event.isAccent,
                sessionBar = barIndex,
                statusLine = statusFor(it, barIndex, it.sessionPhase)
            )
        }
        if (_state.value.haptics && clicksOn) {
            vibrate(event.isAccent)
        }
    }

    private fun onBarAdvanced(newBar: Int) {
        applyBarGate(newBar)
        maybeAdvanceTrainer(newBar)
    }

    private fun applyBarGate(bar: Int) {
        val s = _state.value
        val phase: SessionPhase
        val audible: Boolean
        when {
            bar < s.countInBars -> {
                phase = SessionPhase.COUNT_IN
                audible = true
            }
            s.mutePattern.silentBars == 0 -> {
                phase = if (s.trainerEnabled && trainerAtTarget(s) && bar > s.countInBars) {
                    SessionPhase.TRAINER_DONE
                } else {
                    SessionPhase.PLAYING
                }
                audible = true
            }
            else -> {
                val practiceBar = bar - s.countInBars
                val cycle = s.mutePattern.playBars + s.mutePattern.silentBars
                val pos = practiceBar % cycle
                if (pos < s.mutePattern.playBars) {
                    phase = SessionPhase.PLAYING
                    audible = true
                } else {
                    phase = SessionPhase.SILENT
                    audible = false
                }
            }
        }
        engine.setClicksEnabled(audible)
        _state.update {
            it.copy(
                sessionPhase = phase,
                sessionBar = bar,
                statusLine = statusFor(it.copy(sessionPhase = phase, sessionBar = bar), bar, phase)
            )
        }
    }

    private fun maybeAdvanceTrainer(bar: Int) {
        val s = _state.value
        if (!s.trainerEnabled) return
        if (bar < s.countInBars) return
        val practiceBars = bar - s.countInBars
        if (practiceBars <= 0) return
        if (practiceBars % s.trainerEveryBars != 0) return
        if (trainerAtTarget(s)) {
            finishTrainer(s.trainerTargetBpm)
            return
        }
        val dir = if (s.trainerTargetBpm >= s.bpm) 1 else -1
        val next = (s.bpm + s.trainerStep * dir).let { stepped ->
            if (dir > 0) stepped.coerceAtMost(s.trainerTargetBpm)
            else stepped.coerceAtLeast(s.trainerTargetBpm)
        }.coerceIn(MetronomeEngine.MIN_BPM, MetronomeEngine.MAX_BPM)
        setBpm(next, persist = false)
        _state.update { it.copy(tapHint = "TRAIN → $next") }
        if (next == s.trainerTargetBpm && s.trainerAutoStop) {
            finishTrainer(next)
        }
    }

    private fun trainerAtTarget(s: MetronomeUiState): Boolean =
        abs(s.bpm - s.trainerTargetBpm) == 0

    private fun finishTrainer(targetBpm: Int) {
        val s = _state.value
        if (s.trainerAutoStop) {
            resumeAfterFocusGain = false
            engine.stop()
            unregisterBecomingNoisy()
            abandonAudioFocus()
            lastBeatIndex = -1
            barIndex = 0
            _state.update {
                it.copy(
                    isPlaying = false,
                    activeBeat = -1,
                    sessionBar = 0,
                    sessionPhase = SessionPhase.IDLE,
                    statusLine = "TARGET · $targetBpm",
                    tapHint = "TRAINER DONE · $targetBpm"
                )
            }
            publishPlayback()
        } else {
            _state.update {
                it.copy(
                    sessionPhase = SessionPhase.TRAINER_DONE,
                    tapHint = "TARGET · $targetBpm",
                    statusLine = "TRAINER DONE"
                )
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        // Replace any prior request so we don't leak listeners across start cycles.
        abandonAudioFocus()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .build()
        val granted = audioManager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            focusRequest = request
        }
        return granted
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun registerBecomingNoisy() {
        if (noisyReceiverRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        ContextCompat.registerReceiver(
            getApplication(),
            becomingNoisyReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        noisyReceiverRegistered = true
    }

    private fun unregisterBecomingNoisy() {
        if (!noisyReceiverRegistered) return
        try {
            getApplication<Application>().unregisterReceiver(becomingNoisyReceiver)
        } catch (_: Exception) {
        }
        noisyReceiverRegistered = false
    }

    private fun publishPlayback() {
        val s = _state.value
        PlaybackBridge.playing = s.isPlaying
        PlaybackBridge.bpm = s.bpm
        PlaybackBridge.subtitle = buildList {
            add(s.timeSignature.label)
            add(s.subdivision.label)
            if (s.swing != SwingFeel.OFF) add(s.swing.label)
            if (s.groupTempo) add("dotted")
        }.joinToString(" · ")
        PlaybackService.sync(getApplication())
    }

    private fun statusFor(state: MetronomeUiState, bar: Int, phase: SessionPhase): String {
        return when (phase) {
            SessionPhase.IDLE -> "READY"
            SessionPhase.COUNT_IN -> {
                val remaining = (state.countInBars - bar).coerceAtLeast(1)
                if (remaining == 1) "COUNT IN · LAST BAR" else "COUNT IN · $remaining BARS"
            }
            SessionPhase.PLAYING -> when {
                state.trainerEnabled -> {
                    val practice = (bar - state.countInBars).coerceAtLeast(0)
                    val every = state.trainerEveryBars.coerceAtLeast(1)
                    val until = every - (practice % every)
                    val sign = if (state.trainerTargetBpm < state.bpm) "−" else "+"
                    "TRAIN · $sign${state.trainerStep} in $until"
                }
                state.mutePattern.silentBars > 0 -> "PLAY · KEEP IT"
                else -> "IN TIME"
            }
            SessionPhase.SILENT -> "YOUR MOVE"
            SessionPhase.TRAINER_DONE -> "TARGET LOCKED"
        }
    }

    private fun vibrate(accent: Boolean) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        val duration = if (accent) 28L else 14L
        val amplitude = if (accent) 180 else 90
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(duration)
        }
    }

    override fun onCleared() {
        // Drop bridge callbacks first so a late notification tap cannot restart us.
        PlaybackBridge.onToggle = null
        PlaybackBridge.onStop = null
        resumeAfterFocusGain = false
        mainHandler.removeCallbacksAndMessages(null)
        unregisterBecomingNoisy()
        abandonAudioFocus()
        engine.release()
        _state.update {
            it.copy(
                isPlaying = false,
                activeBeat = -1,
                sessionPhase = SessionPhase.IDLE,
                statusLine = "READY"
            )
        }
        PlaybackBridge.playing = false
        PlaybackService.sync(getApplication())
        super.onCleared()
    }
}

/** Keep first bookmark for each unique tempo/meter/sound setup. */
private fun List<SongPreset>.dedupeSongs(): List<SongPreset> {
    val seen = LinkedHashSet<String>()
    return filter { song ->
        val key = listOf(
            song.bpm,
            song.timeSignature.beats,
            song.timeSignature.noteValue,
            song.subdivision.ordinal,
            song.tone.ordinal,
            song.accentNote.ordinal,
            BeatAccent.encode(song.beatAccents),
            song.swing.ordinal,
            song.groupTempo,
            song.countInBars,
            song.mutePattern.playBars,
            song.mutePattern.silentBars
        ).joinToString("|")
        seen.add(key)
    }
}
