# The tree of decisions — how the game works

> Owner: Dev A (`polma7`). Replaces the "event with options A/B" loop. Code: `tools/game.py` (Python reference),
> `shared/.../engine/` (Kotlin: `Events.kt`, `Game.kt`, `RealGameEngine.kt`), `shared/.../model/Game.kt` and
> `Event.kt`, `shared/.../ui/LifeTree.kt`. Content: `content/events.json` (version 4). How one month is simulated:
> [`simulator.md`](simulator.md); how that was learned: [`how-the-simulator-learns.md`](how-the-simulator-learns.md).

## 1. Idea

The player is not asked binary questions that the engine picks. At each decision point (every 24 months, 5 times)
the player sees **every action they can take** given their situation and **chooses one**. "Carry on as you are" is
always one of them. The player drives the life; the engine only says what each choice may give.

The lives form a **tree**. Each node is a snapshot (a `FinancialStatement` with its `pendingChanges`) at a decision
point. Taking an action at a node creates its child: every uncertain number of the action (a salary, a tuition, a
pay rise, a rate...) is drawn at the percentile given by the luck of that moment, an involuntary shock may happen, and
24 months are simulated. Nothing about the outcome is shown before deciding: the balances are seen afterwards. The user can go back to any node and take another action
there: that opens a new branch, and nothing already lived is lost.

**Nothing advances on its own.** A branch exists only because the user decided it, so there are as many nodes as
decisions taken: no combinatorial explosion and no lives computed behind the user's back. Comparing lives means
comparing branches the user actually explored.

```
start (age 20, month 0)
 ├─► A-master            (22, month 24)   ← decided at the start
 │    └─► A-master/NOTHING (24, month 48) ← decided at "A-master"
 └─► A-start-working     (22, month 24)   ← also decided at the start: a second branch
```

## 2. The model

```kotlin
data class DecisionNode(
    val id: String,              // the path of action ids: "" (start), "A-master", "A-master/NOTHING"...
    val parentId: String?,       // null at the start
    val depth: Int,              // decisions taken so far (0..5)
    val actionId: String?,       // the action taken AT THE PARENT that led here (null at the start)
    val luck: Double?,           // 0..1: the percentile its uncertain numbers were drawn at
    val shockId: String?,        // an involuntary event in those 24 months (job loss), if any
    val state: FinancialStatement,   // the snapshot: statement + pendingChanges
)
data class DecisionTree(seed, luckMode, preferences, config, nodes: Map<String, DecisionNode>)
```

- A node stores **only its state and what led to it**. The 24 months between a node and its parent (charts, story
  moments, attribution) are **not stored**: the engine is a pure function, so replaying the parent's state with the
  same action and luck gives exactly the same months.
- `GameSession` (initial statement, seed, luck mode and the `Turn`s from the start to a node) is the **view of one
  life** that the screens, the chat and the analysis read. The UI builds it once per node (`ui/LifeTree.kt`).

## 3. The pool of actions (`content/events.json`, version 5)

```json
{"version": 5,
 "actions": [ {"id": "A-master", "requires": {...}, "effects": [... median values ...],
               "uncertain": [{"name": "salary", "law": "LOGNORMAL", "median": 6100, "low": {"q": 0.25, "value": 4500},
                              "high": {"q": 0.75, "value": 7500}, "better": "HIGH", "step": 50, "format": "chf",
                              "targets": [{"effect": 6, "field": "monthlyChf"}]}, ...],
               "outcome": {"EN": "... you start working at CHF {salary} a month.", "ES": "..."}}, ... ],
 "shocks":  [ {"id": "S-job-loss", "requires": {...}, "probability": 0.04, "effects": [...], "uncertain": [...], "outcome": {...}} ]}
```

- An **action** has requirements (`requires`, `earliestTurn`, `repeatable`: by default once per life), its **effects**
  with the median of every uncertain number (they only become `pendingChanges` of the statement, `docs/README.md` 5.3),
  its **uncertain numbers** and an **outcome** sentence where `{name}` is replaced by the drawn value.
- **No pessimistic / neutral / optimistic cases.** Each uncertain number is a distribution "like a normal" through three
  values of the data (`low`, `median`, `high`: p25 / p50 / p75 of salaries, tuitions, pay ratios; p10 / p50 / p90 of
  loan rates). It is a two-piece normal (each half has the spread of its quantile, so it goes exactly through the three
  values), on the logarithm for amounts, factors and durations (`LOGNORMAL`: never negative, longer tail upwards), on the
  value for rates (`NORMAL`). The rent of a new flat uses the luck as the quantile of the rent model (`QUANTILE`).
- **One luck per decision**: every uncertain number of an action is drawn at the same percentile, on its `better` side
  (good luck = a higher salary AND a lower tuition AND a cheaper flat). Percentiles are kept within p5–p95 (no absurd
  tails), and `min` / `max` keep values meaningful (a promotion never cuts pay).
- **NOTHING** ("carry on as you are") is always available: no requirements, repeatable, no effects. It does not freeze
  the life: what is under way carries on (a master's started two years ago ends, a loan keeps being paid back, a
  temporary pay cut is undone).
- Real alternatives are separate actions ("Do a master's" and "Start working now" are two actions for a student).
- **Shocks** are what nobody chooses (job loss). After the action, the first shock whose requirements match the state
  (with the action's immediate changes) happens with its probability per turn, starting in a month drawn within the
  turn; its own luck draws its uncertain numbers (how many months without a job). It is shown and explained like any
  decision, marked as "not chosen".

Today: 17 actions (14 with uncertain numbers) and 2 shocks (job loss under 25 and from 25 to 62, with the Swiss
unemployment rate of the age group as the chance per turn: an assumption). `python tools/validate_events.py` checks the
file; `--preview <profile>` simulates every action available at the start at luck p10 / p50 / p90 (to balance the content).

## 4. Luck

Chosen by the user before the game starts (welcome screen). Both are reproducible from the seed.

| Mode | The luck of a node depends on | Effect |
|---|---|---|
| `SAME_LEVEL` (default) | seed + depth | Every decision at the same age gets the same luck: the differences between branches come only from the decisions |
| `FULL_RANDOM` | seed + the node's path (64-bit FNV-1a hash of its id) | Each decision has its own luck, as in real life |

Taking the same action at the same node always gives the same child (it is reused, not recomputed). The shock of a
turn uses the same node seed with its own salts (`SHOCK_SALT`, `SHOCK_MONTH_SALT`, `SHOCK_CASE_SALT`), so in
`SAME_LEVEL` a job loss at an age hits every sibling that is employed after its action.

## 5. Engine API (UI ↔ engine, `engine/GameEngine.kt`)

| Function | What |
|---|---|
| `actionsAt(session)` | The actions available at the node the life reaches, NOTHING first; empty at the end |
| `decide(session, actionId)` | The new `Turn`: luck of the node → the uncertain numbers drawn at that percentile, shock, 24 months, story moments |
| `finish(session)` / `attribution(session)` | Stats, attribution and moments of the life up to that node |
| `months(session)` | Every month of the life up to that node (index = month), replayed: what the Explore panel breaks down |
| `noiseBand(session)` | Optional month-to-month noise band (sampled flows) |

In the UI (`ui/LifeTree.kt`): `decide(tree, node, action)` adds the child (or returns the existing one),
`choose(tree, action)` decides at the cursor and moves there, `goTo(node)` moves the cursor. `AppState` adds the user
flow: `choose`, `nextTurn` (the dilemma with every available action, or the results), `decideAgain` (back to the moment
before and decide something else), `decideAgainAt(node)` (the dilemma at any node), `switchAt(turn)`.

## 6. Screens

- **Welcome**: the luck mode.
- **Decision**: one card per available action: title, description and what depends on luck with its usual range
  ("Monthly salary: usually between CHF 4’500 and 7’500"). **No balances before deciding.** "Carry on as you are"
  first, "Already explored" on actions taken there before.
- **What happened**: the action, the luck of the moment (percentile) and the outcome with the drawn numbers, the shock if any, before/after, where the numbers
  come from, and "Go back and decide something else".
- **Map of lives**: only the explored nodes; for each, what was decided, its luck and shock, the money and goals, and
  the buttons "Go to this moment", "Decide what comes next" and "Decide something else here".
- **Explore** ("where does this number come from?", `ui/Breakdown.kt`, `ui/components/ExplorePanel.kt`): every amount on
  every screen has a dotted underline; clicking it opens a panel over the screen (right sheet on wide windows, bottom
  sheet on phones) with that figure, a bar per month and the short list of pieces that add up to it. A piece opens one
  level down and the breadcrumbs go back up: **balance** (net worth = cash + investments − debt) → **flows** (cash =
  cash at the start + income − expenses − loan payments − interest on negative cash; one line per category) → **origin**
  (dataset rule, public trend with its rate, the decision and its luck, or an assumption). The period is "since the
  last decision" or "since the start", and tapping a bar narrows everything to that month. Nothing is computed in the
  UI: the rows are sums of the engine's monthly statements and the "it adds up" line is the real residual.
- **Results and chat**: the life up to the selected node, with the net worth month by month split into cash,
  investments and debt (a month opens in Explore). The automatic parallel lives (other first choice, greedy
  strategy) and the "parallel self" were removed: comparisons are between branches the user explored.

## 7. Validation

| Check | Result |
|---|---|
| `tools/test_events.py` | pool and requirements, NOTHING keeps what is under way, luck modes, a shock is told and credited, unavailable actions refused |
| `tools/golden.py` → `GoldenGameTest` | 16 lives (every action, branches from the same nodes, both luck modes, two job losses): Kotlin = Python, 0.00 CHF |
| `RealGameEngineTest`, `LifeTreeTest`, `RealLifeTreeTest` | the UI interface reproduces the reference engine; one snapshot per decision; siblings share the luck in SAME_LEVEL and not in FULL_RANDOM |
| `ContentTwinFactoryTest` | the app plays a whole game from the interview with the real content |
| `BreakdownTest`, `RealBreakdownTest` | Explore: on every node of Alexia's lives (both luck modes), for the last turn, the whole life and every single month, each figure is the sum of its rows (61,817 breakdowns, residual < 0.01 CHF), one level down gives the same money, and the totals match the engine's attribution |

## 8. Open points

- The pool is small (17 actions; 2–5 available at a node). More actions (save, invest, change city, have a child...)
  make the decisions richer; invest needs an investment return (0 % today, `EngineRules`).
- A student who always carries on keeps studying (and paying tuition) for ten years: realistic for the rules, but the
  content could add an action "Finish your studies".
