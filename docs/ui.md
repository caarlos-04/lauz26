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

## Engine and content
`createAppState(content)` in `ui/App.kt` wires the real engine (`RealGameEngine` on `TwinEngine`) and the packaged
content; `createDefaultAppState()` runs the same real engine on the small hand-written model of `fake/FakeData.kt`
(previews and UI tests, no resources).

What the UI expects from the engine (KDoc in `engine/GameEngine.kt`, design in [`decision-tree.md`](decision-tree.md)):
- `actionsAt(session)`: every action available at the node, "carry on as you are" (NOTHING) first; empty at the end.
- `preview(session, action)`: its cases after 24 months; `decide(session, actionId)`: the new turn (luck of the node by
  luck mode, maybe a shock). Pure: the same action at the same node always gives the same child.
- `finish` / `attribution` / `noiseBand` for the life up to any node.

## Flow
Welcome (luck mode) → Interview (P1) → Decision (every available action with its cases) → What happened (the case, a
shock if any, "go back and decide something else") → … → Results (P8–P11): KPIs, goals, what moved net worth, life line,
chronicle, noise band; the map of lives; assumptions → Chat (P12) with your self of the selected moment.

## The tree of decisions (`ui/LifeTree.kt`)
- One node per decision taken (`DecisionTree`), plus the life up to each node (`GameSession`) for the screens.
- Going back to any node and deciding something else opens a new branch; nothing already lived is lost.
- The map (`ui/components/LifeMap.kt`) shows only the explored nodes; each one can be opened, continued ("decide what
  comes next") or re-decided ("decide something else here"). The chat and the story belong to the selected node.

## Design system ("Ink & cobalt")
Editorial, Swiss-style look. Principles: paper and ink do the work, one accent; hairlines and spacing instead of nested
cards, shadows or tinted badges; sentence-case labels; figures are the protagonists, in a monospaced face.
- `ui/theme/Color.kt`: warm paper `#F5F3EE` / ink `#161514`, one cobalt accent `#2B44E0` (selection, your path, links);
  money keeps its own meaning (green `#11774A` up, terracotta `#C2410C` down), ochre for "in progress". Dark mode: night
  `#121110`, chalk `#ECE9E2`, light cobalt `#8C9BFF`. Contrast checked (WCAG) in both themes, numbers in the file.
- `ui/theme/Type.kt`: IBM Plex Sans for text, IBM Plex Mono for every amount, percentage and count (`TextStyle.figures()`).
- `ui/theme/Theme.kt`: small radii (3-14 dp), spacing scale 4-48 dp. `ui/theme/Icons.kt`: own stroke icons, used only
  where they carry meaning (navigation, actions), never as decoration.
- `ui/components/`: `AppShell` (top bar + side panel / compact strip), `SurfaceCard` (the only card, never nested),
  `KpiTile`, `DeltaPill`, `CaseBadge`, `CaseDistributionBar`, `LifeMap`, `GoalViews`, `TracePanel`, `FanChart`.

## Conventions
- UI text in `composeResources/values/strings.xml` (EN) and `values-es/strings.xml` (ES); content text (events, facts) is
  `LocalizedText` from the JSON and shown with `localized()`. Goal names and interview texts are i18n keys (`keyString()`).
- Money in CHF (`formatChf`, Swiss grouping `12’345 CHF`).

## Third-party assets
- **IBM Plex Sans** and **IBM Plex Mono** fonts, © IBM Corp., SIL Open Font License 1.1 —
  `shared/src/commonMain/composeResources/font/`, license in `licenses/IBM-Plex-OFL.txt`.
