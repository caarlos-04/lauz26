package com.lauzhack.twin.ui

import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.lauzhack.twin.ui.components.TracePanel
import org.jetbrains.compose.resources.stringResource
import com.lauzhack.twin.ui.i18n.localized
import com.lauzhack.twin.resources.map_quiet
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.engine.isOver
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.ui.screens.ChatMoment
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
import com.lauzhack.twin.ui.screens.LifeMapSection
import com.lauzhack.twin.ui.screens.MapScreen
import com.lauzhack.twin.ui.screens.ResolutionScreen
import com.lauzhack.twin.ui.screens.ResultsScreen
import com.lauzhack.twin.ui.screens.WelcomeScreen
import com.lauzhack.twin.ui.theme.TwinTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lauzhack.twin.content.AppContent
import com.lauzhack.twin.engine.RealGameEngine
import com.lauzhack.twin.interview.ContentTwinFactory

/**
 * Wires the dependencies: the ONLY place to change the implementations.
 * - engine: RealGameEngine on TwinEngine (#16), with the packaged flow model, macro trends, events and assumptions.
 * - twins + interview: ContentTwinFactory and content/interview.json (#15).
 * - catalog / facts / assumptions: content/events.json, facts.json, assumptions.json ([AppContent]).
 * - chat: the Koog agent (ai/) when OPENAI_API_KEY or ANTHROPIC_API_KEY is set, otherwise canned answers.
 */
fun createAppState(content: AppContent): AppState = AppState(
    engine = RealGameEngine.from(content.flowModel, content.macro, content.events, content.assumptions),
    twins = ContentTwinFactory(content.facts, content.interviewDefaults, content.assumptions.objectivePenalties, content.alexia),
    chat = createChat(content.events.events, CannedFutureSelfChat(templates = content.chronicle, events = content.events)),
    assumptions = content.assumptions,
    facts = content.facts,
    catalog = content.events.events,
    interview = content.interview.questions,
)

/** Everything fake (no resources needed): previews and UI tests. */
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

private fun createChat(catalog: List<Event>, canned: CannedFutureSelfChat = CannedFutureSelfChat()): FutureSelfChat {
    val agent = futureSelfAgent ?: return canned
    val mapper = BriefingMapper(eventById = { id -> catalog.firstOrNull { it.id == id } }, text = ::keyStringAsync)
    return AgentFutureSelfChat(agent, mapper, fallback = canned)
}

/** The app. With no [state] it first loads the packaged content (AppContent: events, interview, models...). */
@Composable
fun App(state: AppState? = null) {
    // Reading and decoding the content takes ~1 s on a laptop (more on a phone): off the UI thread, so the spinner spins.
    val loaded by produceState(state) { if (value == null) value = withContext(Dispatchers.Default) { createAppState(AppContent.load()) } }
    val app = loaded
    if (app == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    AppContentLoaded(app)
}

@Composable
private fun AppContentLoaded(state: AppState) {
    val dark = state.darkMode ?: isSystemInDarkTheme()
    CompositionLocalProvider(LocalAppLocale provides state.lang.tag, LocalLang provides state.lang) {
        // key() forces resources to reload when the language changes.
        key(state.lang) {
            TwinTheme(dark) {
                val actions = TopBarActions(
                    isDark = dark,
                    onToggleLang = { state.lang = if (state.lang == Lang.EN) Lang.ES else Lang.EN },
                    onToggleTheme = { state.darkMode = !dark },
                    onMap = if (state.tree != null && state.screen != Screen.MAP) state::openMap else null,
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
        name = s.initial.person.displayName,
        age = s.current.person.age,         // already the age at the current month (the simulator ages the person)
        employment = s.current.employment,
        state = s.current,
        goals = s.goalStatuses(),
        turnsDone = s.turns.size,
        maxTurns = s.config.maxTurns,
        monthsPerTurn = s.config.monthsPerTurn,
    )
}

/** The self of the current node, for the chat header. */
@Composable
private fun chatMoment(session: GameSession, eventById: (String) -> Event?): ChatMoment {
    val now = session.current
    val turn = session.turns.lastOrNull()
    val event = turn?.eventId?.let(eventById)
    val option = event?.options?.firstOrNull { it.id == turn.chosenOptionId }
    val after = when {
        turn == null -> null
        event == null || option == null -> stringResource(Res.string.map_quiet)
        else -> localized(event.title) + " → " + localized(option.label)
    }
    return ChatMoment(now.person.age, now.month / 12, after, session.isOver)
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
                DilemmaScreen(
                    actions, panel, session, event, previews, state::choose,
                    lived = event.options.map { it.id }.filterTo(mutableSetOf()) { id -> state.tree?.visited?.contains(session.nodeKey + id) == true },
                )
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
                    onOther = if (turn.chosenOptionId != null) state::openOther else null,
                    onChat = if (state.canChat) state::openChat else null,
                    explain = state.tree?.cursor?.let { key -> state.traceAt(key) }?.let { tr -> { TracePanel(tr, state::eventById, state.facts, state.assumptions) } },
                )
            }
        }
        Screen.RESULTS -> {
            // Results first (needed to draw the screen), then the noise band (its card is last and appears when ready).
            LaunchedEffect(state.tree?.cursor) {
                state.prepareResults()
                state.prepareNoiseBand()
            }
            val results = state.results
            if (results == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            if (session != null && results != null && panel != null) {
                ResultsScreen(
                    actions = actions,
                    panel = panel,
                    session = session,
                    results = results,
                    noiseBand = state.noiseBand,
                    assumptions = state.assumptions,
                    facts = state.facts,
                    eventById = state::eventById,
                    isEdited = state.isEdited,
                    onResetPath = state::resetToPlayed,
                    map = { LifeMapSection(state) },
                    explainLife = { state.traceOfLife()?.let { TracePanel(it, state::eventById, state.facts, state.assumptions) } },
                    onChat = state::openChat,
                    onRestart = state::restart,
                    chronicle = state.chronicle,
                    chronicleLoading = state.chronicleLoading,
                    onWriteChronicle = state::writeChronicle,
                )
            }
        }
        Screen.MAP -> MapScreen(actions, panel, state)
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
            onBack = state::closeChat,
            moment = session?.let { chatMoment(it, state::eventById) },
        )
    }
}
