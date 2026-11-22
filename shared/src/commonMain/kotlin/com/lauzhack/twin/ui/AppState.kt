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
import com.lauzhack.twin.model.Event
import com.lauzhack.twin.model.FactCatalog
import com.lauzhack.twin.model.GameConfig
import com.lauzhack.twin.model.GameResult
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.model.InterviewAnswer
import com.lauzhack.twin.model.InterviewQuestion
import com.lauzhack.twin.model.Lang
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
    private val catalog: List<Event>,
    val interview: List<InterviewQuestion>,
    initialLang: Lang = Lang.EN,
) {
    var screen by mutableStateOf(Screen.WELCOME); private set
    var lang by mutableStateOf(initialLang)
    var darkMode by mutableStateOf<Boolean?>(null) // null = follow the system

    /** Every explored life and where the user is; null before a game starts. */
    var tree by mutableStateOf<LifeTree?>(null); private set

    /** The node the user is at: the turns played on the current branch. */
    val session: GameSession? get() = tree?.current

    /** The question waiting at the current node (live play, or after switching branch in the tree). */
    var currentEvent by mutableStateOf<Event?>(null); private set

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

    /** True once every possible life of the game is in the tree (see [prepareMap]). */
    var mapReady by mutableStateOf(false); private set

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

    fun answer(questionId: String, optionId: String) {
        answers.removeAll { it.questionId == questionId }
        answers += InterviewAnswer(questionId, optionId)
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
        tree = LifeTree.start(GameSession(game.opening, seed, game.preferences, emptyList(), config))
        nextTurn()
    }

    fun previews(event: Event) = session?.let { engine.preview(it, event) }.orEmpty()

    /** Live play: the user picks an option of the current question; the turn's fixed luck draws the case. */
    fun choose(optionId: String) {
        val t = tree ?: return
        tree = engine.choose(t, optionId)
        currentEvent = null
        screen = Screen.RESOLUTION
    }

    /** Next turn: a dilemma, or a quiet period shown directly as its resolution, or the results. */
    fun nextTurn() {
        val t = tree ?: return
        if (t.current.isOver) {
            if (playedKey == null) playedKey = t.cursor
            currentEvent = null
            screen = Screen.RESULTS
            return
        }
        val event = engine.questionAt(t)
        currentEvent = event
        if (event == null) {
            tree = engine.playQuiet(t)
            screen = Screen.RESOLUTION
        } else {
            screen = Screen.DILEMMA
        }
    }

    val isLastTurn: Boolean get() = session?.isOver ?: true

    // ---------- Exploring the other lives (what-if) ----------

    /** True when the current node is not the life first played. */
    val isEdited: Boolean
        get() = playedKey != null && tree?.cursor != playedKey

    /**
     * Goes to any node of the tree, explored or not (it is computed if needed: the luck is fixed, so it is the same
     * life you would get by playing those choices). Shows that moment: the dilemma at the start, what happened in the
     * node's last turn, or the results of a complete life.
     */
    fun open(key: NodeKey) {
        val t = tree ?: return
        tree = reach(t, key).goTo(key)
        showCurrent(resolution = key.isNotEmpty())
    }

    /** [t] with every node from the start down to [key] computed (existing nodes are reused as they are). */
    private fun reach(t: LifeTree, key: NodeKey): LifeTree {
        var out = t
        for (i in key.indices) {
            val c = key[i]
            out = engine.withChild(out, key.take(i), if (c == QUIET) null else c.toString()).first
        }
        return out
    }

    /** "What if I had chosen the other option in the turn I just played?": that same turn, the other option. */
    fun openOther() {
        val key = tree?.cursor ?: return
        if (key.isEmpty() || key.last() == QUIET) return
        open(siblingOf(key))
    }

    /**
     * Takes the other option at turn [index] of the current branch and continues that life as far as it was already
     * explored; then the next unanswered question, or the results. The case of every turn is fixed by its luck.
     */
    fun switchAt(index: Int) {
        val t = tree ?: return
        if (t.cursor.getOrNull(index)?.let { it == QUIET } != false) return
        tree = engine.switchAt(t, index)
        showCurrent(resolution = false)
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
                currentEvent = null
                screen = Screen.RESULTS
            }
            resolution && t.current.turns.isNotEmpty() -> {
                currentEvent = null
                screen = Screen.RESOLUTION
            }
            else -> nextTurn()
        }
    }

    // ---------- Where the numbers come from ----------

    private val traceCache = mutableMapOf<Pair<NodeKey, NodeKey>, Trace>()

    /** What happened in the last turn of node [key] (from its parent to it); null at the start. */
    fun traceAt(key: NodeKey): Trace? {
        val t = tree ?: return null
        val parent = parentOf(key) ?: return null
        if (key !in t.nodes || parent !in t.nodes) return null
        return traceCache.getOrPut(parent to key) { engine.trace(t.nodes.getValue(parent), t.nodes.getValue(key)) }
    }

    /** The whole life up to the current node, from the opening statement. */
    fun traceOfLife(): Trace? {
        val t = tree ?: return null
        return traceCache.getOrPut("" to t.cursor) { engine.trace(t.root, t.current) }
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

    /** Computes every possible life of the game (off the UI thread) so the map can show the whole tree. */
    suspend fun prepareMap() {
        val t = tree ?: return
        if (mapReady) return
        val all = withContext(Dispatchers.Default) { engine.expandAll(LifeTree.start(t.root)) }
        val now = tree ?: return
        if (now.root != t.root) return                  // a new game started meanwhile
        tree = now.copy(nodes = all.nodes + now.nodes)
        mapReady = true
    }

    // ---------- Chat with your self of a moment ----------

    /** The chat needs at least one turn: the self of a moment is you right after a decision (or a quiet period). */
    val canChat: Boolean get() = tree?.cursor?.isNotEmpty() == true

    /** True when the current node is a complete life: the self is ten years older and the parallel self exists. */
    val isCompleteLife: Boolean get() = session?.isOver == true

    /** Opens the chat with your self of the current moment. */
    fun openChat() {
        if (!canChat || screen == Screen.CHAT) return
        if (!isCompleteLife) persona = Persona.FUTURE_SELF
        beforeChat = screen
        screen = Screen.CHAT
    }

    /** Opens the chat with your self at node [key] (e.g. from the map); closing it shows that moment. */
    fun chatAt(key: NodeKey) {
        val t = tree ?: return
        if (key.isEmpty()) return
        if (key != t.cursor) {
            tree = reach(t, key).goTo(key)
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
        currentEvent = null
        mapReady = false
        resultCache.clear()
        traceCache.clear()
        bandCache.clear()
        chats.clear()
        chronicles.clear()
        persona = Persona.FUTURE_SELF
    }

    fun eventById(id: String): Event? = catalog.firstOrNull { it.id == id }

    // ---------- Chat and chronicle (P11-P12) ----------

    /**
     * What the self of the current moment may know: the life up to this node. Before the end of the game the other
     * lives and the noise band are left out (they describe years this self has not lived).
     */
    private suspend fun chatContext(t: LifeTree, key: NodeKey): ChatContext {
        val r = resultFor(t, key)
        return if (t.nodes.getValue(key).isOver) ChatContext(r, facts, assumptions, lang, whatIfTurns(r.session), bandFor(t, key))
        else ChatContext(r.copy(parallelLives = emptyList(), sensitivity = emptyList()), facts, assumptions, lang, whatIfTurns(r.session), null)
    }

    private val lastAnswerOffline: Boolean get() = (chat as? ReportsOffline)?.lastAnswerOffline == true

    /** Each persona has its own conversation at each node (the other self has another life). */
    fun choosePersona(p: Persona) {
        if (p == Persona.PARALLEL_SELF && !isCompleteLife) return
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

    /** Turns whose event, option or case differ from the life first played. */
    private fun whatIfTurns(s: GameSession): Set<Int> {
        val played = playedSession?.turns ?: return emptySet()
        return s.turns.indices.filterTo(mutableSetOf()) { i ->
            val p = played.getOrNull(i)
            val t = s.turns[i]
            p == null || p.eventId != t.eventId || p.chosenOptionId != t.chosenOptionId || p.resolvedCase != t.resolvedCase
        }
    }

    companion object {
        const val DEMO_SEED = 2026L
    }
}
