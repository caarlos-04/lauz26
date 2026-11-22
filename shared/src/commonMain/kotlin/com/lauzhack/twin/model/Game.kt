package com.lauzhack.twin.model

import kotlinx.serialization.Serializable

// The game is a tree of decisions (docs/decision-tree.md). Every node is a snapshot (a FinancialStatement with its
// pendingChanges) at a decision point; a child exists only if the player took that action there. Nothing advances on
// its own, so there are as many nodes as decisions taken.

@Serializable
data class GameConfig(
    val horizonMonths: Int = 120,
    val monthsPerTurn: Int = 24,
    val maxTurns: Int = 5,
)

@Serializable
data class Preferences(
    val objective: Objective,
    val minCashChf: Double,
    val penaltyChfPerMonthBelowMin: Double,
)

/**
 * One node of the tree: the statement at a decision point and what led there. [id] is the path of action ids from the
 * start ("" = start, "A-master", "A-master/NOTHING"...), so a node is identified by the decisions that made it.
 * The 24 months between a node and its parent are not stored: the engine replays them (pure, same result).
 */
@Serializable
data class DecisionNode(
    val id: String,
    val parentId: String?,               // null at the start
    val depth: Int,                      // decisions taken so far (0 = start, maxTurns = end of the game)
    val actionId: String?,               // the action taken at the parent (null at the start)
    val luck: Double?,                   // 0..1: the percentile its uncertain numbers were drawn at
    val shockId: String? = null,         // an involuntary event in those 24 months (job loss), if any
    val state: FinancialStatement,       // the snapshot: statement + pendingChanges
)

@Serializable
data class DecisionTree(
    val seed: Long,
    val luckMode: LuckMode,
    val preferences: Preferences,
    val config: GameConfig,
    val nodes: Map<String, DecisionNode>, // always contains the start ("")
) {
    val root: DecisionNode get() = nodes.getValue(ROOT)

    fun children(id: String): List<DecisionNode> = nodes.values.filter { it.parentId == id }

    /** The nodes from the start to [id], in order. */
    fun path(id: String): List<DecisionNode> = generateSequence(nodes.getValue(id)) { n -> n.parentId?.let { nodes.getValue(it) } }
        .toList().asReversed()

    companion object {
        const val ROOT = ""

        fun start(opening: FinancialStatement, seed: Long, luckMode: LuckMode, preferences: Preferences,
                  config: GameConfig = GameConfig()): DecisionTree =
            DecisionTree(seed, luckMode, preferences, config,
                         mapOf(ROOT to DecisionNode(ROOT, null, 0, null, null, null, opening)))

        /** The id of the child of [parentId] reached with [actionId]. */
        fun childId(parentId: String, actionId: String): String = if (parentId.isEmpty()) actionId else "$parentId/$actionId"

        fun parentOf(id: String): String? = if (id.isEmpty()) null else id.substringBeforeLast('/', "")

        fun depthOf(id: String): Int = if (id.isEmpty()) 0 else id.count { it == '/' } + 1
    }
}

/**
 * A life from the start to one node of the tree, as the screens, the chat and the analysis read it: the path's
 * decisions with their states and the story moments of each turn. Derived from the tree by the engine, never stored.
 */
@Serializable
data class GameSession(
    val initial: FinancialStatement,        // month 0 (from the interview, or a dataset profile such as Alexia)
    val seed: Long,
    val preferences: Preferences,
    val turns: List<Turn>,
    val config: GameConfig = GameConfig(),
    val luckMode: LuckMode = LuckMode.SAME_LEVEL,
)

/** One decision of a life and the 24 months after it. */
@Serializable
data class Turn(
    val index: Int,
    val actionId: String,                   // NOTHING = carried on as before
    val luck: Double,                       // 0..1: the percentile the action's uncertain numbers were drawn at
    val values: Map<String, Double> = emptyMap(),   // the drawn values, by name (salary, tuition...)
    val shockId: String? = null,            // an involuntary event during the turn (job loss), if any
    val shockLuck: Double? = null,
    val shockValues: Map<String, Double> = emptyMap(),
    val shockMonth: Int? = null,            // month of the turn (0..23) when it started
    val stateAfter: FinancialStatement,
    val beats: List<StoryBeat>,
)
