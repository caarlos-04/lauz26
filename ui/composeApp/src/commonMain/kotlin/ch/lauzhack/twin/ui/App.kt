package ch.lauzhack.twin.ui

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
import ch.lauzhack.twin.fake.CannedFutureSelfChat
import ch.lauzhack.twin.fake.FakeData
import ch.lauzhack.twin.fake.FakeGameEngine
import ch.lauzhack.twin.fake.FakeProfileBuilder
import ch.lauzhack.twin.model.Lang
import ch.lauzhack.twin.ui.components.PanelData
import ch.lauzhack.twin.ui.components.TopBarActions
import ch.lauzhack.twin.ui.i18n.LocalAppLocale
import ch.lauzhack.twin.ui.i18n.tag
import ch.lauzhack.twin.ui.screens.ChatScreen
import ch.lauzhack.twin.ui.screens.DilemmaScreen
import ch.lauzhack.twin.ui.screens.InterviewScreen
import ch.lauzhack.twin.ui.screens.ResolutionScreen
import ch.lauzhack.twin.ui.screens.ResultsScreen
import ch.lauzhack.twin.ui.screens.WelcomeScreen
import ch.lauzhack.twin.ui.theme.TwinTheme

/** Wires the fakes. Swap FakeGameEngine / CannedFutureSelfChat here when the real ones exist. */
fun createDefaultAppState(): AppState = AppState(
    engine = FakeGameEngine(FakeData.events, FakeData.assumptions),
    chat = CannedFutureSelfChat(),
    assumptions = FakeData.assumptions,
    catalog = FakeData.events,
    interview = FakeData.interview,
    buildProfile = FakeProfileBuilder::build,
    demoProfile = FakeData.alexiaProfile to FakeData.alexiaState,
)

@Composable
fun App(state: AppState = remember { createDefaultAppState() }) {
    val dark = state.darkMode ?: isSystemInDarkTheme()
    CompositionLocalProvider(LocalAppLocale provides state.lang.tag) {
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
        age = s.profile.ageAtStart,
        employment = s.profile.employment,
        state = s.turns.lastOrNull()?.stateAfter ?: s.initial,
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
                    event = state.eventById(turn.eventId),
                    lang = state.lang,
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
                )
            }
        }
        Screen.CHAT -> ChatScreen(actions, panel, state.chatHistory, state.chatLoading, state.isEdited, state::ask, state::backToResults)
    }
}
