package com.metrom.app.data

import android.content.SharedPreferences
import com.metrom.app.MutePattern
import com.metrom.app.engine.AccentNote
import com.metrom.app.engine.BeatAccent
import com.metrom.app.engine.MetronomeTone
import com.metrom.app.engine.Subdivision
import com.metrom.app.engine.SwingFeel
import com.metrom.app.engine.TimeSignature
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SongPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val bpm: Int,
    val timeSignature: TimeSignature,
    val subdivision: Subdivision,
    val tone: MetronomeTone,
    val accentNote: AccentNote,
    val beatAccents: List<BeatAccent> = BeatAccent.defaultPattern(timeSignature.beats, timeSignature.noteValue),
    val swing: SwingFeel = SwingFeel.OFF,
    val groupTempo: Boolean = false,
    val countInBars: Int = 1,
    val mutePattern: MutePattern = MutePattern.OFF
) {
    fun sameSetupAs(
        bpm: Int,
        timeSignature: TimeSignature,
        subdivision: Subdivision,
        tone: MetronomeTone,
        accentNote: AccentNote,
        beatAccents: List<BeatAccent>,
        swing: SwingFeel,
        groupTempo: Boolean,
        countInBars: Int,
        mutePattern: MutePattern
    ): Boolean = this.bpm == bpm &&
        this.timeSignature == timeSignature &&
        this.subdivision == subdivision &&
        this.tone == tone &&
        this.accentNote == accentNote &&
        this.beatAccents == beatAccents &&
        this.swing == swing &&
        this.groupTempo == groupTempo &&
        this.countInBars == countInBars &&
        this.mutePattern == mutePattern

    companion object {
        fun autoName(bpm: Int, timeSignature: TimeSignature, subdivision: Subdivision): String =
            "$bpm · ${timeSignature.label} · ${subdivision.label}"
    }
}

class SongStore(private val prefs: SharedPreferences) {
    fun load(): List<SongPreset> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    parseSong(o)?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveAll(songs: List<SongPreset>) {
        val array = JSONArray()
        songs.forEach { song ->
            array.put(
                JSONObject()
                    .put("id", song.id)
                    .put("name", song.name)
                    .put("bpm", song.bpm)
                    .put("beats", song.timeSignature.beats)
                    .put("noteValue", song.timeSignature.noteValue)
                    .put("subdivision", song.subdivision.ordinal)
                    .put("toneId", song.tone.id)
                    .put("accentNote", song.accentNote.ordinal)
                    .put("beatAccents", BeatAccent.encode(song.beatAccents))
                    .put("swing", song.swing.ordinal)
                    .put("groupTempo", song.groupTempo)
                    .put("countInBars", song.countInBars)
                    .put("mutePlayBars", song.mutePattern.playBars)
                    .put("muteSilentBars", song.mutePattern.silentBars)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun parseSong(o: JSONObject): SongPreset? = runCatching {
        val toneId = o.optString("toneId", "").ifBlank { return null }
        val tone = MetronomeTone.fromId(toneId) ?: return null
        val beats = o.getInt("beats")
        val noteValue = o.getInt("noteValue")
        SongPreset(
            id = o.getString("id"),
            name = o.getString("name"),
            bpm = o.getInt("bpm"),
            timeSignature = TimeSignature(beats = beats, noteValue = noteValue),
            subdivision = Subdivision.entries.getOrElse(o.getInt("subdivision")) {
                Subdivision.QUARTER
            },
            tone = tone,
            accentNote = AccentNote.entries.getOrElse(o.getInt("accentNote")) {
                AccentNote.DEFAULT
            },
            beatAccents = BeatAccent.decode(
                raw = if (o.has("beatAccents")) o.getString("beatAccents") else null,
                beats = beats,
                noteValue = noteValue
            ),
            swing = SwingFeel.entries.getOrElse(o.optInt("swing", 0)) { SwingFeel.OFF },
            groupTempo = o.optBoolean("groupTempo", false),
            countInBars = o.optInt("countInBars", 1),
            mutePattern = MutePattern(
                playBars = o.optInt("mutePlayBars", 1),
                silentBars = o.optInt("muteSilentBars", 0)
            )
        )
    }.getOrNull()

    companion object {
        private const val KEY = "songs_json"
    }
}
