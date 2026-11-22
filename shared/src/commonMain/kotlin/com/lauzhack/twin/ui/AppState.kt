package com.lauzhack.twin.ui

import androidx.compose.runtime.getValue
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
import com.lauzhack.twin.model.CaseType
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
import kotlin.random.Random

enum class Screen { WELCOME, INTERVIEW, DILEMMA, RESOLUTION, RESULTS, CHAT }

/** A chat message as shown in the UI: [offline] = it came from the canned fallback, not the LLM. */
data class ChatBubble(val message: ChatMessage, val offline: Boolean = false)

/**
 * UI state holder. [GameSession] is the single source of truth during the game and [GameResult] at the end
 * (docs section 6); everything else here is navigation or view state. The engine ([GameEngine], [TwinFactory])
 * and the chat ([FutureSelfChat]) are injected, so the fakes are swapped for the real ones in one place
 * (createDefaultAppState in App.kt) without touching the screens.
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

    var session by mutableStateOf<GameSession?>(null); private set
    var currentEvent by mutableStateOf<Event?>(null); private set
    var lastTurn by mutableStateOf<Turn?>(null); private set
    var results by mutableStateOf<GameResult?>(null); private set

    /** Optional noise band of the current path (#12): month-to-month noise only, not the analysis. */
    var noiseBand by mutableStateOf<CashBand?>(null); private set

    /** Path actually played, captured when the game ends; used to reset the what-if edits. */
    var playedSession by mutableStateOf<GameSession?>(null); private set

    /** First unanswered question after a what-if edit (the tree stops there), or null. */
    var pendingEvent by mutableStateOf<Event?>(null); private set

    /** Explored branches of the what-if tree. */
    private var memory: PathMemory = emptyMap()

    val answers = mutableStateListOf<InterviewAnswer>()
    val chatHistory = mutableStateListOf<ChatBubble>()
    var chatLoading by mutableStateOf(false); private set
    var persona by mutableStateOf(Persona.FUTURE_SELF); private set

    /** Chronicle of the current path (P11), generated on demand; cleared when the path changes. */
    var chronicle by mutableStateOf<ChatBubble?>(null); private set
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
        session = GameSession(game.opening, seed, game.preferences, emptyList(), config)
        lastTurn = null
        results = null
        noiseBand = null
        playedSession = null
        pendingEvent = null
        memory = emptyMap()
        resetChat()
        nextTurn()
    }

    fun previews(event: Event) = session?.let { engine.preview(it, event) }.orEmpty()

    fun choose(optionId: String) {
        val s = session ?: return
        val event = currentEvent ?: return
        val option = event.options.first { it.id == optionId }
        val (luck, case) = engine.resolveLuck(s, option)
        play(s, event, optionId, case, luck)
    }

    /** Next turn: a dilemma, or a quiet period shown directly as its resolution, or the results. */
    fun nextTurn() {
        val s = session ?: return
        if (s.isOver) {
            screen = finishGame()
            return
        }
        val event = engine.nextEvent(s)
        currentEvent = event
        if (event == null) play(s, null, null, null, QUIET_LUCK) else screen = Screen.DILEMMA
    }

    private fun play(s: GameSession, event: Event?, optionId: String?, case: CaseType?, luck: Double) {
        val turn = engine.playTurn(s, event, optionId, case, luck)
        session = s.copy(turns = s.turns + turn)
        lastTurn = turn
        screen = Screen.RESOLUTION
    }

    val isLastTurn: Boolean get() = session?.isOver ?: true

    private fun finishGame(): Screen {
        val s = session ?: return Screen.WELCOME
        playedSession = s
        memory = memory.record(s)
        results = engine.finish(s)
        noiseBand = engine.noiseBand(s)
        return Screen.RESULTS
    }

    // ---------- What-if editing of the finished game ----------

    val isEdited: Boolean
        get() = playedSession != null && (session != playedSession || pendingEvent != null)

    /**
     * Changes the option and/or case of one past turn. Later questions are kept if still valid,
     * replaced otherwise; unanswered ones become [pendingEvent]. Everything is recomputed.
     */
    fun editTurn(index: Int, optionId: String? = null, case: CaseType? = null) {
        val s = session ?: return
        val old = s.turns.getOrNull(index)?.takeIf { it.eventId != null }?.choice ?: return
        val choice = old.copy(optionId = optionId ?: old.optionId, case = case ?: old.case)
        if (choice == old) return
        memory = memory.record(s)
        apply(engine.editPath(s, index, choice, memory, ::eventById))
    }

    /** Answers the pending question; the case comes from the turn's luck, as in live play. */
    fun decidePending(optionId: String) {
        val s = session ?: return
        val event = pendingEvent ?: return
        val option = event.options.first { it.id == optionId }
        val (luck, case) = engine.resolveLuck(s, option)
        val next = s.copy(turns = s.turns + engine.playTurn(s, event, optionId, case, luck))
        apply(engine.resolvePath(next, emptyList(), memory, ::eventById))
    }

    fun resetToPlayed() {
        val played = playedSession ?: return
        apply(ResolvedPath(played, null))
    }

    private fun apply(resolved: ResolvedPath) {
        val s = resolved.session
        session = s
        pendingEvent = resolved.pending
        memory = memory.record(s)
        lastTurn = s.turns.lastOrNull()
        results = engine.finish(s)
        noiseBand = engine.noiseBand(s)
        // New path => new conversation and story (the old answers referred to other numbers).
        resetChat()
    }

    fun openChat() { screen = Screen.CHAT }
    fun backToResults() { screen = Screen.RESULTS }

    fun restart() {
        playedSession = null
        pendingEvent = null
        memory = emptyMap()
        session = null
        currentEvent = null
        lastTurn = null
        results = null
        noiseBand = null
        answers.clear()
        resetChat()
        screen = Screen.WELCOME
    }

    fun eventById(id: String): Event? = catalog.firstOrNull { it.id == id }

    // ---------- Chat and chronicle (P11-P12) ----------

    private fun chatContext(): ChatContext? {
        val r = results ?: return null
        return ChatContext(r, facts, assumptions, lang, whatIfTurns(r.session), noiseBand)
    }

    private val lastAnswerOffline: Boolean get() = (chat as? ReportsOffline)?.lastAnswerOffline == true

    /** Switching persona starts a new conversation (the other self has another life). */
    fun choosePersona(p: Persona) {
        if (p == persona) return
        persona = p
        chatHistory.clear()
    }

    suspend fun ask(question: String) {
        val context = chatContext() ?: return
        if (question.isBlank() || chatLoading) return
        // Offline (canned) answers are not the agent's words: they stay out of the history it receives.
        val history = chatHistory.filterNot { it.offline }.map { it.message }
        chatHistory += ChatBubble(ChatMessage(ChatRole.USER, question))
        chatLoading = true
        try {
            val answer = chat.ask(context, persona, history, question)
            chatHistory += ChatBubble(answer, offline = lastAnswerOffline)
        } finally {
            chatLoading = false
        }
    }

    suspend fun writeChronicle() {
        val context = chatContext() ?: return
        if (chronicleLoading) return
        chronicleLoading = true
        try {
            val story = chat.chronicle(context)
            chronicle = ChatBubble(ChatMessage(ChatRole.TWIN, story), offline = lastAnswerOffline)
        } finally {
            chronicleLoading = false
        }
    }

    private fun resetChat() {
        chatHistory.clear()
        chronicle = null
    }

    /** Turns whose event, option or luck differ from the game actually played. */
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

        /** Luck stored for quiet turns (no case to resolve). */
        private const val QUIET_LUCK = 0.5
    }
}
