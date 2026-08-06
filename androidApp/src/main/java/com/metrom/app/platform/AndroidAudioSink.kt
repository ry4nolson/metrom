package com.metrom.app.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.metrom.shared.platform.AudioRouteHint
import com.metrom.shared.platform.AudioSink
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

class AndroidAudioSink(
    private val audioManager: AudioManager?,
) : AudioSink {
    /**
     * Sole owner of the live [AudioTrack].
     * Release takes ownership via [AtomicReference.getAndSet]; the winner releases,
     * every other caller sees null and is a no-op.
     */
    private val track = AtomicReference<AudioTrack?>(null)

    override fun start(sampleRate: Int, channelCount: Int, preferredBufferFrames: Int): Int {
        // Fully release any prior track before building a new one (no orphans).
        releaseOwnedTrack()
        val minBufBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufBytes == AudioTrack.ERROR ||
            minBufBytes == AudioTrack.ERROR_BAD_VALUE ||
            minBufBytes <= 0
        ) {
            Log.e(TAG, "getMinBufferSize failed: $minBufBytes sampleRate=$sampleRate")
            throw IllegalStateException("AudioTrack getMinBufferSize failed: $minBufBytes")
        }
        // Mono 16-bit: 2 bytes per frame.
        val minBufFrames = max(minBufBytes / 2, 1)
        val writeFrames = max(minBufFrames, preferredBufferFrames)
        // ~200ms at writeFrames; never below the device minimum in bytes.
        val trackBufferBytes = max(minBufBytes, writeFrames * 2 * 2)
        Log.i(
            TAG,
            "AudioTrack start sampleRate=$sampleRate minBufBytes=$minBufBytes " +
                "minBufFrames=$minBufFrames preferredBufferFrames=$preferredBufferFrames " +
                "writeFrames=$writeFrames trackBufferBytes=$trackBufferBytes",
        )
        val local = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(trackBufferBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack.Builder.build failed", t)
            throw t
        }
        // Do not publish into [track] until play succeeds — no half-built owner.
        try {
            local.setVolume(1f)
            local.play()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack setVolume/play failed", t)
            try {
                local.release()
            } catch (_: Exception) {
            }
            throw t
        }
        track.set(local)
        return writeFrames
    }

    override fun write(pcm: ShortArray, offset: Int, count: Int): Int {
        val t = track.get() ?: return -1
        return try {
            t.write(pcm, offset, count, AudioTrack.WRITE_BLOCKING)
        } catch (_: IllegalStateException) {
            -1
        } catch (_: Exception) {
            -1
        }
    }

    override fun playbackHeadFrames(): Long {
        val t = track.get() ?: return 0L
        return try {
            Integer.toUnsignedLong(t.playbackHeadPosition)
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Unblock a pending [write] (pause + flush). Keeps the [AudioTrack] reference valid.
     * Does not release. Safe if the track is already gone.
     */
    override fun stop() {
        val t = track.get() ?: return
        try {
            t.pause()
        } catch (_: Exception) {
        }
        try {
            t.flush()
        } catch (_: Exception) {
        }
    }

    /**
     * Unblock if needed, then release. Only the [AtomicReference.getAndSet] winner
     * performs native release; concurrent callers are no-ops.
     */
    override fun dispose() {
        releaseOwnedTrack()
    }

    /**
     * Claim the current track (or nothing) and release it.
     * `getAndSet(null)` is the ownership handoff: exactly one caller wins.
     */
    private fun releaseOwnedTrack() {
        val t = track.getAndSet(null) ?: return
        try {
            t.pause()
        } catch (_: Exception) {
        }
        try {
            t.flush()
        } catch (_: Exception) {
        }
        try {
            t.stop()
        } catch (_: Exception) {
        }
        try {
            t.release()
        } catch (_: Exception) {
        }
    }

    override fun routeHint(): AudioRouteHint {
        val t = track.get()
        val routed = try {
            t?.routedDevice
        } catch (_: Exception) {
            null
        }
        if (routed != null) return AndroidLatencyPad.hintFromDeviceType(routed.type)
        val inferred = inferOutputType()
        return if (inferred != null) AndroidLatencyPad.hintFromDeviceType(inferred)
        else AudioRouteHint.UNKNOWN
    }

    override fun setVolume(volume: Float) {
        try {
            track.get()?.setVolume(volume.coerceIn(0f, 1f))
        } catch (_: Exception) {
        }
    }

    fun previewStatic(pcm: ShortArray, volume: Float) {
        var preview: AudioTrack? = null
        try {
            val bytes = pcm.size * 2
            preview = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(44_100)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bytes)
                .build()
            preview.setVolume(volume.coerceIn(0.2f, 1f))
            preview.write(pcm, 0, pcm.size)
            preview.play()
            Thread.sleep((pcm.size * 1000L / 44_100L) + 40L)
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
    }

    private fun inferOutputType(): Int? {
        if (audioManager == null) return null
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val types = devices.map { it.type }
        types.firstOrNull { AndroidLatencyPad.isBluetoothType(it) }?.let { return it }
        types.firstOrNull {
            it == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        }?.let { return it }
        types.firstOrNull {
            it == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                it == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }?.let { return it }
        return types.firstOrNull()
    }

    companion object {
        const val TAG = "MetromAudio"
    }
}
