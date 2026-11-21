# FinancialStatement — the person's snapshot at each step

> Shared contract (Dev A, B, C). If it changes, announce it with an issue labelled `contrato`.
> Reference implementation: `tools/statement.py`. Generator: `tools/build_statements.py`.
> Kotlin data classes: `composeApp/src/commonMain/kotlin/com/lauzhack/twin/model/FinancialStatement.kt`.

A `FinancialStatement` is **everything we know about a person at one point in time**: who they are, which life facts
they have, how much money they have and where, what they owe, what they earn and pay every month, what happened to
their cash that month, how their goals are going and a few financial health indicators. It is the twin's state: the
engine takes a statement and returns another one, the UI draws statements and the LLM tells them.

## 1. Conventions

| Topic | Rule |
|---|---|
| Month | `month = 0` is the opening statement, as of `startMonth-01` (2025-01-01), with no cash flow. `month = t ≥ 1` is the statement at the **end** of calendar month `startMonth + (t − 1)` and includes that month's cash flow |
| Period | `period`: `"2025-01-01"` for the opening; `"2025-01"`, `"2025-02"`… afterwards. Month 12 = December 2025; month 120 = December 2034 |
| Money | `Double` in CHF (the whole dataset is in CHF). Interest rates as an annual fraction |
| Origin | Every statement has an `origin`: `OBSERVED` (dataset months 0-24) or `SIMULATED` (engine). Every flow line carries the origin of **its amount**: `OBSERVED` (dataset column), `DERIVED` (computed from the dataset), `ASSUMED` (our assumption) or `SIMULATED` (created by an event) |
| Goals | `targetMonth` = statement at the end of the target month (2029-12-31 → month 60) |
| Immutable | Each step produces a new statement. The derived parts (`balanceSheet`, `goals`, `metrics`) are recomputed in **a single place** (`assemble` in Python, `finalize` in Kotlin) |

## 2. Schema

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

`LifeChange`, `FactKey`, `Employment`, `Household`, `AssetType`, `LiabilityType`, `ExpenseCategory`: see `docs/README.md` (section 5).

## 3. Where each field comes from (datasets joined by `profile_id`)

| Statement field | File.column | Origin | Notes |
|---|---|---|---|
| `profileId` | `synthetic-profiles.profile_id` | OBSERVED | Key for every join |
| `person.displayName` | `synthetic-profiles.display_name` | OBSERVED | Only 30 distinct names |
| `person.ageAtStart`, `age` | `synthetic-profiles.age_at_start` | OBSERVED / DERIVED | `age` grows with the month |
| `person.city`, `canton`, `country` | `synthetic-profiles.city/canton/country` | OBSERVED | `country` is always CH |
| `person.employment` | `synthetic-profiles.employment_status` | DERIVED | Work status only: `student` → `NOT_WORKING` (studying goes to `facts.EDUCATION`); the rest unchanged. Changed by events |
| `person.household` | `synthetic-profiles.household_type` | OBSERVED | |
| `person.facts` | `content/facts.json` → `initialFromProfile` | DERIVED | Rules based on the dataset's employment_status, household and city. `EDUCATION` says whether the person studies |
| `person.synthetic` | `synthetic-profiles.synthetic` | OBSERVED | Always `true` |
| `currency` | `currency` in every file | OBSERVED | Always CHF |
| `accounts[]` | `synthetic-accounts.*` | OBSERVED | One cash account per profile |
| `accounts[].balanceChf` (month 0) | `synthetic-accounts.opening_balance_chf` | OBSERVED | = `profiles.opening_cash_chf` = `cash` asset |
| `accounts[].balanceChf` (months 1-24) | `synthetic-cash-balances.closing_cash_chf` | OBSERVED | |
| `accounts[].status` | `synthetic-cash-balances.status` | OBSERVED | Always matches cash < 0 |
| `investments[]` | `synthetic-assets` with `asset_type = investment_portfolio` | OBSERVED | Everyone except Alexia (1,199 profiles) |
| `investments[].accountId` | `synthetic-assets.account_id` | OBSERVED | The `A-…-INV` accounts **do not exist** in `synthetic-accounts` |
| `investments[].annualReturn` | — | ASSUMED | No returns in the dataset |
| `liabilities[]` | `synthetic-liabilities.*` | OBSERVED | 283 profiles, one personal loan each |
| `liabilities[].monthlyPaymentChf` | — | ASSUMED | The dataset has 0; we assume a 60-month annuity (`paymentIsAssumed`) |
| `incomeStreams[income/pension]` | `synthetic-profiles.reference_monthly_net_income_chf` | OBSERVED | `pension` if retired |
| `expenseStreams[rent]` | `synthetic-profiles.reference_monthly_rent_chf` | OBSERVED | |
| `expenseStreams[living]` | `synthetic-cash-balances.net_transactions_chf` | DERIVED | `income − rent − mean net (non-December months)` |
| `cashFlow` (months 1-24) | `synthetic-cash-balances.opening/net/closing` | OBSERVED + DERIVED | Net, opening and closing observed; breakdown derived (4.3) |
| `goals[]` | `synthetic-goals.*` | OBSERVED | 1-2 goals per profile; dates 2029-12-31 or 2032-12-31 |
| `goals[].priority` | `synthetic-goals.priority` | OBSERVED | HIGH or MEDIUM |
| `scenarioParameters[]` | `scenario-assumptions.*` | OBSERVED (challenge assumption) | Only profiles P00001-P00120: tuition (CHF 1,200-8,000/year) and length (2 years) of scenario S001 |
| `balanceSheet`, `goals[].status`, `metrics` | — | DERIVED | Computed in `assemble` / `finalize` |

Files that do **not** go into the statement: `scenario-catalog.csv` (inspiration for events) and the full monthly cash
series (each statement keeps only its own month).

## 4. Design decisions

1. **Cash is counted once.** It appears in three places with the same value (profile, account and `cash` asset).
   In the statement it lives only in `accounts`; `investments` only holds investments.
2. **Streams as definitions, not just amounts.** `incomeStreams`/`expenseStreams` carry `fromMonth`/`untilMonth`
   so the statement is enough to keep simulating from it.
3. **Breakdown of the observed months.** The dataset only gives the net. It is split into income (observed) − rent
   (observed) − living cost (derived) + `otherChf` (derived: noise and December bonus). It adds up by construction.
4. **Loans are not paid in the observed months.** The dataset ledger does not include their payment. The observed
   statements reflect that (empty `debtService`, constant principal); the simulated ones do amortise with the assumed payment.
5. **Basis of each goal.** "Emergency savings" and "Housing deposit" are measured with **cash**; the rest with
   **net worth**. It is our assumption and it is shown (`progressBasis`).
6. **Run-rate indicators.** `metrics` uses the streams active next month (not what happened), so it exists for the
   opening statement too. The 3-month cushion threshold is a rule of thumb, not data.
7. **Studying and working are independent.** `person.employment` is only about how the person earns money; whether they
   study is `facts.EDUCATION` (a value ending in `_IN_PROGRESS`). A working student has `PART_TIME` + `MASTER_IN_PROGRESS`.
   The dataset's single `employment_status` is split when loading (`student` → `NOT_WORKING` + `BACHELOR_IN_PROGRESS`).
8. **Visible origin.** Every amount says whether it is observed, derived, assumed or simulated: that is what the
   challenge asks for ("distinguish observations, assumptions and calculated outcomes").

## 5. Identities that are checked (`check_statement`)

- `cash + investments − liabilities = net worth`
- `income − expenses − debt payments − financing + other = net for the month`
- `opening cash + net = closing cash = balance-sheet cash`

Current result: **1,200 profiles × 25 statements (opening + 24 observed months), 0 errors**; the simulated statements
per turn from `--play` add up too.

## 6. How to generate them

```
python tools/build_statements.py --profile P00001          # opening + 24 observed months -> tools/out/statements/
python tools/build_statements.py --all --check-only        # check the identities for every profile
python tools/calibrate.py                                   # profiles-sample.json with the initialStatement of ~56 profiles
python tools/validate_events.py --play P00001 --choices A,B --dump tools/out/statements/P00001-game.json
                                                            # one statement per turn of a simulated game
```

## 7. What the dataset lacks (and how we cover it)

| Missing | Solution |
|---|---|
| Individual transactions | Breakdown derived from the net (4.3) |
| Loan payments | 60-month annuity (assumption) |
| Investment returns | `Assumptions.investmentReturnAnnual` |
| Taxes | Not modelled (documented limitation) |
| How assets and debts evolve over time | Only an opening snapshot; the engine makes them evolve |
| Inflation and pay rises | Public data (pending, `content/assumptions.json`) |
