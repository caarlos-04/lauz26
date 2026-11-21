# FinancialStatement — the person's snapshot at each step

> Shared contract (Dev A, B, C). If it changes, announce it with an issue labelled `contrato`.
> Kotlin: `shared/src/commonMain/kotlin/com/lauzhack/twin/model/FinancialStatement.kt`. Python reference:
> `tools/statement.py` (contract and opening statements), `tools/simulate.py` (how it moves in time, see
> [`docs/simulator.md`](simulator.md)).

A `FinancialStatement` is the person at one point in time. **It holds only what the datasets have and what the
simulator needs to produce the next statement.** Anything that can be computed from it (net worth, goal progress,
ratios) is computed when needed, never stored.

## 1. Conventions

| Topic | Rule |
|---|---|
| Month | `month = 0` is the opening statement (`period = "2025-01-01"`, no cash flow). `month = t ≥ 1` is the end of calendar month `period` (`"2025-01"` … `"2034-12"`) and holds that month's cash flow |
| Money | `Double` in CHF (the whole dataset is in CHF). Interest rates as an annual fraction |
| Immutable | Each monthly step produces a new statement |

## 2. Schema

```kotlin
data class FinancialStatement(
    val profileId: String,                  // profile_id, or "USER-..." for a person from the interview
    val month: Int,                         // 0..120
    val period: String,                     // "2025-01-01" (opening) or "2025-01" (end of that month)
    val person: Person,
    val cashChf: Double,
    val investmentsChf: Double,
    val loan: Loan?,                        // 0 or 1 personal loan
    val monthlyIncomeChf: Double,
    val monthlyRentChf: Double,
    val eventExpensesChf: Map<ExpenseCategory, Double>,  // recurring monthly expenses added by events (e.g. TUITION)
    val cashFlow: CashFlow?,                // what happened during this month; null at month 0
    val goals: List<Goal>,
    val pendingChanges: List<PendingChange>,// decided by events, applied when their month arrives
)

data class Person(
    val displayName: String,
    val age: Int,
    val employment: Employment,             // work only
    val household: Household,
    val facts: Map<FactKey, String>,        // EDUCATION, OCCUPATION, CITY, HOUSING, FAMILY (content/facts.json)
    val flags: List<String>,                // milestones
)

data class Loan(val principalChf: Double, val annualRate: Double, val monthlyPaymentChf: Double)

// net = sum(income) - sum(expenses) - loanPayment - financingCost; cash(t) = cash(t-1) + net
data class CashFlow(
    val incomeChf: Map<String, Double>,     // "salary", "bonus", "loan" (a new loan paid out)
    val expensesChf: Map<String, Double>,   // "rent", learned flows ("groceries", "travel"...), event expenses ("TUITION")
    val loanPaymentChf: Double,
    val financingCostChf: Double,           // interest on negative cash
    val netChf: Double,
)

data class Goal(val name: String, val targetChf: Double, val targetDate: String, val priority: Priority)

// Exactly one group of fields is set; applied at the start of `month`.
data class PendingChange(
    val month: Int, val sourceEventId: String,
    val employment: Employment? = null,
    val factKey: FactKey? = null, val factValue: String? = null,
    val flag: String? = null,
    val monthlyIncomeChf: Double? = null, val incomeDeltaChf: Double? = null,
    val expenseCategory: ExpenseCategory? = null, val expenseDeltaChf: Double? = null,
    val loan: Loan? = null,
)
```

Computed, not stored: `netWorth = cash + investments − loan.principal`; a goal is reached when its basis (cash for
"Emergency savings" and "Housing deposit", net worth for the rest — our assumption, shown) reaches the target, missed
when its date passes first. Studying = `facts.EDUCATION` ends in `_IN_PROGRESS`.

## 3. Where each field comes from (datasets joined by `profile_id`)

The opening statement of a dataset profile (`statement.dataset_opening`, e.g. Alexia). A person from the interview
fills the same fields from the answers (`statement.new_person`).

| Field | File.column | Notes |
|---|---|---|
| `profileId` | `synthetic-profiles.profile_id` | Key of every join |
| `person.displayName`, `age` | `synthetic-profiles.display_name`, `age_at_start` | `age` grows every 12 months |
| `person.employment` | `synthetic-profiles.employment_status` | Work only: `student` → `NOT_WORKING` + `facts.EDUCATION = BACHELOR_IN_PROGRESS` |
| `person.household` | `synthetic-profiles.household_type` | |
| `person.facts` | `content/facts.json` → `initialFromProfile` | From employment_status, household and city; `CITY` is the city |
| `cashChf` | `synthetic-accounts.opening_balance_chf` | = `profiles.opening_cash_chf` = the `cash` asset (counted once) |
| `investmentsChf` | `synthetic-assets.value_chf` (`investment_portfolio`) | One portfolio per profile (0 for Alexia) |
| `loan` | `synthetic-liabilities.*` | 283 profiles, one personal loan each. `monthly_payment_chf` is 0 (never paid): we assume a 60-month annuity |
| `monthlyIncomeChf` | `synthetic-profiles.reference_monthly_net_income_chf` | |
| `monthlyRentChf` | `synthetic-profiles.reference_monthly_rent_chf` | |
| `goals` | `synthetic-goals.*` | 1-2 per profile |
| `cashFlow` | categories of `synthetic-transactions.zip` | Simulated months; the flow model learned them from the ledger |

Not in the statement: `synthetic-cash-balances.csv` and the transactions (used to learn and validate the flow model),
`scenario-assumptions.csv` (only scenario S001; events carry their own numbers), `scenario-catalog.csv` and
`life-events.csv` (inspiration for events), and constant columns (`country` = CH, `currency` = CHF,
`synthetic` = true). `canton` is derived from the city when the model needs it.

## 4. Design decisions

1. **One value per thing, as in the data.** One cash balance, one investment portfolio, at most one loan, one income
   and one rent. A second loan from an event is merged into the existing one.
2. **Events become pending changes.** An event never edits the statement directly: its effects become
   `pendingChanges` (life, income, event expenses, a new loan) that the simulator applies at the start of their month.
   A change that lasts N months schedules its own reversal.
3. **The simulator writes flows, accounting writes cash.** `cash(t) = cash(t−1) + net`; nothing else is derived and
   stored.
4. **Studying and working are independent.** `person.employment` is only about how the person earns money; studying
   is `facts.EDUCATION`. A working student has `PART_TIME` + `MASTER_IN_PROGRESS`.

## 5. Identities that are checked (`check_statement`)

- `sum(income) − sum(expenses) − loan payment − financing cost = net`
- `cash(t−1) + net = cash(t)`

Current result: **1,200 profiles × 24 simulated months, 0 errors** (`simulate.py --validate`); every month of a
`--play` game adds up too; Kotlin decodes the Python statements (`SimulationContractTest`).

## 6. How to generate them

```
python tools/simulate.py --profile P00001 --dump tools/out/simulated-P00001.json   # opening + 120 simulated months
python tools/simulate.py --validate                         # every profile, 24 months, identities
python tools/validate_events.py --play P00001 --choices A,B --dump tools/out/P00001-game.json
                                                            # one statement per turn of a game with events
```

## 7. What the dataset lacks (and how we cover it)

| Missing | Solution |
|---|---|
| Loan payments | 60-month annuity (assumption) |
| Investment returns | Default 0 % until `content/assumptions.json` |
| Taxes | Not modelled (documented limitation) |
| How assets and debts evolve over time | Only an opening snapshot; the simulator makes them evolve |
| Inflation, pay rises, rent rises, career progression | Public Swiss data in `macro.json` (`docs/simulator.md` section 4) |
