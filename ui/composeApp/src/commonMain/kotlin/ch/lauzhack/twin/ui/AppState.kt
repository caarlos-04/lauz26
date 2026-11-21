package ch.lauzhack.twin.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.lauzhack.twin.ai.FutureSelfChat
import ch.lauzhack.twin.engine.GameEngine
import ch.lauzhack.twin.engine.PathKind
import ch.lauzhack.twin.model.Assumptions
import ch.lauzhack.twin.model.CaseType
import ch.lauzhack.twin.model.ChatContext
import ch.lauzhack.twin.model.ChatMessage
import ch.lauzhack.twin.model.ChatRole
import ch.lauzhack.twin.model.Event
import ch.lauzhack.twin.model.FinancialState
import ch.lauzhack.twin.model.GameConfig
import ch.lauzhack.twin.model.GameSession
import ch.lauzhack.twin.model.InterviewAnswer
import ch.lauzhack.twin.model.InterviewQuestion
import ch.lauzhack.twin.model.Lang
import ch.lauzhack.twin.model.PathComparison
import ch.lauzhack.twin.model.SimulationResult
import ch.lauzhack.twin.model.Stats
import ch.lauzhack.twin.model.Turn
import ch.lauzhack.twin.model.UserProfile
import kotlin.random.Random

enum class Screen { WELCOME, INTERVIEW, DILEMMA, RESOLUTION, RESULTS, CHAT }

/** Everything the results and chat screens need, computed once at the end of the game. */
data class GameResults(
    val chosenSim: SimulationResult,
    val alternativeSim: SimulationResult,
    val chosenStats: Stats,
    val comparison: PathComparison,
)

/**
 * UI state holder. [GameSession] is the single source of truth for the game (docs section 6);
 * everything else here is navigation or view state. Dependencies are injected so the fakes can be
 * swapped for the real engine (Dev A) and chat (Dev C) without touching the screens.
 */
class AppState(
    private val engine: GameEngine,
    private val chat: FutureSelfChat,
    val assumptions: Assumptions,
    private val catalog: List<Event>,
    val interview: List<InterviewQuestion>,
    private val buildProfile: (List<InterviewQuestion>, List<InterviewAnswer>) -> Pair<UserProfile, FinancialState>,
    private val demoProfile: Pair<UserProfile, FinancialState>,
    initialLang: Lang = Lang.EN,
) {
    var screen by mutableStateOf(Screen.WELCOME); private set
    var lang by mutableStateOf(initialLang)
    var darkMode by mutableStateOf<Boolean?>(null) // null = follow the system

    var session by mutableStateOf<GameSession?>(null); private set
    var currentEvent by mutableStateOf<Event?>(null); private set
    var lastTurn by mutableStateOf<Turn?>(null); private set
    var results by mutableStateOf<GameResults?>(null); private set

    /** Path actually played, captured when the game ends; used to reset the what-if edits. */
    var playedSession by mutableStateOf<GameSession?>(null); private set

    /** First unanswered question after a what-if edit (the tree stops there), or null. */
    var pendingEvent by mutableStateOf<Event?>(null); private set

    /** Explored branches of the what-if tree. */
    private var memory: PathMemory = emptyMap()

    val answers = mutableStateListOf<InterviewAnswer>()
    val chatHistory = mutableStateListOf<ChatMessage>()
    var chatLoading by mutableStateOf(false); private set

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
            val (profile, state) = buildProfile(interview, answers.toList())
            startGame(profile, state, Random.nextLong())
        }
    }

    fun undoLastAnswer() {
        if (answers.isEmpty()) screen = Screen.WELCOME else answers.removeAt(answers.lastIndex)
    }

    /** Alexia uses a fixed seed so the demo is reproducible. */
    fun startDemo() = startGame(demoProfile.first, demoProfile.second, DEMO_SEED)

    private fun startGame(profile: UserProfile, initial: FinancialState, seed: Long) {
        val newSession = GameSession(profile, initial, seed, emptyList(), config)
        session = newSession
        lastTurn = null
        results = null
        playedSession = null
        pendingEvent = null
        memory = emptyMap()
        chatHistory.clear()
        currentEvent = engine.nextEvent(newSession)
        screen = if (currentEvent == null) finishGame() else Screen.DILEMMA
    }

    fun previews(event: Event) = session?.let { engine.preview(it, event, lang) }.orEmpty()

    fun choose(optionId: String) {
        val s = session ?: return
        val event = currentEvent ?: return
        val option = event.options.first { it.id == optionId }
        val case = engine.drawCase(option, s.seed, s.turns.size)
        val turn = engine.playTurn(s, event, optionId, case)
        session = s.copy(turns = s.turns + turn)
        lastTurn = turn
        screen = Screen.RESOLUTION
    }

    fun nextTurn() {
        val s = session ?: return
        currentEvent = engine.nextEvent(s)
        screen = if (currentEvent == null) finishGame() else Screen.DILEMMA
    }

    val isLastTurn: Boolean
        get() = session?.let { it.turns.size >= it.config.maxTurns || engine.nextEvent(it) == null } ?: true

    private fun finishGame(): Screen {
        val s = session ?: return Screen.WELCOME
        playedSession = s
        memory = memory.record(s)
        results = computeResults(s)
        return Screen.RESULTS
    }

    private fun computeResults(s: GameSession) = GameResults(
        chosenSim = engine.simulate(s, PathKind.CHOSEN),
        alternativeSim = engine.simulate(s, PathKind.ALTERNATIVE),
        chosenStats = engine.stats(s, PathKind.CHOSEN),
        comparison = engine.compare(s),
    )

    // ---------- What-if editing of the finished game ----------

    val isEdited: Boolean
        get() = playedSession != null && (session != playedSession || pendingEvent != null)

    /**
     * Changes the option and/or case of one past turn. Later questions are kept if still valid,
     * replaced otherwise; unanswered ones become [pendingEvent]. Everything is recomputed.
     */
    fun editTurn(index: Int, optionId: String? = null, case: CaseType? = null) {
        val s = session ?: return
        val old = s.turns.getOrNull(index)?.choice ?: return
        val choice = TurnChoice(optionId ?: old.optionId, case ?: old.case)
        if (choice == old) return
        memory = memory.record(s)
        apply(engine.editPath(s, index, choice, memory, ::eventById))
    }

    /** Answers the pending question; the case is drawn with the seed as in live play. */
    fun decidePending(optionId: String) {
        val s = session ?: return
        val event = pendingEvent ?: return
        val option = event.options.first { it.id == optionId }
        val case = engine.drawCase(option, s.seed, s.turns.size)
        val next = s.copy(turns = s.turns + engine.playTurn(s, event, optionId, case))
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
        results = computeResults(s)
        // New path => new conversation (the old answers referred to other numbers).
        chatHistory.clear()
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
        answers.clear()
        chatHistory.clear()
        screen = Screen.WELCOME
    }

    fun eventById(id: String): Event? = catalog.firstOrNull { it.id == id }

    // ---------- Chat ----------

    suspend fun ask(question: String) {
        val s = session ?: return
        val r = results ?: return
        if (question.isBlank() || chatLoading) return
        val history = chatHistory.toList()
        chatHistory += ChatMessage(ChatRole.USER, question)
        chatLoading = true
        try {
            val context = ChatContext(s, r.chosenStats, r.comparison, r.chosenSim, assumptions, lang)
            chatHistory += chat.ask(context, history, question)
        } finally {
            chatLoading = false
        }
    }

    companion object {
        const val DEMO_SEED = 2026L
    }
}
