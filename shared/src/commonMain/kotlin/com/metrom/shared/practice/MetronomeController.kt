package com.metrom.shared.practice

import com.metrom.shared.audio.SampleToneCache
import com.metrom.shared.db.MetromDatabase
import com.metrom.shared.library.DeleteResult
import com.metrom.shared.library.Section
import com.metrom.shared.library.Setlist
import com.metrom.shared.library.Song
import com.metrom.shared.library.SongSectionRef
import com.metrom.shared.library.Usage
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
    val activeSongId: String? = null,
    val activeSectionIndex: Int = -1,
    val sectionBar: Int = 0,
) {
    val accentsCustomized: Boolean
        get() = !BeatAccent.isDefault(beatAccents, timeSignature.beats, timeSignature.noteValue)
    val inSetMode: Boolean
        get() = (activeSetlistId != null || activeSongId != null) && activeSectionIndex >= 0

    fun setlistSlots(setlist: Setlist): List<SetlistSlot> = flattenSongIds(setlist.songIds)

    fun songSlots(song: Song): List<SetlistSlot> {
        val sectionById = sections.associateBy { it.id }
        return song.sectionRefs.mapNotNull { ref ->
            val section = sectionById[ref.sectionId] ?: return@mapNotNull null
            SetlistSlot(songId = song.id, section = section, autoAdvance = ref.autoAdvance)
        }
    }

    fun activeSetlist(): Setlist? = setlists.firstOrNull { it.id == activeSetlistId }

    fun activeSong(): Song? = songs.firstOrNull { it.id == activeSongId }

    /**
     * Playback sequence computed from the real structure:
     * setlist.songIds → each Song.sectionRefs → sections (or a standalone song's refs).
     * Not a stored parallel slot list.
     */
    fun activeSlots(): List<SetlistSlot> = when {
        activeSetlistId != null -> activeSetlist()?.let { setlistSlots(it) }.orEmpty()
        activeSongId != null -> activeSong()?.let { songSlots(it) }.orEmpty()
        else -> emptyList()
    }

    private fun flattenSongIds(songIds: List<String>): List<SetlistSlot> {
        val songById = songs.associateBy { it.id }
        val sectionById = sections.associateBy { it.id }
        return songIds.flatMap { songId ->
            val song = songById[songId] ?: return@flatMap emptyList()
            song.sectionRefs.mapNotNull { ref ->
                val section = sectionById[ref.sectionId] ?: return@mapNotNull null
                SetlistSlot(songId = songId, section = section, autoAdvance = ref.autoAdvance)
            }
        }
    }
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

    fun sectionUsage(sectionId: String): Usage = library.sectionUsage(sectionId)

    fun songUsage(songId: String): Usage = library.songUsage(songId)

    fun deleteSection(section: Section): DeleteResult {
        val usage = library.sectionUsage(section.id)
        if (usage.isReferenced) return DeleteResult.Blocked(usage)
        library.deleteSection(section.id)
        reloadLibrary {
            it.copy(activeSavedSectionId = if (it.activeSavedSectionId == section.id) null else it.activeSavedSectionId)
        }
        return DeleteResult.Deleted
    }

    fun deleteSong(song: Song): DeleteResult {
        val usage = library.songUsage(song.id)
        if (usage.isReferenced) return DeleteResult.Blocked(usage)
        library.deleteSong(song.id)
        reloadLibrary()
        if (_state.value.activeSongId == song.id) exitSetlist()
        return DeleteResult.Deleted
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

    fun createSong(name: String): Song? {
        val trimmed = name.trim().ifEmpty { return null }
        val song = library.createSong(trimmed)
        reloadLibrary { it.copy(tapHint = "SONG · ${song.name}") }
        return song
    }

    fun createSongFromCurrent(name: String): Song? {
        val trimmed = name.trim().ifEmpty { return null }
        val song = library.createSong(trimmed)
        val section = snapshotSection(_state.value, id = randomUuid(), name = null)
        library.addSectionToSong(song.id, section)
        reloadLibrary { it.copy(tapHint = "SONG · ${song.name}") }
        return _state.value.songs.firstOrNull { it.id == song.id }
    }

    fun renameSong(songId: String, name: String) {
        val trimmed = name.trim().ifEmpty { return }
        library.renameSong(songId, trimmed)
        reloadLibrary { it.copy(tapHint = "RENAMED") }
    }

    fun setSongLoop(songId: String, enabled: Boolean) {
        library.setSongLoop(songId, enabled)
        reloadLibrary()
    }

    fun addSectionToSong(songId: String) {
        if (library.getSong(songId) == null) return
        val section = snapshotSection(_state.value, id = randomUuid(), name = null)
        library.addSectionToSong(songId, section)
        reloadLibrary { it.copy(tapHint = "SECTION ADDED") }
    }

    fun addExistingSectionToSong(songId: String, sectionId: String) {
        if (!library.addExistingSectionToSong(songId, sectionId)) return
        reloadLibrary { it.copy(tapHint = "SECTION ADDED") }
    }

    fun unlinkSectionFromSong(songId: String, sectionId: String) {
        val song = library.getSong(songId) ?: return
        val index = song.sectionRefs.indexOfFirst { it.sectionId == sectionId }
        if (index < 0) return
        unlinkSectionFromSongAt(songId, index)
    }

    fun unlinkSectionFromSongAt(songId: String, index: Int) {
        val s = _state.value
        val songSlots = s.activeSlots().withIndex().filter { it.value.songId == songId }
        val removedIndex = songSlots.getOrNull(index)?.index ?: -1
        library.unlinkSectionFromSongAt(songId, index)
        reloadLibrary()
        if (removedIndex < 0 || s.activeSectionIndex < 0) return
        val remainingLast = _state.value.activeSlots().lastIndex
        val nextIndex = when {
            remainingLast < 0 -> -1
            s.activeSectionIndex > removedIndex -> s.activeSectionIndex - 1
            s.activeSectionIndex == removedIndex -> s.activeSectionIndex.coerceAtMost(remainingLast)
            else -> s.activeSectionIndex
        }
        _state.update { it.copy(activeSectionIndex = nextIndex) }
    }

    fun moveSongSection(songId: String, from: Int, to: Int) {
        val s = _state.value
        library.moveSongSection(songId, from, to)
        reloadLibrary()
        if (s.activeSongId != songId) return
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

    fun setSongSectionAutoAdvance(songId: String, sectionId: String, autoAdvance: Boolean) {
        val song = library.getSong(songId) ?: return
        val section = library.getSection(sectionId)
        val enabled = autoAdvance && (section?.bars ?: 0) > 0
        library.setSongSectionAutoAdvance(songId, sectionId, enabled)
        reloadLibrary()
    }

    fun setSongSectionAutoAdvanceAt(songId: String, index: Int, autoAdvance: Boolean) {
        val song = library.getSong(songId) ?: return
        val ref = song.sectionRefs.getOrNull(index) ?: return
        val section = library.getSection(ref.sectionId)
        val enabled = autoAdvance && (section?.bars ?: 0) > 0
        library.setSongSectionAutoAdvanceAt(songId, index, enabled)
        reloadLibrary()
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

    fun setSetlistLoop(setlistId: String, enabled: Boolean) {
        if (library.getSetlist(setlistId) == null) return
        library.setSetlistLoop(setlistId, enabled)
        reloadLibrary()
    }

    fun addSongToSetlist(setlistId: String, songId: String) {
        if (!library.addSongToSetlist(setlistId, songId)) return
        reloadLibrary { it.copy(tapHint = "SONG ADDED") }
    }

    fun removeSongFromSetlist(setlistId: String, songId: String) {
        val s = _state.value
        library.removeSongFromSetlist(setlistId, songId)
        reloadLibrary()
        if (s.activeSetlistId != setlistId || s.activeSectionIndex < 0) return
        val last = _state.value.activeSlots().lastIndex
        _state.update {
            it.copy(activeSectionIndex = if (last < 0) -1 else it.activeSectionIndex.coerceIn(0, last))
        }
    }

    fun moveSetlistSong(setlistId: String, from: Int, to: Int) {
        val s = _state.value
        val current = s.activeSlots().getOrNull(s.activeSectionIndex)
        library.moveSetlistSong(setlistId, from, to)
        reloadLibrary()
        if (s.activeSetlistId != setlistId || current == null) return
        val newIndex = _state.value.activeSlots().indexOfFirst {
            it.songId == current.songId && it.section.id == current.section.id
        }
        if (newIndex >= 0) _state.update { it.copy(activeSectionIndex = newIndex) }
    }

    fun deleteSetlist(id: String): DeleteResult {
        library.deleteSetlist(id)
        reloadLibrary()
        if (_state.value.activeSetlistId == id) exitSetlist()
        return DeleteResult.Deleted
    }

    fun setSectionBpm(sectionId: String, bpm: Int) {
        mutateSection(sectionId) {
            it.copy(bpm = bpm.coerceIn(MetronomeLimits.MIN_BPM, MetronomeLimits.MAX_BPM))
        }
    }

    fun setSectionTimeSignature(sectionId: String, signature: TimeSignature) {
        val normalized = TimeSignature.normalize(signature.beats, signature.noteValue) ?: return
        mutateSection(sectionId) { section ->
            section.copy(
                beats = normalized.beats,
                noteValue = normalized.noteValue,
                beatAccents = BeatAccent.defaultPattern(normalized.beats, normalized.noteValue),
                groupTempo = section.groupTempo && normalized.isCompound,
            )
        }
    }

    fun setSectionSubdivision(sectionId: String, subdivision: Subdivision) {
        mutateSection(sectionId) { it.copy(subdivision = subdivision) }
    }

    fun setSectionSwing(sectionId: String, swing: SwingFeel) {
        mutateSection(sectionId) { it.copy(swing = swing) }
    }

    fun setSectionTone(sectionId: String, tone: MetronomeTone) {
        val applied = availableTones().find { it.id == tone.id } ?: MetronomeTone.DEFAULT
        mutateSection(sectionId) { it.copy(toneId = applied.id) }
    }

    fun setSectionAccentNote(sectionId: String, note: AccentNote) {
        mutateSection(sectionId) { it.copy(accentNote = note) }
    }

    fun setSectionRestNote(sectionId: String, note: AccentNote) {
        mutateSection(sectionId) { it.copy(restNote = note) }
    }

    fun setSectionBeatAccents(sectionId: String, levels: List<BeatAccent>) {
        mutateSection(sectionId) { section ->
            section.copy(
                beatAccents = BeatAccent.decode(
                    BeatAccent.encode(levels),
                    section.beats,
                    section.noteValue,
                ),
            )
        }
    }

    fun setSectionGroupTempo(sectionId: String, enabled: Boolean) {
        mutateSection(sectionId) { section ->
            section.copy(groupTempo = enabled && section.timeSignature.isCompound)
        }
    }

    fun setSectionCountInBars(sectionId: String, bars: Int) {
        mutateSection(sectionId) { it.copy(countInBars = bars.coerceIn(0, 4)) }
    }

    fun setSectionLabel(sectionId: String, label: String?) {
        mutateSection(sectionId) {
            it.copy(name = label?.trim()?.takeIf { trimmed -> trimmed.isNotEmpty() })
        }
    }

    fun setSectionBars(sectionId: String, bars: Int) {
        val clamped = bars.coerceIn(0, SECTION_BARS_MAX)
        mutateSection(sectionId) { it.copy(bars = clamped) }
        if (clamped == 0) {
            library.clearAutoAdvanceForSection(sectionId)
            reloadLibrary()
        }
    }

    fun captureCurrentIntoSection(sectionId: String) {
        val s = _state.value
        mutateSection(sectionId) { current ->
            snapshotSection(s, id = current.id, name = current.name).copy(bars = current.bars)
        }
    }

    private fun mutateSection(sectionId: String, transform: (Section) -> Section) {
        val section = library.getSection(sectionId) ?: return
        library.upsertSection(transform(section))
        reloadLibrary()
        maybeReapplyActiveSection(sectionId)
    }

    /** Stopped + this section is active/loaded → applySectionSetup. Playing → persist only. */
    private fun maybeReapplyActiveSection(sectionId: String) {
        val s = _state.value
        if (s.isPlaying) return
        val slots = s.activeSlots()
        val activeSlot = slots.getOrNull(s.activeSectionIndex)
        val section = when {
            activeSlot?.section?.id == sectionId ->
                _state.value.activeSlots().getOrNull(s.activeSectionIndex)?.section
            s.activeSavedSectionId == sectionId ->
                _state.value.sections.firstOrNull { it.id == sectionId }
            else -> null
        } ?: return
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
                    activeSongId = null,
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
                activeSongId = null,
                activeSectionIndex = 0,
                sectionBar = 0,
                tapHint = first.section.displayName().uppercase(),
            )
        }
    }

    fun loadSong(song: Song) {
        val resolved = _state.value.songs.firstOrNull { it.id == song.id } ?: song
        pendingSetNav = PendingSetNav.NONE
        sectionStartBar = barIndex
        val first = _state.value.songSlots(resolved).firstOrNull()
        if (first == null) {
            _state.update {
                it.copy(
                    activeSetlistId = null,
                    activeSongId = resolved.id,
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
                activeSetlistId = null,
                activeSongId = resolved.id,
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
            it.copy(
                activeSetlistId = null,
                activeSongId = null,
                activeSectionIndex = -1,
                sectionBar = 0,
            )
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
            savedSections = sections,
            setlists = library.loadSetlists(),
        )
    }

    private fun maybeAdvanceSection(bar: Int) {
        val s = _state.value
        if (!s.inSetMode) return
        val slots = s.activeSlots()
        val slot = slots.getOrNull(s.activeSectionIndex) ?: return
        val elapsed = bar - sectionStartBar
        val reachedEnd = slot.autoAdvance && slot.section.bars > 0 && elapsed >= slot.section.bars
        val nav = pendingSetNav
        if (nav == PendingSetNav.NONE && !reachedEnd) {
            _state.update { it.copy(sectionBar = elapsed.coerceAtLeast(0)) }
            return
        }
        pendingSetNav = PendingSetNav.NONE
        val targetIndex = nextSequenceIndex(s, slots, nav) ?: run {
            finishNonLoopingSequence()
            return
        }
        applySlotAt(targetIndex, bar)
    }

    /**
     * Flattened (song, section) sequence with song-boundary wrap:
     * - NEXT inside a song → next section
     * - NEXT past last section of a non-last setlist song → first section of the next song
     * - NEXT past the last slot → setlist.loop wraps to 0, else last song.loop wraps to
     *   that song's first slot, else null (stop)
     * Standalone loadSong uses Song.loop the same way at the end of its slots.
     */
    private fun nextSequenceIndex(
        s: MetronomeUiState,
        slots: List<SetlistSlot>,
        nav: PendingSetNav,
    ): Int? {
        val i = s.activeSectionIndex
        return when (nav) {
            PendingSetNav.RESTART -> 0
            PendingSetNav.PREVIOUS -> when {
                i > 0 -> i - 1
                sequenceWraps(s, slots) -> slots.lastIndex
                else -> 0
            }
            PendingSetNav.NEXT, PendingSetNav.NONE -> {
                val nextIndex = i + 1
                if (nextIndex <= slots.lastIndex) {
                    nextIndex
                } else {
                    wrapIndex(s, slots)
                }
            }
        }
    }

    private fun sequenceWraps(s: MetronomeUiState, slots: List<SetlistSlot>): Boolean {
        if (slots.isEmpty()) return false
        val setlist = s.activeSetlist()
        val song = currentSequenceSong(s, slots)
        return setlist?.loop == true || song?.loop == true
    }

    private fun wrapIndex(s: MetronomeUiState, slots: List<SetlistSlot>): Int? {
        if (slots.isEmpty()) return null
        val setlist = s.activeSetlist()
        val song = currentSequenceSong(s, slots)
        return when {
            setlist?.loop == true -> 0
            song?.loop == true -> slots.indexOfFirst { it.songId == song.id }.takeIf { it >= 0 } ?: 0
            else -> null
        }
    }

    private fun currentSequenceSong(s: MetronomeUiState, slots: List<SetlistSlot>): Song? {
        val songId = slots.getOrNull(s.activeSectionIndex)?.songId ?: s.activeSongId
        return s.songs.firstOrNull { it.id == songId }
    }

    private fun finishNonLoopingSequence() {
        stop()
        applySlotAt(0, barIndex)
    }

    private fun armLoadedSetFromTop() {
        if (_state.value.activeSlots().isEmpty()) return
        applySlotAt(0, barIndex)
    }

    private fun applySlotAt(index: Int, bar: Int) {
        val slot = _state.value.activeSlots().getOrNull(index) ?: return
        applySectionSetup(slot.section)
        sectionStartBar = bar
        _state.update {
            it.copy(
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
                    savedSections = sections,
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

