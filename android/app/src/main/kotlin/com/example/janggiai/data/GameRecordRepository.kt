package com.example.janggiai.data

import android.content.Context
import com.example.janggiai.coach.CoachComment
import com.example.janggiai.game.BikjangRule
import com.example.janggiai.game.GameSession
import com.example.janggiai.game.RepetitionRule
import com.example.janggiai.game.RuleConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class GameRecord(
    val id: String,
    val title: String,
    val createdAt: Long,
    val startFen: String,
    val moves: List<String>,
    val bikjang: BikjangRule,
    val repetition: RepetitionRule,
    val result: String,
    /** Optional metadata (absent in records saved by older versions). */
    val humanSide: Int? = null,
    val coachComments: Map<Int, CoachComment> = emptyMap(),
) {
    fun toSession(): GameSession = GameSession(startFen = startFen, moves = moves, viewIndex = moves.size,
        rules = RuleConfig(bikjang = bikjang, repetition = repetition))
}

/** Saves game records as small JSON files in the app's private storage (no permissions needed). */
class GameRecordRepository(context: Context) {
    private val dir = File(context.filesDir, "records").apply { mkdirs() }

    suspend fun list(): List<GameRecord> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f -> runCatching { parse(f.readText()) }.getOrNull() }
            .sortedByDescending { it.createdAt }
    }

    suspend fun save(session: GameSession, title: String, result: String, humanSide: Int? = null, coachComments: Map<Int, CoachComment> = emptyMap()): GameRecord = withContext(Dispatchers.IO) {
        val id = "g${System.currentTimeMillis()}"
        val rec = GameRecord(id, title, System.currentTimeMillis(), session.startFen, session.moves,
            session.rules.bikjang, session.rules.repetition, result, humanSide, coachComments)
        File(dir, "$id.json").writeText(serialize(rec))
        rec
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { File(dir, "$id.json").delete(); Unit }

    private fun serialize(r: GameRecord): String = JSONObject()
        .put("id", r.id).put("title", r.title).put("createdAt", r.createdAt).put("startFen", r.startFen)
        .put("moves", JSONArray(r.moves)).put("bikjang", r.bikjang.id).put("repetition", r.repetition.id)
        .put("result", r.result)
        .apply { if (r.humanSide != null) put("humanSide", r.humanSide) }
        .apply { if (r.coachComments.isNotEmpty()) put("coach", CoachComment.encodeAll(r.coachComments.values)) }
        .toString()

    private fun parse(text: String): GameRecord {
        val o = JSONObject(text)
        val arr = o.getJSONArray("moves")
        return GameRecord(
            id = o.getString("id"), title = o.optString("title", "기보"), createdAt = o.optLong("createdAt", 0),
            startFen = o.getString("startFen"), moves = List(arr.length()) { arr.getString(it) },
            bikjang = BikjangRule.fromId(o.optString("bikjang", "off")), repetition = RepetitionRule.fromId(o.optString("repetition", "draw")),
            result = o.optString("result", ""),
            humanSide = if (o.has("humanSide")) o.getInt("humanSide") else null,
            coachComments = CoachComment.decodeAll(o.optString("coach", "")),
        )
    }
}
