package com.lauzhack.twin.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lauzhack.twin.ai.ChatContext
import com.lauzhack.twin.ai.FutureSelfChat
import com.lauzhack.twin.ai.ReportsOffline
import com.lauzhack.twin.engine.CashBand
import com.lauzhack.twin.engine.GameEngine
import com.lauzhack.twin.engine.NewGame
import com.lauzhack.twin.engine.TwinFactory
import com.lauzhack.twin.engine.isOver
import com.lauzhack.twin.model.Assumptions
import com.lauzhack.twin.model.ChatMessage
import com.lauzhack.twin.model.ChatRole
import com.lauzhack.twin.model.Action
import com.lauzhack.twin.model.FactCatalog
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.GameConfig
import com.lauzhack.twin.model.GameResult
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.InterviewAnswer
import com.lauzhack.twin.model.InterviewQuestion
import com.lauzhack.twin.model.Lang
import com.lauzhack.twin.model.LuckMode
import com.lauzhack.twin.model.Persona
import com.lauzhack.twin.model.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

enum class Screen { WELCOME, INTERVIEW, DILEMMA, RESOLUTION, RESULTS, CHAT, MAP }

/** A chat message as shown in the UI: [offline] = it came from the canned fallback, not the LLM. */
data class ChatBubble(val message: ChatMessage, val offline: Boolean = false)

/**
 * UI state holder. The [LifeTree] is the single source of truth: every life explored in the game and the node the
 * user is at ([session] = the turns up to that node). Everything else here is navigation, view state or a cache of
 * pure engine results per node. The engine ([GameEngine], [TwinFactory]) and the chat ([FutureSelfChat]) are
 * injected, so the fakes are swapped for the real ones in one place (createAppState in App.kt).
 */
class AppState(
    private val engine: GameEngine,
    private val twins: TwinFactory,
    private val chat: FutureSelfChat,
    val assumptions: Assumptions,
    val facts: FactCatalog,
    val catalog: Catalog,
    val interview: List<InterviewQuestion>,
    initialLang: Lang = Lang.EN,
    /** The yearly rates of macro.json the Explore panel quotes; null when the engine runs without macro trends. */
    val trends: TrendRates? = null,
) {
    var screen by mutableStateOf(Screen.WELCOME); private set
    var lang by mutableStateOf(initialLang)
    var darkMode by mutableStateOf<Boolean?>(null) // null = follow the system

    /** Every explored life and where the user is; null before a game starts. */
    var tree by mutableStateOf<LifeTree?>(null); private set

    /** The node the user is at: the turns played on the current branch. */
    val session: GameSession? get() = tree?.current

    /** The actions the user can take at the current node (the dilemma screen); empty when the life is complete. */
    var available by mutableStateOf<List<Action>>(emptyList()); private set

    /** How luck is drawn in the next game, chosen on the welcome screen (docs/decision-tree.md). */
    var luckMode by mutableStateOf(LuckMode.SAME_LEVEL)

    /** The last turn of the current node (what the resolution screen shows). */
    val lastTurn: Turn? get() = session?.turns?.lastOrNull()

    /** The first complete life the user played: "the path I played", to go back to it and to mark what-ifs. */
    var playedKey by mutableStateOf<NodeKey?>(null); private set
    val playedSession: GameSession? get() = playedKey?.let { tree?.nodes?.get(it) }

    // Pure engine results per node, computed once (the tree never changes a node) and OFF the UI thread: the screens
    // only read these caches and show a progress indicator until [prepareResults] / [prepareNoiseBand] fill them.
    // Measured on the JVM (warm): finish ~30 ms, noise band ~220 ms; several times more on a phone.
    // The caches are written on the UI thread (after withContext), so they need no locking.
    private val resultCache = mutableStateMapOf<NodeKey, GameResult>()
    private val bandCache = mutableStateMapOf<NodeKey, CashBand?>()

    /** Results of the current node (P8-P10), or null while they are computed (see [prepareResults]). */
    val results: GameResult?
        get() {
            val t = tree ?: return null
            if (playedKey == null) return null           // no results screen before the first life is complete
            return resultCache[t.cursor]
        }

    /** Computes the results of the current node in the background, once. */
    suspend fun prepareResults() {
        val t = tree ?: return
        resultFor(t, t.cursor)
    }

    private suspend fun resultFor(t: LifeTree, key: NodeKey): GameResult {
        resultCache[key]?.let { return it }
        val session = t.nodes.getValue(key)
        val r = withContext(Dispatchers.Default) { engine.finish(session) }
        resultCache[key] = r
        return r
    }

    /** Optional noise band of the current node (#12): month-to-month noise only, not the analysis. Null until ready. */
    val noiseBand: CashBand?
        get() {
            val t = tree ?: return null
            if (playedKey == null) return null
            return bandCache[t.cursor]
        }

    /** Computes the noise band of the current node in the background, once (only for complete lives). */
    suspend fun prepareNoiseBand() {
        val t = tree ?: return
        if (t.current.isOver) bandFor(t, t.cursor)
    }

    private suspend fun bandFor(t: LifeTree, key: NodeKey): CashBand? {
        if (bandCache.containsKey(key)) return bandCache[key]
        val session = t.nodes.getValue(key)
        val band = withContext(Dispatchers.Default) { engine.noiseBand(session) }
        bandCache[key] = band
        return band
    }

    /** Screen to go back to when the map is closed. */
    private var beforeMap = Screen.WELCOME

    /** Screen to go back to when the chat is closed. */
    private var beforeChat = Screen.RESULTS

    val answers = mutableStateListOf<InterviewAnswer>()
    var chatLoading by mutableStateOf(false); private set
    var persona by mutableStateOf(Persona.FUTURE_SELF); private set

    /** One conversation per node and persona, kept for the whole session. */
    private val chats = mutableStateMapOf<String, List<ChatBubble>>()
    private fun chatKey(node: NodeKey, p: Persona) = "$node|$p"
    val chatHistory: List<ChatBubble>
        get() = tree?.let { chats[chatKey(it.cursor, persona)] }.orEmpty()

    /** Chronicle per node (P11), generated on demand. */
    private val chronicles = mutableStateMapOf<NodeKey, ChatBubble>()
    val chronicle: ChatBubble? get() = tree?.let { chronicles[it.cursor] }
    var chronicleLoading by mutableStateOf(false); private set

    val config = GameConfig()

    // ---------- Navigation / flow ----------

    fun startInterview() {
        answers.clear()
        screen = Screen.INTERVIEW
    }

    /** A choice (or "I don't know") for the current question. */
    fun answer(questionId: String, optionId: String) = record(InterviewAnswer(questionId, optionId = optionId))

    /** A typed number for the current question (the screen checks it is within the question's range). */
    fun answerNumber(questionId: String, value: Double) = record(InterviewAnswer(questionId, value = value))

    private fun record(a: InterviewAnswer) {
        answers.removeAll { it.questionId == a.questionId }
        answers += a
        if (answers.size == interview.size) {
            startGame(twins.fromInterview(interview, answers.toList()), Random.nextLong())
        }
    }

    fun undoLastAnswer() {
        if (answers.isEmpty()) screen = Screen.WELCOME else answers.removeAt(answers.lastIndex)
    }

    /** Alexia uses a fixed seed so the demo is reproducible. */
    fun startDemo() = startGame(twins.demo(), DEMO_SEED)

    private fun startGame(game: NewGame, seed: Long) {
        clearGame()
        tree = LifeTree.start(GameSession(game.opening, seed, game.preferences, emptyList(), config, luckMode))
        nextTurn()
    }

    /** Live play: the user takes one of the actions available now; the luck of the new node draws its case. */
    fun choose(actionId: String) {
        val t = tree ?: return
        tree = engine.choose(t, actionId)
        available = emptyList()
        screen = Screen.RESOLUTION
    }

    /** After a decision: the next dilemma (every action available at this node), or the results of a complete life. */
    fun nextTurn() {
        val t = tree ?: return
        if (t.current.isOver) {
            if (playedKey == null) playedKey = t.cursor
            available = emptyList()
            screen = Screen.RESULTS
            return
        }
        available = engine.actionsAt(t)
        screen = Screen.DILEMMA
    }

    /** Actions already taken at the current node (their child exists): the dilemma marks them as explored. */
    val explored: Set<String>
        get() = tree?.let { t -> t.children(t.cursor).map { it.substringAfterLast('/') }.toSet() }.orEmpty()

    val isLastTurn: Boolean get() = session?.isOver ?: true

    // ---------- Exploring the other lives (what-if) ----------

    /** True when the current node is not the life first played. */
    val isEdited: Boolean
        get() = playedKey != null && tree?.cursor != playedKey

    /** Goes to a node of the tree (every node is a decision the user took) and shows that moment. */
    fun open(key: NodeKey) {
        val t = tree ?: return
        if (key !in t.nodes) return
        tree = t.goTo(key)
        showCurrent(resolution = key.isNotEmpty())
    }

    /** "Go back and decide something else": the dilemma of the moment before the current node. */
    fun decideAgain() {
        val key = tree?.cursor ?: return
        decideAgainAt(parentOf(key) ?: return)
    }

    /** Back to the dilemma at node [key] (an ancestor or any node of the tree), to take another action there. */
    fun decideAgainAt(key: NodeKey) {
        val t = tree ?: return
        if (key !in t.nodes || t.nodes.getValue(key).isOver) return
        tree = t.goTo(key)
        nextTurn()
    }

    /** Back to the decision of turn [index] of the current life (0 = the first one). */
    fun switchAt(index: Int) {
        val t = tree ?: return
        if (index >= depthOf(t.cursor)) return
        decideAgainAt(ancestorAt(t.cursor, index))
    }

    fun resetToPlayed() {
        open(playedKey ?: return)
    }

    /** Shows the node at the cursor: results if the life is complete, else its last turn or its question. */
    private fun showCurrent(resolution: Boolean) {
        val t = tree ?: return
        when {
            t.current.isOver -> {
                if (playedKey == null) playedKey = t.cursor
                available = emptyList()
                screen = Screen.RESULTS
            }
            resolution && t.current.turns.isNotEmpty() -> {
                available = emptyList()
                screen = Screen.RESOLUTION
            }
            else -> nextTurn()
        }
    }

    // ---------- Where the numbers come from (the Explore panel) ----------

    /** What the Explore panel shows, or null when it is closed. */
    var inspecting by mutableStateOf<Inspect?>(null); private set

    // Every month of the life up to a node (replayed by the engine, pure): filled in the background by [prepareMonths].
    private val monthsCache = mutableStateMapOf<NodeKey, List<FinancialStatement>>()

    /** The monthly statements of the life up to node [key] (index = month), or null until [prepareMonths] has run. */
    fun monthsOf(key: NodeKey): List<FinancialStatement>? = monthsCache[key]

    /** Replays the life up to node [key] month by month, once, off the UI thread. */
    suspend fun prepareMonths(key: NodeKey) {
        val t = tree ?: return
        if (monthsCache.containsKey(key)) return
        val session = t.nodes[key] ?: return
        val months = withContext(Dispatchers.Default) { engine.months(session) }
        if (tree?.nodes?.get(key) === session) monthsCache[key] = months     // not if the game was restarted meanwhile
    }

    /** Opens the Explore panel on a figure of a node (the current one by default). */
    fun inspect(request: InspectRequest) {
        val t = tree ?: return
        val key = request.key ?: t.cursor
        val session = t.nodes[key] ?: return
        // A complete life is read as a whole; a moment on the way, as what happened since the last decision.
        val scope = request.scope ?: if (session.isOver) Scope.LIFE else Scope.TURN
        inspecting = Inspect(key, request.figure, scope, request.month)
    }

    /** One level down (a row of the panel) or up (a breadcrumb): the same moment and months, another figure. */
    fun inspectFigure(figure: Figure) {
        inspecting = inspecting?.copy(figure = figure)
    }

    fun inspectScope(scope: Scope) {
        inspecting = inspecting?.copy(scope = scope, month = null)
    }

    /** Narrows the panel to one month (null = the whole period again). */
    fun inspectMonth(month: Int?) {
        inspecting = inspecting?.copy(month = month)
    }

    fun closeInspect() {
        inspecting = null
    }

    // ---------- Map of lives ----------

    fun openMap() {
        if (tree == null || screen == Screen.MAP) return
        beforeMap = screen
        screen = Screen.MAP
    }

    fun closeMap() {
        screen = beforeMap
    }

    // ---------- Chat with your self of a moment ----------

    /** The chat needs at least one turn: the self of a moment is you right after a decision (or a quiet period). */
    val canChat: Boolean get() = tree?.cursor?.isNotEmpty() == true

    /** True when the current node is a complete life: the self is ten years older. */
    val isCompleteLife: Boolean get() = session?.isOver == true

    /** Opens the chat with your self of the current moment. */
    fun openChat() {
        if (!canChat || screen == Screen.CHAT) return
        beforeChat = screen
        screen = Screen.CHAT
    }

    /** Opens the chat with your self at node [key] (e.g. from the map); closing it shows that moment. */
    fun chatAt(key: NodeKey) {
        val t = tree ?: return
        if (key.isEmpty()) return
        if (key !in t.nodes) return
        if (key != t.cursor) {
            tree = t.goTo(key)
            showCurrent(resolution = true)
        }
        openChat()
    }

    fun closeChat() {
        screen = beforeChat
    }

    fun restart() {
        clearGame()
        answers.clear()
        screen = Screen.WELCOME
    }

    private fun clearGame() {
        tree = null
        playedKey = null
        available = emptyList()
        resultCache.clear()
        monthsCache.clear()
        inspecting = null
        bandCache.clear()
        chats.clear()
        chronicles.clear()
        persona = Persona.FUTURE_SELF
    }


    // ---------- Chat and chronicle (P11-P12) ----------

    /**
     * What the self of the current moment may know: the life up to this node. Before the end of the game the noise
     * band is left out (it describes years this self has not lived).
     */
    private suspend fun chatContext(t: LifeTree, key: NodeKey): ChatContext {
        val r = resultFor(t, key)
        return if (t.nodes.getValue(key).isOver) ChatContext(r, facts, assumptions, lang, whatIfTurns(r.session), bandFor(t, key))
        else ChatContext(r.copy(sensitivity = emptyList()), facts, assumptions, lang, whatIfTurns(r.session), null)
    }

    private val lastAnswerOffline: Boolean get() = (chat as? ReportsOffline)?.lastAnswerOffline == true

    /** Each persona has its own conversation at each node. */
    fun choosePersona(p: Persona) {
        persona = p
    }

    suspend fun ask(question: String) {
        val t = tree ?: return
        val node = t.cursor
        if (!canChat || question.isBlank() || chatLoading) return
        // The answer goes to the conversation the question was asked in, even if the user moves meanwhile.
        val asked = persona
        val key = chatKey(node, asked)
        // Offline (canned) answers are not the agent's words: they stay out of the history it receives.
        val history = chats[key].orEmpty().filterNot { it.offline }.map { it.message }
        chats[key] = chats[key].orEmpty() + ChatBubble(ChatMessage(ChatRole.USER, question))
        chatLoading = true
        try {
            val context = chatContext(t, node)           // computed in the background if not cached yet
            val answer = chat.ask(context, asked, history, question)
            chats[key] = chats[key].orEmpty() + ChatBubble(answer, offline = lastAnswerOffline)
        } finally {
            chatLoading = false
        }
    }

    suspend fun writeChronicle() {
        val t = tree ?: return
        val node = t.cursor
        if (!canChat || chronicleLoading) return
        chronicleLoading = true
        try {
            val story = chat.chronicle(chatContext(t, node))
            chronicles[node] = ChatBubble(ChatMessage(ChatRole.TWIN, story), offline = lastAnswerOffline)
        } finally {
            chronicleLoading = false
        }
    }

    /** Turns whose decision or case differ from the life first played. */
    private fun whatIfTurns(s: GameSession): Set<Int> {
        val played = playedSession?.turns ?: return emptySet()
        return s.turns.indices.filterTo(mutableSetOf()) { i ->
            val p = played.getOrNull(i)
            val t = s.turns[i]
            p == null || p.actionId != t.actionId || p.values != t.values || p.shockId != t.shockId
        }
    }

    companion object {
        const val DEMO_SEED = 2026L
    }
}
