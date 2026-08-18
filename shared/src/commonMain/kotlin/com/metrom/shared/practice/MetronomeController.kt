package com.metrom.shared.practice

import com.metrom.shared.audio.SampleToneCache
import com.metrom.shared.data.SetSection
import com.metrom.shared.data.Setlist
import com.metrom.shared.data.SetlistStore
import com.metrom.shared.data.SongPreset
import com.metrom.shared.data.SongStore
import com.metrom.shared.detect.DetectDebug
import com.metrom.shared.detect.DetectState
import com.metrom.shared.detect.FailReason
import com.metrom.shared.detect.TempoAnalyze
import com.metrom.shared.domain.AccentNote
import com.metrom.shared.domain.BeatAccent
import com.metrom.shared.domain.BeatEvent
import com.metrom.shared.domain.MetronomeLimits
import com.metrom.shared.domain.MetronomeTone
import com.metrom.shared.domain.MutePattern
import com.metrom.shared.domain.SessionPhase
import com.metrom.shared.domain.Subdivision
import com.metrom.shared.domain.SwingFeel
import com.metrom.shared.domain.TimeSignature
import com.metrom.shared.engine.MetronomeEngine
import com.metrom.shared.platform.EngineRunner
import com.metrom.shared.platform.Haptics
import com.metrom.shared.platform.MicCapture
import com.metrom.shared.platform.PrefsStore
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MetronomeUiState(
    val bpm: Int = 120,
    val isPlaying: Boolean = false,
    val timeSignature: TimeSignature = TimeSignature(4, 4),
    val subdivision: Subdivision = Subdivision.QUARTER,
    val swing: SwingFeel = SwingFeel.OFF,
    val groupTempo: Boolean = false,
    val tone: MetronomeTone = MetronomeTone.DEFAULT,
    val toneOptions: List<MetronomeTone> = emptyList(),
    val accentNote: AccentNote = AccentNote.DEFAULT,
    val restNote: AccentNote = AccentNote.OFF,
    val beatAccents: List<BeatAccent> = BeatAccent.defaultPattern(4, 4),
    val volume: Float = 1f,
    val muted: Boolean = false,
    val haptics: Boolean = true,
    val activeBeat: Int = -1,
    val beatFlash: Long = 0L,
    val beatAtMs: Long = 0L,
    val isAccentBeat: Boolean = false,
    val tapHint: String? = null,
    val countInBars: Int = 0,
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
    val songs: List<SongPreset> = emptyList(),
    val activeSongId: String? = null,
    val setlists: List<Setlist> = emptyList(),
    val activeSetlistId: String? = null,
    val activeSectionIndex: Int = -1,
    val sectionBar: Int = 0,
) {
    val accentsCustomized: Boolean
        get() = !BeatAccent.isDefault(beatAccents, timeSignature.beats, timeSignature.noteValue)
    val inSetMode: Boolean
        get() = activeSetlistId != null && activeSectionIndex >= 0
}

/**
 * Shared metronome controller — UI platforms bind to [state] / [detectState].
 * [onPlaybackChanged] lets Android sync FGS / iOS Now Playing.
 * [canStart] lets Android gate on audio focus.
 */
class MetronomeController(
    private val prefs: PrefsStore,
    private val haptics: Haptics,
    private val sampleCache: SampleToneCache,
    private val engine: MetronomeEngine,
    private val runner: EngineRunner,
    private val micCapture: MicCapture?,
    private val canStart: () -> Boolean = { true },
    private val onPlaybackChanged: (playing: Boolean, bpm: Int, subtitle: String) -> Unit = { _, _, _ -> },
    private val onTrainerAutoStopped: () -> Unit = {},
) {
    private val songStore = SongStore(prefs)
    private val setlistStore = SetlistStore(prefs)
    private val tapTimes = ArrayDeque<Long>()
    private var lastBeatIndex = -1
    private var barIndex = 0
    private var beatSerial = 0L
    private var pendingAdvance = false
    private var sectionStartBar = 0

    private var listenCancel = false
    private var listenWorkerRunning = false

    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<MetronomeUiState> = _state.asStateFlow()

    private val _detectState = MutableStateFlow<DetectState>(DetectState.Idle)
    val detectState: StateFlow<DetectState> = _detectState.asStateFlow()

    private val _detectDebug = MutableStateFlow<DetectDebug?>(null)
    val detectDebug: StateFlow<DetectDebug?> = _detectDebug.asStateFlow()

    init {
        val s = _state.value
        engine.setBpm(s.bpm)
        engine.setTimeSignature(s.timeSignature)
        engine.setBeatAccents(s.beatAccents)
        engine.setSubdivision(s.subdivision)
        engine.setSwing(s.swing)
        engine.setGroupTempo(s.groupTempo)
        engine.setTone(s.tone)
        engine.setAccentNote(s.accentNote)
        engine.setRestNote(s.restNote)
        engine.setVolume(s.volume)
        engine.setMuted(s.muted)
        publishPlayback()
    }

    fun handleBeat(event: BeatEvent) {
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
                statusLine = statusFor(it, barIndex, it.sessionPhase),
            )
        }
        if (_state.value.haptics && clicksOn) haptics.beat(event.isAccent)
    }

    fun togglePlay() {
        if (_state.value.isPlaying || runner.isRunning(engine)) stop() else start()
    }

    fun start() {
        if (listenWorkerRunning) return
        if (runner.isRunning(engine)) {
            if (!_state.value.isPlaying) {
                _state.update {
                    it.copy(
                        isPlaying = true,
                        statusLine = statusFor(
                            it,
                            it.sessionBar,
                            if (it.sessionPhase == SessionPhase.IDLE) SessionPhase.PLAYING else it.sessionPhase,
                        ),
                    )
                }
                publishPlayback()
            }
            return
        }
        if (!canStart()) {
            landStartFailure("AUDIO BUSY")
            return
        }
        lastBeatIndex = -1
        barIndex = 0
        if (_state.value.inSetMode) {
            sectionStartBar = 0
            _state.update { it.copy(sectionBar = 0) }
        }
        val s = _state.value
        if (s.trainerEnabled && !s.inSetMode) {
            engine.setBpm(s.bpm)
            _state.update {
                it.copy(trainerStartBpm = s.bpm, tapHint = "TRAIN ${s.bpm}→${s.trainerTargetBpm}")
            }
            prefs.putInt("trainerStartBpm", s.bpm)
        }
        applyBarGate(0)
        if (!runner.start(engine)) {
            // Rejected while WEDGED/STOPPING — same visible stall as a timed-out stop.
            landStartFailure("AUDIO STALLED")
            return
        }
        _state.update {
            it.copy(
                isPlaying = true,
                activeBeat = -1,
                sessionBar = 0,
                sessionPhase = if (it.countInBars > 0) SessionPhase.COUNT_IN else SessionPhase.PLAYING,
                statusLine = statusFor(
                    it,
                    0,
                    if (it.countInBars > 0) SessionPhase.COUNT_IN else SessionPhase.PLAYING,
                ),
                tapHint = null,
            )
        }
        publishPlayback()
    }

    /**
     * User / focus stop. Returns true if teardown completed (IDLE).
     * Returns false if WEDGED — UI must not claim a clean stop; keep audio focus.
     */
    fun stop(): Boolean {
        val clean = runner.stop(engine)
        lastBeatIndex = -1
        barIndex = 0
        _state.update {
            it.copy(
                isPlaying = false,
                activeBeat = -1,
                sessionBar = 0,
                sessionPhase = SessionPhase.IDLE,
                statusLine = "READY",
                tapHint = if (clean) null else "AUDIO STALLED",
            )
        }
        publishPlayback()
        return clean
    }

    fun pauseTransient(): Boolean {
        val clean = runner.stop(engine)
        lastBeatIndex = -1
        _state.update {
            it.copy(
                isPlaying = false,
                activeBeat = -1,
                statusLine = if (clean) "PAUSED" else "READY",
                tapHint = if (clean) "AUDIO PAUSED" else "AUDIO STALLED",
            )
        }
        publishPlayback()
        return clean
    }

    /** Async engine death (STARTING/RUNNING → FAILED). Lands not-playing with a visible hint. */
    fun handleEngineFailed() {
        lastBeatIndex = -1
        barIndex = 0
        landStartFailure("AUDIO FAILED")
    }

    private fun landStartFailure(hint: String) {
        lastBeatIndex = -1
        barIndex = 0
        _state.update {
            it.copy(
                isPlaying = false,
                activeBeat = -1,
                sessionBar = 0,
                sessionPhase = SessionPhase.IDLE,
                statusLine = "READY",
                tapHint = hint,
            )
        }
        publishPlayback()
    }

    fun dispose() {
        cancelListen()
        runner.dispose(engine)
        _state.update {
            it.copy(isPlaying = false, activeBeat = -1, sessionPhase = SessionPhase.IDLE, statusLine = "READY")
        }
        publishPlayback()
    }

    fun setBpm(bpm: Int, persist: Boolean = true) {
        val value = bpm.coerceIn(MetronomeLimits.MIN_BPM, MetronomeLimits.MAX_BPM)
        engine.setBpm(value)
        _state.update { it.copy(bpm = value, tapHint = null) }
        if (persist) prefs.putInt("bpm", value)
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
            it.copy(timeSignature = signature, beatAccents = accents, groupTempo = group, activeBeat = -1)
        }
        prefs.putInt("beats", signature.beats)
        prefs.putInt("noteValue", signature.noteValue)
        prefs.putBoolean("groupTempo", group)
        prefs.putString("beatAccents", BeatAccent.encode(accents))
    }

    fun setSwing(feel: SwingFeel) {
        engine.setSwing(feel)
        _state.update {
            it.copy(
                swing = feel,
                tapHint = if (feel == SwingFeel.OFF) "STRAIGHT" else "SWING · ${feel.label.uppercase()}",
            )
        }
        prefs.putInt("swing", feel.ordinal)
    }

    fun toggleGroupTempo() {
        val s = _state.value
        if (!s.timeSignature.isCompound) return
        val enabled = !s.groupTempo
        val converted = if (enabled) {
            (s.bpm / 3).coerceIn(MetronomeLimits.MIN_BPM, MetronomeLimits.MAX_BPM)
        } else {
            (s.bpm * 3).coerceIn(MetronomeLimits.MIN_BPM, MetronomeLimits.MAX_BPM)
        }
        engine.setGroupTempo(enabled)
        engine.setBpm(converted)
        _state.update {
            it.copy(
                groupTempo = enabled,
                bpm = converted,
                tapHint = if (enabled) "DOTTED · $converted = felt beat" else "PULSE · $converted = each click",
            )
        }
        prefs.putBoolean("groupTempo", enabled)
        prefs.putInt("bpm", converted)
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
                },
            )
        }
        prefs.putString("beatAccents", BeatAccent.encode(next))
        if (!s.isPlaying) {
            when (next[index]) {
                BeatAccent.MUTE -> {}
                BeatAccent.STRONG -> runner.preview(engine, true)
                BeatAccent.NORMAL -> runner.preview(engine, false)
            }
        }
    }

    fun resetBeatAccents() {
        val s = _state.value
        val accents = BeatAccent.defaultPattern(s.timeSignature.beats, s.timeSignature.noteValue)
        engine.setBeatAccents(accents)
        _state.update { it.copy(beatAccents = accents, tapHint = "BEAT 1 STRONG · TAP BEATS TO CHANGE") }
        prefs.putString("beatAccents", BeatAccent.encode(accents))
    }

    fun setSubdivision(subdivision: Subdivision) {
        engine.setSubdivision(subdivision)
        _state.update { it.copy(subdivision = subdivision) }
        prefs.putInt("subdivision", subdivision.ordinal)
    }

    fun setTone(tone: MetronomeTone, preview: Boolean = true) {
        val applied = engine.setTone(tone)
        _state.update { it.copy(tone = applied) }
        prefs.putString(PREF_TONE_ID, applied.id)
        prefs.remove("tone")
        if (preview && !_state.value.isPlaying) runner.preview(engine, true)
    }

    fun setAccentNote(note: AccentNote, preview: Boolean = true) {
        engine.setAccentNote(note)
        _state.update { it.copy(accentNote = note) }
        prefs.putInt("accentNote", note.ordinal)
        if (preview && !_state.value.isPlaying) runner.preview(engine, true)
    }

    fun setRestNote(note: AccentNote, preview: Boolean = true) {
        engine.setRestNote(note)
        _state.update { it.copy(restNote = note) }
        prefs.putInt("restNote", note.ordinal)
        if (preview && !_state.value.isPlaying) runner.preview(engine, false)
    }

    fun previewTone() = runner.preview(engine, true)

    fun setVolume(volume: Float) {
        val value = volume.coerceIn(0f, 1f)
        engine.setVolume(value)
        engine.setMuted(false)
        _state.update { it.copy(volume = value, muted = false) }
        prefs.putFloat("volume", value)
        prefs.putBoolean("muted", false)
    }

    fun toggleMute() {
        val muted = !_state.value.muted
        engine.setMuted(muted)
        _state.update { it.copy(muted = muted) }
        prefs.putBoolean("muted", muted)
    }

    fun toggleHaptics() {
        val enabled = !_state.value.haptics
        _state.update { it.copy(haptics = enabled) }
        prefs.putBoolean("haptics", enabled)
    }

    fun setCountInBars(bars: Int) {
        val value = bars.coerceIn(0, 4)
        _state.update { it.copy(countInBars = value) }
        prefs.putInt("countInBars", value)
    }

    fun setMutePattern(pattern: MutePattern) {
        _state.update { it.copy(mutePattern = pattern) }
        prefs.putInt("mutePlayBars", pattern.playBars)
        prefs.putInt("muteSilentBars", pattern.silentBars)
    }

    fun toggleTrainer() {
        val enabled = !_state.value.trainerEnabled
        _state.update {
            it.copy(trainerEnabled = enabled, trainerStartBpm = if (enabled) it.bpm else it.trainerStartBpm)
        }
        prefs.putBoolean("trainerEnabled", enabled)
        prefs.putInt("trainerStartBpm", _state.value.trainerStartBpm)
    }

    fun setTrainerTarget(bpm: Int) {
        val value = bpm.coerceIn(MetronomeLimits.MIN_BPM, MetronomeLimits.MAX_BPM)
        _state.update { it.copy(trainerTargetBpm = value) }
        prefs.putInt("trainerTargetBpm", value)
    }

    fun cycleTrainerTarget() {
        val s = _state.value
        val options = listOf(
            (s.bpm - 20).coerceAtLeast(MetronomeLimits.MIN_BPM),
            (s.bpm - 10).coerceAtLeast(MetronomeLimits.MIN_BPM),
            (s.bpm + 10).coerceAtMost(MetronomeLimits.MAX_BPM),
            (s.bpm + 20).coerceAtMost(MetronomeLimits.MAX_BPM),
            100, 120, 140, 160,
        ).distinct().sorted()
        val idx = options.indexOf(s.trainerTargetBpm)
        val next = if (idx < 0) options.first() else options[(idx + 1) % options.size]
        setTrainerTarget(next)
    }

    fun setTrainerStep(step: Int) {
        val value = step.coerceIn(1, 10)
        _state.update { it.copy(trainerStep = value) }
        prefs.putInt("trainerStep", value)
    }

    fun setTrainerEveryBars(bars: Int) {
        val value = bars.coerceIn(1, 16)
        _state.update { it.copy(trainerEveryBars = value) }
        prefs.putInt("trainerEveryBars", value)
    }

    fun toggleTrainerAutoStop() {
        val enabled = !_state.value.trainerAutoStop
        _state.update { it.copy(trainerAutoStop = enabled) }
        prefs.putBoolean("trainerAutoStop", enabled)
    }

    fun saveCurrentSong(name: String? = null) {
        val s = _state.value
        val existing = s.songs.firstOrNull {
            it.sameSetupAs(
                s.bpm, s.timeSignature, s.subdivision, s.tone, s.accentNote, s.restNote,
                s.beatAccents, s.swing, s.groupTempo, s.countInBars, s.mutePattern,
            )
        }
        if (existing != null) {
            _state.update { it.copy(activeSongId = existing.id, tapHint = "ALREADY SAVED") }
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
            restNote = s.restNote,
            beatAccents = s.beatAccents,
            swing = s.swing,
            groupTempo = s.groupTempo,
            countInBars = s.countInBars,
            mutePattern = s.mutePattern,
        )
        val next = s.songs + song
        songStore.saveAll(next)
        _state.update { it.copy(songs = next, activeSongId = song.id, tapHint = "SAVED · ${song.name}") }
    }

    fun loadSong(song: SongPreset) {
        applySongSetup(song)
        _state.update { it.copy(activeSongId = song.id, tapHint = song.name.uppercase()) }
    }

    fun deleteSong(song: SongPreset) {
        val next = _state.value.songs.filterNot { it.id == song.id }
        songStore.saveAll(next)
        _state.update {
            it.copy(songs = next, activeSongId = if (it.activeSongId == song.id) null else it.activeSongId)
        }
    }

    fun renameSong(song: SongPreset, name: String) {
        val trimmed = name.trim().ifEmpty { return }
        val next = _state.value.songs.map { if (it.id == song.id) it.copy(name = trimmed) else it }
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
                restNote = s.restNote,
                beatAccents = s.beatAccents,
                swing = s.swing,
                groupTempo = s.groupTempo,
                countInBars = s.countInBars,
                mutePattern = s.mutePattern,
                name = if (song.name.contains("·")) {
                    SongPreset.autoName(s.bpm, s.timeSignature, s.subdivision)
                } else song.name,
            )
        }
        songStore.saveAll(next)
        _state.update { it.copy(songs = next, tapHint = "UPDATED") }
    }

    fun createSetlist(name: String) {
        val trimmed = name.trim().ifEmpty { return }
        val setlist = Setlist(name = trimmed, sections = emptyList())
        persistSetlists(_state.value.setlists + setlist)
        _state.update { it.copy(tapHint = "SET · ${setlist.name}") }
    }

    fun renameSetlist(id: String, name: String) {
        val trimmed = name.trim().ifEmpty { return }
        persistSetlists(_state.value.setlists.map { if (it.id == id) it.copy(name = trimmed) else it })
        _state.update { it.copy(tapHint = "RENAMED") }
    }

    fun deleteSetlist(id: String) {
        persistSetlists(_state.value.setlists.filterNot { it.id == id })
        if (_state.value.activeSetlistId == id) exitSetlist()
    }

    fun addSectionFromCurrent(setlistId: String) {
        val s = _state.value
        if (s.setlists.none { it.id == setlistId }) return
        val section = SetSection(
            config = snapshotPreset(s),
            bars = 0,
            autoAdvance = false,
        )
        persistSetlists(
            s.setlists.map { setlist ->
                if (setlist.id == setlistId) setlist.copy(sections = setlist.sections + section) else setlist
            },
        )
        _state.update { it.copy(tapHint = "SECTION ADDED") }
    }

    fun removeSection(setlistId: String, sectionId: String) {
        val s = _state.value
        val setlist = s.setlists.firstOrNull { it.id == setlistId } ?: return
        val removedIndex = setlist.sections.indexOfFirst { it.id == sectionId }
        if (removedIndex < 0) return
        val remaining = setlist.sections.filterNot { it.id == sectionId }
        persistSetlists(
            s.setlists.map { if (it.id == setlistId) it.copy(sections = remaining) else it },
        )
        if (s.activeSetlistId != setlistId || s.activeSectionIndex < 0) return
        val nextIndex = when {
            remaining.isEmpty() -> -1
            s.activeSectionIndex > removedIndex -> s.activeSectionIndex - 1
            s.activeSectionIndex == removedIndex -> s.activeSectionIndex.coerceAtMost(remaining.lastIndex)
            else -> s.activeSectionIndex
        }
        _state.update { it.copy(activeSectionIndex = nextIndex) }
    }

    fun moveSection(setlistId: String, from: Int, to: Int) {
        val s = _state.value
        val setlist = s.setlists.firstOrNull { it.id == setlistId } ?: return
        if (from !in setlist.sections.indices || to !in setlist.sections.indices) return
        val sections = setlist.sections.toMutableList()
        val item = sections.removeAt(from)
        sections.add(to, item)
        persistSetlists(
            s.setlists.map { if (it.id == setlistId) it.copy(sections = sections) else it },
        )
        if (s.activeSetlistId != setlistId) return
        val newIndex = when (s.activeSectionIndex) {
            from -> to
            else -> {
                var index = s.activeSectionIndex
                if (from < index && to >= index) index -= 1
                if (from > index && to <= index) index += 1
                index
            }
        }
        _state.update { it.copy(activeSectionIndex = newIndex) }
    }

    fun updateSection(setlistId: String, section: SetSection) {
        persistSetlists(
            _state.value.setlists.map { setlist ->
                if (setlist.id != setlistId) setlist
                else setlist.copy(sections = setlist.sections.map { if (it.id == section.id) section else it })
            },
        )
    }

    fun loadSetlist(setlist: Setlist) {
        val resolved = _state.value.setlists.firstOrNull { it.id == setlist.id } ?: setlist
        pendingAdvance = false
        sectionStartBar = barIndex
        val first = resolved.sections.firstOrNull()
        if (first == null) {
            _state.update {
                it.copy(
                    activeSetlistId = resolved.id,
                    activeSectionIndex = -1,
                    sectionBar = 0,
                    tapHint = resolved.name.uppercase(),
                )
            }
            return
        }
        applySongSetup(first.config)
        _state.update {
            it.copy(
                activeSetlistId = resolved.id,
                activeSectionIndex = 0,
                sectionBar = 0,
                tapHint = (first.label ?: resolved.name).uppercase(),
            )
        }
    }

    fun advanceSection() {
        if (!_state.value.inSetMode) return
        pendingAdvance = true
        if (!_state.value.isPlaying) maybeAdvanceSection(barIndex)
    }

    fun exitSetlist() {
        pendingAdvance = false
        _state.update {
            it.copy(activeSetlistId = null, activeSectionIndex = -1, sectionBar = 0)
        }
    }

    fun tapTempo(nowMs: Long) {
        while (tapTimes.isNotEmpty() && nowMs - tapTimes.first() > 3000) tapTimes.removeFirst()
        tapTimes.addLast(nowMs)
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

    fun startListen() {
        if (_state.value.isPlaying) return
        val mic = micCapture ?: run {
            _detectState.value = DetectState.Failed(FailReason.MIC_UNAVAILABLE)
            return
        }
        if (listenWorkerRunning) return
        listenCancel = false
        _detectDebug.value = null
        _detectState.value = DetectState.Listening(0f)
        listenWorkerRunning = true
        // Platform should call runListenCapture on a background thread
        listenCaptureRunner?.invoke(mic) ?: runListenCapture(mic)
    }

    /** Optional override so platforms can spawn their own thread. */
    var listenCaptureRunner: ((MicCapture) -> Unit)? = null

    fun runListenCapture(mic: MicCapture) {
        try {
            val pcm = mic.capture(
                seconds = 8f,
                onProgress = { p ->
                    if (!listenCancel) _detectState.value = DetectState.Listening(p)
                },
                isCancelled = { listenCancel },
            )
            if (listenCancel || pcm == null) {
                _detectState.value = DetectState.Failed(
                    if (listenCancel) FailReason.CANCELLED else FailReason.MIC_UNAVAILABLE,
                )
                return
            }
            _detectState.value = DetectState.Analyzing
            val result = TempoAnalyze.analyze(pcm)
            _detectDebug.value = result.debug
            _detectState.value = if (result.options.isEmpty()) {
                DetectState.Failed(FailReason.NO_CLEAR_BEAT)
            } else {
                DetectState.Success(result.options, result.confidence)
            }
        } finally {
            listenWorkerRunning = false
        }
    }

    fun cancelListen() {
        listenCancel = true
        val current = _detectState.value
        if (current is DetectState.Listening || current is DetectState.Analyzing) {
            if (!listenWorkerRunning) {
                _detectState.value = DetectState.Failed(FailReason.CANCELLED)
            }
        }
    }

    fun resetListen() {
        val current = _detectState.value
        if (current is DetectState.Listening || current is DetectState.Analyzing) {
            cancelListen()
            return
        }
        _detectState.value = DetectState.Idle
    }

    fun clearListenDebug() {
        _detectDebug.value = null
    }

    fun applyListenBpm(bpm: Int) {
        setBpm(bpm)
        resetListen()
    }

    fun onListenLifecyclePause() {
        val s = _detectState.value
        if (s is DetectState.Listening || s is DetectState.Analyzing) cancelListen()
    }

    private fun onBarAdvanced(newBar: Int) {
        applyBarGate(newBar)
        maybeAdvanceSection(newBar)
        maybeAdvanceTrainer(newBar)
    }

    private fun applyBarGate(bar: Int) {
        val s = _state.value
        if (s.inSetMode) {
            val phase = if (bar < s.countInBars) SessionPhase.COUNT_IN else SessionPhase.PLAYING
            engine.setClicksEnabled(true)
            _state.update {
                it.copy(
                    sessionPhase = phase,
                    sessionBar = bar,
                    statusLine = statusFor(it.copy(sessionPhase = phase, sessionBar = bar), bar, phase),
                )
            }
            return
        }
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
                } else SessionPhase.PLAYING
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
                statusLine = statusFor(it.copy(sessionPhase = phase, sessionBar = bar), bar, phase),
            )
        }
    }

    private fun maybeAdvanceTrainer(bar: Int) {
        val s = _state.value
        if (s.inSetMode) return
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
            if (dir > 0) stepped.coerceAtMost(s.trainerTargetBpm) else stepped.coerceAtLeast(s.trainerTargetBpm)
        }.coerceIn(MetronomeLimits.MIN_BPM, MetronomeLimits.MAX_BPM)
        setBpm(next, persist = false)
        _state.update { it.copy(tapHint = "TRAIN → $next") }
        if (next == s.trainerTargetBpm && s.trainerAutoStop) finishTrainer(next)
    }

    private fun trainerAtTarget(s: MetronomeUiState): Boolean = abs(s.bpm - s.trainerTargetBpm) == 0

    private fun finishTrainer(targetBpm: Int) {
        val s = _state.value
        if (s.trainerAutoStop) {
            val clean = runner.stop(engine)
            lastBeatIndex = -1
            barIndex = 0
            _state.update {
                it.copy(
                    isPlaying = false,
                    activeBeat = -1,
                    sessionBar = 0,
                    sessionPhase = SessionPhase.IDLE,
                    statusLine = "TARGET · $targetBpm",
                    tapHint = if (clean) "TRAINER DONE · $targetBpm" else "AUDIO STALLED",
                )
            }
            publishPlayback()
            if (clean) onTrainerAutoStopped()
        } else {
            _state.update {
                it.copy(
                    sessionPhase = SessionPhase.TRAINER_DONE,
                    tapHint = "TARGET · $targetBpm",
                    statusLine = "TRAINER DONE",
                )
            }
        }
    }

    private fun statusFor(state: MetronomeUiState, bar: Int, phase: SessionPhase): String = when (phase) {
        SessionPhase.IDLE -> "READY"
        SessionPhase.COUNT_IN -> {
            val remaining = (state.countInBars - bar).coerceAtLeast(1)
            if (remaining == 1) "COUNT IN · LAST BAR" else "COUNT IN · $remaining BARS"
        }
        SessionPhase.PLAYING -> when {
            state.inSetMode -> "IN TIME"
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

    private fun publishPlayback() {
        val s = _state.value
        val subtitle = buildList {
            add(s.timeSignature.label)
            add(s.subdivision.label)
            if (s.swing != SwingFeel.OFF) add(s.swing.label)
            if (s.groupTempo) add("dotted")
        }.joinToString(" · ")
        onPlaybackChanged(s.isPlaying, s.bpm, subtitle)
    }

    private fun loadInitialState(): MetronomeUiState {
        val beats = prefs.getInt("beats", 4)
        val noteValue = prefs.getInt("noteValue", 4)
        val toneOptions = availableTones()
        val storedTone = MetronomeTone.fromId(prefs.getString(PREF_TONE_ID).orEmpty())
            ?.takeIf { it in toneOptions }
            ?: MetronomeTone.DEFAULT
        val songs = songStore.load().dedupeSongs()
        val setlists = setlistStore.load()
        return MetronomeUiState(
            bpm = prefs.getInt("bpm", 120),
            volume = prefs.getFloat("volume", 0.9f).coerceIn(0f, 1f),
            muted = prefs.getBoolean("muted", false),
            haptics = prefs.getBoolean("haptics", true),
            tone = storedTone,
            toneOptions = toneOptions,
            accentNote = AccentNote.entries.getOrElse(prefs.getInt("accentNote", AccentNote.DEFAULT.ordinal)) {
                AccentNote.DEFAULT
            },
            restNote = AccentNote.entries.getOrElse(prefs.getInt("restNote", AccentNote.OFF.ordinal)) {
                AccentNote.OFF
            },
            subdivision = Subdivision.entries.getOrElse(prefs.getInt("subdivision", 0)) { Subdivision.QUARTER },
            swing = SwingFeel.entries.getOrElse(prefs.getInt("swing", 0)) { SwingFeel.OFF },
            groupTempo = prefs.getBoolean("groupTempo", false) && noteValue == 8 && beats % 3 == 0,
            timeSignature = TimeSignature(beats, noteValue),
            beatAccents = BeatAccent.decode(prefs.getString("beatAccents"), beats, noteValue),
            countInBars = prefs.getInt("countInBars", 0),
            mutePattern = MutePattern(
                playBars = prefs.getInt("mutePlayBars", 1),
                silentBars = prefs.getInt("muteSilentBars", 0),
            ),
            trainerEnabled = prefs.getBoolean("trainerEnabled", false),
            trainerStartBpm = prefs.getInt("trainerStartBpm", 80),
            trainerTargetBpm = prefs.getInt("trainerTargetBpm", 120),
            trainerStep = prefs.getInt("trainerStep", 2),
            trainerEveryBars = prefs.getInt("trainerEveryBars", 4),
            trainerAutoStop = prefs.getBoolean("trainerAutoStop", true),
            songs = songs,
            setlists = setlists,
        )
    }

    private fun maybeAdvanceSection(bar: Int) {
        val s = _state.value
        if (!s.inSetMode) return
        val setlist = s.setlists.firstOrNull { it.id == s.activeSetlistId } ?: return
        val section = setlist.sections.getOrNull(s.activeSectionIndex) ?: return
        val elapsed = bar - sectionStartBar
        val reachedEnd = section.autoAdvance && section.bars > 0 && elapsed >= section.bars
        if (!pendingAdvance && !reachedEnd) {
            _state.update { it.copy(sectionBar = elapsed.coerceAtLeast(0)) }
            return
        }
        pendingAdvance = false
        val nextIndex = s.activeSectionIndex + 1
        if (nextIndex > setlist.sections.lastIndex) {
            if (setlist.loop && setlist.sections.isNotEmpty()) {
                applySectionAt(setlist, 0, bar)
            } else {
                stop()
            }
            return
        }
        applySectionAt(setlist, nextIndex, bar)
    }

    private fun applySectionAt(setlist: Setlist, index: Int, bar: Int) {
        val section = setlist.sections.getOrNull(index) ?: return
        applySongSetup(section.config)
        sectionStartBar = bar
        _state.update {
            it.copy(
                activeSetlistId = setlist.id,
                activeSectionIndex = index,
                sectionBar = 0,
                tapHint = (section.label ?: setlist.name).uppercase(),
            )
        }
    }

    private fun applySongSetup(song: SongPreset) {
        setBpm(song.bpm)
        engine.setTimeSignature(song.timeSignature)
        engine.setBeatAccents(song.beatAccents)
        setSubdivision(song.subdivision)
        setSwing(song.swing)
        setTone(song.tone, preview = false)
        setAccentNote(song.accentNote, preview = false)
        setRestNote(song.restNote, preview = false)
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
            )
        }
        prefs.putInt("beats", song.timeSignature.beats)
        prefs.putInt("noteValue", song.timeSignature.noteValue)
        prefs.putBoolean("groupTempo", group)
        prefs.putString("beatAccents", BeatAccent.encode(song.beatAccents))
    }

    private fun persistSetlists(next: List<Setlist>) {
        setlistStore.saveAll(next)
        _state.update { it.copy(setlists = next) }
    }

    private fun snapshotPreset(s: MetronomeUiState): SongPreset = SongPreset(
        name = SongPreset.autoName(s.bpm, s.timeSignature, s.subdivision),
        bpm = s.bpm,
        timeSignature = s.timeSignature,
        subdivision = s.subdivision,
        tone = s.tone,
        accentNote = s.accentNote,
        restNote = s.restNote,
        beatAccents = s.beatAccents,
        swing = s.swing,
        groupTempo = s.groupTempo,
        countInBars = s.countInBars,
        mutePattern = s.mutePattern,
    )

    private fun availableTones(): List<MetronomeTone> =
        MetronomeTone.all.filter { tone ->
            when (tone) {
                is MetronomeTone.Synth -> true
                is MetronomeTone.Sample -> sampleCache.get(tone.tone) != null
            }
        }

    companion object {
        private const val PREF_TONE_ID = "toneId"
    }
}

private fun List<SongPreset>.dedupeSongs(): List<SongPreset> {
    val seen = LinkedHashSet<String>()
    return filter { song ->
        val key = listOf(
            song.bpm, song.timeSignature.beats, song.timeSignature.noteValue,
            song.subdivision.ordinal, song.tone.id, song.accentNote.ordinal, song.restNote.ordinal,
            BeatAccent.encode(song.beatAccents), song.swing.ordinal, song.groupTempo,
            song.countInBars, song.mutePattern.playBars, song.mutePattern.silentBars,
        ).joinToString("|")
        seen.add(key)
    }
}
