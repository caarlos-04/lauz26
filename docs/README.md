# Plan del proyecto — AI Financial Twin (LauzHack 2026)

> Documento de contexto para los tres desarrolladores y sus agentes (Claude Code, etc.).
> Resume las decisiones tomadas. **Si algo cambia, actualizad este fichero.**
> Los comentarios en el código van en inglés (ver `AGENTS.md`); esta documentación está en español.

## Índice
1. [Qué construimos](#1-qué-construimos)
2. [Decisiones tomadas](#2-decisiones-tomadas)
3. [Experiencia y bucle de juego](#3-experiencia-y-bucle-de-juego)
4. [Arquitectura](#4-arquitectura)
5. [Modelo de datos (dataclasses)](#5-modelo-de-datos-dataclasses)
6. [Flujo de información: input y output de cada paso](#6-flujo-de-información-input-y-output-de-cada-paso)
7. [Reglas de cálculo del motor](#7-reglas-de-cálculo-del-motor)
8. [Datos y calibración](#8-datos-y-calibración)
9. [Análisis: árbol de decisiones, vidas paralelas y sensibilidad](#9-análisis-árbol-de-decisiones-vidas-paralelas-y-sensibilidad)
10. [LLM: de dónde salen las respuestas y cómo no pagar](#10-llm-de-dónde-salen-las-respuestas-y-cómo-no-pagar)
11. [Reparto del trabajo](#11-reparto-del-trabajo)
12. [Cosas importantes y preguntas abiertas](#12-cosas-importantes-y-preguntas-abiertas)
13. [Mejoras futuras (si sobra tiempo)](#13-mejoras-futuras-si-sobra-tiempo)

---

## 1. Qué construimos

Un juego tipo **"diseña tu propia vida"** en el que el usuario ve a **su gemelo digital** vivir diez años:
cada decisión cambia su vida y su dinero, y al final se entiende **qué cambios le llevaron hasta su estado final**.

Feedback de la organización que guía el diseño:
- **Lo importante es la historia**: el gemelo digital de la persona y la **narrativa de los cambios** que lo llevan al estado final.
- **Monte Carlo es demasiado simple**: hay que ir más allá. Lo sustituimos por un árbol de decisiones resuelto de forma exacta, vidas paralelas y análisis de sensibilidad (sección 9).

Integra dos retos a la vez:
- **AI Financial Twin** (reto principal, autora del concepto: Stefania Stan). Principio: *"Don't predict the future. Make it explorable."*
  La rúbrica (borrador) pesa: modelo financiero y corrección 25, escenarios y contrafactual 20, ejecución y validación 15,
  experiencia de usuario 15, innovación 15, transparencia 10. Preguntas de los jueces: *¿qué supuestos afectan más?,
  ¿cómo cambia el estado una decisión?, ¿cómo lo habéis validado?, ¿qué pasa si se viola una restricción?*
- **JetBrains Kotlin Multiplatform**. La app debe correr en **al menos 2 plataformas** y se puntúa cuánta lógica
  vive en `commonMain`, además de modo oscuro/claro, localización, Compose Multiplatform, backend Kotlin y lógica de IA compartida (Koog).
  Puntos extra por *Agentic Development* con JetBrains Air (documentad qué hizo cada agente y qué corregimos).

## 2. Decisiones tomadas

| Tema | Decisión |
|---|---|
| Stack | Kotlin Multiplatform + Compose Multiplatform |
| Plataformas | Android + Desktop (JVM) |
| Idiomas | ES / EN |
| Tiempo | Menos de 24 h → MVP muy recortado |
| Perfil | El usuario introduce el suyo mediante entrevista de opción múltiple. **Alexia** (20 años, estudiante, Lausanne, CHF 8.000) queda precargada como perfil de demo por si falla |
| Gemelo digital | Un **`FinancialStatement`** por paso con toda la información de la persona (ver `docs/financial-statement.md`): dinero (lo calcula el motor) + vida: hechos (estudios, ocupación, ciudad, vivienda, familia) e hitos (`flags`), que solo cambian los eventos, de forma explícita |
| Dimensiones no financieras medidas | **No** (nada de bienestar, estrés, etc.: no hay datos) |
| Partida | ~5 turnos de 24 meses (10 años, 120 meses) |
| Casos | Cada decisión tiene 3 casos: pesimista / neutro / optimista, sorteados con la **suerte** de la partida |
| Historia | Relato corto en cada turno (narrativa fija del caso) + **crónica completa** generada por el LLM al final, a partir de hechos calculados |
| Incertidumbre y análisis | **Sin Monte Carlo.** Árbol de decisiones exacto (estrategia óptima y vidas paralelas), sensibilidad y punto de equilibrio, y banda de ruido analítica |
| Restricciones | Liquidez mínima del usuario; la caja negativa se financia con interés |
| Dinero | `Double` en CHF (tests de coherencia con tolerancia). Sin impuestos (limitación documentada) |
| LLM | **Sin decidir**, pero **gratis** (sección 10). Oculto tras la interfaz `FutureSelfChat` |
| Persistencia | Partida en memoria durante la demo (por decidir si se guarda en disco) |

Terminología: **caso** (pesimista/neutro/optimista), **hecho** (`fact`), **hito** (`flag`), **momento** (`StoryBeat`), **vida paralela**.

## 3. Experiencia y bucle de juego

```
Entrevista ─► Gemelo inicial (dinero + vida)
                 │
   ┌─────────────▼──────────────────────────────────────────────┐
   │ Turno N: el motor elige un evento según la vida actual      │
   │   ├─ Decisión A ──► 3 casos (pesimista / neutro / optimista)│
   │   └─ Decisión B ──► 3 casos                                 │
   │ El usuario elige → la suerte resuelve un caso → relato corto│
   │ → pasan 24 meses → cambian dinero, hechos e hitos           │
   └─────────────┬──────────────────────────────────────────────┘
                 │ (5 turnos)
                 ▼
 Final: línea de vida · cascada "de dónde viene tu patrimonio" · vidas paralelas
        · "tu historia cambiaría si..." · crónica · chat con tu yo del futuro
```

- **La historia depende de la vida**: los eventos se eligen según hechos, hitos y caja (`requires`). Si hiciste el máster,
  después pueden salir eventos para quien tiene máster; si la caja está en negativo, sale el evento de financiación.
- **Antes de elegir** se ven los 3 casos de A y de B (preview determinista).
- **Al elegir**, la suerte de ese turno (un número de 0 a 1 derivado de la semilla) decide el caso. La misma suerte
  se usa en las vidas paralelas: "con la misma suerte, si hubieras elegido B...".
- **Turnos tranquilos**: si ningún evento cumple las condiciones, pasan 24 meses sin decisión (también es un momento de la historia).
- **Al final** (todo calculado por el motor; el LLM solo lo cuenta):
  1. **Línea de vida** con edad, hitos y momentos clave (mínimo de caja, metas alcanzadas, cambios de vida).
  2. **Cascada** del patrimonio: de dónde sale y a dónde va cada franco, y cuánto aporta cada decisión.
  3. **Vidas paralelas**: tu otra vida (primera decisión cambiada) y la vida con la estrategia óptima para tus prioridades.
  4. **"Tu historia cambiaría si..."**: los supuestos que más mueven el resultado y el punto en que cambia la mejor decisión.
  5. **Crónica** de tu vida y **chat** con tu yo del futuro (o con tu yo paralelo).
- Opcional: modo "elige tú el caso" para exploración libre. *(Pendiente de confirmar.)*

## 4. Arquitectura

Casi todo en `commonMain`:

```
commonMain
├─ model/     dataclasses (sección 5)                       [contrato compartido]
├─ engine/    apply(), avance mensual, suerte, financiación, cambios de vida,
│             momentos (StoryBeat), atribución, árbol (expectimax), vidas paralelas, sensibilidad
├─ events/    catálogo de eventos y selección según la vida actual
├─ data/      carga de JSON (perfiles, calibración, eventos, hechos, supuestos, entrevista)
├─ ai/        interfaz FutureSelfChat y crónica (implementación LLM intercambiable)
└─ ui/        pantallas Compose: entrevista, dilema, resolución, línea de vida, resultados, chat
androidMain / desktopMain  → solo arranque, ficheros y clave API
tools/        scripts Python offline (calibración, backtest, validación y preview de eventos)
content/      contenido del juego en JSON (eventos, hechos, supuestos), escrito por Dev C
```

Principios:
- El motor es **función pura e inmutable**: `apply(state, option, case) → newState`. Fácil de testear y de reproducir.
- **El dinero lo calcula el motor; la vida la cambian los eventos.** Nada se inventa por el camino.
- Los eventos son **datos JSON**, no código. La app solo lee JSON (los CSV se procesan offline en `tools/`).
- **Nadie cambia `model/` sin avisar a los demás**: es el contrato.

## 5. Modelo de datos (dataclasses)

Convenciones:
- Todas `@Serializable` (kotlinx.serialization). Todas **inmutables** (`val`, `copy()` para cambiar).
- Dinero: `Double` en **CHF**. Tipos de interés/rentabilidad: **fracción anual** (0.05 = 5 %).
- Tiempo: `month` = índice de mes desde el inicio de la partida (**0..120**). `startMonth` como texto `"2025-01"`.
- Probabilidades: `0.0..1.0`. Los ids son `String` estables.
- Textos del **contenido** (eventos, hechos, hitos) van en línea: `LocalizedText = Map<Lang, String>`.
  Los textos fijos de la **UI** usan claves i18n.

### 5.1 Enums

| Enum | Valores | Origen |
|---|---|---|
| `Employment` | `STUDENT, EMPLOYED, PART_TIME, SELF_EMPLOYED, RETIRED, UNEMPLOYED` | `employment_status` del dataset |
| `Household` | `SINGLE, COUPLE, FAMILY, SHARED_HOUSING` | `household_type` |
| `CaseType` | `PESSIMISTIC, NEUTRAL, OPTIMISTIC` | diagrama del equipo (este orden se usa para resolver la suerte) |
| `AssetType` | `CASH, INVESTMENT_PORTFOLIO` | `asset_type` (solo existen estos dos). Añadir otros **solo si un evento lo necesita** |
| `LiabilityType` | `PERSONAL_LOAN` | `liability_type` (solo existe este). Añadir otros **solo si un evento lo necesita** |
| `ExpenseCategory` | `RENT, LIVING, TUITION, INSURANCE, TRAVEL, OTHER` | propio |
| `FactKey` | `EDUCATION, OCCUPATION, CITY, HOUSING, FAMILY` | propio; valores en `content/facts.json` |
| `Objective` | `GROWTH, BALANCED, SAFE` | prioridad del usuario (entrevista) |
| `BeatKind` | `START, DECISION, CASE_RESOLVED, LIFE_CHANGE, QUIET_PERIOD, CASH_NEGATIVE, CASH_RECOVERED, LOW_POINT, GOAL_REACHED, GOAL_MISSED, END` | momentos de la historia |
| `Priority` | `HIGH, MEDIUM` | `priority` de `synthetic-goals.csv` |
| `Origin` | `OBSERVED, DERIVED, ASSUMED, SIMULATED` | de dónde sale cada importe |
| `Indexation` | `WAGES, CPI, NONE` | qué índice actualiza un flujo cuando se modele la inflación |
| `Lang` | `ES, EN` | i18n |

### 5.2 Perfil y estado del gemelo: `FinancialStatement`

El estado del gemelo en cada paso es un **`FinancialStatement`**: toda la información de la persona en un punto del
tiempo (quién es, hechos de vida, caja, inversiones, deudas, flujos, qué pasó con su caja ese mes, metas e indicadores),
construido cruzando todos los datasets por `profile_id`.
**Esquema completo, origen de cada campo y decisiones: [`docs/financial-statement.md`](financial-statement.md).**

```kotlin
// Static profile. Produced by the interview (or loaded for Alexia). Subset of synthetic-profiles.csv.
data class UserProfile(
    val id: String,                 // "P00001" or "USER-<uuid>"
    val displayName: String,
    val ageAtStart: Int,
    val city: String,
    val employment: Employment,     // at start
    val household: Household,       // at start
    val startMonth: String,         // "2025-01"
    val goals: List<Goal>
)

// The digital twin at one point in time. Month 0 = opening; month t = end of month t (with that month's cash flow).
// The engine takes a statement and returns a new one; derived parts are recomputed in finalize().
data class FinancialStatement(
    val version: Int, val profileId: String, val month: Int, val period: String,
    val origin: Origin,                         // OBSERVED (dataset months) | SIMULATED
    val currency: String,                       // "CHF"
    val person: PersonSnapshot,                 // age, city, employment, household, facts, flags
    val accounts: List<CashAccount>,            // cash (counted once)
    val investments: List<Investment>,
    val liabilities: List<Liability>,
    val incomeStreams: List<IncomeStream>,      // with fromMonth / untilMonth / indexation / origin / sourceEventId
    val expenseStreams: List<ExpenseStream>,
    val cashFlow: CashFlow?,                    // null at month 0
    val balanceSheet: BalanceSheet,             // derived
    val goals: List<GoalStatus>,                // derived
    val scenarioParameters: List<ScenarioParameter>,   // scenario-assumptions.csv for this profile
    val metrics: StatementMetrics,              // derived: savings rate, debt service ratio, emergency months...
    val pendingLifeChanges: List<LifeChange>    // decided by events, not applied yet
)

// A scheduled life change. Exactly one of employment / fact / flag is set.
data class LifeChange(
    val month: Int,
    val sourceEventId: String,
    val employment: Employment? = null,
    val factKey: FactKey? = null,
    val factValue: String? = null,
    val flag: String? = null
)

data class Goal(
    val id: String,                 // "G-P00001-01"
    val name: String,
    val targetChf: Double,
    val targetDate: String,         // "2029-12-31"
    val targetMonth: Int,           // statement month at the end of the target month (2029-12-31 -> 60)
    val priority: Priority          // HIGH | MEDIUM
)
```

### 5.3 Eventos (contenido del juego, en JSON)

Contrato definido por Dev C. Contenido en `content/events.json`, validado con `tools/validate_events.py`
(guía de escritura en `content/README.md`).

```kotlin
typealias LocalizedText = Map<Lang, String>   // {"ES": "...", "EN": "..."}

data class Event(
    val id: String,                 // "E-master" (unique)
    val category: String,           // category from scenario-catalog.csv, e.g. "Education & skills"
    val title: LocalizedText,
    val description: LocalizedText,
    val requires: Requirements? = null,   // null = eligible for everyone
    val priority: Int = 0,          // among eligible events the highest priority is picked (e.g. financing = 10)
    val repeatable: Boolean = false,      // false = at most once per game
    val earliestTurn: Int = 0,
    val sources: List<String>,      // where each number comes from (shown in the UI)
    val options: List<EventOption>  // ALWAYS exactly 2, ids "A" and "B"
)

// All present fields must match. Evaluated at the start of the turn.
data class Requirements(
    val employment: List<Employment>? = null,
    val minAge: Int? = null,        // age = ageAtStart + month / 12
    val maxAge: Int? = null,
    val facts: Map<FactKey, List<String>>? = null,   // fact value must be one of these
    val flagsAll: List<String>? = null,     // must have all these milestones
    val flagsNone: List<String>? = null,    // must have none of these
    val minCashChf: Double? = null,
    val maxCashChf: Double? = null          // e.g. 0 -> only when cash is negative
)

data class EventOption(
    val id: String,                 // "A" | "B"
    val label: LocalizedText,
    val cases: Map<CaseType, CaseOutcome>   // must contain all 3 CaseType
)

data class CaseOutcome(
    val prob: Double,               // the 3 probs of an option MUST sum to 1.0
    val effects: List<Effect>,
    val narrative: LocalizedText    // short story shown after the case is resolved
)

// JSON discriminator: "type" (use @SerialName). Offsets are months relative to the start of the turn (s).
sealed interface Effect {
    // Money
    @SerialName("set_income")          // replace income stream from s+offset on (ends the old one). Permanent.
    data class SetIncome(val streamId: String, val monthlyChf: Double, val startOffsetMonths: Int = 0) : Effect
    @SerialName("income_delta")        // extra income stream (negative = cut) for `months` (null = permanent)
    data class IncomeDelta(val streamId: String, val deltaChf: Double,
                           val startOffsetMonths: Int = 0, val months: Int? = null) : Effect
    @SerialName("expense_delta")       // extra expense stream (negative = saving) for `months` (null = permanent)
    data class ExpenseDelta(val category: ExpenseCategory, val deltaChf: Double,
                            val startOffsetMonths: Int = 0, val months: Int? = null) : Effect
    @SerialName("one_off")             // one-time cost at s+offset (negative = one-time gain)
    data class OneOff(val chf: Double, val startOffsetMonths: Int = 0) : Effect
    @SerialName("add_liability")       // new debt at s; annuity payment over termMonths
    data class AddLiability(val liabilityType: LiabilityType, val principalChf: Double, val annualRate: Double,
                            val termMonths: Int, val disburseToCash: Boolean = true) : Effect
    @SerialName("asset_return_delta")  // change the annual return of an asset type from s on
    data class AssetReturnDelta(val assetType: AssetType, val deltaAnnualRate: Double) : Effect
    // Life
    @SerialName("set_employment")
    data class SetEmployment(val employment: Employment, val startOffsetMonths: Int = 0) : Effect
    @SerialName("set_fact")            // value must exist in content/facts.json for that key
    data class SetFact(val key: FactKey, val value: String, val startOffsetMonths: Int = 0) : Effect
    @SerialName("add_flag")            // flag must be declared in content/facts.json
    data class AddFlag(val flag: String, val startOffsetMonths: Int = 0) : Effect
}
```

Cómo los traduce el motor (sin estado oculto):
- `income_delta` / `expense_delta` → flujo con `fromMonth = s + offset`, `untilMonth = fromMonth + months`, `sourceEventId`.
- `one_off` → gasto de un solo mes. `set_income` → pone `untilMonth = s + offset` al flujo existente y crea el nuevo.
- `set_employment` / `set_fact` / `add_flag` → `LifeChange` en `life.pending`; se aplica cuando `t` llega a su mes y genera un `StoryBeat` `LIFE_CHANGE`.
- `tools/validate_events.py --preview` implementa estas reglas en Python como **referencia** para los tests del motor.

### 5.4 Contenido narrativo (`content/facts.json`)

Catálogo de hechos e hitos con sus textos, y cómo se deducen los hechos iniciales del perfil.

```jsonc
{
  "facts": {                       // FactKey -> allowed codes with labels
    "EDUCATION": { "UNKNOWN": {"ES": "...", "EN": "..."}, "BACHELOR_IN_PROGRESS": {...}, "BACHELOR": {...}, "MASTER": {...} },
    "OCCUPATION": { ... }, "CITY": { ... }, "HOUSING": { ... }, "FAMILY": { ... }
  },
  "flags": { "did_master": {"ES": "...", "EN": "..."}, ... },
  "initialFromProfile": {          // how P2 builds the initial person.facts (same rules in tools/calibrate.py)
    "EDUCATION":  { "from": "employment", "map": { "STUDENT": "BACHELOR_IN_PROGRESS" }, "default": "UNKNOWN" },
    "OCCUPATION": { "from": "employment", "map": { ... } },
    "HOUSING":    { "from": "household",  "map": { ... }, "default": "RENTED_FLAT" },
    "FAMILY":     { "from": "household",  "map": { ... } },
    "CITY":       { "from": "city" }
  }
}
```

### 5.5 Partida

```kotlin
data class GameSession(
    val profile: UserProfile,
    val initial: FinancialStatement,     // month 0
    val seed: Long,                 // same seed + same decisions => same game
    val preferences: Preferences,
    val turns: List<Turn>,          // history, in order
    val config: GameConfig
)

data class GameConfig(
    val horizonMonths: Int = 120,
    val monthsPerTurn: Int = 24,
    val maxTurns: Int = 5,
    val manualCaseMode: Boolean = false
)

data class Preferences(             // from the interview; shown to the user (weights are value judgements)
    val objective: Objective,       // GROWTH, BALANCED, SAFE
    val minCashChf: Double,         // personal liquidity floor
    val penaltyChfPerMonthBelowMin: Double  // GROWTH 0, BALANCED 500, SAFE 3000 (Assumptions defaults)
)

data class Turn(
    val index: Int,
    val eventId: String?,           // null = quiet period (no eligible event)
    val chosenOptionId: String?,    // "A" | "B"
    val luck: Double,               // 0..1, derived from seed + index
    val resolvedCase: CaseType?,
    val stateAfter: FinancialStatement,  // end of the turn; state before = previous turn's stateAfter (or initial)
    val counterfactual: Map<CaseType, FinancialStatement>,   // the NOT chosen option, per case, at the end of this turn
    val beats: List<StoryBeat>      // story moments produced during this turn
)

// Preview shown BEFORE choosing (deterministic).
data class OptionPreview(
    val optionId: String,
    val perCase: Map<CaseType, CasePreview>,
    val outlook: Outlook?           // optional look-ahead with the decision tree (section 9)
)
data class CasePreview(
    val prob: Double,
    val stateAfter: FinancialStatement,  // end of the turn
    val deltaNetWorth: Double,
    val minCash: Double,
    val lifeChanges: List<LifeChange>,
    val narrative: LocalizedText
)
```

### 5.6 Historia, análisis y resultados

```kotlin
// A story moment. Structured data only: the UI renders it with templates and the LLM narrates it. No prose here.
data class StoryBeat(
    val month: Int,
    val age: Int,
    val kind: BeatKind,
    val eventId: String? = null,
    val data: Map<String, String> = emptyMap()  // e.g. "cash" -> "-39573", "fact" -> "EDUCATION", "value" -> "MASTER"
)

// Where the net worth comes from. Invariant: start + sum(items) == end (tested).
data class Attribution(
    val startNetWorth: Double,
    val items: List<AttributionItem>,
    val endNetWorth: Double
)
data class AttributionItem(
    val key: String,                // "income:income", "expense:RENT", "debt_interest", "financing_cost", "asset_growth"
    val chf: Double,                // signed contribution over the whole game
    val sourceEventId: String?      // null = baseline flows; otherwise the decision that created it
)

data class Stats(
    val finalNetWorth: Double,
    val minCash: Double,
    val monthOfMinCash: Int,
    val monthsNegativeCash: Int,
    val monthsBelowMinCash: Int,    // vs Preferences.minCashChf
    val financingCostChf: Double,
    val goalsReached: List<String>,
    val goalsMissed: List<String>
)

data class ParallelLife(
    val kind: ParallelKind,         // OTHER_FIRST_CHOICE, OPTIMAL_STRATEGY
    val turns: List<Turn>,          // same luck as the real game
    val final: FinancialStatement,
    val stats: Stats,
    val attribution: Attribution
)
enum class ParallelKind { OTHER_FIRST_CHOICE, OPTIMAL_STRATEGY }

// Look-ahead for one option, assuming the rest of the game follows the optimal strategy (expectimax).
data class Outlook(
    val expectedFinalNetWorth: Double,
    val worstFinalNetWorth: Double,
    val bestFinalNetWorth: Double,
    val probNegativeCash: Double,   // exact probability over the case tree
    val expectedUtility: Double     // finalNetWorth - penalty * monthsBelowMinCash
)

// "Your story would change if..." (section 9).
data class SensitivityItem(
    val parameter: String,          // id from Assumptions.sensitivity
    val lowValue: Double, val highValue: Double,
    val utilityGapAtLow: Double,    // utility(A) - utility(B) for the analysed decision
    val utilityGapAtHigh: Double,
    val breakEven: Double?          // parameter value where the better option flips; null if it never flips in range
)

// Uncertainty from monthly noise (analytic, no sampling): p50 +- 1.2816 * sd * sqrt(months)
data class Band(val p10: List<Double>, val p50: List<Double>, val p90: List<Double>)

data class GameResult(
    val session: GameSession,
    val stats: Stats,
    val attribution: Attribution,
    val beats: List<StoryBeat>,     // whole life line
    val parallelLives: List<ParallelLife>,
    val sensitivity: List<SensitivityItem>,     // for the first decision
    val cashBand: Band
)
```

### 5.7 Entrevista, calibración y supuestos

```kotlin
data class InterviewQuestion(val id: String, val textKey: String, val options: List<InterviewOption>)
data class InterviewOption(
    val id: String,
    val labelKey: String,
    val sets: Map<ProfileField, String>      // which fields this answer fills (numbers as strings)
)
enum class ProfileField { AGE, CITY, EMPLOYMENT, HOUSEHOLD, OPENING_CASH, MONTHLY_INCOME, MONTHLY_RENT, GOAL_TYPE,
                          OBJECTIVE, MIN_CASH }
data class InterviewAnswer(val questionId: String, val optionId: String)

// Calibrated offline by tools/calibrate.py. One per "EMPLOYMENT|HOUSEHOLD" (24) plus "EMPLOYMENT|ANY" fallbacks (6).
// In the data: net = income - rent - livingCost + noise (see tools/README.md).
data class SegmentCalibration(
    val segment: String,                     // "EMPLOYED|FAMILY" or "STUDENT|ANY"
    val n: Int,
    val lowConfidence: Boolean,              // n < 20 -> use the "EMPLOYMENT|ANY" fallback
    val medianIncomeChf: Double,
    val medianRentChf: Double,
    val livingCostChf: LivingCost,           // monthly
    val monthlyNoiseSdChf: Double,           // for the analytic Band
    val decemberBonusRatio: Double,          // 13th salary as a fraction of monthly income (employed ~0.35)
    val negativeMonthRate: Double
)
data class LivingCost(val mean: Double, val sd: Double, val p10: Double, val p50: Double, val p90: Double,
                      val deciles: List<Double>)   // 11 values: min, p10..p90, max

// content/assumptions.json (Dev C). Every number has a source or is labelled as an assumption.
data class Assumptions(
    val inflationAnnual: Double,
    val salaryGrowthAnnual: Double,
    val investmentReturnAnnual: Double,
    val financingRateAnnual: Double,         // interest on negative cash; default 0.085 = max rate in synthetic-liabilities.csv
    val defaultCaseProbs: Map<CaseType, Double>,
    val objectivePenalties: Map<Objective, Double>,   // CHF per month below minCash
    val sensitivity: List<SensitivityParam>,
    val sourceNotes: List<String>
)
data class SensitivityParam(
    val id: String,                          // "salary_multiplier", "tuition_multiplier", "job_search_delay_months"...
    val label: LocalizedText,
    val base: Double, val low: Double, val high: Double
)
```

### 5.8 Chat y crónica

```kotlin
enum class Persona { FUTURE_SELF, PARALLEL_SELF }
data class ChatMessage(val role: ChatRole, val text: String)     // USER | TWIN
interface FutureSelfChat {
    suspend fun ask(context: ChatContext, persona: Persona, history: List<ChatMessage>, question: String): ChatMessage
    suspend fun chronicle(context: ChatContext): String     // the full life story at the end
}
data class ChatContext(
    val result: GameResult,          // everything the LLM may use; it computes nothing
    val facts: FactCatalog,          // labels for facts and flags
    val assumptions: Assumptions,
    val language: Lang
)
```

### 5.9 Quién es dueño de qué

| Dev | Clases |
|---|---|
| **A – Motor** | `FinancialStatement` y sus partes (`finalize()`), `LifeChange`, flujos, `Turn`, `OptionPreview`, `CasePreview`, `StoryBeat`, `Attribution`, `Stats`, `ParallelLife`, `Outlook`, `SensitivityItem`, `Band`, `GameResult` |
| **B – UI** | `InterviewQuestion`, `InterviewOption`, `InterviewAnswer` |
| **C – Datos + contenido + IA** | `Event`, `Requirements`, `EventOption`, `CaseOutcome`, `Effect`, catálogo de hechos (`facts.json`), `SegmentCalibration`, `Assumptions`, `SensitivityParam`, `ChatMessage`, `ChatContext`, `FutureSelfChat` |
| **Compartidos (acordar al inicio)** | `UserProfile`, `Goal`, `GameSession`, `GameConfig`, `Preferences`, todos los enums |

## 6. Flujo de información: input y output de cada paso

### Vista general

```
OFFLINE (Dev C)
  Data/*.csv + content/facts.json ──► [P0 Calibración] ──► calibration.json, profiles-sample.json, backtest-report.json
  content/events.json ──► [P0b Validación + preview] ──► OK / errores, números de referencia

RUNTIME (en la app)
  [P1 Entrevista] ─► [P2 Gemelo inicial] ─► ┌► [P3 Elegir evento] ─► [P4 Preview] ─► [P5 Decisión]
                                            │                                            │
                                            │                                   [P6 Resolver con la suerte]
                                            │                                            │
                                            │                                   [P7 Avanzar 24 meses + momentos]
                                            └────────────── (siguiente turno) ◄──────────┘
                                                              │ (fin de partida)
       [P8 Atribución y stats] ─► [P9 Vidas paralelas + árbol] ─► [P10 Sensibilidad] ─► [P11 Crónica] ─► [P12 Chat]
```

### Detalle por paso

| Paso | Quién | Input | Proceso | Output |
|---|---|---|---|---|
| **P0 Calibración** (offline) | C | `Data/*.csv`, `content/facts.json` | Calibra por segmento (coste de vida, ruido, paga extra). Backtest 80/20 de la banda analítica. Muestra ~50 perfiles con su gemelo inicial | `calibration.json`, `profiles-sample.json`, `backtest-report.json` |
| **P0b Validación** (offline) | C | `content/events.json`, `content/facts.json`, `profiles-sample.json` | Valida contrato, hechos e hitos; simula cada opción × caso en un perfil | errores o OK; tabla de referencia |
| **P1 Entrevista** | B | `List<InterviewQuestion>` | Preguntas de opción múltiple (incluye prioridad y colchón mínimo) | `List<InterviewAnswer>` |
| **P2 Gemelo inicial** | A (+C) | respuestas, `profiles-sample.json`, `calibration.json`, `facts.json` | Rellena el perfil; lo que falte (gasto, activos, deudas) sale del perfil sintético más parecido; los hechos iniciales salen de `initialFromProfile` | `UserProfile`, `FinancialStatement` de apertura (mes 0), `Preferences`, `GameSession` vacía con semilla. Alexia se carga directamente |
| **P3 Elegir evento** | A (lógica) / C (contenido) | `FinancialStatement` actual, turnos hechos, catálogo | Filtra por `requires`, `earliestTurn` y `repeatable`; elige la mayor `priority`; desempate con la semilla. Si no hay ninguno → turno tranquilo | `Event?` |
| **P4 Preview** | A | `FinancialStatement`, `Event`, `Assumptions`, `Preferences` | Para cada opción × caso aplica `apply` y avanza 24 meses. Opcional: `Outlook` con el árbol | `List<OptionPreview>` |
| **P5 Decisión** | B | previews en pantalla | El usuario elige A o B | `chosenOptionId` |
| **P6 Resolver** | A | opción, `seed`, índice del turno | `luck = rng(seed, index)`; caso = primer caso cuya probabilidad acumulada (orden P, N, O) supera `luck` | `resolvedCase`, `luck` |
| **P7 Avanzar** | A | `FinancialStatement`, `CaseOutcome`, `Assumptions` | Aplica efectos, simula 24 meses (sección 7), aplica cambios de vida, detecta momentos; calcula el contrafactual | `Turn` (con `beats`) |
| **P8 Atribución y stats** | A | `GameSession` | Suma cada flujo por origen; `Stats` | `Attribution`, `Stats` |
| **P9 Vidas paralelas** | A | `GameSession`, catálogo | Reproduce la partida con la misma suerte: (1) primera decisión cambiada y después estrategia óptima; (2) estrategia óptima desde el inicio (expectimax) | `List<ParallelLife>` |
| **P10 Sensibilidad** | A (+C rangos) | sesión, `Assumptions.sensitivity` | Para la primera decisión, varía cada parámetro a low/high y busca el punto de equilibrio | `List<SensitivityItem>` |
| **P11 Crónica** | C | `ChatContext` | El LLM cuenta la historia a partir de `beats`, `attribution` y vidas paralelas. Plan B: plantillas de texto | `String` |
| **P12 Chat** | C | `ChatContext`, `Persona`, historial, pregunta | Prompt con todo el contexto; el LLM solo explica | `ChatMessage` |

### Reglas de flujo
- **Una sola fuente de verdad**: `GameSession` (y `GameResult` al final). La UI y el LLM leen de ahí.
- **El LLM nunca calcula**: recibe `ChatContext` y redacta. Si un número no está, dice que no lo sabe.
- **Reproducibilidad**: misma semilla + mismas decisiones ⇒ misma partida. La suerte de cada turno depende solo de `seed` e índice.
- **Preview ≠ resolución**: el preview es determinista; la resolución usa la suerte.
- **Los JSON de contenido los produce C** y los consumen A y B; el formato es el de la sección 5.

## 7. Reglas de cálculo del motor

**Inicio del turno (mes `s`):** primero se aplican los `LifeChange` pendientes con `month <= s` (así un hito que llega en el mes 24 ya cuenta para elegir el evento del turno 2); después se elige el evento y se aplican los `effects` del caso resuelto (sección 5.3): flujos con `fromMonth`/`untilMonth`
y `sourceEventId`, deuda nueva y `LifeChange` programados.

**Cada mes `t → t+1`**, en este orden:
1. `income_t = Σ` flujos de ingreso activos (`fromMonth <= t && (untilMonth == null || t < untilMonth)`), con crecimiento salarial anual.
   En diciembre, si `employment` es `EMPLOYED` o `SELF_EMPLOYED`, se suma `decemberBonusRatio × ingreso` (paga extra, dato calibrado).
2. `expense_t = Σ` flujos de gasto activos, con inflación anual.
3. Deudas: `due = principal × (1 + annualRate/12)`; `pay = min(monthlyPayment, due)`; `principal = due − pay`.
4. **Financiación**: si `cash_t < 0`, se paga interés: `financing_t = |cash_t| × financingRateAnnual / 12`.
5. `cash_{t+1} = cash_t + income_t − expense_t − Σ pay − financing_t`
6. Activos: `valueChf × (1 + annualReturn/12)`.
7. (Se hace al **principio** del mes, antes del paso 1.) Vida: se aplican los `LifeChange` con `month <= t` y se emite `LIFE_CHANGE`; así el empleo nuevo ya cuenta para la paga extra de ese mes.
8. Momentos: `CASH_NEGATIVE` al cruzar a negativo, `CASH_RECOVERED` al volver a positivo, `GOAL_REACHED` cuando el
   patrimonio alcanza la meta antes de su fecha, `GOAL_MISSED` si llega la fecha sin alcanzarla. `LOW_POINT` (mínimo de
   caja) se marca al final de la partida.

**Atribución:** cada mes se suma cada flujo a su `AttributionItem` (`income:<id>`, `expense:<categoría>`, `debt_interest`,
`financing_cost`, `asset_growth`), guardando su `sourceEventId`.

Invariantes que deben comprobar los tests:
- Coherencia contable: `netWorth_{t+1} − netWorth_t = ingresos − gastos − intereses − financiación + crecimiento de activos` (tolerancia 0,01 CHF).
- Atribución: `startNetWorth + Σ items = endNetWorth`.
- Probabilidades de cada opción suman 1; cada evento tiene 2 opciones con los 3 casos.
- Misma semilla ⇒ mismo resultado; las vidas paralelas usan la misma `luck` por turno.
- Un préstamo se amortiza a 0 en su plazo.

## 8. Datos y calibración

Datos **sintéticos** del reto en `ai-financial-twin-lauzhack-2026/Data` (1.200 perfiles, enlazados por `profile_id`):

| Archivo | Contenido | Va a |
|---|---|---|
| `synthetic-profiles.csv` | Edad, ciudad, empleo, hogar, caja, ingreso, alquiler (se ignoran `canton`, `country`, `currency`, `synthetic`) | `UserProfile`, flujos de ingreso y alquiler, hechos iniciales |
| `synthetic-accounts.csv` / `synthetic-assets.csv` | Efectivo e inversiones | `cash`, `assets` |
| `synthetic-liabilities.csv` | Préstamos personales (solo instantánea, cuota 0, **no amortizados**) | `liabilities` (cuota supuesta: anualidad a 60 meses) |
| `synthetic-goals.csv` | Metas con importe y fecha (se ignora `priority`) | `goals` |
| `synthetic-cash-balances.csv` | Serie mensual de caja (24 meses por perfil) | **No entra en el modelo**: calibra y valida |
| `scenario-catalog.csv` / `scenario-assumptions.csv` | 120 escenarios; matrícula y duración de estudios | Inspiración para eventos y supuestos |

Lo que muestran los datos (detalle en `tools/README.md` y sección 13):
- Cada mes: `neto = ingreso − alquiler − coste_vida + ruido` (R² ≈ 0,98-0,99). Coste de vida ≈ 1.540 CHF para todos.
- Paga extra en diciembre ≈ 0,35 × ingreso en empleados y autónomos. Ruido sin memoria entre meses.
- Solo **24 meses** por perfil: más allá todo son supuestos explícitos (`Assumptions`), marcados en la UI.
- Estudiantes y desempleados pierden caja cada mes (ingreso < alquiler). **Alexia pierde ≈ 1.000 CHF/mes**.
- Segmentos pequeños (39 estudiantes, 37 desempleados) → `lowConfidence`.
- Los datos son sintéticos: calibrar reconstruye el generador, no el comportamiento real. Hay que decirlo.
- El README del dataset menciona ficheros que **no existen** (`synthetic-transactions.csv`, `life-events.csv`, `alexia-profile.json`).

Calibración (`tools/calibrate.py`, Dev C): coste de vida, ruido y paga extra por segmento.
**Backtest** (`tools/backtest.py`): calibra con el 80 % de los perfiles y comprueba la banda analítica p10-p90 sobre el 20 % restante.
Interés de financiación por defecto: 8,5 % (máximo de `synthetic-liabilities.csv`; los préstamos van de 2,5 % a 8,5 %).

## 9. Análisis: árbol de decisiones, vidas paralelas y sensibilidad

Sustituye a Monte Carlo. Todo es **exacto y reproducible**, y alimenta la historia.

**Árbol de decisiones (expectimax).** El juego es un proceso de decisión de Markov pequeño: 5 turnos × (2 decisiones × 3 casos)
= como mucho 6⁵ = 7.776 futuros. Se recorren todos (compartiendo los prefijos: ≈ 225.000 pasos mensuales).
- Valor de una hoja: `utilidad = patrimonio final − penalización × meses por debajo del colchón mínimo` (`Preferences`).
- Nodo de decisión: la opción de mayor utilidad esperada. Nodo de suerte: media ponderada por las probabilidades de los casos.
- Da la **estrategia óptima** para las prioridades del usuario y el `Outlook` de cada opción (esperado, peor, mejor, probabilidad de negativo).
- Las penalizaciones son juicios de valor: **se muestran al usuario**, como pide el reto.
- Frase para los jueces: *"El juego es un MDP; en lugar de aproximarlo con Monte Carlo o con aprendizaje por refuerzo, lo resolvemos de forma exacta."*

**Vidas paralelas.** Con la misma suerte por turno que la partida real:
1. `OTHER_FIRST_CHOICE`: la vida en la que tomaste la otra primera decisión (y después la estrategia óptima).
2. `OPTIMAL_STRATEGY`: la vida con la estrategia óptima desde el principio.
Cada una tiene su línea de vida, cascada y crónica: el contrafactual se convierte en personajes comparables.

**Sensibilidad y punto de equilibrio** ("tu historia cambiaría si..."). Para la primera decisión, cada parámetro de
`Assumptions.sensitivity` se lleva a su valor bajo y alto (multiplicadores de sueldo, matrícula, coste de vida; meses de
retraso en encontrar trabajo; inflación; interés). Se calcula la diferencia de utilidad entre A y B y, por bisección,
el valor en que la mejor opción cambia. Ejemplo: *"El máster deja de compensar si tu sueldo después baja de 5.600 CHF"*.
Responde a la primera pregunta de los jueces.

**Banda de ruido analítica.** El ruido mensual no tiene memoria (autocorrelación −0,07), así que la incertidumbre de la
caja a `k` meses es `± 1,2816 × σ × √k` (p10-p90), con `σ = monthlyNoiseSdChf`. Sin muestreo.

**Restricciones.** Colchón mínimo del usuario (`minCashChf`) y caja negativa financiada con interés. El evento de
financiación (prioridad alta, `maxCashChf: 0`) convierte la violación de la restricción en una decisión de la historia.

**Estadísticas del final (MVP):** patrimonio final, mínimo de caja y cuándo, meses en negativo y bajo el colchón,
coste de financiación, metas alcanzadas, comparación con las vidas paralelas.

## 10. LLM: de dónde salen las respuestas y cómo no pagar

### De dónde sale el contenido
El LLM no sabe nada del usuario. Solo sabe lo que se le pasa en `ChatContext`:

| Parte del prompt | Contenido | Quién lo produce |
|---|---|---|
| System prompt | Rol (yo del futuro o yo paralelo), tono, reglas | Dev C (a mano) |
| Historia | `StoryBeat` de la partida y de las vidas paralelas, hechos e hitos con sus etiquetas | El motor + `facts.json` |
| Cifras | `Stats`, `Attribution`, `SensitivityItem` | El motor |
| Supuestos y límites | `Assumptions.sourceNotes`, qué está calibrado y qué es supuesto | JSON |
| Pregunta | Texto libre | El usuario |

Reglas: *usa solo las cifras y hechos del contexto; si no están, di que no lo sabes; cita la edad o el momento;
recuerda que es una simulación y no asesoramiento financiero.*
- **Durante la partida**: la narrativa de cada caso es fija (`CaseOutcome.narrative`), sin LLM.
- **Al final**: crónica completa (`chronicle`) y chat. **Plan B**: crónica con plantillas de texto por tipo de momento.
- El chat explica la historia y los resultados; **no** propone escenarios nuevos (fuera del MVP).

### Que no cueste dinero
Los límites y condiciones de los servicios gratuitos cambian: **verificadlos antes de depender de ellos**.

| Opción | Coste | Dónde | Nota |
|---|---|---|---|
| Ollama local (Llama, Qwen, Gemma…) | 0 € | Desktop | Necesita RAM/GPU; un modelo pequeño responde peor en español. Android puede llamarlo por la red local |
| APIs con capa gratuita (Gemini, Groq, OpenRouter "free"…) | 0 € dentro del límite | Android y Desktop | Límite de peticiones; necesita clave y red |
| Créditos del hackathon | 0 € | Ambos | Preguntar a la organización |
| Plantillas (plan B) | 0 € | Ambos | No es conversación real |

Recomendación: **Ollama en desarrollo**, API gratuita en la demo si hay wifi fiable (Ollama de respaldo) y **plantillas siempre listas**.
Privacidad: con Ollama los datos que escribe el usuario no salen de su máquina. Nunca meter claves en el repo ni en la app.

## 11. Reparto del trabajo

Somos 3 desarrolladores. Coordinación entre nosotros mediante issues de GitHub (ver `AGENTS.md`).

| Rol | Persona | Responsabilidad principal |
|---|---|---|
| **Dev A – Motor + integración** | `polma7` | Esqueleto KMP, `model/`, motor (dinero + vida), momentos, atribución, árbol, vidas paralelas, sensibilidad, tests |
| **Dev B – UI/UX (front)** | `OriolVines` | Pantallas, línea de vida, cascada, vidas paralelas, tema, i18n, empaquetado |
| **Dev C – Datos + contenido + IA** | `caarlos-04` | Calibración, eventos encadenados, hechos e hitos, supuestos, crónica y chat |

### Carpetas propias

| Dev | Carpetas |
|---|---|
| **A** | `model/` (propone y mantiene), `engine/`, `data/` (carga de JSON), build Gradle y configuración |
| **B** | `ui/`, `androidMain`, `desktopMain`, recursos i18n |
| **C** | `events/`, `ai/`, `tools/`, `content/` (se copia a los recursos de KMP) |

### Responsabilidades de Dev A – Motor + integración
1. **Esqueleto KMP**, `model/` y mocks (Alexia y un evento). Entrega en la **hora 2**.
2. **Motor** (sección 7): efectos, avance mensual, financiación, cambios de vida, momentos, preview, suerte, contrafactual.
3. **Controlador de partida** (P3-P7) y **carga de JSON** en `commonMain`. Es el punto de contacto con B.
4. **Atribución** y `Stats`.
5. **Árbol (expectimax)**, `Outlook` y **vidas paralelas**.
6. **Sensibilidad** y punto de equilibrio.
7. **Tests**: invariantes de la sección 7. `tools/validate_events.py --preview` da valores de referencia.

### Responsabilidades de Dev C – Datos + contenido + IA
1. **Calibración y backtest** (`tools/`). Hecho: coste de vida, ruido, paga extra; banda analítica.
2. **Catálogo de hechos e hitos** (`content/facts.json`) y reglas de hechos iniciales.
3. **Eventos encadenados** (`content/events.json`): 6-8 eventos que formen historias (requisitos por hechos, hitos y caja), incluido el de financiación.
4. **Supuestos** (`content/assumptions.json`) con fuentes, penalizaciones por prioridad y rangos de sensibilidad.
5. **Plantillas de momentos** (ES/EN) para la línea de vida y la crónica de plan B.
6. **Crónica y chat** (`ai/`): prompts, contexto, proveedor gratuito, plan B, pruebas de que no inventa cifras.

### Contrato entre A y C
- Los formatos JSON de eventos, hechos, calibración y supuestos los **define C y los aprueba A**. Si cambian, se avisa a A y a B.
- A entrega `model/` y mocks en la hora 2; C entrega eventos y hechos reales hacia la hora 6.

### Reparto con el front (Dev B)
Además de la UI, B es dueño de las preguntas de la entrevista y de las cadenas ES/EN, y puede ayudar con 1-2 eventos,
el guion de demo, el README final y las notas del proceso con agentes. Vistas clave: **línea de vida**, **cascada**,
**vidas paralelas lado a lado**, **"tu historia cambiaría si..."**. Gráficos: decidir pronto Canvas o Koalaplot.

### Cronograma (24 h)
- **h0-2, los tres juntos**: esqueleto KMP, acordar `model/`, mocks.
- **h2-10**: en paralelo (A motor y controlador, B UI contra mocks, C eventos, hechos y supuestos).
- **h8**: A + B conectan.
- **h12**: catálogo integrado, **partida completa con Alexia** y línea de vida.
- **h14**: atribución y vidas paralelas.
- **h16**: crónica y chat.
- **h18: congelar funcionalidades.**
- **h18-24**: pruebas en Android y Desktop, pulido, demo.

### Orden de prioridad (si falta tiempo, se corta por abajo)
1. Bucle de juego con Alexia: eventos, casos, suerte, relato corto por turno.
2. Línea de vida con hechos, hitos y momentos.
3. Cascada (atribución) y estadísticas del final.
4. Vida paralela `OTHER_FIRST_CHOICE` (no necesita el árbol: basta con la mejor opción a ciegas o la neutra).
5. Crónica (plantillas primero, LLM después) y chat.
6. Entrevista y perfil propio.
7. Árbol completo (`OPTIMAL_STRATEGY`, `Outlook`) y sensibilidad.

### Reglas
Una rama por persona (`dev-a/motor`, `dev-b/ui`, `dev-c/datos`), PRs pequeñas a `main`, merge al menos cada 3-4 horas. Ver `AGENTS.md`.

## 12. Cosas importantes y preguntas abiertas

- **La historia manda**: cada pantalla debe responder "¿qué le pasó a esta persona y por qué?".
- **El LLM no inventa números ni hechos**: solo cuenta lo que calcula el motor.
- **Transparencia**: supuestos, unidades, penalizaciones y limitaciones visibles; avisar de que no es asesoramiento financiero ni una predicción.
- **Plan B de demo**: crónica con plantillas y chat con respuestas de ejemplo; Alexia precargada.
- **Validación para los jueces**: coherencia contable, atribución que cuadra, backtest de la banda, semilla reproducible.
- **Agentic Development**: documentar qué hizo cada agente (JetBrains Air) y qué corregimos.
- **Licencia**: los materiales del reto son "all rights reserved" (autora: Stefania Stan). Usarlos solo para participar y citar la fuente.

Preguntas abiertas:
1. Proveedor de LLM concreto (y quién aporta la clave, si hace falta).
2. ¿Se implementa el modo "elige tú el caso" (`manualCaseMode`)?
3. ¿Se guarda la partida en disco o solo en memoria?
4. ¿`profiles-sample.json` y `calibration.json` se suben al repo (licencia del dataset) o cada uno los genera?

## 13. Mejoras futuras (si sobra tiempo)

Ideas evaluadas y **aplazadas**. No bloquean el MVP.

### Lo que sabemos del generador de los datos sintéticos
- Cada mes: `neto = ingreso − alquiler − coste_vida + estacionalidad(mes) + ruido` (R² ≈ 0,98-0,99).
- Coste de vida: uno por perfil, ≈ 1.540 CHF (sd ≈ 166, rango 770-2.400), igual para todos los segmentos.
- Estacionalidad: diciembre ≈ +1.756 CHF de media (paga extra ≈ 0,35 × ingreso, solo empleados y autónomos); **febrero y agosto ≈ −300 CHF**.
- Ruido sin memoria (autocorrelación −0,07), con **colas gordas** (curtosis 8,5) y sesgo negativo: ±500 CHF normalmente, con golpes puntuales de −2.000 a −5.000 CHF.
- Sin tendencia: 2026 ≈ 2025 (no hay inflación ni subidas de sueldo).
- Ingreso: un rango por tipo de empleo. Alquiler: depende de ciudad y hogar, no del ingreso. Caja inicial: crece con la edad (correlación 0,42).

**No generamos datos nuevos con él**: no aporta información nueva ni resuelve el horizonte más allá de 24 meses.

### Mejoras aplazadas
| # | Mejora | Coste | Impacto |
|---|---|---|---|
| 1 | **Estacionalidad mensual** (12 ajustes por segmento; diciembre entra ahí) | ~15 min | `SegmentCalibration` gana `seasonalityChf`; el motor suma el ajuste del mes |
| 2 | **Shocks separados del ruido** (probabilidad y tamaños) | ~20 min | Útil para un evento de "gasto imprevisto" sacado de los datos |
| 3 | **Valores por defecto para la entrevista** (alquiler por ciudad × hogar, caja por edad, ingreso por empleo) | ~15 min | Nuevo `defaults.json`; P2 los usa y el vecino más cercano pasa a opcional; B puede ofrecer "no lo sé" |
| 4 | **Economía con memoria** (cadena de Markov expansión/normal/recesión entre turnos) | ~1 h | Los casos dejan de ser independientes entre turnos; probabilidades de transición = supuestos |
| 5 | **Frente de Pareto** de estrategias (patrimonio esperado frente a riesgo) | ~1 h | Vista extra del árbol |
