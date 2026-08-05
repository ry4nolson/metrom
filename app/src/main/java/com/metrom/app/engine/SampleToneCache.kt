package com.metrom.app.engine

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Decoded PCM for a [SampleTone]. Loaded once, then reused — never from the audio loop.
 */
data class CachedSampleBuffers(
    val strong: ShortArray,
    val normal: ShortArray,
    /** Null → engine uses [normal] at the existing subdivision gain (~0.32x). */
    val ghost: ShortArray?,
)

/**
 * Per-tone ShortArray cache. Decode happens at selection / prepare time only.
 */
object SampleToneCache {
    private const val TAG = "SampleToneCache"

    private val cache = ConcurrentHashMap<String, CachedSampleBuffers>()

    /** Return cached buffers, decoding on first successful load. Null = fall back to synth. */
    fun get(context: Context, tone: SampleTone): CachedSampleBuffers? {
        cache[tone.id]?.let { return it }
        val loaded = load(context.applicationContext, tone) ?: return null
        val raced = cache.putIfAbsent(tone.id, loaded)
        return raced ?: loaded
    }

    /** Already-decoded buffers, or null if this tone was never loaded successfully. */
    fun peek(id: String): CachedSampleBuffers? = cache[id]

    private fun load(context: Context, tone: SampleTone): CachedSampleBuffers? {
        val strongPath = "${tone.assetDir}/strong.wav"
        val normalPath = "${tone.assetDir}/normal.wav"
        val ghostPath = "${tone.assetDir}/ghost.wav"

        val strong = WavSampleLoader.loadToneWav(context, strongPath).getOrElse { e ->
            Log.w(TAG, "Sample tone '${tone.id}' unavailable ($strongPath): ${e.message}")
            return null
        }
        val normal = WavSampleLoader.loadToneWav(context, normalPath).getOrElse { e ->
            Log.w(TAG, "Sample tone '${tone.id}' unavailable ($normalPath): ${e.message}")
            return null
        }
        // ghost optional — absent is fine; present-but-invalid → null ghost + one log
        val ghost: ShortArray? =
            if (!WavSampleLoader.toneAssetExists(context, ghostPath)) {
                null
            } else {
                WavSampleLoader.loadToneWav(context, ghostPath).fold(
                    onSuccess = { it },
                    onFailure = { e ->
                        Log.w(
                            TAG,
                            "Sample tone '${tone.id}': ghost.wav skipped (${e.message}); " +
                                "using normal for subdivisions"
                        )
                        null
                    }
                )
            }
        return CachedSampleBuffers(strong = strong, normal = normal, ghost = ghost)
    }
}
