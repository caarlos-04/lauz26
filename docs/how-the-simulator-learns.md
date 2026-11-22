# How the simulator learns from the data

> Owner: Dev A (`polma7`). This page explains **where `step()` comes from**: how the 1,200 synthetic profiles and the
> public Swiss statistics become the function that turns the `FinancialStatement` of month *t* into the one of month
> *t+1*. `docs/simulator.md` is the reference (fields, tables, rules); this page is the story behind it.
> Code: `tools/flow_model.py` (learning), `tools/macro.py` (trends), `tools/simulate.py` and
> `shared/.../engine/Simulator.kt` (the step). Every number below comes from `tools/out/flow-model.json`,
> `tools/out/macro.json` and `tools/out/flow-backtest.json` as generated today.

## 0. In one paragraph

We do not learn "what happens to people like you" by finding similar people. We learn **rules**: for each kind of
money that moves in a month (salary, rent, groceries, travel, a December bonus...), *how likely it is to happen* and
*how big it is*, as a function of who the person is. The rules are multiplicative ("Geneva costs 29 % more than
Neuchâtel", "students spend 21 % less on food"), fitted to the transaction ledger, and a statistical test decides
which of the person's 15 fields are allowed into each rule. What the dataset cannot teach (it is two flat years, with
no growth) comes from public Swiss data: prices, wages, rents and the career curve by age. `step()` is these pieces
glued together, plus plain accounting: **cash(t+1) = cash(t) + what came in − what went out**.

```
 synthetic-transactions.zip ─┐
 synthetic-profiles.csv ─────┼─► flow_model.py ─► flow-model.json ─┐
 synthetic-cash-balances.csv ┘   (learn the rules)                 │
                                                                   ├─► step(statement t) = statement t+1
 BFS / World Bank / SNB data ───► macro.py ──────► macro.json ─────┤
                                 (yearly trends)                   │
 events (content/events.json) ──► pendingChanges in the statement ─┘
```

## 1. The question we answer

`step()` must work for a person **who is not in the dataset** (the player, built from the interview) and in
situations **the dataset never shows** (a student who becomes a senior employee in Zurich eight years later). That
rules out two simple ideas:

- **Nearest neighbours** ("copy the profile most like you"): a neighbour is a fixed person; when the player moves to
  Geneva or finishes their studies, the neighbour does not. We would have to jump between neighbours, and the money
  would jump with them.
- **Predicting the balance directly** ("cash in 2 years ≈ f(cash today)"): it breaks accounting (net worth would not
  equal cash + investments − debt) and cannot say *why* the money changed, which the results screen needs.

So the model predicts **the flows of one month**, from the state at the start of that month, and the balance is only
ever the running sum. Because the flows are read again every month from the current fields, any change in the
statement (a move, a new job, the end of a master's) changes the following months automatically.

## 2. What the dataset gives us, and what it does not

| File | What it has | What we use it for |
|---|---|---|
| `synthetic-profiles.csv` | 1,200 people: age, city, employment, household, opening cash, reference income and rent | Who each person is (the features) |
| `synthetic-transactions.zip` | Every transaction of 24 months (2025-01 .. 2026-12), with a category | **What we learn from**: the flows |
| `synthetic-cash-balances.csv` | Opening, net and closing cash per person and month | Checking the model (backtest) |
| `synthetic-accounts/assets/liabilities/goals.csv` | Balance sheet and goals at the start | The opening statement |

What the data **cannot** teach, and where it comes from instead:

| Missing in the data | Why | Taken from |
|---|---|---|
| Growth of wages, rents, prices | 2026 looks exactly like 2025 | Public Swiss series (section 9) |
| Pay rising with age and seniority | Each person's income is flat for 24 months | BFS wage structure survey (section 9) |
| Loan repayments | `monthly_payment_chf` is always 0 | Annuity over 60 months (assumption) |
| Interest on negative cash | Not modelled in the data | 8.5 %/year, the highest rate in `synthetic-liabilities.csv` |
| Investment returns | Asset values never move | 0 % for now (assumption, `EngineRules`) |
| How people react to their own cash | Spending does not depend on it (correlation −0.003) | Nothing: we do not invent it |

## 3. From raw data to training rows

### 3.1 Who the person is: the features

Each profile is turned into the **same opening `FinancialStatement`** the game uses (`statement.dataset_opening`), and
from it the 15 candidate features are read with **one function shared by training and simulation**
(`flow_model.person_features` in Python, `Simulator.features` in Kotlin). This is the bridge that lets a model learned
on dataset people run on the player:

| Kind | Features |
|---|---|
| Categorical | `studying` (EDUCATION ends in `_IN_PROGRESS`), `employment`, `canton`, `household`, `city`, `education`, `occupation`, `housing`, `family` |
| Numeric, as bands | `ageBand` (18/25/35/45/55/65), `incomeBand` (2,500/5,000/7,500/10,000), `rentBand`, `cashBand`, `investmentsBand` (dataset quintiles), `debtBand` (none vs quartiles of those with debt) |

Numbers become bands so that they can enter the same multiplicative rule as the categories ("people with more than
113,000 CHF cash spend x % more on restaurants" would be a factor of the band `113000+`).

### 3.2 What happened: one number per person, month and category

The ledger is summed to **(person, month, category) → CHF**. There are 15 categories; each one is mapped to a flow of
the statement with a direction (in/out) and a **basis**, the quantity its size is measured against:

| Basis | Flows | Why |
|---|---|---|
| The person's income | salary, bonus | A bonus is "59 % of a month's pay", not "3,000 CHF": the rule then works for any salary |
| The person's rent | rent | Rent is a field of the statement; the flow only checks it is paid |
| CHF | everything else | Groceries do not scale with income in this dataset (the test says so: section 6) |

### 3.3 When it can happen: eligible months

A category is possible in a calendar month if that month holds at least 1 % of its occurrences. This is how the model
finds, without being told, that **tuition** is paid in February and September, **travel** in February and August and
the **bonus** only in December. In the other months the flow is simply zero.

### 3.4 Two questions per flow (a "hurdle" model)

Many flows are zero most of the time: travel happens in 43 % of the eligible months, an unexpected expense in 7 %, a
bonus is either about 59 % of a pay check or nothing. Averaging the zeros into the amount would describe nobody, so
every flow is split in two questions, each with its own rule:

```
flow(person, month) = [month eligible] × happens? × amount × basis
                        happens?  ~ Bernoulli( probability(x) )
                        amount    = size relative to the basis, when it happens
```

### 3.5 One row per person, not per month

The 24 months of one person are not 24 independent observations: the same person shops in the same city every month.
Counting them separately would make every difference look 24 times more certain than it is and let noise into the
model. So each flow gets **one training row per profile**:

- for `probability`: the share of eligible months in which it happened, weighted by the number of eligible months;
- for `amount`: the mean size of the occurrences (divided by the basis), weighted by the number of occurrences.

## 4. The shape of the rule: multiplicative

Both questions use the same form:

```
probability(x) = base × factor_city[x.city] × factor_studying[x.studying] × ...
amount(x)      = base × factor_city[x.city] × factor_studying[x.studying] × ...
```

Why multiplicative (a log-linear model) and not, say, a tree or a neural network:

- **It matches the data.** The city effect on groceries, restaurants, retail, leisure, pharmacy and utilities is the
  same percentage in every category; students spend about 20 % less in all of them. Percentages, not fixed amounts.
- **It generalises to combinations the data never saw.** A student in Zurich is "Zurich factor × student factor" even
  if few such people exist.
- **It is readable.** Every number in the JSON means "x % more or less than the reference person", which the UI and
  the chat can repeat without inventing anything.
- **It is cheap and portable.** The model is a JSON of numbers; Kotlin evaluates it in a few multiplications on every
  platform, with the exact same result as Python.

## 5. Fitting the numbers: backfitting

For the features already chosen, the factors are found by **backfitting** (iterative proportional fitting): start
with every factor at 1 and the base at the average; then, one feature at a time, set each level's factor so that the
model's total for that level equals the observed total; repeat 30 times. Each round moves the fit closer, and for
this kind of model the result is the same as a Poisson log-linear maximum-likelihood fit. Zeros are allowed (a
factor of 0 means "never": a part-time worker never gets the bonus).

A small example with the real shape of groceries (CHF per month, four groups of equal weight):

| | not studying | studying |
|---|---|---|
| Neuchâtel | 315 | 249 |
| Geneva | 406 | 321 |

1. Base = mean = 322.75.
2. City: Neuchâtel = (315 + 249) / (2 × 322.75) = 0.874; Geneva = (406 + 321) / 645.5 = 1.126.
3. Studying, given the city factors: no = (315 + 406) / (322.75 × (0.874 + 1.126)) = 1.117; yes = 0.883.
4. Check: Geneva, studying = 322.75 × 1.126 × 0.883 = 321. ✓ (exactly multiplicative data fit in one round; real data
   need a few more.)
5. **Re-reference** to the most common person (Neuchâtel, not studying): base = 322.75 × 0.874 × 1.117 = **315**,
   Geneva = 1.126 / 0.874 = **×1.29**, studying = 0.883 / 1.117 = **×0.79**.

These are, to two decimals, the numbers learned from the 1,200 profiles: groceries base **314.74 CHF**, Geneva
**×1.287**, Zurich ×1.292, Fribourg ×0.941, studying **×0.792**.

## 6. Choosing which fields matter: forward selection

Fitting every field into every rule would "learn" noise (with 12 cities × 6 employments × 5 income bands... there is
always some random difference). Each rule starts empty and adds **one field at a time**:

1. For every field not yet in the rule, fit the rule with it added.
2. **Is the improvement real?** A partial F-test compares the error with and without the field, accounting for how
   many extra numbers it adds (one per level). It must give **p < 10⁻⁶**: very strict on purpose, because we test 15
   fields × 30 rules.
3. **Is it big enough to matter?** Some level holding at least 2 % of the people must move the value by at least
   **3 %**. A significant but tiny effect would only add clutter.
4. Among the fields that pass both, the one with the lowest **BIC** (error plus a penalty per extra level) enters.
5. Repeat until no field passes.

Step 4 matters because some fields **carry the same information in the dataset but not in the game**: the students are
exactly the not-working people, and occupation is derived from employment. In the data, "students spend less" and
"not-working people spend less" fit equally well, but in the game a student may take a part-time job. BIC picks the
simpler explanation (`studying`, 2 levels, rather than `employment`, 6 levels) unless the richer one really fits better:
the bonus needs `employment`, because part-time workers get none.

The test for every field is kept in the JSON (`selection`), so we can always say *why* a field is in or out. Groceries,
for example:

| Field | Kept | p-value | Largest effect |
|---|---|---|---|
| city | **yes** | ≈ 0 | 19 % |
| studying | **yes** | ≈ 0 | 20 % |
| household | no | 0.12 | 0.7 % |
| incomeBand | no | 0.91 | 0.3 % |
| cashBand | no | 0.81 | 0.3 % |
| ageBand | no | 0.97 | 0.3 % |
| canton | no | 1.0 | 0.7 % (the city already says it, better) |
| family / housing / occupation / ... | no | > 0.07 | < 1 % |

The bonus is the closest call: `cashBand` reaches p = 0.0001 with a 14 % effect, strong-looking but far from the
10⁻⁶ bar, so it stays out.

**What the selection found across all 15 flows and 30 rules: only three fields change behaviour.**

| Flow | Months | Happens | Size | Depends on |
|---|---|---|---|---|
| salary | all | always | 0.997 × income | — |
| bonus | December | 57 % if employed (55 % self-employed), never otherwise | 0.59 × income | employment |
| rent | all | always | 1.00 × rent | — |
| health insurance | all | always | 375 CHF | — |
| groceries, restaurants, retail, leisure, pharmacy | all | 100 / 83 / 75 / 75 / 67 % | 315 / 121 / 147 / 109 / 47 CHF (Neuchâtel) | **city**, **studying** |
| utilities | all | always | 128 CHF (Neuchâtel) | city |
| transport, telecom | all | always | 132 / 63 CHF | — |
| tuition | Feb, Sep | always if studying, never otherwise | 869 CHF | studying |
| travel | Feb, Aug | 43 % | 868 CHF | — |
| unexpected | all | 7 % | 950 CHF | — |

Age, household, income, rent, cash, debt, investments, education level, occupation, housing and family were all tested
and **rejected by the data**. That is a finding about the synthetic generator, not about real life; with real data the
same code would keep whatever matters.

## 7. The reference person and situations never seen

After fitting, each feature is expressed against its most common level (factor 1), so `base` is the value for a
reference person (groceries: someone in Neuchâtel, not studying) and every factor reads as "x % more or less".

A level the data never saw (a city abroad, a new combination) gets `unknown[feature]`: the **national average** of that
feature's factors, weighted by how many people have each level. Groceries for someone in an unknown city: ×1.084.

## 8. Uncertainty, kept for later

The rule gives the *expected* flow. For each flow we also keep:

- `residualDeciles`: how one occurrence varies around the prediction (groceries: 27 % to 197 % of the expected value
  from the 0th to the 100th percentile; travel: 11 % to 184 %);
- `personLevelSd`: how much one person's own level differs from the prediction, with month-to-month noise removed
  (almost zero: the generator gives everyone in the same group the same habits).

The game today runs in `EXPECTED` mode (one state per decision, deterministic). `SAMPLE` mode draws "does it happen"
and "how big" from these distributions and is ready for uncertainty bands (`noiseBand`).

## 9. Market rent: a second, smaller model

When an event moves the person (new city, shared flat, own flat), the old rent no longer applies. The same algorithm
(rows = the 1,200 reference rents, same 15 candidates minus the rent band itself) learned a **rent model**: it keeps
**household** and **city**.

- Base: single person in Neuchâtel, **1,574 CHF**.
- Household: shared flat ×0.68, couple ×1.46, family ×1.45.
- City: Geneva ×1.27, Zurich ×1.25, Lucerne ×1.14, St Gallen ×0.93.
- Spread (deciles of actual / predicted): 0.52 to 1.42.

The event says at which quantile the new flat falls (a cheaper flat = a low quantile). The rent is then indexed to the
current year with the rent trend.

## 10. What the dataset cannot teach: trends from public data

The ledger is flat for two years, so all growth over the 10-year game comes from public Swiss statistics, processed by
`tools/macro.py` (sources and URLs in `data/MANIFEST.tsv`). Each trend is a long-run average (2000-2025, including the
2008 and 2022 shocks), with p10/p90 kept for a future pessimistic/optimistic band:

| Trend | Yearly value | How it is obtained | Applied to |
|---|---|---|---|
| Prices | 0.61 % (p10 −0.48 %, p90 2.1 %) | Mean yearly Swiss CPI inflation, World Bank | Every learned flow measured in CHF (groceries, insurance, travel...) |
| Wages | 1.17 % (p10 0.58 %, p90 2.1 %) | Mean of the quarterly year-on-year changes of the BFS Swiss wage index | Income of everyone |
| Rents | 1.39 % (p10 0.57 %, p90 2.5 %) | Mean yearly change of the BFS rent index (mostly sitting tenants) | Rent, while the person stays put |
| Career | Curve by age | BFS wage structure survey (LSE) 2024: median wage per age group and professional position | Income, **only while working** |

**The career curve.** The LSE gives a median wage per age group (under 20, 20-29, ..., 50-64) and position. Each group
is placed at its middle age (18, 25, 35, 45, 57), straight lines join them, and the curve stays flat after 57 (older
medians are lower because of who still works, not because pay falls). The person's `OCCUPATION` picks the curve
(the mapping is an assumption): JUNIOR → no management, PROFESSIONAL → all positions, SENIOR → management.

| Curve | 18 | 25 | 35 | 45 | 57 |
|---|---|---|---|---|---|
| no management (JUNIOR) | 4,727 | 5,515 | 6,529 | 6,696 | 6,881 |
| all positions (PROFESSIONAL) | 4,734 | 5,623 | 7,055 | 7,628 | 7,820 |
| management (SENIOR) | 5,000 | 6,945 | 9,595 | 11,729 | 12,321 |

Only **ratios** of the curve are used: each month pay is multiplied by `curve(age + 1 month) / curve(age)`. The person
keeps their own level (a part-time student at 1,200 CHF is not lifted to the national median), and gross vs net does not
matter. The curve comes from one survey year (a snapshot across ages), so it contains no economy-wide growth and does
not double count the wage index.

**Monthly, compounded.** Every yearly rate *r* is applied every month as `(1 + r)^(1/12)`, so after 12 months the value
has grown by exactly *r*.

## 11. Putting it together: `step()`

```
step(statement at month t):
  0. apply the pendingChanges due this month        (events: new job, move, pay change, expense, loan)
  1. trends:   income ×= wages^(1/12) × career(age)/career(age − 1 month)    (career only if working)
               rent   ×= rents^(1/12)        or the market rent if the person moved
  2. features = person_features(statement)          (the same function used in training)
     for every flow eligible in this calendar month:
        value = probability(features) × amount(features) × basis
        basis = income | rent | 1 CHF × price index (prices since the start)
     + event expenses, + cash from a new loan
  3. loan: interest, payment, new principal;  financing cost if cash < 0;  investments × (1 + return/12)
  4. cash(t+1) = cash(t) + income lines − expense lines − loan payment − financing cost;  age +1 every 12 months
  → statement at month t+1, with its cashFlow lines (used for the story and the attribution)
```

A turn of the game is 24 of these steps; the decision only adds `pendingChanges` before them.

### A real month, line by line

Yara (`P00004`): 24, employed, single, Fribourg, PROFESSIONAL, pay 5,206.83 CHF, rent 1,403.82 CHF, 41,807 CHF cash.
First month (January 2025, no trend yet):

| Line | Calculation | CHF |
|---|---|---|
| salary | 1 × 0.9966 × 5,206.83 | +5,189.13 |
| rent | 1 × 1.0001 × 1,403.82 | −1,403.96 |
| health insurance | 1 × 374.85 | −374.85 |
| groceries | 1 × 314.74 × 0.941 (Fribourg) × 1 (not studying) | −296.17 |
| restaurants, retail, leisure, pharmacy | same form | −302.24 |
| utilities, transport, telecom | same form | −314.55 |
| unexpected | 0.0694 × 950.30 | −65.95 |
| **net** | | **+2,431.41** |

Cash goes from 41,807.00 to 44,238.41. In February travel appears (0.4271 × 867.64 × price index = −370.76); in
December the bonus (0.5682 × 0.5932 × 5,374.27 = +1,811.43, by then pay has grown with wages and career).

## 12. How we know it works

| Check | Result |
|---|---|
| **Out of sample**: learn on 960 profiles, predict the other 240 for 24 months from their opening state only | Median error of closing cash **1,214 CHF**; the p10-p90 band holds the real value **85 %** of the time (target 80 %) |
| Yara, 24 months, no macro trends (the dataset has none) | Predicted closing cash 102,189 vs real **103,227** (−1.0 %) |
| `simulate.py --validate`: all 1,200 profiles × 24 months | 0 accounting errors; median error 1,275 CHF |
| `test_simulator.py` | Fitting recovers factors planted in fake data; selection drops planted noise and picks the simplest of two equivalent fields |
| Kotlin vs Python (`GoldenGameTest`, 13 full games covering every event) | Largest difference **0.00 CHF** |

## 13. Limits, said honestly

- **The data are synthetic.** A good score means we recovered the generator, not that we predict real Swiss people.
  The method would carry over to real data; the numbers would not.
- **Only city, studying and employment change spending.** Income, age or family size do not, because the generator
  does not make them. A richer dataset would let the same selection keep more fields.
- **Everything beyond month 24 is trends plus assumptions.** Wage, rent and price growth and the career curve are
  public averages; loan terms, the financing rate and the 0 % investment return are explicit assumptions.
- **Categorical fields only change through events.** Nobody moves or changes job "by trend"; only the game's
  decisions do that (by design: the player's choices are the story).
- **Deterministic today.** One expected path per decision; the uncertainty is learned but not yet shown.

## 14. Reproduce it

```
bash data/download.sh            # public macro data (data/raw/, not committed)
python tools/flow_model.py       # → tools/out/flow-model.json, flow-backtest.json
python tools/macro.py            # → tools/out/macro.json
python tools/simulate.py --validate
(cd tools && python -m unittest)
./gradlew :shared:jvmTest        # Kotlin engine, including the golden games
```
