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
import com.metrom.shared.audio.SampleToneCache
import com.metrom.shared.data.SongPreset
import com.metrom.shared.detect.DetectDebug
import com.metrom.shared.detect.DetectState
import com.metrom.shared.domain.AccentNote
import com.metrom.shared.domain.MetronomeTone
import com.metrom.shared.domain.MutePattern
import com.metrom.shared.domain.Subdivision
import com.metrom.shared.domain.SwingFeel
import com.metrom.shared.domain.TimeSignature
import com.metrom.shared.engine.MetronomeEngine
import com.metrom.shared.practice.MetronomeController
import com.metrom.shared.practice.MetronomeUiState
import kotlinx.coroutines.flow.StateFlow

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

    private val controller = MetronomeController(
        prefs = prefs,
        haptics = AndroidHaptics(application),
        sampleCache = sampleCache,
        engine = engine,
        runner = runner,
        micCapture = AndroidMicCapture(application),
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

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        mainHandler.post {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    resumeAfterFocusGain = false
                    if (state.value.isPlaying) stop()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    if (state.value.isPlaying) {
                        resumeAfterFocusGain = true
                        controller.pauseTransient()
                        unregisterBecomingNoisy()
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
        // Audio thread ended without a normal ViewModel.stop (start failure, write error).
        runner.onSessionEnded = {
            mainHandler.post {
                if (!state.value.isPlaying) return@post
                resumeAfterFocusGain = false
                controller.stop()
                unregisterBecomingNoisy()
                abandonAudioFocus()
            }
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
        if (!requestAudioFocus()) {
            unregisterBecomingNoisy()
            return
        }
        controller.start()
        // Keep becoming-noisy registration matched to actual playback.
        if (state.value.isPlaying) {
            registerBecomingNoisy()
        } else {
            unregisterBecomingNoisy()
            abandonAudioFocus()
        }
    }

    fun stop() {
        resumeAfterFocusGain = false
        controller.stop()
        unregisterBecomingNoisy()
        abandonAudioFocus()
    }

    fun setBpm(bpm: Int, persist: Boolean = true) = controller.setBpm(bpm, persist)
    fun nudgeBpm(delta: Int) = controller.nudgeBpm(delta)
    fun setTimeSignature(signature: TimeSignature) = controller.setTimeSignature(signature)
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
    fun saveCurrentSong(name: String? = null) = controller.saveCurrentSong(name)
    fun loadSong(song: SongPreset) = controller.loadSong(song)
    fun deleteSong(song: SongPreset) = controller.deleteSong(song)
    fun renameSong(song: SongPreset, name: String) = controller.renameSong(song, name)
    fun updateActiveSong() = controller.updateActiveSong()
    fun tapTempo() = controller.tapTempo(System.currentTimeMillis())
    fun startListen() = controller.startListen()
    fun cancelListen() = controller.cancelListen()
    fun resetListen() = controller.resetListen()
    fun clearListenDebug() = controller.clearListenDebug()
    fun applyListenBpm(bpm: Int) = controller.applyListenBpm(bpm)
    fun onListenLifecyclePause() = controller.onListenLifecyclePause()

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
        abandonAudioFocus()
        controller.dispose()
        PlaybackBridge.playing = false
        PlaybackService.sync(getApplication())
        super.onCleared()
    }
}
