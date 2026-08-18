package com.metrom.shared.domain

import com.metrom.shared.platform.PrefsStore

class CustomMeterStore(private val prefs: PrefsStore) {
    fun all(): List<TimeSignature> {
        val raw = prefs.getString(KEY) ?: return emptyList()
        return raw.split("\n").mapNotNull { TimeSignature.parse(it) }
            .filter { it !in TimeSignature.COMMON }
            .distinct()
    }

    fun add(beats: Int, noteValue: Int): TimeSignature? {
        val sig = TimeSignature.normalize(beats, noteValue) ?: return null
        if (sig in TimeSignature.COMMON) return sig
        val next = all() + sig
        write(next.distinct())
        return sig
    }

    fun remove(signature: TimeSignature) {
        write(all().filter { it != signature })
    }

    private fun write(list: List<TimeSignature>) {
        if (list.isEmpty()) prefs.remove(KEY)
        else prefs.putString(KEY, list.joinToString("\n") { it.label })
    }

    companion object {
        private const val KEY = "customMeters"
    }
}
