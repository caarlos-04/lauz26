package com.lauzhack.twin.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.lauzhack.twin.ai.AgentFutureSelfChat
import com.lauzhack.twin.ai.BriefingMapper
import com.lauzhack.twin.ai.FutureSelfAgent
import com.lauzhack.twin.ai.FutureSelfChat
import com.lauzhack.twin.fake.CannedFutureSelfChat
import com.lauzhack.twin.ui.i18n.keyStringAsync
import com.lauzhack.twin.fake.FakeData
import com.lauzhack.twin.fake.FakeGameEngine
import com.lauzhack.twin.fake.FakeTwinFactory
import com.lauzhack.twin.model.Event
import com.lauzhack.twin.model.Lang
import com.lauzhack.twin.ui.components.PanelData
import com.lauzhack.twin.ui.components.TopBarActions
import com.lauzhack.twin.ui.i18n.LocalAppLocale
import com.lauzhack.twin.ui.i18n.LocalLang
import com.lauzhack.twin.ui.i18n.tag
import com.lauzhack.twin.ui.screens.ChatScreen
import com.lauzhack.twin.ui.screens.DilemmaScreen
import com.lauzhack.twin.ui.screens.InterviewScreen
import com.lauzhack.twin.ui.screens.ResolutionScreen
import com.lauzhack.twin.ui.screens.ResultsScreen
import com.lauzhack.twin.ui.screens.WelcomeScreen
import com.lauzhack.twin.ui.theme.TwinTheme

/**
 * Wires the dependencies: the ONLY place to change when the real engine (Dev A) and content (Dev C) arrive.
 * - engine / twins: FakeGameEngine / FakeTwinFactory -> the real GameEngine / TwinFactory (engine/GameEngine.kt).
 * - catalog / facts / assumptions: FakeData -> content/events.json, facts.json, assumptions.json (data/ loading).
 * - chat: the Koog agent (ai/) when OPENAI_API_KEY or ANTHROPIC_API_KEY is set, otherwise canned answers.
 */
fun createDefaultAppState(): AppState = AppState(
    engine = FakeGameEngine(FakeData.events, FakeData.assumptions),
    twins = FakeTwinFactory(),
    chat = createChat(FakeData.events),
    assumptions = FakeData.assumptions,
    facts = FakeData.facts,
    catalog = FakeData.events,
    interview = FakeData.interview,
)

/** The LLM agent, or null when no API key is configured (then the chat uses canned answers). */
private val futureSelfAgent: FutureSelfAgent? by lazy { FutureSelfAgent.fromEnvironment() }

/** Model shown in the chat badge, or null for canned answers. */
val aiChatModel: String? get() = futureSelfAgent?.modelId

private fun createChat(catalog: List<Event>): FutureSelfChat {
    val canned = CannedFutureSelfChat()
    val agent = futureSelfAgent ?: return canned
    val mapper = BriefingMapper(eventById = { id -> catalog.firstOrNull { it.id == id } }, text = ::keyStringAsync)
    return AgentFutureSelfChat(agent, mapper, fallback = canned)
}

@Composable
fun App(state: AppState = remember { createDefaultAppState() }) {
    val dark = state.darkMode ?: isSystemInDarkTheme()
    CompositionLocalProvider(LocalAppLocale provides state.lang.tag, LocalLang provides state.lang) {
        // key() forces resources to reload when the language changes.
        key(state.lang) {
            TwinTheme(dark) {
                val actions = TopBarActions(
                    isDark = dark,
                    onToggleLang = { state.lang = if (state.lang == Lang.EN) Lang.ES else Lang.EN },
                    onToggleTheme = { state.darkMode = !dark },
                )
                AnimatedContent(
                    targetState = state.screen,
                    transitionSpec = {
                        (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 40 }) togetherWith fadeOut(tween(160))
                    },
                ) { screen -> Router(state, screen, actions) }
            }
        }
    }
}

/** Live finances for the side panel, from the single source of truth (GameSession). */
private fun panelData(state: AppState): PanelData? {
    val s = state.session ?: return null
    return PanelData(
        name = s.profile.displayName,
        age = s.current.person.age,
        employment = s.current.employment,
        state = s.current,
        goals = s.profile.goals,
        turnsDone = s.turns.size,
        maxTurns = s.config.maxTurns,
        monthsPerTurn = s.config.monthsPerTurn,
    )
}

@Composable
private fun Router(state: AppState, screen: Screen, actions: TopBarActions) {
    val session = state.session
    val panel = panelData(state)
    when (screen) {
        Screen.WELCOME -> WelcomeScreen(actions, state::startInterview, state::startDemo)
        Screen.INTERVIEW -> InterviewScreen(actions, state.interview, state.answers, state::answer, state::undoLastAnswer)
        Screen.DILEMMA -> {
            val event = state.currentEvent
            if (session != null && event != null && panel != null) {
                val previews = remember(session, event, state.lang) { state.previews(event) }
                DilemmaScreen(actions, panel, session, event, previews, state::choose)
            }
        }
        Screen.RESOLUTION -> {
            val turn = state.lastTurn
            if (turn != null && panel != null) {
                ResolutionScreen(
                    actions = actions,
                    panel = panel,
                    turn = turn,
                    before = session?.stateBefore(turn.index) ?: turn.stateAfter,
                    event = turn.eventId?.let(state::eventById),
                    monthsPerTurn = state.config.monthsPerTurn,
                    isLastTurn = state.isLastTurn,
                    onNext = state::nextTurn,
                )
            }
        }
        Screen.RESULTS -> {
            val results = state.results
            if (session != null && results != null && panel != null) {
                ResultsScreen(
                    actions = actions,
                    panel = panel,
                    session = session,
                    results = results,
                    assumptions = state.assumptions,
                    eventById = state::eventById,
                    playedTurns = state.playedSession?.turns,
                    isEdited = state.isEdited,
                    pendingEvent = state.pendingEvent,
                    pendingPreviews = state.pendingEvent?.let { ev -> remember(session, ev, state.lang) { state.previews(ev) } }.orEmpty(),
                    onEditTurn = state::editTurn,
                    onDecide = state::decidePending,
                    onResetPath = state::resetToPlayed,
                    onChat = state::openChat,
                    onRestart = state::restart,
                    chronicle = state.chronicle,
                    chronicleLoading = state.chronicleLoading,
                    onWriteChronicle = state::writeChronicle,
                )
            }
        }
        Screen.CHAT -> ChatScreen(
            actions = actions,
            panel = panel,
            history = state.chatHistory,
            loading = state.chatLoading,
            isEditedPath = state.isEdited,
            aiModel = aiChatModel,
            persona = state.persona,
            onPersona = state::choosePersona,
            onAsk = state::ask,
            onBack = state::backToResults,
        )
    }
}
