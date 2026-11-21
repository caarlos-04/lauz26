# FinancialStatement — la foto de la persona en cada paso

> Contrato compartido (Dev A, B, C). Si cambia, se avisa con una issue con la etiqueta `contrato`.
> Implementación de referencia: `tools/statement.py`. Generador: `tools/build_statements.py`.

Un `FinancialStatement` es **todo lo que sabemos de una persona en un punto del tiempo**: quién es, qué hechos de vida
tiene, cuánto dinero tiene y dónde, qué debe, qué cobra y paga cada mes, qué pasó con su caja ese mes, cómo van sus
metas y unos indicadores de salud financiera. Es el estado del gemelo: el motor recibe un statement y devuelve otro,
la UI pinta statements y el LLM los cuenta.

## 1. Convenciones

| Tema | Regla |
|---|---|
| Mes | `month = 0` es el statement de apertura, a fecha `startMonth-01` (2025-01-01), sin flujo de caja. `month = t ≥ 1` es el statement **al final** del mes de calendario `startMonth + (t − 1)` e incluye el flujo de caja de ese mes |
| Periodo | `period`: `"2025-01-01"` en la apertura; `"2025-01"`, `"2025-02"`… después. Mes 12 = diciembre 2025; mes 120 = diciembre 2034 |
| Dinero | `Double` en CHF (todo el dataset está en CHF). Tipos de interés como fracción anual |
| Origen | Cada statement tiene `origin`: `OBSERVED` (meses 0-24 del dataset) o `SIMULATED` (motor). Cada línea de flujo tiene el origen de **su importe**: `OBSERVED` (columna del dataset), `DERIVED` (calculado a partir del dataset), `ASSUMED` (supuesto nuestro) o `SIMULATED` (creado por un evento) |
| Metas | `targetMonth` = statement al final del mes objetivo (31-12-2029 → mes 60) |
| Inmutable | Cada paso produce un statement nuevo. Las partes derivadas (`balanceSheet`, `goals`, `metrics`) se recalculan en **un único sitio** (`assemble` en Python, `finalize` en Kotlin) |

## 2. Esquema

```kotlin
data class FinancialStatement(
    val version: Int,                           // 1
    val profileId: String,                      // "P00001"
    val month: Int,                             // 0..120 (see conventions)
    val period: String,                         // "2025-01-01" | "2025-01"
    val origin: Origin,                         // OBSERVED | SIMULATED
    val currency: String,                       // "CHF"
    val person: PersonSnapshot,
    val accounts: List<CashAccount>,            // cash, counted ONCE (see 4.1)
    val investments: List<Investment>,
    val liabilities: List<Liability>,
    val incomeStreams: List<IncomeStream>,      // definitions with fromMonth/untilMonth (needed to keep simulating)
    val expenseStreams: List<ExpenseStream>,
    val cashFlow: CashFlow?,                    // what happened to cash during this month; null at month 0
    val balanceSheet: BalanceSheet,             // derived
    val goals: List<GoalStatus>,                // derived
    val scenarioParameters: List<ScenarioParameter>,
    val metrics: StatementMetrics,              // derived
    val pendingLifeChanges: List<LifeChange>    // changes already decided by events, not applied yet
)

enum class Origin { OBSERVED, DERIVED, ASSUMED, SIMULATED }
enum class Indexation { WAGES, CPI, NONE }      // which index updates the stream when inflation is modelled

data class PersonSnapshot(
    val displayName: String, val ageAtStart: Int, val age: Int,     // age = ageAtStart + month / 12
    val city: String, val canton: String?, val country: String,     // canton = null if the person moved city
    val employment: Employment, val household: Household,
    val facts: Map<FactKey, String>, val flags: List<String>,        // life state (content/facts.json)
    val synthetic: Boolean
)

data class CashAccount(val id: String, val type: String /* "CASH" */, val currency: String,
                       val balanceChf: Double, val status: String /* OK | NEGATIVE_REQUIRES_FINANCING */)

data class Investment(val id: String, val accountId: String?, val type: AssetType,
                      val valueChf: Double, val annualReturn: Double?,  // null in the dataset -> Assumptions
                      val valuationBasis: String)

data class Liability(val id: String, val type: LiabilityType, val principalChf: Double, val annualRate: Double,
                     val monthlyPaymentChf: Double, val paymentIsAssumed: Boolean,
                     val datasetMonthlyPaymentChf: Double? = null, val sourceEventId: String? = null)

data class IncomeStream(val id: String, val label: String, val monthlyChf: Double,
                        val fromMonth: Int = 0, val untilMonth: Int? = null,
                        val indexation: Indexation, val origin: Origin, val sourceEventId: String? = null)

data class ExpenseStream(val id: String, val category: ExpenseCategory, val monthlyChf: Double,
                         val fromMonth: Int = 0, val untilMonth: Int? = null,
                         val indexation: Indexation, val origin: Origin, val sourceEventId: String? = null)

data class CashFlow(
    val openingCashChf: Double,
    val income: List<FlowLine>, val expenses: List<FlowLine>, val debtService: List<DebtLine>,
    val otherChf: Double,                       // not itemised (observed months: noise, December bonus...)
    val financingCostChf: Double,               // interest on negative cash
    val totalIncomeChf: Double, val totalExpensesChf: Double, val totalDebtServiceChf: Double,
    val netCashFlowChf: Double, val closingCashChf: Double
)
data class FlowLine(val key: String /* "income:income", "expense:RENT" */, val amountChf: Double,
                    val origin: Origin, val sourceEventId: String?)
data class DebtLine(val liabilityId: String, val paymentChf: Double, val interestChf: Double,
                    val principalRepaidChf: Double, val sourceEventId: String?)

data class BalanceSheet(val cashChf: Double, val investmentsChf: Double, val totalAssetsChf: Double,
                        val totalLiabilitiesChf: Double, val netWorthChf: Double)

data class GoalStatus(
    val id: String, val name: String, val targetChf: Double, val targetDate: String, val targetMonth: Int,
    val priority: Priority,                     // HIGH | MEDIUM
    val progressBasis: String,                  // CASH | NET_WORTH (see 4.5)
    val currentChf: Double, val progressRatio: Double, val monthsLeft: Int,
    val status: String                          // IN_PROGRESS | REACHED | MISSED
)

data class ScenarioParameter(val id: String, val scenarioId: String, val parameter: String, val value: Double,
                             val unit: String, val frequency: String, val sourceType: String, val notes: String)

data class StatementMetrics(
    val basis: String,                          // "RUN_RATE": streams active next month
    val monthlyIncomeChf: Double, val monthlyOutflowsChf: Double, val monthlyNetCashFlowChf: Double,
    val savingsRate: Double?,                   // net / income (null if income = 0)
    val debtServiceRatio: Double?,              // debt payments / income
    val rentToIncomeRatio: Double?,
    val emergencyFundMonths: Double?,           // cash / monthly outflows
    val liquidityStatus: String,                // NEGATIVE | LOW (< 3 months, assumption) | OK
    val netWorthChf: Double
)
```

`LifeChange`, `FactKey`, `Employment`, `Household`, `AssetType`, `LiabilityType`, `ExpenseCategory`: ver `docs/README.md` (sección 5).

## 3. De dónde sale cada campo (cruce de los datasets por `profile_id`)

| Campo del statement | Fichero.columna | Origen | Notas |
|---|---|---|---|
| `profileId` | `synthetic-profiles.profile_id` | OBSERVED | Clave de todos los cruces |
| `person.displayName` | `synthetic-profiles.display_name` | OBSERVED | Solo 30 nombres distintos |
| `person.ageAtStart`, `age` | `synthetic-profiles.age_at_start` | OBSERVED / DERIVED | `age` crece con el mes |
| `person.city`, `canton`, `country` | `synthetic-profiles.city/canton/country` | OBSERVED | `country` siempre CH |
| `person.employment`, `household` | `synthetic-profiles.employment_status/household_type` | OBSERVED | Cambian por eventos |
| `person.facts` | `content/facts.json` → `initialFromProfile` | DERIVED | Reglas a partir de empleo, hogar y ciudad |
| `person.synthetic` | `synthetic-profiles.synthetic` | OBSERVED | Siempre `true` |
| `currency` | `currency` de todos los ficheros | OBSERVED | Siempre CHF |
| `accounts[]` | `synthetic-accounts.*` | OBSERVED | Una cuenta de caja por perfil |
| `accounts[].balanceChf` (mes 0) | `synthetic-accounts.opening_balance_chf` | OBSERVED | = `profiles.opening_cash_chf` = activo `cash` |
| `accounts[].balanceChf` (meses 1-24) | `synthetic-cash-balances.closing_cash_chf` | OBSERVED | |
| `accounts[].status` | `synthetic-cash-balances.status` | OBSERVED | Coincide siempre con caja < 0 |
| `investments[]` | `synthetic-assets` con `asset_type = investment_portfolio` | OBSERVED | Todos menos Alexia (1.199 perfiles) |
| `investments[].accountId` | `synthetic-assets.account_id` | OBSERVED | Las cuentas `A-…-INV` **no existen** en `synthetic-accounts` |
| `investments[].annualReturn` | — | ASSUMED | No hay rentabilidades en el dataset |
| `liabilities[]` | `synthetic-liabilities.*` | OBSERVED | 283 perfiles, un préstamo personal cada uno |
| `liabilities[].monthlyPaymentChf` | — | ASSUMED | El dataset tiene 0; suponemos anualidad a 60 meses (`paymentIsAssumed`) |
| `incomeStreams[income/pension]` | `synthetic-profiles.reference_monthly_net_income_chf` | OBSERVED | `pension` si jubilado |
| `expenseStreams[rent]` | `synthetic-profiles.reference_monthly_rent_chf` | OBSERVED | |
| `expenseStreams[living]` | `synthetic-cash-balances.net_transactions_chf` | DERIVED | `ingreso − alquiler − media del neto (meses sin diciembre)` |
| `cashFlow` (meses 1-24) | `synthetic-cash-balances.opening/net/closing` | OBSERVED + DERIVED | Neto, apertura y cierre observados; desglose derivado (4.3) |
| `goals[]` | `synthetic-goals.*` | OBSERVED | 1-2 metas por perfil; fechas 2029-12-31 o 2032-12-31 |
| `goals[].priority` | `synthetic-goals.priority` | OBSERVED | HIGH o MEDIUM |
| `scenarioParameters[]` | `scenario-assumptions.*` | OBSERVED (supuesto del reto) | Solo perfiles P00001-P00120: matrícula (1.200-8.000 CHF/año) y duración (2 años) del escenario S001 |
| `balanceSheet`, `goals[].status`, `metrics` | — | DERIVED | Calculados en `assemble` / `finalize` |

Ficheros que **no** entran en el statement: `scenario-catalog.csv` (inspiración para eventos) y la serie mensual completa
de caja (cada statement guarda solo su mes).

## 4. Decisiones de diseño

1. **La caja se cuenta una sola vez.** Aparece en tres sitios con el mismo valor (perfil, cuenta y activo `cash`).
   En el statement vive solo en `accounts`; `investments` solo tiene inversiones.
2. **Flujos como definiciones, no solo importes.** `incomeStreams`/`expenseStreams` llevan `fromMonth`/`untilMonth`
   para que el statement sea suficiente para seguir simulando desde él.
3. **Desglose de los meses observados.** El dataset solo da el neto. Se descompone en ingreso (observado) − alquiler
   (observado) − coste de vida (derivado) + `otherChf` (derivado: ruido y paga extra). Las cuentas cuadran por construcción.
4. **Los préstamos no se pagan en los meses observados.** El ledger del dataset no incluye su cuota. Los statements
   observados reflejan eso (`debtService` vacío, principal constante); los simulados sí amortizan con la cuota supuesta.
5. **Base de cada meta.** "Emergency savings" y "Housing deposit" se miden con la **caja**; el resto con el
   **patrimonio neto**. Es un supuesto nuestro y se muestra (`progressBasis`).
6. **Indicadores a ritmo actual.** `metrics` se calcula con los flujos activos el mes siguiente (no con lo ocurrido),
   así existe también en la apertura. El umbral de 3 meses de colchón es una regla práctica, no un dato.
7. **Origen visible.** Cada importe dice si es observado, derivado, supuesto o simulado: es lo que pide el reto
   ("distinguir observaciones, supuestos y resultados calculados").

## 5. Identidades que se comprueban (`check_statement`)

- `caja + inversiones − deudas = patrimonio neto`
- `ingresos − gastos − cuotas − financiación + otros = neto del mes`
- `caja de apertura + neto = caja de cierre = caja del balance`

Resultado actual: **1.200 perfiles × 25 statements (apertura + 24 meses observados), 0 errores**; y los statements
simulados por turno de `--play` también cuadran.

## 6. Cómo generarlos

```
python tools/build_statements.py --profile P00001          # apertura + 24 meses observados -> tools/out/statements/
python tools/build_statements.py --all --check-only        # comprobar las identidades en todos los perfiles
python tools/calibrate.py                                   # profiles-sample.json con initialStatement de ~56 perfiles
python tools/validate_events.py --play P00001 --choices A,B --dump tools/out/statements/P00001-game.json
                                                            # un statement por turno de una partida simulada
```

## 7. Lo que el dataset no tiene (y cómo lo cubrimos)

| Falta | Solución |
|---|---|
| Transacciones individuales | Desglose derivado del neto (4.3) |
| Cuotas de los préstamos | Anualidad a 60 meses (supuesto) |
| Rentabilidad de las inversiones | `Assumptions.investmentReturnAnnual` |
| Impuestos | No se modelan (limitación documentada) |
| Evolución de activos y deudas en el tiempo | Solo hay foto inicial; el motor los hace evolucionar |
| Inflación y subidas de sueldo | Datos públicos (pendiente, `content/assumptions.json`) |
