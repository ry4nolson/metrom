package com.metrom.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.metrom.app.platform.AndroidAssetIO
import com.metrom.app.platform.AndroidAudioSink
import com.metrom.app.platform.AndroidEngineRunner
import com.metrom.app.platform.AndroidHaptics
import com.metrom.app.platform.AndroidLatencyPad
import com.metrom.app.platform.AndroidMicCapture
import com.metrom.app.platform.AndroidPrefsStore
import com.metrom.app.platform.AndroidUiClock
import com.metrom.app.garmin.GarminCompanion
import com.metrom.shared.audio.SampleToneCache
import com.metrom.shared.garmin.GarminProtocol
import com.metrom.shared.library.Section
import com.metrom.shared.library.Setlist
import com.metrom.shared.detect.DetectDebug
import com.metrom.shared.detect.DetectState
import com.metrom.shared.domain.AccentNote
import com.metrom.shared.domain.MetronomeTone
import com.metrom.shared.domain.MutePattern
import com.metrom.shared.domain.Subdivision
import com.metrom.shared.domain.SwingFeel
import com.metrom.shared.domain.TimeSignature
import com.metrom.shared.domain.CustomMeterStore
import com.metrom.shared.engine.MetronomeEngine
import com.metrom.shared.practice.MetronomeController
import com.metrom.shared.practice.MetronomeUiState
import com.metrom.shared.theme.ColorTheme
import com.metrom.shared.theme.ColorThemeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MetronomeViewModel(application: Application) : AndroidViewModel(application) {
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var resumeAfterFocusGain = false
    private var noisyReceiverRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val prefs = AndroidPrefsStore(
        application.getSharedPreferences("metrom", Context.MODE_PRIVATE),
    )
    private val sampleCache = SampleToneCache(AndroidAssetIO(application))
    private val sink = AndroidAudioSink(audioManager)
    private val runner = AndroidEngineRunner(sink)

    private val engine = MetronomeEngine(
        sink = sink,
        clock = AndroidUiClock(),
        latencyPad = AndroidLatencyPad(audioManager),
        sampleCache = sampleCache,
    )

    private val garmin = GarminCompanion(application) { cmd ->
        mainHandler.post { handleGarminCommand(cmd) }
    }

    private val controller = MetronomeController(
        prefs = prefs,
        haptics = AndroidHaptics(application),
        sampleCache = sampleCache,
        engine = engine,
        runner = runner,
        micCapture = AndroidMicCapture(application),
        database = (application as MetromApplication).metromDatabase,
        canStart = { requestAudioFocus() },
        onPlaybackChanged = { playing, bpm, subtitle ->
            PlaybackBridge.playing = playing
            PlaybackBridge.bpm = bpm
            PlaybackBridge.subtitle = subtitle
            PlaybackService.sync(getApplication())
        },
        onTrainerAutoStopped = {
            resumeAfterFocusGain = false
            unregisterBecomingNoisy()
            abandonAudioFocus()
        },
    )

    val state: StateFlow<MetronomeUiState> = controller.state
    val detectState: StateFlow<DetectState> = controller.detectState
    val detectDebug: StateFlow<DetectDebug?> = controller.detectDebug

    private val themeStore = ColorThemeStore(prefs)
    private val _theme = MutableStateFlow(themeStore.load())
    val theme: StateFlow<ColorTheme> = _theme.asStateFlow()
    private val _savedThemes = MutableStateFlow(themeStore.saved())
    val savedThemes: StateFlow<List<ColorTheme>> = _savedThemes.asStateFlow()
    private val meterStore = CustomMeterStore(prefs)
    private val _customMeters = MutableStateFlow(meterStore.all())
    val customMeters: StateFlow<List<TimeSignature>> = _customMeters.asStateFlow()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        mainHandler.post {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // Permanent loss: clear focusRequest even if not playing, so the
                    // idempotent requestAudioFocus() does not falsely report a hold.
                    resumeAfterFocusGain = false
                    if (state.value.isPlaying) {
                        stop()
                    } else {
                        abandonAudioFocus()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    if (state.value.isPlaying) {
                        resumeAfterFocusGain = true
                        controller.pauseTransient()
                        unregisterBecomingNoisy()
                        // Keep focusRequest while stalled/paused — system still owns the grant.
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (resumeAfterFocusGain && !state.value.isPlaying) {
                        resumeAfterFocusGain = false
                        start()
                    }
                }
            }
        }
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            mainHandler.post {
                resumeAfterFocusGain = false
                if (state.value.isPlaying) stop()
            }
        }
    }

    init {
        engine.onBeat = { event -> controller.handleBeat(event) }
        controller.listenCaptureRunner = { capture ->
            Thread({ controller.runListenCapture(capture) }, "TempoListen").start()
        }
        PlaybackBridge.onToggle = { togglePlay() }
        PlaybackBridge.onStop = { stop() }
        runner.onEngineFailed = {
            mainHandler.post {
                resumeAfterFocusGain = false
                controller.handleEngineFailed()
                unregisterBecomingNoisy()
                abandonAudioFocus()
            }
        }
        runner.onWedgeCleared = {
            mainHandler.post {
                // Zombie released its track; safe to drop focus if UI already stopped.
                if (!state.value.isPlaying) abandonAudioFocus()
            }
        }
        garmin.bind(controller.state)
    }

    private fun handleGarminCommand(cmd: GarminProtocol.Command) {
        when (cmd) {
            GarminProtocol.Command.Sync -> Unit
            GarminProtocol.Command.Toggle -> togglePlay()
            GarminProtocol.Command.Play -> if (!state.value.isPlaying) start()
            GarminProtocol.Command.Stop -> if (state.value.isPlaying) stop()
            GarminProtocol.Command.Tap -> tapTempo()
            is GarminProtocol.Command.Nudge -> nudgeBpm(cmd.delta)
            is GarminProtocol.Command.SetBpm -> setBpm(cmd.bpm)
            is GarminProtocol.Command.Meter -> setTimeSignature(TimeSignature(cmd.beats, cmd.note))
        }
    }

    fun togglePlay() {
        if (state.value.isPlaying) {
            stop()
        } else {
            start()
        }
    }

    fun start() {
        // Focus gating lives in canStart → controller lands "AUDIO BUSY" on deny.
        controller.start()
        if (state.value.isPlaying) {
            registerBecomingNoisy()
        } else {
            unregisterBecomingNoisy()
            // Keep focus while a WEDGED thread may still produce audio.
            if (state.value.tapHint != "AUDIO STALLED") abandonAudioFocus()
        }
    }

    fun stop() {
        resumeAfterFocusGain = false
        val clean = controller.stop()
        unregisterBecomingNoisy()
        // Do not abandon focus while a WEDGED thread may still produce audio.
        if (clean) abandonAudioFocus()
    }

    fun setBpm(bpm: Int, persist: Boolean = true) = controller.setBpm(bpm, persist)
    fun nudgeBpm(delta: Int) = controller.nudgeBpm(delta)
    fun setTimeSignature(signature: TimeSignature) = controller.setTimeSignature(signature)

    fun addCustomMeter(beats: Int, noteValue: Int) {
        val sig = meterStore.add(beats, noteValue) ?: return
        _customMeters.value = meterStore.all()
        setTimeSignature(sig)
    }

    fun deleteCustomMeter(signature: TimeSignature) {
        meterStore.remove(signature)
        _customMeters.value = meterStore.all()
    }
    fun setSwing(feel: SwingFeel) = controller.setSwing(feel)
    fun toggleGroupTempo() = controller.toggleGroupTempo()
    fun cycleBeatAccent(index: Int) = controller.cycleBeatAccent(index)
    fun resetBeatAccents() = controller.resetBeatAccents()
    fun setSubdivision(subdivision: Subdivision) = controller.setSubdivision(subdivision)
    fun setTone(tone: MetronomeTone, preview: Boolean = true) = controller.setTone(tone, preview)
    fun setAccentNote(note: AccentNote, preview: Boolean = true) = controller.setAccentNote(note, preview)
    fun setRestNote(note: AccentNote, preview: Boolean = true) = controller.setRestNote(note, preview)
    fun previewTone() = controller.previewTone()
    fun setVolume(volume: Float) = controller.setVolume(volume)
    fun toggleMute() = controller.toggleMute()
    fun toggleHaptics() = controller.toggleHaptics()
    fun setCountInBars(bars: Int) = controller.setCountInBars(bars)
    fun setMutePattern(pattern: MutePattern) = controller.setMutePattern(pattern)
    fun toggleTrainer() = controller.toggleTrainer()
    fun setTrainerTarget(bpm: Int) = controller.setTrainerTarget(bpm)
    fun cycleTrainerTarget() = controller.cycleTrainerTarget()
    fun setTrainerStep(step: Int) = controller.setTrainerStep(step)
    fun setTrainerEveryBars(bars: Int) = controller.setTrainerEveryBars(bars)
    fun toggleTrainerAutoStop() = controller.toggleTrainerAutoStop()
    fun saveCurrentSection(name: String? = null) = controller.saveCurrentSection(name)
    fun loadSection(section: Section) = controller.loadSection(section)
    fun deleteSection(section: Section) { controller.deleteSection(section) }
    fun renameSection(section: Section, name: String) = controller.renameSection(section, name)
    fun updateActiveSection() = controller.updateActiveSection()
    fun createSetlist(name: String) = controller.createSetlist(name)
    fun renameSetlist(id: String, name: String) = controller.renameSetlist(id, name)
    fun deleteSetlist(id: String) { controller.deleteSetlist(id) }
    fun addSectionFromCurrent(setlistId: String) = controller.addSectionFromCurrent(setlistId)
    fun removeSection(setlistId: String, sectionId: String) = controller.removeSection(setlistId, sectionId)
    fun moveSection(setlistId: String, from: Int, to: Int) = controller.moveSection(setlistId, from, to)
    fun setSectionBars(setlistId: String, sectionId: String, bars: Int) = controller.setSectionBars(setlistId, sectionId, bars)
    fun setSectionAutoAdvance(setlistId: String, sectionId: String, autoAdvance: Boolean) =
        controller.setSectionAutoAdvance(setlistId, sectionId, autoAdvance)
    fun loadSetlist(setlist: Setlist) = controller.loadSetlist(setlist)
    fun advanceSection() = controller.advanceSection()
    fun exitSetlist() = controller.exitSetlist()
    fun tapTempo() = controller.tapTempo(System.currentTimeMillis())
    fun startListen() = controller.startListen()
    fun cancelListen() = controller.cancelListen()
    fun resetListen() = controller.resetListen()
    fun clearListenDebug() = controller.clearListenDebug()
    fun applyListenBpm(bpm: Int) = controller.applyListenBpm(bpm)
    fun onListenLifecyclePause() = controller.onListenLifecyclePause()

    fun selectColorTheme(id: String) {
        themeStore.select(id)
        refreshTheme()
    }

    fun customizeCurrentTheme() {
        themeStore.saveCustom(_theme.value)
        refreshTheme()
    }

    fun updateThemeSlot(key: String, hex: String) {
        themeStore.updateSlot(key, hex)
        refreshTheme()
    }

    fun saveNamedTheme(name: String) {
        themeStore.saveNamed(name, _theme.value)
        refreshTheme()
    }

    fun deleteSavedTheme(id: String) {
        themeStore.deleteSaved(id)
        refreshTheme()
    }

    private fun refreshTheme() {
        _theme.value = themeStore.load()
        _savedThemes.value = themeStore.saved()
    }

    private fun requestAudioFocus(): Boolean {
        // Idempotent: start() and canStart() both call this; do not abandon/re-request.
        if (focusRequest != null) return true
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
        ContextCompat.registerReceiver(
            getApplication(),
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
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

    override fun onCleared() {
        PlaybackBridge.onToggle = null
        PlaybackBridge.onStop = null
        resumeAfterFocusGain = false
        mainHandler.removeCallbacksAndMessages(null)
        unregisterBecomingNoisy()
        controller.dispose()
        garmin.dispose()
        abandonAudioFocus()
        PlaybackBridge.playing = false
        PlaybackService.sync(getApplication())
        super.onCleared()
    }
}
