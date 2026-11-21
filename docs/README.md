# Plan del proyecto — AI Financial Twin (LauzHack 2026)

> Documento de contexto para los tres desarrolladores y sus agentes (Claude Code, etc.).
> Resume las decisiones tomadas. **Si algo cambia, actualizad este fichero.**
> Los comentarios en el código van en inglés (ver `AGENTS.md`); esta documentación está en español.

## Índice
1. [Qué construimos](#1-qué-construimos)
2. [Decisiones tomadas](#2-decisiones-tomadas)
3. [Bucle de juego](#3-bucle-de-juego)
4. [Arquitectura](#4-arquitectura)
5. [Modelo de datos (dataclasses)](#5-modelo-de-datos-dataclasses)
6. [Flujo de información: input y output de cada paso](#6-flujo-de-información-input-y-output-de-cada-paso)
7. [Reglas de cálculo del motor](#7-reglas-de-cálculo-del-motor)
8. [Datos y calibración](#8-datos-y-calibración)
9. [Monte Carlo y estadísticas](#9-monte-carlo-y-estadísticas)
10. [LLM: de dónde salen las respuestas y cómo no pagar](#10-llm-de-dónde-salen-las-respuestas-y-cómo-no-pagar)
11. [Reparto del trabajo](#11-reparto-del-trabajo)
12. [Cosas importantes y preguntas abiertas](#12-cosas-importantes-y-preguntas-abiertas)

---

## 1. Qué construimos

Un juego tipo **"diseña tu propia vida"** que ayuda al usuario a visualizar y comparar escenarios financieros.
Integra dos retos a la vez:

- **AI Financial Twin** (reto principal, autora del concepto: Stefania Stan). Principio: *"Don't predict the future. Make it explorable."*
  Hay que mostrar: estado financiero ficticio, una intervención/decisión, comparación base vs. alternativa, supuestos transparentes,
  limitaciones y explicación de por qué difieren los resultados.
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
| Partida | ~5-6 turnos sobre un horizonte de 10 años (120 meses) |
| Casos | Cada decisión tiene 3 casos: pesimista / neutro / optimista |
| Incertidumbre | Los 3 casos son la capa narrativa del juego; **Monte Carlo** es la capa estadística (abanico, probabilidades) |
| Dinero | `Double` en CHF (tests de coherencia con tolerancia). Sin impuestos (limitación documentada) |
| LLM | **Sin decidir**, pero **gratis** (ver sección 10). Oculto tras la interfaz `FutureSelfChat` |
| Persistencia | Partida en memoria durante la demo (por decidir si se guarda en disco) |

Terminología: usamos **caso** (pesimista/neutro/optimista), no "entorno".

## 3. Bucle de juego

Basado en el diagrama del equipo: entrevista → modelado del perfil → eventos (con bucle) → chat con el "yo del futuro" + estadísticas.

```
Turno N (un evento de vida)
 ├─ Decisión A ──► resultado con 3 casos (pesimista / neutro / optimista)
 └─ Decisión B ──► resultado con 3 casos (pesimista / neutro / optimista)
        │
   el usuario elige A o B
        │
   se resuelve UN caso → el estado financiero se actualiza → Turno N+1
```

- Cada evento ofrece **dos decisiones**; cada decisión produce un resultado con **tres casos**.
- Antes de elegir se muestran los 3 casos de A y de B para comparar (preview determinista).
- Al elegir, se **sortea un caso con probabilidades** (por defecto 20 % pesimista / 60 % neutro / 20 % optimista, ajustables por evento), con semilla para reproducibilidad.
- Opcional: modo "elige tú el caso" para exploración libre. *(Pendiente de confirmar.)*
- Cada turno avanza el tiempo `monthsPerTurn` meses (por defecto 24; 5 turnos ≈ 10 años).
- Al final se compara el camino elegido con el **camino alternativo** (qué habría pasado con la otra decisión).
- Los eventos que aparecen dependen del perfil (p. ej. estudiante → máster/beca; jubilado → salud/herencia) con condiciones simples (`requires`).

## 4. Arquitectura

Casi todo en `commonMain`:

```
commonMain
├─ model/     dataclasses (sección 5)                      [contrato compartido]
├─ engine/    apply(), avance mensual, sorteo, contrafactual, Monte Carlo, stats
├─ events/    catálogo de eventos y selección según perfil
├─ data/      carga de JSON (perfiles, calibración, eventos, supuestos, entrevista)
├─ ai/        interfaz FutureSelfChat (implementación LLM intercambiable)
└─ ui/        pantallas Compose: entrevista, dilema, resolución, resultados, chat
androidMain / desktopMain  → solo arranque, ficheros y clave API
tools/        scripts Python offline (calibración, backtest, conversión CSV→JSON)
```

Principios:
- El motor es **función pura e inmutable**: `apply(state, option, case) → newState`. Fácil de testear y de reproducir.
- Los eventos son **datos JSON**, no código.
- Los CSV no se pueden leer con `java.io` en `commonMain`: un script offline los convierte a JSON y la app solo lee JSON.
- **Nadie cambia `model/` sin avisar a los demás**: es el contrato.

## 5. Modelo de datos (dataclasses)

Convenciones:
- Todas `@Serializable` (kotlinx.serialization). Todas **inmutables** (`val`, `copy()` para cambiar).
- Dinero: `Double` en **CHF**. Tipos de interés/rentabilidad: **fracción anual** (0.05 = 5 %).
- Tiempo: `month` = índice entero de mes desde el inicio de la partida (**0..119**). `YearMonth` como texto `"2025-01"`.
- Probabilidades: `0.0..1.0`. Los ids son `String` estables (se usan como claves entre ficheros).
- Campos de texto visibles al usuario van como **clave de traducción** (`titleKey`) salvo en las narrativas del catálogo, que van en `Map<Lang, String>`.

### 5.1 Enums

| Enum | Valores | Origen |
|---|---|---|
| `Employment` | `STUDENT, EMPLOYED, PART_TIME, SELF_EMPLOYED, RETIRED, UNEMPLOYED` | `employment_status` del dataset |
| `Household` | `SINGLE, COUPLE, FAMILY, SHARED_HOUSING` | `household_type` |
| `CaseType` | `PESSIMISTIC, NEUTRAL, OPTIMISTIC` | diagrama del equipo |
| `AssetType` | `CASH, INVESTMENT_PORTFOLIO` | `asset_type` de `synthetic-assets.csv` (solo existen estos dos). Añadir `PROPERTY` u otros **solo si un evento lo necesita** (p. ej. comprar casa) |
| `LiabilityType` | `PERSONAL_LOAN` | `liability_type` de `synthetic-liabilities.csv` (solo existe este). Añadir `MORTGAGE`, `STUDENT_LOAN`… **solo si un evento lo necesita** |
| `ExpenseCategory` | `RENT, LIVING, TUITION, INSURANCE, TRAVEL, OTHER` | propio |
| `Lang` | `ES, EN` | i18n |

### 5.2 Perfil y estado

```kotlin
// Static profile. Produced by the interview (or loaded for Alexia). Subset of synthetic-profiles.csv.
data class UserProfile(
    val id: String,                 // "P00001" or "USER-<uuid>"
    val displayName: String,
    val ageAtStart: Int,            // years
    val city: String,
    val employment: Employment,
    val household: Household,
    val startMonth: String,         // "2025-01"
    val goals: List<Goal>
)

// Dynamic snapshot of the person's finances at one month. This is what the engine transforms.
data class FinancialState(
    val month: Int,                 // 0..119
    val cash: Double,               // CHF, may go negative (=> flagged as needing financing)
    val assets: List<Asset>,
    val liabilities: List<Liability>,
    val income: List<IncomeStream>,
    val expenses: List<ExpenseStream>,
    val employment: Employment      // can change through events
) {
    val netWorth: Double            // cash + sum(assets.valueChf) - sum(liabilities.principalChf)
    val monthlyNet: Double          // sum(income.monthlyChf) - sum(expenses.monthlyChf) - sum(liabilities.monthlyPaymentChf)
}

data class Asset(
    val id: String,
    val type: AssetType,
    val valueChf: Double,
    val annualReturn: Double        // fraction/year, e.g. 0.04
)

data class Liability(
    val id: String,
    val type: LiabilityType,
    val principalChf: Double,
    val annualRate: Double,         // fraction/year
    val monthlyPaymentChf: Double   // paid from cash each month
)

data class IncomeStream(
    val id: String,                 // "salary", "scholarship", "pension"
    val label: String,
    val monthlyChf: Double,
    val untilMonth: Int? = null     // null = open-ended; stream stops at this month (exclusive)
)

data class ExpenseStream(
    val id: String,                 // "rent", "living", "tuition"
    val category: ExpenseCategory,
    val monthlyChf: Double,
    val untilMonth: Int? = null
)

data class Goal(
    val id: String,                 // "G-P00001-01"
    val name: String,
    val targetChf: Double,
    val targetMonth: Int            // month index when it should be reached
)
```

### 5.3 Eventos (datos del juego, en JSON)

```kotlin
data class Event(
    val id: String,                 // "E-master"
    val titleKey: String,           // i18n key
    val descriptionKey: String,
    val requires: Requirements? = null,  // null = appears for everyone
    val earliestTurn: Int = 0,      // do not show before this turn
    val options: List<EventOption>  // ALWAYS exactly 2
)

data class Requirements(
    val employment: List<Employment>? = null,
    val household: List<Household>? = null,
    val minAge: Int? = null,
    val maxAge: Int? = null
)

data class EventOption(
    val id: String,                 // "A" | "B"
    val labelKey: String,
    val cases: Map<CaseType, CaseOutcome>   // must contain all 3 CaseType
)

data class CaseOutcome(
    val prob: Double,               // the 3 probs of an option MUST sum to 1.0
    val effects: List<Effect>,
    val narrative: Map<Lang, String>
)

sealed interface Effect {
    // Add/replace a monthly income stream (or change an existing one by id).
    data class IncomeDelta(val streamId: String, val deltaChf: Double, val months: Int?) : Effect
    // Change a monthly expense for N months (null = forever).
    data class ExpenseDelta(val category: ExpenseCategory, val deltaChf: Double, val months: Int?) : Effect
    // One-time cash cost (negative = one-time gain).
    data class OneOffCost(val chf: Double) : Effect
    // Add a new debt.
    data class AddLiability(val liability: Liability) : Effect
    // Change the annual return of an asset type from now on.
    data class AssetReturnDelta(val assetType: AssetType, val deltaAnnualRate: Double) : Effect
    // Change employment status.
    data class SetEmployment(val employment: Employment) : Effect
}
```

### 5.4 Partida y resultados

```kotlin
data class GameSession(
    val profile: UserProfile,
    val initial: FinancialState,
    val seed: Long,                 // same seed + same decisions => same game
    val turns: List<Turn>,          // history, in order
    val config: GameConfig
)

data class GameConfig(
    val horizonMonths: Int = 120,
    val monthsPerTurn: Int = 24,
    val maxTurns: Int = 5,
    val manualCaseMode: Boolean = false    // true = user picks the case
)

data class Turn(
    val index: Int,                 // 0-based
    val eventId: String,
    val chosenOptionId: String,     // "A" | "B"
    val resolvedCase: CaseType,     // the case that was drawn
    val stateBefore: FinancialState,
    val stateAfter: FinancialState,
    val counterfactual: Map<CaseType, FinancialState>  // what the NOT chosen option would have given, per case
)

// Used for the preview shown BEFORE choosing (deterministic, no random draw).
data class OptionPreview(
    val optionId: String,
    val perCase: Map<CaseType, CasePreview>
)
data class CasePreview(
    val prob: Double,
    val stateAfter: FinancialState,
    val deltaNetWorth: Double,      // vs stateBefore
    val minCash: Double,            // lowest cash during the turn
    val narrative: String           // already in the selected language
)
```

### 5.5 Monte Carlo y estadísticas

```kotlin
data class SimulationResult(
    val nPaths: Int,
    val seed: Long,
    val horizonMonths: Int,
    val cash: FanBand,
    val netWorth: FanBand,
    val probNegativeCash: Double,           // share of paths with cash < 0 at some month
    val goalProbabilities: Map<String, Double>,   // goalId -> P(reached by targetMonth)
    val goalMedianMonth: Map<String, Int?>        // goalId -> median month reached, null if rarely
)

data class FanBand(
    val p10: List<Double>,          // one value per month (size = horizonMonths + 1)
    val p50: List<Double>,
    val p90: List<Double>
)

data class Stats(
    val finalNetWorth: Double,
    val minCash: Double,
    val monthOfMinCash: Int,
    val monthsNegativeCash: Int,
    val goalsReached: List<String>,          // goal ids
    val goalsMissed: List<String>
)

// Final comparison: chosen path vs alternative.
data class PathComparison(
    val chosen: Stats,
    val alternative: Stats,
    val deltaFinalNetWorth: Double,
    val explanation: List<String>            // structured reasons, built by the engine (not by the LLM)
)
```

### 5.6 Entrevista, calibración y supuestos

```kotlin
data class InterviewQuestion(
    val id: String,
    val textKey: String,
    val options: List<InterviewOption>
)
data class InterviewOption(
    val id: String,
    val labelKey: String,
    val sets: Map<ProfileField, String>      // which profile fields this answer fills (numbers as strings)
)
enum class ProfileField { AGE, CITY, EMPLOYMENT, HOUSEHOLD, OPENING_CASH, MONTHLY_INCOME, MONTHLY_RENT, GOAL_TYPE }
data class InterviewAnswer(val questionId: String, val optionId: String)

// Calibrated from the dataset by tools/ (offline). One per segment "EMPLOYMENT|HOUSEHOLD" (24) plus
// "EMPLOYMENT|ANY" fallbacks (6). In the data: net = income - rent - livingCost + noise (see tools/README.md).
data class SegmentCalibration(
    val segment: String,                     // "EMPLOYED|FAMILY" or "STUDENT|ANY"
    val n: Int,                              // number of profiles in the segment
    val lowConfidence: Boolean,              // n < 20 -> use the "EMPLOYMENT|ANY" fallback
    val medianIncomeChf: Double,
    val medianRentChf: Double,
    val livingCostChf: LivingCost,           // monthly; optimistic = p10, neutral = p50, pessimistic = p90
    val monthlyNoiseSdChf: Double,
    val decemberUpliftChf: Double,           // extra net in December vs other months
    val decemberBonusRatio: Double,          // same, as a fraction of monthly income (employed ~0.35)
    val negativeMonthRate: Double,           // fraction of months flagged negative_balance_requires_financing
    val residualsChf: List<Double>           // within-profile monthly deviations, for bootstrap
)
data class LivingCost(val mean: Double, val p10: Double, val p50: Double, val p90: Double,
                      val deciles: List<Double>)   // 11 values: min, p10..p90, max

data class Assumptions(
    val inflationAnnual: Double,
    val salaryGrowthAnnual: Double,
    val investmentReturn: Map<CaseType, Double>,   // annual return per case
    val defaultCaseProbs: Map<CaseType, Double>,   // e.g. 0.2 / 0.6 / 0.2
    val sourceNotes: List<String>                  // where each number comes from; shown in the UI
)
```

### 5.7 Chat

```kotlin
data class ChatMessage(val role: ChatRole, val text: String)     // USER | FUTURE_SELF
interface FutureSelfChat {
    suspend fun ask(context: ChatContext, history: List<ChatMessage>, question: String): ChatMessage
}
data class ChatContext(
    val session: GameSession,
    val stats: Stats,
    val comparison: PathComparison?,
    val simulation: SimulationResult?,
    val assumptions: Assumptions,
    val language: Lang
)
```

### 5.8 Quién es dueño de qué

| Dev | Clases |
|---|---|
| **A – Motor** | `FinancialState`, `Asset`, `Liability`, `IncomeStream`, `ExpenseStream`, `Effect`, `Turn`, `OptionPreview`, `CasePreview`, `SimulationResult`, `FanBand`, `Stats`, `PathComparison` |
| **B – UI** | `InterviewQuestion`, `InterviewOption`, `InterviewAnswer` |
| **C – Datos + IA** | `Event`, `Requirements`, `EventOption`, `CaseOutcome`, `SegmentCalibration`, `Assumptions`, `ChatMessage`, `ChatContext`, `FutureSelfChat` |
| **Compartidos (acordar al inicio)** | `UserProfile`, `Goal`, `GameSession`, `GameConfig`, todos los enums |

Se pueden **dejar fuera del MVP**: la clase `Stats` avanzada (tasa de ahorro, endeudamiento) y modelos de hogar con varias personas.

## 6. Flujo de información: input y output de cada paso

### Vista general

```
OFFLINE (Dev C, una vez)
  Data/*.csv ──► [P0 Calibración] ──► calibration.json, profiles-sample.json, backtest-report.json

RUNTIME (en la app)
  [P1 Entrevista] ─► [P2 Perfil] ─► ┌► [P3 Elegir evento] ─► [P4 Preview] ─► [P5 Decisión]
                                    │                                              │
                                    │                                       [P6 Resolver caso]
                                    │                                              │
                                    │                                       [P7 Avanzar tiempo]
                                    └──────── (siguiente turno) ◄──────────────────┘
                                                      │ (fin de partida)
                                    [P8 Monte Carlo] ─► [P9 Estadísticas] ─► [P10 Chat]
```

### Detalle por paso

| Paso | Quién | Input | Proceso | Output |
|---|---|---|---|---|
| **P0 Calibración** (offline) | C | `Data/*.csv` | Agrupa por segmento (empleo × hogar), calcula media/desviación/percentiles/efecto diciembre/residuos. Backtest 80/20 con cobertura p10-p90. Muestrea ~50 perfiles | `calibration.json` (`List<SegmentCalibration>`), `profiles-sample.json` (`List<UserProfile>` + estados iniciales), `backtest-report.json` |
| **P1 Entrevista** | B | `List<InterviewQuestion>` (JSON) | Muestra preguntas de opción múltiple una a una | `List<InterviewAnswer>` |
| **P2 Modelado de perfil** | B + C | `List<InterviewAnswer>`, `profiles-sample.json`, `calibration.json` | Aplica los `sets` de cada respuesta → campos. Busca el perfil sintético más parecido (vecino más cercano por edad, empleo, hogar, ingreso) para inferir gasto, activos y deudas. Construye flujos de ingreso/gasto | `UserProfile` + `FinancialState` inicial (month = 0) + `GameSession` vacía (seed generada). Para Alexia se carga directamente |
| **P3 Elegir evento** | C | `GameSession` (turnos hechos), catálogo `List<Event>` | Filtra por `requires` contra perfil/estado actual, descarta eventos ya usados, respeta `earliestTurn`, elige el siguiente (orden fijo o sorteo con la semilla) | Un `Event` |
| **P4 Preview** | A | `FinancialState` actual, `Event`, `Assumptions` | Para cada opción × caso aplica `apply` y avanza `monthsPerTurn` meses (determinista, sin sorteo) | `List<OptionPreview>` (2 opciones × 3 casos) |
| **P5 Decisión** | B | `List<OptionPreview>` en pantalla | El usuario toca la carta A o B (y, si `manualCaseMode`, también el caso) | `chosenOptionId` (+ `CaseType` opcional) |
| **P6 Resolver caso** | A | `chosenOptionId`, `EventOption`, `seed`, `turn.index` | Sortea el caso con las `prob` usando RNG derivado de `seed + turn.index` | `resolvedCase: CaseType` |
| **P7 Avanzar tiempo** | A | `stateBefore`, `CaseOutcome` elegido, `Assumptions` | Aplica los `effects` y simula mes a mes (reglas de la sección 7). Calcula también el `counterfactual` de la opción no elegida | `Turn` (se añade a `GameSession.turns`) y nuevo `FinancialState` |
| **P8 Monte Carlo** | A | `FinancialState` final, `SegmentCalibration` del perfil, `Assumptions`, `horizonMonths`, `nPaths`, `seed` | Simula N trayectorias con bootstrap de residuos + ruido en parámetros. Se ejecuta para el camino elegido y para el alternativo | `SimulationResult` (uno por camino) |
| **P9 Estadísticas** | A | `GameSession`, `SimulationResult` de ambos caminos | Calcula `Stats` de cada camino, diferencias y la lista de motivos (qué evento y qué caso explican la diferencia) | `Stats` (×2), `PathComparison` |
| **P10 Chat** | C | `ChatContext`, `history`, pregunta del usuario | Construye el prompt (system + sesión serializada + cifras + supuestos) y llama al proveedor LLM | `ChatMessage` (FUTURE_SELF) |

### Reglas de flujo
- **Una sola fuente de verdad**: el `GameSession`. La UI, las estadísticas y el LLM leen de él; nadie guarda copias del estado.
- **El LLM nunca calcula**: solo recibe `ChatContext` y redacta. Si un número no está en el contexto, debe decir que no lo sabe.
- **Reproducibilidad**: misma `seed` + mismas decisiones ⇒ misma partida. Toda aleatoriedad pasa por un RNG con semilla.
- **Preview ≠ resolución**: el preview es determinista (los 3 casos); la resolución sortea uno.
- **Los ficheros JSON los produce C** y los consumen A y B; el formato se acuerda al principio (sección 5).

## 7. Reglas de cálculo del motor

Paso mensual (`t → t+1`), en este orden:

1. Al inicio del turno se aplican los `effects` del caso resuelto (one-off, nuevos flujos, deuda nueva, cambio de empleo).
2. Para cada mes del turno:
   - `income_t = Σ income.monthlyChf` de los flujos activos (`untilMonth == null || t < untilMonth`), ajustado por crecimiento salarial anual.
   - `expense_t = Σ expense.monthlyChf` de los flujos activos, ajustado por inflación anual.
   - `debtPayment_t = Σ liability.monthlyPaymentChf` mientras `principalChf > 0`.
   - `cash_{t+1} = cash_t + income_t − expense_t − debtPayment_t − oneOff_t`
   - `asset.valueChf_{t+1} = asset.valueChf_t × (1 + annualReturn/12)`
   - `principal_{t+1} = max(0, principal_t × (1 + annualRate/12) − monthlyPayment)`
3. Si `cash < 0`, no se detiene la simulación: se marca el mes como "requiere financiación" (igual que `status` en `synthetic-cash-balances.csv`).

Invariantes que deben comprobar los tests:
- `netWorth_{t+1} − netWorth_t = income − expense − oneOff + variación de activos − intereses de deuda` (coherencia contable, tolerancia 0.01 CHF).
- Las probabilidades de cada opción suman 1.0.
- Cada opción tiene exactamente los 3 casos; cada evento tiene exactamente 2 opciones.
- `untilMonth` nunca es menor que el mes de inicio.
- Misma semilla ⇒ mismo resultado.

## 8. Datos y calibración

Datos **sintéticos** del reto en `ai-financial-twin-lauzhack-2026/Data` (1.200 perfiles, enlazados por `profile_id`):

| Archivo | Contenido | Va a |
|---|---|---|
| `synthetic-profiles.csv` | Edad, ciudad, empleo, hogar, caja, ingreso, alquiler (se ignoran `canton`, `country`, `currency` y `synthetic`) | `UserProfile` + flujos de ingreso y alquiler |
| `synthetic-accounts.csv` / `synthetic-assets.csv` | Saldo de apertura, efectivo, inversiones | `cash`, `assets` |
| `synthetic-liabilities.csv` | Deudas (solo instantánea inicial, **no amortizadas**) | `liabilities` (el motor las amortiza) |
| `synthetic-goals.csv` | Metas con importe y fecha (se ignora `priority`) | `goals` |
| `synthetic-cash-balances.csv` | Serie mensual de caja (24 meses por perfil) | **No entra en el modelo**: sirve para calibrar y validar |
| `scenario-catalog.csv` / `scenario-assumptions.csv` | 120 escenarios y parámetros ilustrativos | Inspiración para el catálogo de eventos y `Assumptions` |

Hallazgos del análisis exploratorio:
- Cada perfil tiene solo **24 meses** (2025-01 a 2026-12). Alcanza para calibrar parámetros por segmento, **no** para entrenar un modelo que prediga 10 años.
- Ratio neto/ingreso medio: empleados ≈ 0,52; autónomos ≈ 0,45 (más volátiles); tiempo parcial ≈ 0,11; jubilados ≈ 0,04. Estudiantes y desempleados salen negativos y distorsionados (ingreso casi 0): usar CHF absolutos.
- **Diciembre** tiene un neto medio ≈ 4.388 CHF frente a ≈ 2.700 el resto de meses (paga extra o bonus).
- 61 perfiles (772 meses) caen en negativo (`negative_balance_requires_financing`).
- Solo 39 estudiantes: el segmento de Alexia es pequeño.
- El README del dataset menciona `synthetic-transactions.csv`, `life-events.csv` y `alexia-profile.json` que **no existen**.
- Los datos son sintéticos (probablemente generados con una fórmula): calibrar reconstruye el generador, no el comportamiento real. Hay que decirlo.

Calibración (script offline en `tools/`, Dev C):
1. Agrupar por segmento `employment|household` y calcular los campos de `SegmentCalibration`.
2. Los 3 casos salen del propio dato, a través del coste de vida mensual del segmento: optimista = p10, neutro = p50 (mediana), pesimista = p90. En los datos, `neto = ingreso − alquiler − coste de vida + ruido` (detalle en `tools/README.md`).
3. **Backtest**: calibrar con el 80 % de los perfiles; con el 20 % restante simular los 24 meses desde el saldo inicial y comparar con el saldo real (error medio en CHF y **cobertura**: si el abanico p10-p90 es correcto, ≈ 80 % de los saldos reales deben caer dentro).
4. Más allá del mes 24 no hay datos: se usan **supuestos explícitos** (`Assumptions`: inflación, crecimiento salarial, rentabilidad) etiquetados como tales. La UI debe marcar el tramo "calibrado con datos" (0-24 meses) y el tramo "supuestos" (24-120).
5. Aprendizaje automático solo para el vecino más cercano del perfil. Nada más en 24 h.

## 9. Monte Carlo y estadísticas

Monte Carlo cuesta poco con un motor puro (1.000 trayectorias × 120 meses) y funciona en Android y Desktop.

- **Método**: en cada mes se remuestrean los `residuals` del segmento (bootstrap, no se asume distribución normal) y en cada trayectoria se perturban ligeramente la media, la desviación y los supuestos (inflación, rentabilidad) para que el abanico no salga demasiado estrecho. Semilla fija.
- **Salida**: `SimulationResult` con abanico p10/p50/p90 de caja y patrimonio, probabilidad de caja negativa, probabilidad de alcanzar cada meta y mes mediano.
- **Relación con los 3 casos**: los casos son lo que el usuario **ve y elige** (con texto y efectos concretos); Monte Carlo es lo que **se muestra al final** (abanico y probabilidades). Las probabilidades de los casos pueden salir de Monte Carlo.
- **Límite**: Monte Carlo no crea información más allá del mes 24; solo propaga las distribuciones supuestas.

Estadísticas **MVP** (imprescindibles):
1. Evolución de la caja con abanico.
2. Patrimonio neto (caja + activos − deudas).
3. Liquidez mínima y meses en negativo.
4. Metas alcanzadas y fecha estimada.
5. Camino elegido vs. camino alternativo.

Si da tiempo: tasa de ahorro, ratio de endeudamiento, fondo de emergencia (meses de gasto cubiertos), riesgo (todo pesimista), comparación con perfiles similares, puntuación final / "perfil de decisor".

## 10. LLM: de dónde salen las respuestas y cómo no pagar

### De dónde sale el contenido
El LLM no sabe nada del usuario. Solo sabe lo que se le pasa en cada llamada:

| Parte del prompt | Contenido | Quién lo produce |
|---|---|---|
| System prompt | Rol de "tú dentro de 10 años", tono, reglas | Dev C (a mano) |
| Contexto | `GameSession` serializada | El motor |
| Cifras | `Stats`, `PathComparison`, `SimulationResult` | El motor |
| Supuestos y límites | `Assumptions.sourceNotes`, qué está calibrado y qué es supuesto | JSON |
| Pregunta | Texto libre | El usuario |

Reglas: *usa solo las cifras del contexto; si no están, di que no lo sabes; cita el turno o el dato; recuerda que es una simulación y no asesoramiento financiero.*
Las narrativas de los eventos van **fijas en el JSON** (`CaseOutcome.narrative`); el LLM solo conversa en el chat. El chat explica resultados; **no** propone escenarios nuevos (fuera del MVP).

### Que no cueste dinero
Los límites y condiciones de los servicios gratuitos cambian: **verificadlos antes de depender de ellos**.

| Opción | Coste | Dónde | Nota |
|---|---|---|---|
| Ollama local (Llama, Qwen, Gemma…) | 0 € | Desktop | Necesita RAM/GPU; un modelo pequeño responde peor en español. Android puede llamarlo por la red local |
| APIs con capa gratuita (Gemini, Groq, OpenRouter "free"…) | 0 € dentro del límite | Android y Desktop | Límite de peticiones; necesita clave y red |
| Créditos del hackathon | 0 € | Ambos | Preguntar a la organización |
| Respuestas de ejemplo (plan B) | 0 € | Ambos | No es conversación real |

Recomendación: **Ollama en desarrollo**, API gratuita en la demo si hay wifi fiable (Ollama de respaldo) y **respuestas de ejemplo siempre listas**. Todo detrás de `FutureSelfChat`.
Ahorro: contexto pequeño (5-6 turnos), narrativas sin LLM, respuestas cortas, tope de preguntas por sesión.
Privacidad: con Ollama los datos que el usuario escribe no salen de su máquina; con una API externa sí. Nunca meter claves en el repo ni en la app (variable de entorno).

## 11. Reparto del trabajo

Somos 3 desarrolladores. **Dev B (front) ya está asignado**; las responsabilidades de **Dev A** y **Dev C** se reparten el resto.
*(Pendiente: escribir aquí el nombre de cada persona.)*

| Rol | Persona | Responsabilidad principal |
|---|---|---|
| **Dev A – Motor + integración** | _por asignar_ | Esqueleto KMP, `model/`, motor, controlador de partida, carga de JSON, Monte Carlo, estadísticas, tests |
| **Dev B – UI/UX (front)** | _asignado_ | Todo lo que ve el usuario: pantallas, tema, i18n, gráficos, empaquetado |
| **Dev C – Datos + contenido + IA** | _por asignar_ | Calibración con los datos, catálogo de eventos, supuestos, chat del "yo del futuro" |

### Carpetas propias (para evitar conflictos de merge)

| Dev | Carpetas |
|---|---|
| **A** | `model/` (propone y mantiene), `engine/`, `data/` (carga de JSON), build Gradle y configuración |
| **B** | `ui/`, `androidMain`, `desktopMain`, recursos i18n |
| **C** | `events/`, `ai/`, `tools/` (scripts Python), JSON de contenido (`resources/`) |

### Responsabilidades de Dev A – Motor + integración
1. **Esqueleto KMP**: Gradle, módulos, que compile y arranque en Android y Desktop. Es lo primero, porque desbloquea a los demás.
2. **`model/`**: dataclasses de la sección 5, enums y mocks (Alexia en JSON y un evento de ejemplo). Entrega en la **hora 2**.
3. **Motor** (`engine/`): `apply(state, option, case)`, avance mensual y amortización de deudas (sección 7), preview determinista de los 3 casos (`OptionPreview`), sorteo de caso con semilla y contrafactual.
4. **Controlador de partida**: máquina de estados que une los pasos P3-P7 y expone a la UI el estado actual y las acciones (`startGame`, `chooseOption`, `next`). Es el punto de contacto con B.
5. **Carga de datos** (`data/`): lectura de los JSON (perfiles, calibración, eventos, supuestos, entrevista) en `commonMain`.
6. **Monte Carlo** (bootstrap de residuos + ruido en parámetros) y **estadísticas**: `SimulationResult`, `Stats`, `PathComparison` con los motivos de la diferencia.
7. **Tests**: coherencia contable, semilla reproducible, probabilidades que suman 1, 2 opciones × 3 casos por evento.

### Responsabilidades de Dev C – Datos + contenido + IA
1. **Calibración offline** (`tools/`): CSV → JSON, `SegmentCalibration` por segmento (empleo × hogar), muestra de ~50 perfiles.
2. **Backtest** 80/20 con cobertura p10-p90 y su informe (`backtest-report.json`).
3. **Vecino más cercano**: dado el resultado de la entrevista, elegir el perfil sintético parecido para inferir gastos, activos y deudas.
4. **Contenido del juego**: catálogo de eventos (5-8 × 2 opciones × 3 casos, con efectos numéricos y narrativas ES/EN), y `Assumptions` con fuente de cada cifra.
5. **Chat del "yo del futuro"** (`ai/`): implementación de `FutureSelfChat`, system prompt, construcción del contexto desde `GameSession`, proveedor gratuito y **plan B con respuestas de ejemplo**.
6. **Calidad del prompt**: comprobar que el LLM solo usa cifras del contexto y que mantiene el aviso de "simulación, no asesoramiento".

### Contrato entre A y C
- **Los formatos JSON** de `Event`, `SegmentCalibration` y `Assumptions` los **define C y los aprueba A** antes de escribir contenido. Si cambian, se avisa al otro y a B.
- A entrega `model/` y mocks en la hora 2; C entrega el primer JSON real de eventos y parámetros hacia la hora 6.
- Nadie cambia `model/` sin avisar (es el contrato con B).

### Reparto con el front (Dev B)
Además de la UI, B es dueño de las preguntas de la entrevista (`InterviewQuestion`) y de las cadenas ES/EN, y puede ayudar con: 1-2 eventos del catálogo (formato acordado, los revisa C), el guion de demo, el README final y las notas del proceso con agentes. Gráficos: decidir pronto Canvas o Koalaplot.

### Cronograma (24 h)
- **h0-2, los tres juntos**: esqueleto KMP, acordar `model/`, mocks.
- **h2-10**: trabajo en paralelo (A motor y controlador, B UI contra mocks, C datos y eventos).
- **h8**: A + B conectan (UI con motor real).
- **h12**: catálogo de C integrado, **partida completa con Alexia**.
- **h16**: chat conectado.
- **h18: congelar funcionalidades.**
- **h18-24**: pruebas en Android y Desktop, pulido, demo, README, vídeo.

### Orden de prioridad (si falta tiempo, se corta por abajo)
1. Bucle de juego completo con Alexia (motor + eventos + UI).
2. Preview de los 3 casos y sorteo.
3. Estadísticas MVP y comparación con el camino alternativo.
4. Entrevista y perfil propio.
5. Chat del "yo del futuro".
6. Monte Carlo y calibración con backtest (si no llega, el abanico se sustituye por las trayectorias de los 3 casos).

### Cómo aliviar la carga
- Reducir a 5 eventos y a las 5 estadísticas del MVP.
- Usar un LLM para los borradores de los eventos y validarlos a mano.
- Cada dev revisa las PRs del otro (también cuenta como proceso con agentes).
- Quien termine antes ayuda con eventos, tests o pruebas de integración.

### Reglas
Una rama por persona (`dev-a/motor`, `dev-b/ui`, `dev-c/datos`), PRs pequeñas a `main`, merge al menos cada 3-4 horas. Ver `AGENTS.md`.

## 12. Cosas importantes y preguntas abiertas

- **El LLM no inventa números**: recibe los resultados del motor y solo los explica.
- **Plan B de demo**: chat con respuestas de ejemplo por si falla la red o el modelo; Alexia precargada.
- **Gráficos**: pocas librerías en KMP; `Canvas` o Koalaplot (decidirlo pronto).
- **Transparencia**: mostrar supuestos, unidades y limitaciones; avisar de que no es asesoramiento financiero ni una predicción.
- **Agentic Development**: documentar qué hizo cada agente (JetBrains Air) y qué corregimos, para los puntos extra.
- **Licencia**: los materiales del reto son "all rights reserved" (autora: Stefania Stan). Usarlos solo para participar y citar la fuente. No copiar los datos del reto fuera del repo del equipo sin revisarlo.
- **Pendiente de leer**: `evaluation/judge-scorecard.md` (rúbrica propuesta).

Preguntas abiertas:
1. Proveedor de LLM concreto (y quién aporta la clave, si hace falta).
2. ¿Se implementa el modo "elige tú el caso" (`manualCaseMode`)?
3. ¿Se guarda la partida en disco o solo en memoria?
4. Nombre de las personas que son Dev A y Dev C (B ya está asignado).
