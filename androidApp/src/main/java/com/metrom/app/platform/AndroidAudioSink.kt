package com.metrom.app.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.metrom.shared.platform.AudioRouteHint
import com.metrom.shared.platform.AudioSink
import kotlin.math.max

class AndroidAudioSink(
    private val audioManager: AudioManager?,
) : AudioSink {
    @Volatile private var track: AudioTrack? = null

    override fun start(sampleRate: Int, channelCount: Int, preferredBufferFrames: Int): Int {
        releaseTrack()
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val writeFrames = max(minBuf, preferredBufferFrames)
        val trackBufferBytes = writeFrames * 2 * 2 // ~200ms
        val local = AudioTrack.Builder()
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
        track = local
        local.setVolume(1f)
        local.play()
        return writeFrames
    }

    override fun write(pcm: ShortArray, offset: Int, count: Int): Int {
        val t = track ?: return -1
        return t.write(pcm, offset, count, AudioTrack.WRITE_BLOCKING)
    }

    override fun playbackHeadFrames(): Long {
        val t = track ?: return 0L
        return Integer.toUnsignedLong(t.playbackHeadPosition)
    }

    override fun stop() {
        val t = track ?: return
        try {
            t.pause()
            t.flush()
            t.stop()
        } catch (_: Exception) {
        }
        try {
            t.release()
        } catch (_: Exception) {
        }
        if (track === t) track = null
    }

    override fun dispose() {
        stop()
    }

    override fun routeHint(): AudioRouteHint {
        val t = track
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
        track?.setVolume(volume.coerceIn(0f, 1f))
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

    private fun releaseTrack() {
        stop()
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
}
