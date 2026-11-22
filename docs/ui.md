# UI (Dev B) — `shared/src/commonMain/kotlin/com/lauzhack/twin/ui/`

Compose Multiplatform UI for Android, Desktop and Web, built on `main`'s model (`model/`). It runs end to end
on a **fake engine** until Dev A's engine exists; plugging the real one in is a one-place change.

## Run
From the repo root (JDK 17+; with IntelliJ's bundled JBR: `export JAVA_HOME=/opt/idea-…/jbr`):
- **Desktop:** `./gradlew :desktopApp:run`
- **Web:** `./gradlew :webApp:wasmJsBrowserDevelopmentRun`
- **Android:** `./gradlew :androidApp:assembleDebug` (needs the Android SDK)
- **Tests:** `./gradlew :shared:jvmTest`
- **Chat with a real LLM** (desktop): `OPENAI_API_KEY=… ./gradlew :desktopApp:run` — see `docs/ai.md`.

## Layout (`shared/src/commonMain/kotlin/com/lauzhack/twin/`)
| Path | Content |
|---|---|
| `App.kt` | Root composable used by `androidApp/`, `desktopApp/`, `webApp/` |
| `model/` | Shared contract (Dev A). Not changed by the UI |
| `engine/GameEngine.kt` | **Proposed contract** UI ↔ engine: `TwinFactory` (P2) and `GameEngine` (P3–P10). The UI only talks to these |
| `fake/` | `FakeGameEngine` + `FakeLedger` (money engine on `FinancialStatement`), `FakeTwinFactory`, `FakeData` (Alexia, 6 events with ES/EN text, facts, interview, assumptions), `CannedFutureSelfChat` (plan B). All values synthetic/illustrative |
| `ai/` | Future-self chat and chronicle (Koog). See `docs/ai.md` |
| `ui/` | `App.kt` (wiring + router), `AppState.kt` (holds `GameSession` / `GameResult`), `PathReplay.kt` (what-if tree), `StatementExt.kt` (read-only shortcuts over the model), `theme/`, `i18n/`, `components/`, `screens/` |

Platform code: `jvmMain`, `androidMain`, `wasmJsMain` only contain the locale override (`LocalAppLocale`) and `readEnv`
(API keys: desktop reads env vars; Android and Web read none and use the canned chat).

## Plugging in the real engine and content
Edit `createDefaultAppState()` in `ui/App.kt` (the only place that names the fakes):
- `engine = FakeGameEngine(…)` → Dev A's `GameEngine`; `twins = FakeTwinFactory()` → Dev A's `TwinFactory`.
- `catalog`, `facts`, `assumptions` → loaded from `content/*.json` (`data/`, Dev A).
- `interview` stays in the UI (Dev B owns the questions; texts are i18n keys).

What the UI expects from the engine (see the KDoc in `engine/GameEngine.kt`):
- `nextEvent` and `resolveLuck` **deterministic** (seed + turn index): the tree replays paths.
- `nextEvent` returns `null` for a **quiet turn**; the game ends after `maxTurns` turns (`GameSession.isOver`).
- `isEligible` exposed (the tree checks if a later question still fits after an edit).
- `playTurn` accepts a forced case (the tree lets the user change the luck).
- `finish` returns the full `GameResult` (stats, attribution, beats, parallel lives, sensitivity, `cashBand` of size horizon+1).

## Flow
Welcome → Interview (P1) → Dilemma (P3–P5, previews) → Resolution (P6–P7; quiet turns are shown here too) → … →
Results (P8–P11): KPIs, cash band, **your story** (chronicle), goals, **parallel lives**, what moved net worth (attribution),
decision tree, assumptions → Chat (P12) with persona **future self / parallel self**.

## What-if decision tree
After the last turn, `ResultsScreen` shows an editable tree (`ui/components/DecisionTree.kt`). Switching a turn's option or
case re-resolves the rest of the path (`ui/PathReplay.kt`), then `finish()` recomputes everything and the chat and story are cleared:
- A later question is **kept if still valid** (`isEligible`); otherwise a new one is picked with `nextEvent` (or the turn becomes quiet).
- A question never answered becomes a **pending** node that the user must decide.
- Explored branches are **remembered** (keyed by the path prefix), so switching back restores later decisions.

Demo: buying the car with a loan sets the milestone `took_loan`, which triggers `E-car-loan`; switching to the travelcard in the
tree replaces it with a pending `E-sabbatical`.

## Design system
- `ui/theme/Color.kt`: navy brand + emerald accent, light/dark schemes and `TwinColors` (cases, gains/losses, chart series), checked
  with a palette validator (lightness band, colour-blind separation, contrast) in both themes; case colors always paired with their name.
- `ui/theme/Type.kt`: Inter typography (tabular figures for money). `ui/theme/Icons.kt`: own stroke icons (no icon library).
- `ui/components/`: `AppShell` (top bar + side panel / compact strip), `SurfaceCard`, `KpiTile`, `DeltaPill`, `CaseBadge`,
  `CaseDistributionBar`, `FanChart` (gradient band, years axis, hover tooltip, draw-in animation), `DecisionTree`.

## Conventions
- UI text in `composeResources/values/strings.xml` (EN) and `values-es/strings.xml` (ES); content text (events, facts) is
  `LocalizedText` from the JSON and shown with `localized()`. Goal names and interview texts are i18n keys (`keyString()`).
- Money in CHF (`formatChf`, Swiss grouping `12’345 CHF`).

## Third-party assets
- **Inter** font by The Inter Project Authors, SIL Open Font License 1.1 — `shared/src/commonMain/composeResources/font/`,
  license in `licenses/Inter-OFL.txt`.
