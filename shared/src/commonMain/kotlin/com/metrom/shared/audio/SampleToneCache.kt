package com.metrom.shared.audio

import com.metrom.shared.domain.SampleTone
import com.metrom.shared.platform.AssetIO

data class CachedSampleBuffers(
    val strong: ShortArray,
    val normal: ShortArray,
    val ghost: ShortArray?,
)

class SampleToneCache(private val assets: AssetIO) {
    private val cache = mutableMapOf<String, CachedSampleBuffers>()

    fun get(tone: SampleTone): CachedSampleBuffers? {
        cache[tone.id]?.let { return it }
        val loaded = load(tone) ?: return null
        cache[tone.id] = loaded
        return loaded
    }

    fun peek(id: String): CachedSampleBuffers? = cache[id]

    private fun load(tone: SampleTone): CachedSampleBuffers? {
        val strongPath = "${tone.assetDir}/strong.wav"
        val normalPath = "${tone.assetDir}/normal.wav"
        val ghostPath = "${tone.assetDir}/ghost.wav"

        val strongBytes = assets.open(strongPath) ?: return null
        val normalBytes = assets.open(normalPath) ?: return null
        val strong = runCatching { WavDecoder.decodeStrict(strongBytes, strongPath) }.getOrNull()
            ?: return null
        val normal = runCatching { WavDecoder.decodeStrict(normalBytes, normalPath) }.getOrNull()
            ?: return null
        val ghost = if (!assets.exists(ghostPath)) {
            null
        } else {
            assets.open(ghostPath)?.let { bytes ->
                runCatching { WavDecoder.decodeStrict(bytes, ghostPath) }.getOrNull()
            }
        }
        return CachedSampleBuffers(strong = strong, normal = normal, ghost = ghost)
    }
}
