# UI — Financial Twin (Compose Multiplatform)

First version of the UI (Dev B), running end to end against **fake data**. See `../docs/README.md` for the full plan.

## Run
- **Desktop:** `./gradlew :composeApp:run` (JDK 17+; in IntelliJ use the bundled JBR as Gradle JVM)
- **Android:** `./gradlew :composeApp:assembleDebug` — needs the Android SDK (`sdk.dir` in `local.properties`; IntelliJ/Android Studio creates it)
- **Tests:** `./gradlew :composeApp:desktopTest`

## Layout (`composeApp/src/commonMain/kotlin/ch/lauzhack/twin`)
| Folder | Content |
|---|---|
| `model/` | Dataclasses from docs §5 (shared contract — do not change without telling the team) |
| `engine/GameEngine.kt` | Interface the UI uses (P3–P9). Real implementation: Dev A |
| `ai/FutureSelfChat.kt` | Chat interface (P10) |
| `ai/AgentFutureSelfChat.kt`, `ai/BriefingMapper.kt` | Adapter to the Koog agent in the root `ai/` module (see `../ai/README.md`): maps the selected branch to a `LifeBriefing`, falls back to canned answers offline |
| `fake/` | `FakeGameEngine`, `FakeData` (Alexia, 5 events, interview, assumptions), `FakeProfileBuilder`, `CannedFutureSelfChat` (plan B). All values synthetic/illustrative |
| `ui/` | `App.kt` (wiring + router), `AppState.kt` (holds `GameSession`), `theme/`, `i18n/`, `components/` (incl. Canvas `FanChart`), `screens/` |

`androidMain` / `desktopMain` only contain the entry point and the locale override.

## What-if decision tree
After the last turn, `ResultsScreen` shows an editable tree (`ui/components/DecisionTree.kt`). Switching a turn's option or case re-resolves the rest of the path (`ui/PathReplay.kt`), then recomputes charts and stats and clears the chat:
- A later question is **kept if still valid** (`GameEngine.isEligible`) and not used earlier; otherwise a new one is picked with `nextEvent`.
- A question never answered becomes a **pending** node in the tree that the user must decide.
- Explored branches are **remembered** (keyed by the path prefix), so switching back restores later decisions.

Requirement for the real engine: `nextEvent` must be deterministic (seeded with `seed + turnIndex`), otherwise paths can't be reproduced. Demo: buying the car with a loan triggers `E-car-loan` (`requiresLiability`); switching it to the travelcard in the tree replaces it with a pending `E-sabbatical`.

## Swapping in the real implementations
Edit `createDefaultAppState()` in `ui/App.kt`: replace `FakeGameEngine`, `FakeData.*` and `FakeProfileBuilder::build`. The chat already uses the real agent when `OPENAI_API_KEY` (or `ANTHROPIC_API_KEY`) is set (`OPENAI_API_KEY=… ./gradlew :composeApp:run`), and `CannedFutureSelfChat` otherwise.

## Design system ("modern fintech")
- `ui/theme/Color.kt`: navy brand + emerald accent, light/dark schemes and `TwinColors` (cases, gains/losses, chart series). Chart and case colors were checked with a palette validator (lightness band, colour-blind separation, contrast) in both themes; case colors are always paired with their name.
- `ui/theme/Type.kt`: Inter typography (tabular figures for money). `ui/theme/Icons.kt`: our own stroke icons (no icon library).
- `ui/components/`: `AppShell` (top bar + side panel with live finances on wide screens, compact strip on phones), `SurfaceCard`, `KpiTile`/`AnimatedMoney`, `DeltaPill`, `CaseBadge`, `CaseDistributionBar`, `FanChart` (gradient band, years axis, hover tooltip, draw-in animation).

## Third-party assets
- **Inter** font by The Inter Project Authors, SIL Open Font License 1.1 — `composeApp/src/commonMain/composeResources/font/`, license in `licenses/Inter-OFL.txt`.

## Conventions
- UI text lives in `composeResources/values/strings.xml` (EN) and `values-es/strings.xml` (ES). Data keys (`titleKey`, `labelKey`, goal names) are resolved with `keyString()`.
- `PathComparison.explanation` is assumed to be a list of **event ids** ordered by impact (to be agreed with Dev A).
- Money in CHF (`formatChf`, Swiss grouping `12’345 CHF`).
