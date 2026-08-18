package com.metrom.shared.theme

import com.metrom.shared.platform.PrefsStore

data class ColorSlot(
    val key: String,
    val label: String,
    val group: String,
)

data class ColorTheme(
    val id: String,
    val label: String,
    val ink: String,
    val inkElevated: String,
    val inkLine: String,
    val ash: String,
    val mist: String,
    val bone: String,
    val ember: String,
    val emberSoft: String,
    val emberDeep: String,
    val copper: String,
    val pulse: String,
    val backgroundTop: String,
    val backgroundBottom: String,
) {
    fun hex(key: String): String = when (key) {
        ColorSlots.INK -> ink
        ColorSlots.INK_ELEVATED -> inkElevated
        ColorSlots.INK_LINE -> inkLine
        ColorSlots.ASH -> ash
        ColorSlots.MIST -> mist
        ColorSlots.BONE -> bone
        ColorSlots.EMBER -> ember
        ColorSlots.EMBER_SOFT -> emberSoft
        ColorSlots.EMBER_DEEP -> emberDeep
        ColorSlots.COPPER -> copper
        ColorSlots.PULSE -> pulse
        ColorSlots.BACKGROUND_TOP -> backgroundTop
        ColorSlots.BACKGROUND_BOTTOM -> backgroundBottom
        else -> ember
    }

    fun withHex(key: String, hex: String): ColorTheme {
        val value = normalizeHex(hex) ?: return this
        return when (key) {
            ColorSlots.INK -> copy(ink = value)
            ColorSlots.INK_ELEVATED -> copy(inkElevated = value)
            ColorSlots.INK_LINE -> copy(inkLine = value)
            ColorSlots.ASH -> copy(ash = value)
            ColorSlots.MIST -> copy(mist = value)
            ColorSlots.BONE -> copy(bone = value)
            ColorSlots.EMBER -> copy(ember = value)
            ColorSlots.EMBER_SOFT -> copy(emberSoft = value)
            ColorSlots.EMBER_DEEP -> copy(emberDeep = value)
            ColorSlots.COPPER -> copy(copper = value)
            ColorSlots.PULSE -> copy(pulse = value)
            ColorSlots.BACKGROUND_TOP -> copy(backgroundTop = value)
            ColorSlots.BACKGROUND_BOTTOM -> copy(backgroundBottom = value)
            else -> this
        }
    }

    fun asCustom(): ColorTheme = copy(id = CUSTOM_ID, label = "Custom")

    fun isLight(): Boolean = relativeLuminance(ink) >= 0.55f

    fun isSaved(): Boolean = id.startsWith(SAVED_PREFIX)

    fun accentSwatches(): List<String> = listOf(ember, emberSoft, emberDeep, copper, pulse)

    fun stageSwatches(): List<String> = listOf(ink, inkLine)

    companion object {
        const val CUSTOM_ID = "custom"
        const val SAVED_PREFIX = "saved-"

        val EMBER = stage(
            id = "ember",
            label = "Ember",
            ember = "FF6A3D",
            emberSoft = "FF8F66",
            emberDeep = "E04520",
            copper = "D4A574",
            pulse = "FFC857",
            ink = "100C0A",
            inkElevated = "1C1612",
            inkLine = "3A2C26",
            ash = "8B827A",
            mist = "D0C8C0",
            bone = "F4EFE8",
            backgroundTop = "1A1210",
            backgroundBottom = "0A0706",
        )
        val ICE = stage(
            id = "ice",
            label = "Ice",
            ember = "3DB8FF",
            emberSoft = "6CC8FF",
            emberDeep = "1A7CC7",
            copper = "7EB3C9",
            pulse = "7EE0FF",
            ink = "070B12",
            inkElevated = "101820",
            inkLine = "243848",
            ash = "7A8A96",
            mist = "C0D0DC",
            bone = "ECF2F6",
            backgroundTop = "0C141C",
            backgroundBottom = "05080C",
        )
        val FOREST = stage(
            id = "forest",
            label = "Forest",
            ember = "3DDB8A",
            emberSoft = "6AE8A8",
            emberDeep = "1FA05C",
            copper = "A8C47A",
            pulse = "C4F07A",
            ink = "070C09",
            inkElevated = "101814",
            inkLine = "244030",
            ash = "7A8B80",
            mist = "C4D4C8",
            bone = "ECF4EE",
            backgroundTop = "0C1610",
            backgroundBottom = "050806",
        )
        val VIOLET = stage(
            id = "violet",
            label = "Violet",
            ember = "A56BFF",
            emberSoft = "C49BFF",
            emberDeep = "6E3AD4",
            copper = "C4A5E8",
            pulse = "E07AFF",
            ink = "0C0814",
            inkElevated = "181222",
            inkLine = "382850",
            ash = "8A8096",
            mist = "D0C4DC",
            bone = "F2ECF6",
            backgroundTop = "140E1C",
            backgroundBottom = "08060C",
        )
        val ROSE = stage(
            id = "rose",
            label = "Rose",
            ember = "FF4D7A",
            emberSoft = "FF7A9C",
            emberDeep = "C41E4A",
            copper = "E8A0B0",
            pulse = "FFB0C8",
            ink = "12080C",
            inkElevated = "1E1016",
            inkLine = "442830",
            ash = "968088",
            mist = "DCC4CC",
            bone = "F6ECF0",
            backgroundTop = "1A0C12",
            backgroundBottom = "0A0608",
        )
        val GOLD = stage(
            id = "gold",
            label = "Gold",
            ember = "F0B429",
            emberSoft = "FFD56A",
            emberDeep = "C48A12",
            copper = "E0C090",
            pulse = "FFE28A",
            ink = "100C08",
            inkElevated = "1C1610",
            inkLine = "3E3220",
            ash = "8B8270",
            mist = "D8D0C0",
            bone = "F6F0E4",
            backgroundTop = "18140A",
            backgroundBottom = "0A0804",
        )
        val PAPER = paper(
            id = "paper",
            label = "Paper",
            ember = "E04520",
            emberSoft = "C43818",
            emberDeep = "B83214",
            copper = "A56B3C",
            pulse = "C48A12",
        )
        val SNOW = paper(
            id = "snow",
            label = "Snow",
            ember = "1A7CC7",
            emberSoft = "1568A8",
            emberDeep = "0E4F86",
            copper = "4A7A90",
            pulse = "1280A8",
            ink = "F3F7FA",
            inkElevated = "FFFFFF",
            inkLine = "D0DCE6",
            ash = "6E7A84",
            mist = "3E4A54",
            bone = "141A20",
            backgroundTop = "F7FAFC",
            backgroundBottom = "E6EEF4",
        )
        val BLOOM = paper(
            id = "bloom",
            label = "Bloom",
            ember = "C41E4A",
            emberSoft = "A81840",
            emberDeep = "8C1436",
            copper = "A06070",
            pulse = "C45A78",
            ink = "FAF3F4",
            inkElevated = "FFFCFC",
            inkLine = "E8D4D8",
            ash = "7A686C",
            mist = "4A383C",
            bone = "1C1214",
            backgroundTop = "FDF7F8",
            backgroundBottom = "F0E4E6",
        )

        val PRESETS: List<ColorTheme> = listOf(
            EMBER, ICE, FOREST, VIOLET, ROSE, GOLD, PAPER, SNOW, BLOOM,
        )

        fun preset(id: String): ColorTheme = PRESETS.find { it.id == id } ?: EMBER

        fun normalizeHex(raw: String): String? {
            val hex = raw.trim().removePrefix("#").uppercase()
            if (hex.length != 6) return null
            if (hex.any { it !in "0123456789ABCDEF" }) return null
            return hex
        }

        fun encode(theme: ColorTheme): String = listOf(
            theme.id, theme.label,
            theme.ink, theme.inkElevated, theme.inkLine,
            theme.ash, theme.mist, theme.bone,
            theme.ember, theme.emberSoft, theme.emberDeep,
            theme.copper, theme.pulse,
            theme.backgroundTop, theme.backgroundBottom,
        ).joinToString("|")

        fun decode(raw: String?): ColorTheme? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split("|")
            if (parts.size != 15) return null
            val hexes = parts.drop(2).map { normalizeHex(it) ?: return null }
            return ColorTheme(
                id = parts[0].ifBlank { CUSTOM_ID },
                label = parts[1].ifBlank { "Custom" },
                ink = hexes[0],
                inkElevated = hexes[1],
                inkLine = hexes[2],
                ash = hexes[3],
                mist = hexes[4],
                bone = hexes[5],
                ember = hexes[6],
                emberSoft = hexes[7],
                emberDeep = hexes[8],
                copper = hexes[9],
                pulse = hexes[10],
                backgroundTop = hexes[11],
                backgroundBottom = hexes[12],
            )
        }

        fun relativeLuminance(hex: String): Float {
            val n = normalizeHex(hex) ?: return 0f
            val r = n.substring(0, 2).toInt(16) / 255f
            val g = n.substring(2, 4).toInt(16) / 255f
            val b = n.substring(4, 6).toInt(16) / 255f
            return 0.2126f * r + 0.7152f * g + 0.0722f * b
        }

        private fun stage(
            id: String,
            label: String,
            ember: String,
            emberSoft: String,
            emberDeep: String,
            copper: String,
            pulse: String,
            ink: String = "0A0A0C",
            inkElevated: String = "141418",
            inkLine: String = "2A2A32",
            ash: String = "8B8B96",
            mist: String = "C8C8D0",
            bone: String = "F2F0EA",
            backgroundTop: String = "121218",
            backgroundBottom: String = "08080A",
        ) = ColorTheme(
            id = id,
            label = label,
            ink = ink,
            inkElevated = inkElevated,
            inkLine = inkLine,
            ash = ash,
            mist = mist,
            bone = bone,
            ember = ember,
            emberSoft = emberSoft,
            emberDeep = emberDeep,
            copper = copper,
            pulse = pulse,
            backgroundTop = backgroundTop,
            backgroundBottom = backgroundBottom,
        )

        private fun paper(
            id: String,
            label: String,
            ember: String,
            emberSoft: String,
            emberDeep: String,
            copper: String,
            pulse: String,
            ink: String = "F6F1E8",
            inkElevated: String = "FFFBF4",
            inkLine: String = "E2D6C4",
            ash: String = "7A7268",
            mist: String = "4A433C",
            bone: String = "1C1814",
            backgroundTop: String = "FBF6EE",
            backgroundBottom: String = "EDE4D4",
        ) = stage(
            id = id,
            label = label,
            ember = ember,
            emberSoft = emberSoft,
            emberDeep = emberDeep,
            copper = copper,
            pulse = pulse,
            ink = ink,
            inkElevated = inkElevated,
            inkLine = inkLine,
            ash = ash,
            mist = mist,
            bone = bone,
            backgroundTop = backgroundTop,
            backgroundBottom = backgroundBottom,
        )
    }
}

object ColorSlots {
    const val EMBER = "ember"
    const val EMBER_SOFT = "emberSoft"
    const val EMBER_DEEP = "emberDeep"
    const val COPPER = "copper"
    const val PULSE = "pulse"
    const val INK = "ink"
    const val INK_ELEVATED = "inkElevated"
    const val INK_LINE = "inkLine"
    const val BACKGROUND_TOP = "backgroundTop"
    const val BACKGROUND_BOTTOM = "backgroundBottom"
    const val BONE = "bone"
    const val ASH = "ash"
    const val MIST = "mist"

    val ALL: List<ColorSlot> = listOf(
        ColorSlot(EMBER, "Accent", "ACCENT"),
        ColorSlot(EMBER_SOFT, "Accent soft", "ACCENT"),
        ColorSlot(EMBER_DEEP, "Accent deep", "ACCENT"),
        ColorSlot(COPPER, "Copper", "ACCENT"),
        ColorSlot(PULSE, "Pulse", "ACCENT"),
        ColorSlot(INK, "Background", "STAGE"),
        ColorSlot(INK_ELEVATED, "Surface", "STAGE"),
        ColorSlot(INK_LINE, "Line", "STAGE"),
        ColorSlot(BACKGROUND_TOP, "Glow top", "STAGE"),
        ColorSlot(BACKGROUND_BOTTOM, "Glow bottom", "STAGE"),
        ColorSlot(BONE, "Text", "TYPE"),
        ColorSlot(ASH, "Muted", "TYPE"),
        ColorSlot(MIST, "Soft text", "TYPE"),
    )
}

class ColorThemeStore(private val prefs: PrefsStore) {
    fun selectedId(): String = prefs.getString(KEY_ID) ?: ColorTheme.EMBER.id

    fun load(): ColorTheme {
        val id = selectedId()
        if (id == ColorTheme.CUSTOM_ID) {
            return ColorTheme.decode(prefs.getString(KEY_CUSTOM))
                ?: ColorTheme.EMBER.asCustom()
        }
        ColorTheme.PRESETS.find { it.id == id }?.let { return it }
        saved().find { it.id == id }?.let { return it }
        return ColorTheme.EMBER
    }

    fun select(id: String) {
        if (id == ColorTheme.CUSTOM_ID) {
            if (prefs.getString(KEY_CUSTOM) == null) {
                saveCustom(load())
                return
            }
            prefs.putString(KEY_ID, ColorTheme.CUSTOM_ID)
            return
        }
        val known = ColorTheme.PRESETS.any { it.id == id } || saved().any { it.id == id }
        prefs.putString(KEY_ID, if (known) id else ColorTheme.EMBER.id)
    }

    fun saved(): List<ColorTheme> {
        val raw = prefs.getString(KEY_SAVED) ?: return emptyList()
        return raw.split("\n").mapNotNull { ColorTheme.decode(it) }
            .filter { it.isSaved() }
    }

    fun saveNamed(name: String, theme: ColorTheme): ColorTheme {
        val label = sanitizeLabel(name)
        val existing = saved().find { it.label.equals(label, ignoreCase = true) }
        val id = existing?.id ?: nextSavedId()
        val named = theme.copy(id = id, label = label)
        writeSaved(saved().filter { it.id != id } + named)
        prefs.putString(KEY_ID, id)
        return named
    }

    fun deleteSaved(id: String) {
        writeSaved(saved().filter { it.id != id })
        if (selectedId() != id) return
        if (prefs.getString(KEY_CUSTOM) != null) {
            prefs.putString(KEY_ID, ColorTheme.CUSTOM_ID)
        } else {
            prefs.putString(KEY_ID, ColorTheme.EMBER.id)
        }
    }

    fun saveCustom(theme: ColorTheme) {
        val custom = theme.asCustom()
        prefs.putString(KEY_CUSTOM, ColorTheme.encode(custom))
        prefs.putString(KEY_ID, ColorTheme.CUSTOM_ID)
    }

    fun updateSlot(key: String, hex: String) {
        val base = if (selectedId() == ColorTheme.CUSTOM_ID) load() else load().asCustom()
        saveCustom(base.withHex(key, hex))
    }

    private fun nextSavedId(): String {
        val n = saved().mapNotNull { it.id.removePrefix(ColorTheme.SAVED_PREFIX).toIntOrNull() }
            .maxOrNull() ?: 0
        return "${ColorTheme.SAVED_PREFIX}${n + 1}"
    }

    private fun writeSaved(list: List<ColorTheme>) {
        if (list.isEmpty()) prefs.remove(KEY_SAVED)
        else prefs.putString(KEY_SAVED, list.joinToString("\n") { ColorTheme.encode(it) })
    }

    private fun sanitizeLabel(name: String): String {
        val cleaned = name.trim().replace("|", " ").replace("\n", " ")
            .replace(Regex("\\s+"), " ")
        return cleaned.ifBlank { "Saved" }.take(18)
    }

    companion object {
        private const val KEY_ID = "colorThemeId"
        private const val KEY_CUSTOM = "colorThemeCustom"
        private const val KEY_SAVED = "colorThemeSaved"
    }
}
