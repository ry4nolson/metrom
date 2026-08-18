package com.metrom.shared.data

import com.metrom.shared.platform.PrefsStore
import com.metrom.shared.randomUuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class SetSection(
    val id: String = randomUuid(),
    val label: String? = null,
    val config: SongPreset,
    val bars: Int,
    val autoAdvance: Boolean,
)

data class Setlist(
    val id: String = randomUuid(),
    val name: String,
    val sections: List<SetSection>,
    val loop: Boolean = false,
)

@Serializable
private data class SetSectionDto(
    val id: String,
    val label: String? = null,
    val config: SongDto,
    val bars: Int,
    val autoAdvance: Boolean,
)

@Serializable
private data class SetlistDto(
    val id: String,
    val name: String,
    val sections: List<SetSectionDto> = emptyList(),
    val loop: Boolean = false,
)

class SetlistStore(private val prefs: PrefsStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): List<Setlist> {
        val raw = prefs.getString(KEY) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<SetlistDto>>(raw).map { it.toSetlist() }
        }.getOrElse {
            // Pre-release: discard corrupt state and reset.
            prefs.remove(KEY)
            emptyList()
        }
    }

    fun saveAll(setlists: List<Setlist>) {
        val dtos = setlists.map { it.toDto() }
        prefs.putString(KEY, json.encodeToString(dtos))
    }

    private fun Setlist.toDto() = SetlistDto(
        id = id,
        name = name,
        sections = sections.map { it.toDto() },
        loop = loop,
    )

    private fun SetSection.toDto() = SetSectionDto(
        id = id,
        label = label,
        config = config.toDto(),
        bars = bars,
        autoAdvance = autoAdvance,
    )

    private fun SetlistDto.toSetlist() = Setlist(
        id = id,
        name = name,
        sections = sections.mapNotNull { it.toSection() },
        loop = loop,
    )

    private fun SetSectionDto.toSection(): SetSection? {
        val preset = config.toPreset() ?: return null
        return SetSection(
            id = id,
            label = label,
            config = preset,
            bars = bars.coerceAtLeast(0),
            autoAdvance = autoAdvance,
        )
    }

    companion object {
        private const val KEY = "setlists_json"
    }
}
