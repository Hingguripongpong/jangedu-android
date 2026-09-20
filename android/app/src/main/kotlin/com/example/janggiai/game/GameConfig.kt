package com.example.janggiai.game

import kotlin.random.Random

/** Which side the human plays, as chosen before the game (RANDOM is resolved once, when the game starts). */
enum class SideChoice(val id: String, val label: String) {
    CHO("cho", "초"), HAN("han", "한"), RANDOM("random", "랜덤");

    fun resolve(random: Random = Random.Default): Int = when (this) {
        CHO -> Board.CHO
        HAN -> Board.HAN
        RANDOM -> if (random.nextBoolean()) Board.CHO else Board.HAN
    }

    companion object { fun fromId(id: String?): SideChoice = entries.firstOrNull { it.id == id } ?: CHO }
}

/**
 * Horse/elephant opening setup, named from the owner's own left to right exactly as
 * [Position.SETUPS] (the rules engine is the single source of truth for what each name means).
 * RANDOM resolves to one of the four standard setups and never to anything else.
 */
enum class SetupChoice(val id: String, val label: String, val setupName: String?) {
    MSSM("mssm", "마상상마", "마상상마"),
    SMMS("smms", "상마마상", "상마마상"),
    MSMS("msms", "마상마상", "마상마상"),
    SMSM("smsm", "상마상마", "상마상마"),
    RANDOM("random", "랜덤", null);

    fun resolve(random: Random = Random.Default): String = setupName ?: STANDARD[random.nextInt(STANDARD.size)].setupName!!

    companion object {
        val STANDARD: List<SetupChoice> = entries.filter { it.setupName != null }
        fun fromId(id: String?): SetupChoice = entries.firstOrNull { it.id == id } ?: MSSM
        /** The choice whose setup name matches [name], or null for a non-standard back rank. */
        fun ofSetupName(name: String?): SetupChoice? = STANDARD.firstOrNull { it.setupName == name }
    }
}

/** Everything a human-vs-AI game is started with. Choices are resolved once into [ResolvedGameConfig]. */
data class GameConfig(
    val humanSide: SideChoice = SideChoice.CHO,
    val humanSetup: SetupChoice = SetupChoice.MSSM,
    val aiSetup: SetupChoice = SetupChoice.MSSM,
    val difficulty: Difficulty = Difficulty.INTERMEDIATE,
    val coachEnabled: Boolean = true,
) {
    fun resolve(random: Random = Random.Default): ResolvedGameConfig {
        val side = humanSide.resolve(random)
        return ResolvedGameConfig(humanSide = side, humanSetup = humanSetup.resolve(random), aiSetup = aiSetup.resolve(random),
            difficulty = difficulty, coachEnabled = coachEnabled)
    }
}

/** A started game's fixed configuration: sides and setups never change during the game. */
data class ResolvedGameConfig(
    val humanSide: Int,
    val humanSetup: String,
    val aiSetup: String,
    val difficulty: Difficulty,
    val coachEnabled: Boolean,
) {
    val aiSide: Int get() = humanSide xor 1
    val choSetup: String get() = if (humanSide == Board.CHO) humanSetup else aiSetup
    val hanSetup: String get() = if (humanSide == Board.HAN) humanSetup else aiSetup

    /** New session with each side's own back rank; Cho moves first as in the rules engine. */
    fun newSession(rules: RuleConfig): GameSession = GameSession.newGame(rules, choSetup = choSetup, hanSetup = hanSetup)
}
