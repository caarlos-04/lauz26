# Simulator — how a FinancialStatement moves forward in time

> Owner: Dev A (`polma7`). Summary in `docs/README.md` section 7. Python reference: `tools/flow_model.py`,
> `tools/macro.py`, `tools/simulate.py` (monthly step), `tools/game.py` (events and turns); tests in `tools/test_*.py`.
> Kotlin input classes: `shared/src/commonMain/kotlin/com/lauzhack/twin/model/Simulation.kt`.

## 1. Idea

The simulator takes the `FinancialStatement` of month *t* and returns the one of month *t+1*
(`docs/financial-statement.md`). It does not compare the person with a few similar profiles: it uses **rules of
change learned from the 1,200 profiles × 24 months** of the dataset ("people who study in Geneva spend this much on
food", "employed people get a December bonus 57 % of the time") and **trends from public Swiss data** (prices, wages,
rents, career by age and occupation). Every step reads **every field that describes the person**: the categorical
ones (work, studies, occupation, housing, family, city, canton, household) and the numeric ones (age, income, rent,
cash, debt, investments); the data decide which ones change behaviour.

The model **predicts the month's flows, never the balances**: cash is `cash(t−1) + net`, and net worth or goal progress
are computed from the statement when needed, so the identities always hold.

**Events only change fields of the statement** (employment, city, studies, income, event expenses, a loan), through
`pendingChanges` applied at the start of their month. The next monthly steps read those
fields and the learned flows and trends react: moving from Neuchâtel to Geneva raises living costs by 29 %, starting
a job switches the December bonus on, starting a master's adds tuition in February and September, a promotion to
SENIOR moves the pay onto the management career curve.

## 2. One monthly step

```
statement(t) ──► 0. pending changes due this month      events → person, income, event expenses, a new loan
             ──► 1. trends, every month                  macro.json → income (wages × career for age and
                                                         occupation) and rent grow 1/12 of a year
             ──► 2. flows of the month                   flow-model.json + every categorical and numeric
                                                         field of the state → flow lines
             ──► 3. loans, financing, investments        engine rules (annuity, 8.5 % on negative cash, returns)
             ──► 4. accounting                           cash(t+1) = cash(t) + net
             ──► statement(t+1)
```

Who changes each field between *t* and *t+1*:

| Field | Changes? | Changed by |
|---|---|---|
| `month`, `period`, `person.age` | Always | The clock |
| `person.employment`, `household`, `facts`, `flags` | Only through events | `pendingChanges` |
| `monthlyIncomeChf` | Every month | `wageGrowthAnnual` × career step for age and occupation (macro, career only while working) — and events |
| `monthlyRentChf` | Every month | `rentGrowthSittingTenantAnnual` (macro) — and events |
| `eventExpensesChf` | Only through events | `pendingChanges` (the amount the event sets; not indexed, so its end cancels exactly) |
| `cashFlow` | New every month | **Flow model** (+ income, rent, event expenses, loan, financing) |
| `investmentsChf` | Every month | `investmentReturnAnnual` (assumption) |
| `loan` | Every month | Annuity payment — and events (a new loan is merged) |
| `cashChf` | Every month | Accounting: `cash + net` |

## 3. The flow model (`tools/out/flow-model.json`)

### What it learns

For every ledger category (15, from `synthetic-transactions.zip`):

```
flow(person, month) = [month is eligible] × Bernoulli(probability(x)) × amount(x) × basis
probability(x) = base × Π factor_k[x_k]          amount(x) = base × Π factor_k[x_k]
basis = 1 CHF | the person's income | the person's rent
```

Candidate features `x` (15): **every field of the statement that describes the person** (`flow_model.person_features`,
the same function in training and in the simulator, read at the start of each month):
- categorical: `studying` (EDUCATION ends in `_IN_PROGRESS`), `employment`, `canton`, `household`, `city`,
  `education`, `occupation`, `housing`, `family`;
- numeric, as bands (`meta.bandEdges`: fixed for age and income, dataset quintiles for the rest): `ageBand`,
  `incomeBand` (active income streams), `rentBand` (RENT streams), `cashBand`, `debtBand`, `investmentsBand`
  (balance sheet).

Numeric fields also drive the month **mechanically**, whatever the selection says: salary and rent come from their
streams, the bonus is a share of income, loans are paid from the debt, negative cash pays interest, investments grow.

**Feature selection.** Forward selection on profile-level rows (one row per profile, so 24 months of the same person
do not count as 24 observations). A feature can enter only if a partial F-test gives p < 10⁻⁶ **and** it moves the
value by at least 3 %. Among those, the lowest **BIC** wins (fit minus a penalty per extra level): some fields carry
the same information in the dataset (the students are exactly the not-working people; occupation is derived from
employment), but the game can change them separately, so the simplest explanation must win (studying, not
employment, for the student discount; employment, not occupation, for the bonus, because part-time workers get none).
The JSON reports every field for every flow (`selection`), so we can show *why* a field is in or out.

**Reference person and unknown levels.** Factors are relative to the most common level (factor 1), so `base` is the
value for a reference person. A level never seen (city `ABROAD`) uses `unknown[feature]`, the national average.

### What it found (all 1,200 profiles)

| Flow | Months | Probability | Amount | Depends on |
|---|---|---|---|---|
| salary | every | 1 | 0.997 × income (±8 % monthly noise) | — |
| bonus | December | 0.57 if employed or self-employed, else 0 | 0.59 × income (0.2–1.0) | employment |
| rent | every | 1 | 1.00 × rent | — |
| health insurance | every | 1 | 375 CHF | — |
| groceries / restaurants / retail / leisure / pharmacy | every | 1 / 0.83 / 0.75 / 0.75 / 0.67 | 315 / 121 / 147 / 109 / 47 CHF in Neuchâtel | **city**, **studying** (−18 to −21 %) |
| utilities | every | 1 | 128 CHF in Neuchâtel | city |
| transport / telecom | every | 1 | 132 / 63 CHF | — |
| tuition | Feb, Sep | 1 if studying, else 0 | 869 CHF | studying |
| travel | Feb, Aug | 0.43 | 868 CHF | — |
| unexpected | every | 0.07 | 950 CHF (120–1,800) | — |

City factor (groceries; the same pattern in every living category): Fribourg 0.94 · St Gallen 0.96 · Biel 0.97 ·
Neuchâtel 1.00 · Lugano 1.04 · Bern 1.08 · Lucerne 1.10 · Lausanne 1.11 · Winterthur 1.13 · Basel 1.13 · Geneva 1.29 ·
Zurich 1.29. The city explains more than the canton (Biel ≠ Bern, Winterthur ≠ Zurich), so the canton is left out.
**Of the 15 fields, only city, studying and employment change behaviour**: age, household, income, rent, cash, debt,
investments, education level, occupation, housing and family change nothing in this dataset (their tests are in
`selection`; e.g. for groceries cash has p = 0.81 and a 0.3 % effect). Within a person, spending does not react to
the cash at the start of the month either (correlation −0.003).

This also explains the patterns seen before (`docs/README.md` section 13): the −CHF 300 in February and August is
travel; the December bonus is 57 % × 0.59 ≈ 0.34 × income on average; the fat-tailed "noise" is unexpected expenses
and travel.

### Uncertainty (kept for later)

Each flow keeps `residualDeciles` (spread of one occurrence), `residualMeanSquare` and `personLevelSd` (how much a
person's own level differs from the prediction; month-to-month noise of few occurrences is removed). The game uses
`mode = "expected"` (one state per decision); `mode = "sample"` draws occurrences and amounts and is ready for
pessimistic/optimistic cases or bands.

### Unknown situations

A level the data never saw (city `ABROAD`) uses the national average (`unknown`). The engine should mark those
months so the UI can say "no data for this situation; national average used".

## 4. Trends (`tools/out/macro.json`)

The ledger has no trend (2026 = 2025), so growth comes from public data (`data/download.sh`, sources in
`data/MANIFEST.tsv`). Applied **every month** from the second one, as `(1 + yearly rate)^(1/12)` (compounded: after
12 months the stream has grown by exactly the yearly rate).

| Trend | Value | Origin | Source | Status |
|---|---|---|---|---|
| `priceInflationAnnual` | 0.61 % (p10 −0.48 %, p90 2.1 %) | OBSERVED | World Bank CPI, Switzerland, mean 2000-2025 | Applied to learned flows and CPI streams |
| `wageGrowthAnnual` | 1.17 % (p10 0.58 %, p90 2.1 %) | OBSERVED | BFS Swiss wage index (nominal), mean 2000-2025 | Applied to `WAGES` streams (everyone) |
| `rentGrowthSittingTenantAnnual` | 1.39 % (p10 0.57 %, p90 2.5 %) | OBSERVED | BFS rent price index (LIK), mean 2000-2025 | Applied to RENT while the person does not move |
| `careerCurveByAge` | 5 curves by professional position (below) | OBSERVED (+ flat after the peak and occupation mapping: ASSUMED) | BFS wage structure survey (LSE) 2024, median by age group and position | Applied to `WAGES` streams **only while working**, on the curve of the person's occupation |
| `safeReturnAnnual` | 0.12 % | OBSERVED | SNB, 10-year Confederation bond, last 10 years | Reference only |
| `jobLossRiskByAge` | 8.0 % under 25, 4.0 % 25-74 | OBSERVED | Eurostat `une_rt_m`, Switzerland, 5-year mean | For event probabilities |
| `homePriceGrowthAnnual` | 3.8 % (10-30 y: 2.9-3.8 %) | OBSERVED | BIS residential property prices, Switzerland | For housing events/goals |

**Pay each month** (while working) = `pay × (1 + wageGrowthAnnual)^(1/12) × curve(age + 1 month) / curve(age)`,
on the curve of the person's `OCCUPATION`:

| Occupation (facts.json) | BFS curve | 18 | 25 | 35 | 45 | 57 (peak) |
|---|---|---|---|---|---|---|
| `JUNIOR` | no management function | 4,727 | 5,515 | 6,529 | 6,696 | 6,881 |
| `PROFESSIONAL` (and default) | all positions | 4,734 | 5,623 | 7,055 | 7,628 | 7,820 |
| `SENIOR` | upper/middle management (1+2) | 5,000 | 6,945 | 9,595 | 11,729 | 12,321 |

`macro.json` also has the curves for lower and lowest management. The mapping of our occupation levels to BFS
positions is an assumption (shown). The wage index is the economy-wide rise (everyone); the career curve is the
person's own progression. Each curve comes from one survey year (a cross-section), so it contains no economy-wide
growth and the two do not double count. Only ratios of the curve are used, so the person keeps their own level
(gross vs net and full-time vs part-time do not matter). After the peak (57) curves stay flat: the lower median after
64/65 reflects who still works, not pay cuts.

Example, a new 22-year-old employed in Lausanne (`PROFESSIONAL`, CHF 4,200 pay, CHF 1,300 rent), no events, 10 years:
pay 4,200 → 5,948 (+42 %: wage index and career), rent 1,300 → 1,491 (+15 %), living costs 1,497 → 1,590 (+6 %).
The same person at 25 with CHF 6,000: after 8 years JUNIOR 7,556 · PROFESSIONAL 7,929 · SENIOR 8,597.

`macro.json` also keeps the full series (CPI, GDP, unemployment, wages, rents, wages by age, CHF/EUR and CHF/USD,
policy rate, bond yields, property prices) with provider, URL and retrieval date. Not available: equity returns
(FRED failed, US data anyway) and market rents for new leases by city (needed when an event moves the person).

## 5. Validation

| Check | Result |
|---|---|
| Out-of-sample (train 960 profiles, predict 240 from the opening state only, 24 months) | median abs. error of closing cash **CHF 1,214**; p10-p90 coverage **85 %** (target 80 %; slightly wide because of the fat tails) |
| `simulate.py --validate` (1,200 profiles × 24 months) | 0 identity errors; median abs. error CHF 1,275 |
| `test_simulator.py` (14 tests) | fitting recovers known factors; selection drops irrelevant fields and picks the simplest of two equivalent ones; identities over 10 years with a loan and negative cash; tuition only Feb/Sep when studying; bonus only when employed; moving city changes the factors; trends compound monthly; career by age and occupation, only while working, flat after the peak; sampled months average to the expected month |

Say it in the demo: the data are synthetic, so a good score means the model recovered the generator; beyond month 24
growth comes from public macro data and explicit assumptions.

## 6. What the Kotlin engine needs

- Load `flow-model.json` and `macro.json` (both small: ~48 KB and ~12 KB) with the classes in
  `shared/src/commonMain/kotlin/com/lauzhack/twin/model/Simulation.kt` (`FlowModel`, `MacroFile`), using
  `Json { ignoreUnknownKeys = true }`. `SimulationContractTest` checks that the files and a simulated statement dump
  (`simulate.py --dump tools/out/simulated-P00001.json`) decode and balance.
- Features: `FlowFeature` (every categorical and numeric field of the statement, see `Simulation.kt`).
- `predictRaw(base, factors, unknown, features)`: product of factors; a level not in a map uses `unknown[feature]`.
- `step(statement) → statement` in the order of section 2 (`tools/simulate.py:step` is the reference).
- Cash-flow keys: income `salary`, `bonus`, `loan`; expenses `rent`, the learned flow keys (`groceries`, `travel`...)
  and the event expense categories (`TUITION`, `OTHER`...).

## 7. Rules agreed with this design

1. **Tuition is never counted twice.** The model adds the dataset students' tuition (CHF 869 in February and September)
   while `studying`. If an event adds a `TUITION` expense (e.g. a master's), it replaces the learned tuition for as long
   as it lasts.
2. **Events change spending with `expense_delta`** (an `eventExpensesChf` entry); learned living costs are never stored,
   they come from the flow model every month.
3. **Moving** changes `facts.CITY`; the canton follows from the city (`cityCanton`). A city without data (`ABROAD`) uses
   the national average.
4. **Engine rules without data** (financing rate 8.5 %, investment return 0 %) are defaults in `tools/simulate.py`
   until `content/assumptions.json` (Dev C) exists.
5. **Generated files** (`flow-model.json`, `macro.json`) live in `tools/out/` (git-ignored); whether to commit them is
   open question 4 of `docs/README.md`.
