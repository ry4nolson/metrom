package com.metrom.shared.data

import com.metrom.shared.randomUuid

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
