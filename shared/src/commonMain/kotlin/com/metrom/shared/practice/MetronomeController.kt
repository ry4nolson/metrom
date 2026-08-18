package com.metrom.shared.practice

import com.metrom.shared.audio.SampleToneCache
import com.metrom.shared.db.MetromDatabase
import com.metrom.shared.library.Section
import com.metrom.shared.library.Setlist
import com.metrom.shared.library.Song
import com.metrom.shared.randomUuid
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
    val songs: List<Song> = emptyList(),
    val sections: List<Section> = emptyList(),
    val savedSections: List<Section> = emptyList(),
    val activeSavedSectionId: String? = null,
    val setlists: List<Setlist> = emptyList(),
    val activeSetlistId: String? = null,
    val activeSectionIndex: Int = -1,
    val sectionBar: Int = 0,
) {
    val accentsCustomized: Boolean
        get() = !BeatAccent.isDefault(beatAccents, timeSignature.beats, timeSignature.noteValue)
    val inSetMode: Boolean
        get() = activeSetlistId != null && activeSectionIndex >= 0

    fun setlistSlots(setlist: Setlist): List<SetlistSlot> {
        val songById = songs.associateBy { it.id }
        val sectionById = sections.associateBy { it.id }
        return setlist.songIds.flatMap { songId ->
            val song = songById[songId] ?: return@flatMap emptyList()
            song.sectionRefs.mapNotNull { ref ->
                val section = sectionById[ref.sectionId] ?: return@mapNotNull null
                SetlistSlot(songId = songId, section = section, autoAdvance = ref.autoAdvance)
            }
        }
    }

    fun activeSetlist(): Setlist? = setlists.firstOrNull { it.id == activeSetlistId }

    fun activeSlots(): List<SetlistSlot> = activeSetlist()?.let { setlistSlots(it) }.orEmpty()
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
    private val database: MetromDatabase,
    private val canStart: () -> Boolean = { true },
    private val onPlaybackChanged: (playing: Boolean, bpm: Int, subtitle: String) -> Unit = { _, _, _ -> },
    private val onTrainerAutoStopped: () -> Unit = {},
) {
    private val library = LibraryPersistence(database)
    private val tapTimes = ArrayDeque<Long>()
    private var lastBeatIndex = -1
    private var barIndex = 0
    private var beatSerial = 0L
    private var pendingSetNav = PendingSetNav.NONE
    private var sectionStartBar = 0

    private enum class PendingSetNav { NONE, NEXT, PREVIOUS, RESTART }

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
        pendingSetNav = PendingSetNav.NONE
        if (_state.value.inSetMode) armLoadedSetFromTop()
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
        pendingSetNav = PendingSetNav.NONE
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

    fun saveCurrentSection(name: String? = null) {
        val s = _state.value
        val existing = s.savedSections.firstOrNull {
            it.sameSetupAs(
                s.bpm, s.timeSignature, s.subdivision, s.tone.id, s.accentNote, s.restNote,
                s.beatAccents, s.swing, s.groupTempo, s.countInBars, s.mutePattern,
            )
        }
        if (existing != null) {
            _state.update { it.copy(activeSavedSectionId = existing.id, tapHint = "ALREADY SAVED") }
            return
        }
        val resolvedName = name?.trim().orEmpty().ifEmpty {
            Section.autoName(s.bpm, s.timeSignature, s.subdivision)
        }
        val section = snapshotSection(s, id = randomUuid(), name = resolvedName)
        library.upsertSection(section)
        reloadLibrary { it.copy(activeSavedSectionId = section.id, tapHint = "SAVED · ${section.displayName()}") }
    }

    fun loadSection(section: Section) {
        applySectionSetup(section)
        _state.update { it.copy(activeSavedSectionId = section.id, tapHint = section.displayName().uppercase()) }
    }

    fun deleteSection(section: Section) {
        library.deleteSection(section.id)
        reloadLibrary {
            it.copy(activeSavedSectionId = if (it.activeSavedSectionId == section.id) null else it.activeSavedSectionId)
        }
    }

    fun renameSection(section: Section, name: String) {
        val trimmed = name.trim().ifEmpty { return }
        library.upsertSection(section.copy(name = trimmed))
        reloadLibrary { it.copy(tapHint = "RENAMED") }
    }

    fun updateActiveSection() {
        val s = _state.value
        val id = s.activeSavedSectionId ?: return
        val current = s.savedSections.firstOrNull { it.id == id } ?: return
        val name = if (current.name?.contains("·") == true) {
            Section.autoName(s.bpm, s.timeSignature, s.subdivision)
        } else current.name
        library.upsertSection(snapshotSection(s, id = id, name = name))
        reloadLibrary { it.copy(tapHint = "UPDATED") }
    }

    fun createSetlist(name: String) {
        val trimmed = name.trim().ifEmpty { return }
        val setlist = library.createSetlist(trimmed)
        reloadLibrary { it.copy(tapHint = "SET · ${setlist.name}") }
    }

    fun renameSetlist(id: String, name: String) {
        val trimmed = name.trim().ifEmpty { return }
        library.renameSetlist(id, trimmed)
        reloadLibrary { it.copy(tapHint = "RENAMED") }
    }

    fun deleteSetlist(id: String) {
        library.deleteSetlist(id)
        reloadLibrary()
        if (_state.value.activeSetlistId == id) exitSetlist()
    }

    fun addSectionFromCurrent(setlistId: String) {
        val s = _state.value
        if (s.setlists.none { it.id == setlistId }) return
        library.addSlot(setlistId, snapshotSection(s, id = randomUuid(), name = null), autoAdvance = false)
        reloadLibrary { it.copy(tapHint = "SECTION ADDED") }
    }

    fun removeSection(setlistId: String, sectionId: String) {
        val s = _state.value
        val setlist = s.setlists.firstOrNull { it.id == setlistId } ?: return
        val slots = s.setlistSlots(setlist)
        val removedIndex = slots.indexOfFirst { it.section.id == sectionId }
        if (removedIndex < 0) return
        library.removeSlot(setlistId, sectionId)
        reloadLibrary()
        if (s.activeSetlistId != setlistId || s.activeSectionIndex < 0) return
        val remainingLast = (_state.value.setlists.firstOrNull { it.id == setlistId }
            ?.let { _state.value.setlistSlots(it) }?.lastIndex ?: -1)
        val nextIndex = when {
            remainingLast < 0 -> -1
            s.activeSectionIndex > removedIndex -> s.activeSectionIndex - 1
            s.activeSectionIndex == removedIndex -> s.activeSectionIndex.coerceAtMost(remainingLast)
            else -> s.activeSectionIndex
        }
        _state.update { it.copy(activeSectionIndex = nextIndex) }
    }

    fun moveSection(setlistId: String, from: Int, to: Int) {
        val s = _state.value
        library.moveSlot(setlistId, from, to)
        reloadLibrary()
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

    fun updateSection(setlistId: String, section: Section) {
        library.upsertSection(section)
        reloadLibrary()
    }

    fun setSectionBpm(setlistId: String, sectionId: String, bpm: Int) {
        mutateSection(setlistId, sectionId) {
            it.copy(bpm = bpm.coerceIn(MetronomeLimits.MIN_BPM, MetronomeLimits.MAX_BPM))
        }
    }

    fun setSectionTimeSignature(setlistId: String, sectionId: String, signature: TimeSignature) {
        val normalized = TimeSignature.normalize(signature.beats, signature.noteValue) ?: return
        mutateSection(setlistId, sectionId) { section ->
            section.copy(
                beats = normalized.beats,
                noteValue = normalized.noteValue,
                beatAccents = BeatAccent.defaultPattern(normalized.beats, normalized.noteValue),
                groupTempo = section.groupTempo && normalized.isCompound,
            )
        }
    }

    fun setSectionSubdivision(setlistId: String, sectionId: String, subdivision: Subdivision) {
        mutateSection(setlistId, sectionId) { it.copy(subdivision = subdivision) }
    }

    fun setSectionSwing(setlistId: String, sectionId: String, swing: SwingFeel) {
        mutateSection(setlistId, sectionId) { it.copy(swing = swing) }
    }

    fun setSectionTone(setlistId: String, sectionId: String, tone: MetronomeTone) {
        val applied = availableTones().find { it.id == tone.id } ?: MetronomeTone.DEFAULT
        mutateSection(setlistId, sectionId) { it.copy(toneId = applied.id) }
    }

    fun setSectionAccentNote(setlistId: String, sectionId: String, note: AccentNote) {
        mutateSection(setlistId, sectionId) { it.copy(accentNote = note) }
    }

    fun setSectionRestNote(setlistId: String, sectionId: String, note: AccentNote) {
        mutateSection(setlistId, sectionId) { it.copy(restNote = note) }
    }

    fun setSectionBeatAccents(setlistId: String, sectionId: String, levels: List<BeatAccent>) {
        mutateSection(setlistId, sectionId) { section ->
            section.copy(
                beatAccents = BeatAccent.decode(
                    BeatAccent.encode(levels),
                    section.beats,
                    section.noteValue,
                ),
            )
        }
    }

    fun setSectionGroupTempo(setlistId: String, sectionId: String, enabled: Boolean) {
        mutateSection(setlistId, sectionId) { section ->
            section.copy(groupTempo = enabled && section.timeSignature.isCompound)
        }
    }

    fun setSectionCountInBars(setlistId: String, sectionId: String, bars: Int) {
        mutateSection(setlistId, sectionId) { it.copy(countInBars = bars.coerceIn(0, 4)) }
    }

    fun setSectionLabel(setlistId: String, sectionId: String, label: String?) {
        mutateSection(setlistId, sectionId) {
            it.copy(name = label?.trim()?.takeIf { trimmed -> trimmed.isNotEmpty() })
        }
    }

    fun setSectionBars(setlistId: String, sectionId: String, bars: Int) {
        val clamped = bars.coerceIn(0, SECTION_BARS_MAX)
        mutateSection(setlistId, sectionId) { it.copy(bars = clamped) }
        if (clamped == 0) {
            library.setAutoAdvance(setlistId, sectionId, false)
            reloadLibrary()
        }
    }

    fun setSectionAutoAdvance(setlistId: String, sectionId: String, autoAdvance: Boolean) {
        library.setAutoAdvance(setlistId, sectionId, autoAdvance)
        reloadLibrary()
    }

    fun captureCurrentIntoSection(setlistId: String, sectionId: String) {
        val s = _state.value
        mutateSection(setlistId, sectionId) { current ->
            snapshotSection(s, id = current.id, name = current.name).copy(bars = current.bars)
        }
    }

    private fun mutateSection(
        setlistId: String,
        sectionId: String,
        transform: (Section) -> Section,
    ) {
        val section = slot(setlistId, sectionId)?.section ?: return
        library.upsertSection(transform(section))
        reloadLibrary()
        maybeReapplyActiveSection(setlistId, sectionId)
    }

    private fun slot(setlistId: String, sectionId: String): SetlistSlot? {
        val setlist = _state.value.setlists.firstOrNull { it.id == setlistId } ?: return null
        return _state.value.setlistSlots(setlist).firstOrNull { it.section.id == sectionId }
    }

    /** Stopped + this section is active → applySectionSetup. Playing → persist only. */
    private fun maybeReapplyActiveSection(setlistId: String, sectionId: String) {
        val s = _state.value
        if (s.isPlaying) return
        if (!s.inSetMode || s.activeSetlistId != setlistId) return
        val slots = s.activeSlots()
        val index = slots.indexOfFirst { it.section.id == sectionId }
        if (index != s.activeSectionIndex) return
        val section = slots.getOrNull(index)?.section ?: return
        applySectionSetup(section)
    }

    fun loadSetlist(setlist: Setlist) {
        val resolved = _state.value.setlists.firstOrNull { it.id == setlist.id } ?: setlist
        pendingSetNav = PendingSetNav.NONE
        sectionStartBar = barIndex
        val first = _state.value.setlistSlots(resolved).firstOrNull()
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
        applySectionSetup(first.section)
        _state.update {
            it.copy(
                activeSetlistId = resolved.id,
                activeSectionIndex = 0,
                sectionBar = 0,
                tapHint = first.section.displayName().uppercase(),
            )
        }
    }

    fun advanceSection() {
        queueSetNav(PendingSetNav.NEXT)
    }

    fun previousSection() {
        queueSetNav(PendingSetNav.PREVIOUS)
    }

    fun restartSet() {
        queueSetNav(PendingSetNav.RESTART)
    }

    fun exitSetlist() {
        pendingSetNav = PendingSetNav.NONE
        _state.update {
            it.copy(activeSetlistId = null, activeSectionIndex = -1, sectionBar = 0)
        }
    }

    private fun queueSetNav(nav: PendingSetNav) {
        if (!_state.value.inSetMode) return
        pendingSetNav = nav
        if (!_state.value.isPlaying) maybeAdvanceSection(barIndex)
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
        val songs = library.loadSongs()
        val sections = library.loadSections()
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
            sections = sections,
            savedSections = sections.savedOnly(songs).dedupeSections(),
            setlists = library.loadSetlists(),
        )
    }

    private fun maybeAdvanceSection(bar: Int) {
        val s = _state.value
        if (!s.inSetMode) return
        val setlist = s.activeSetlist() ?: return
        val slots = s.setlistSlots(setlist)
        val slot = slots.getOrNull(s.activeSectionIndex) ?: return
        val elapsed = bar - sectionStartBar
        val reachedEnd = slot.autoAdvance && slot.section.bars > 0 && elapsed >= slot.section.bars
        val nav = pendingSetNav
        if (nav == PendingSetNav.NONE && !reachedEnd) {
            _state.update { it.copy(sectionBar = elapsed.coerceAtLeast(0)) }
            return
        }
        pendingSetNav = PendingSetNav.NONE
        val targetIndex = when (nav) {
            PendingSetNav.RESTART -> 0
            PendingSetNav.PREVIOUS -> when {
                s.activeSectionIndex > 0 -> s.activeSectionIndex - 1
                setlist.loop && slots.isNotEmpty() -> slots.lastIndex
                else -> 0
            }
            PendingSetNav.NEXT, PendingSetNav.NONE -> {
                val nextIndex = s.activeSectionIndex + 1
                if (nextIndex <= slots.lastIndex) {
                    nextIndex
                } else if (setlist.loop && slots.isNotEmpty()) {
                    0
                } else {
                    finishNonLoopingSet(setlist)
                    return
                }
            }
        }
        applySectionAt(setlist, targetIndex, bar)
    }

    private fun finishNonLoopingSet(setlist: Setlist) {
        stop()
        applySectionAt(setlist, 0, barIndex)
    }

    private fun armLoadedSetFromTop() {
        val s = _state.value
        val setlist = s.activeSetlist() ?: return
        if (s.setlistSlots(setlist).isEmpty()) return
        applySectionAt(setlist, 0, barIndex)
    }

    private fun applySectionAt(setlist: Setlist, index: Int, bar: Int) {
        val slot = _state.value.setlistSlots(setlist).getOrNull(index) ?: return
        applySectionSetup(slot.section)
        sectionStartBar = bar
        _state.update {
            it.copy(
                activeSetlistId = setlist.id,
                activeSectionIndex = index,
                sectionBar = 0,
                tapHint = slot.section.displayName().uppercase(),
            )
        }
    }

    private fun applySectionSetup(section: Section) {
        val tone = availableTones().find { it.id == section.toneId } ?: MetronomeTone.DEFAULT
        setBpm(section.bpm)
        engine.setTimeSignature(section.timeSignature)
        engine.setBeatAccents(section.beatAccents)
        setSubdivision(section.subdivision)
        setSwing(section.swing)
        setTone(tone, preview = false)
        setAccentNote(section.accentNote, preview = false)
        setRestNote(section.restNote, preview = false)
        setCountInBars(section.countInBars)
        setMutePattern(section.mutePattern)
        val group = section.groupTempo && section.timeSignature.isCompound
        engine.setGroupTempo(group)
        _state.update {
            it.copy(
                timeSignature = section.timeSignature,
                beatAccents = section.beatAccents,
                groupTempo = group,
                activeBeat = -1,
            )
        }
        prefs.putInt("beats", section.timeSignature.beats)
        prefs.putInt("noteValue", section.timeSignature.noteValue)
        prefs.putBoolean("groupTempo", group)
        prefs.putString("beatAccents", BeatAccent.encode(section.beatAccents))
    }

    private fun reloadLibrary(transform: (MetronomeUiState) -> MetronomeUiState = { it }) {
        val songs = library.loadSongs()
        val sections = library.loadSections()
        _state.update {
            transform(
                it.copy(
                    songs = songs,
                    sections = sections,
                    savedSections = sections.savedOnly(songs).dedupeSections(),
                    setlists = library.loadSetlists(),
                ),
            )
        }
    }

    private fun snapshotSection(s: MetronomeUiState, id: String, name: String?): Section = Section(
        id = id,
        name = name?.trim()?.takeIf { it.isNotEmpty() },
        bars = 0,
        bpm = s.bpm,
        beats = s.timeSignature.beats,
        noteValue = s.timeSignature.noteValue,
        subdivision = s.subdivision,
        toneId = s.tone.id,
        accentNote = s.accentNote,
        restNote = s.restNote,
        beatAccents = s.beatAccents,
        swing = s.swing,
        groupTempo = s.groupTempo,
        countInBars = s.countInBars,
        mutePlayBars = s.mutePattern.playBars,
        muteSilentBars = s.mutePattern.silentBars,
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
        private const val SECTION_BARS_MAX = 999
    }
}

private fun List<Section>.savedOnly(songs: List<Song>): List<Section> {
    val used = songs.flatMap { it.sectionIds }.toSet()
    return filter { it.id !in used }
}

private fun List<Section>.dedupeSections(): List<Section> {
    val seen = LinkedHashSet<String>()
    return filter { section ->
        val key = listOf(
            section.bpm, section.beats, section.noteValue,
            section.subdivision.ordinal, section.toneId, section.accentNote.ordinal, section.restNote.ordinal,
            BeatAccent.encode(section.beatAccents), section.swing.ordinal, section.groupTempo,
            section.countInBars, section.mutePlayBars, section.muteSilentBars,
        ).joinToString("|")
        seen.add(key)
    }
}
