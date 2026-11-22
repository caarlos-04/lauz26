# Events — from the scenario catalogue to measurable events

> Since `content/events.json` version 4 these 15 events are a **pool of actions** the player chooses from
> ([`decision-tree.md`](decision-tree.md)): option A of each event became an action (`A-...`), the "stay as you are"
> options became `NOTHING`, the master's became two actions, the promotions became "Go for a management job in
> Zurich / Geneva", and the job loss became a shock (`S-job-loss`). The analysis below (what is measurable, the
> requirements and the effects) still holds.

> How the 120 scenarios of `scenario-catalog.csv` become game events (`content/events.json`): which ones we keep,
> what each one changes in the `FinancialStatement` and which fields the person needs for it to appear.
> Numbers: `tools/event_data.py` (`tools/out/event-data.json`), every one from the data with its source.

## 1. Rule: only what our data can measure

An event stays only if **every number it changes comes from data we have**: the challenge dataset
(`synthetic-*.csv`, transactions) or the public Swiss data of `data/download.sh` (BFS, SNB, Eurostat, World Bank,
BIS). An event whose effect we would have to make up is dropped, however interesting it is.

Result: **22 of 120 scenarios** are measurable. They become **15 events** (some scenarios merge into one event as an
option or a case, and a few events are the way back of another: losing a job and finding one, going part time and
back). The other **98 are dropped**, each with its reason (section 5).

| Why dropped | Scenarios |
|---|---|
| Another country (Berlin, London, Singapore, remote abroad, FX…) | 9 |
| Owning a home, mortgages, house prices | 8 |
| Investment returns and allocation | 12 |
| Inside a business (funding, staff, shares, exit) | 9 |
| Economy-wide shocks (inflation, rates, energy…): sensitivity of the trends, not a person's event | 6 |
| Saving and spending rules (budgets, automatic saving, buffers) | 9 |
| Credit products not in the data (cards, floating rates, several loans) | 7 |
| Partner, children, pensions and health in old age | 10 |
| No data for the size or the outcome (negotiation, bootcamp, car, wedding, taxes…) | 23 |
| Contradicted by the data, or already in the model (lifestyle inflation, childcare, unexpected expenses, rent rises) | 5 |

## 2. What the data let us measure

| Measured | From | Used by |
|---|---|---|
| Pay by work status: part time 0.56 × employed; self-employed p25/p50/p75 = 0.60/0.94/1.28; retired 0.50; unemployed 0.07 | `synthetic-profiles.csv` | Part time, self-employment, retirement, job loss |
| Pay does **not** depend on the city or the household (not significant) | `synthetic-profiles.csv` | Moving changes costs, never pay |
| Rent depends on **household × city** (shared flat 0.68 × living alone; couple/family 1.46×; Geneva 1.27×, Zurich 1.25×, Fribourg 0.94×) | rent model learned from `synthetic-profiles.csv` | Every move (`market_rent`) |
| Living costs by city and studying; December bonus only for employees and the self-employed | flow model (`synthetic-transactions.zip`) | Moves, part time, studies |
| Tuition 2,500 / 4,800 / 8,000 CHF a year (p25/p50/p75), always 2 years | `scenario-assumptions.csv` (S001) | Master's events |
| Pay by age and management level (management 1.45×, middle 1.30×, lowest 1.08× all positions at 40) | BFS LSE 2024 | Starting salaries, promotion |
| Personal loan rates 3.0 / 5.8 / 8.0 % (p10/p50/p90), median principal 37,000 | `synthetic-liabilities.csv` | Financing |
| Unemployment 8 % under 25, 4 % 25-74 | Eurostat | Context of job loss |

## 3. New effects (data-driven, relative to the person)

Fixed CHF amounts cannot express "part time = 56 % of **your** pay" or "the rent of a flat like yours **in Zurich**".
Three effects were added (`docs/README.md` 5.3, `model/Event.kt`); each becomes a `PendingChange`:

| Effect | What it does | Pending change |
|---|---|---|
| `income_factor` `{factor, months?}` | Income × factor; undone after `months` | `incomeFactor` |
| `set_household` `{household}` | New household (rent model input). The validator requires the `HOUSING` / `FAMILY` facts it implies | `household` |
| `market_rent` `{quantile}` | Rent = rent model for the new city and household, at that quantile of the dataset spread (0.25 cheap, 0.75 expensive), at today's rent level | `marketRentQuantile` |

`requires.household` was added so that events can ask for a household.

## 4. The 15 events

Who can get each event (`requires`) and what it changes. A = the scenario's intervention, B = its baseline.
Cases: pessimistic / neutral / optimistic = p25 / p50 / p75 of the measured spread (or p75/p50/p25 for costs).

| Event | Catalogue | Who (requires) | A changes | B changes |
|---|---|---|---|---|
| `E-master` | S001, S003, S004, S011 | Studying a bachelor's, age ≤ 30 | EDUCATION → MASTER_IN_PROGRESS → MASTER at 24 months; tuition 667/400/208 CHF a month for 24 months; then EMPLOYED, JUNIOR, pay 4,500/6,100/7,500 | EDUCATION → BACHELOR, EMPLOYED, JUNIOR now, pay 4,300/5,800/7,200 |
| `E-part-time-master` | S002 | EMPLOYED, EDUCATION = BACHELOR, age ≤ 40 | PART_TIME, pay × 0.56 for 24 months (no bonus), tuition for 24 months, then EMPLOYED + MASTER | — |
| `E-promotion-zurich` | S013 + S021 | EMPLOYED, OCCUPATION JUNIOR/PROFESSIONAL, age 28-55, not in Zurich | OCCUPATION → SENIOR, pay × 1.08/1.30/1.45, CITY → Zurich, market rent p75/p50/p25 | — |
| `E-promotion-geneva` | S013 + S022 | Same, living in Zurich | Same, to Geneva | — |
| `E-job-loss` | S014, S058, S104 | EMPLOYED, age 20-62 | PART_TIME, pay × 0.56 for 24 months, then EMPLOYED | UNEMPLOYED, pay × 0.07 for 12/6/3 months, then EMPLOYED |
| `E-job-offer` | S014 (way back) | UNEMPLOYED or NOT_WORKING and not studying, age 20-62 | EMPLOYED, PROFESSIONAL now at 5,700 | Same after 12/6/3 months at 5,700/7,700/9,600 |
| `E-four-day-week` | S017 | EMPLOYED, age ≥ 25 | PART_TIME, pay × 0.8 (no bonus) | — |
| `E-back-to-full-time` | S017 (way back) | PART_TIME (not from a four-day week or a job loss), age ≤ 62 | EMPLOYED, pay × 1.79 | — |
| `E-shared-flat` | S032, S049 | Household SINGLE in a rented flat | household → SHARED_HOUSING, HOUSING → SHARED_FLAT, market rent | — |
| `E-own-flat` | S031 | Household SHARED_HOUSING | household → SINGLE, HOUSING → RENTED_FLAT, market rent | — |
| `E-move-cheaper` | S021/S022 (other way) | Living in one of the 7 expensive cities | CITY → Fribourg, market rent | — |
| `E-self-employed` | S111, S015 | EMPLOYED, age 25-55 | SELF_EMPLOYED, pay × 0.60/0.94/1.28 | — |
| `E-back-to-employee` | S015 (way back) | SELF_EMPLOYED, age ≤ 60 | EMPLOYED, pay × 0.79/1.06/1.33 | — |
| `E-retirement` | S091, S092 | Working, age ≥ 63 | RETIRED now, pay × 0.42/0.50/0.58, OCCUPATION NONE | The same in 24 months |
| `E-financing` | S077, S009, S071 | Cash ≤ 0 (priority 10) | Loan of 37,000 at 8.0/5.8/3.0 % over 60 months | Stay overdrawn (8.5 %) |

"—" = nothing changes except a milestone (`add_flag`) for the story. Every case also adds a milestone.

**Coverage** (one game per dataset profile, always A and always B, 2,400 games, 0 accounting errors): employees
1.3 quiet turns out of 5, students 0.7, part time 0.3, self-employed 0.7, unemployed 0.4, retired 3.2. 32 retired
couples who already live in a cheap city get no event: nothing measurable in the catalogue fits them.

## 5. Critical findings (say them in the demo)

1. **The master's has no measurable payoff.** The dataset has no education level and the BFS table we have is by age
   and position, so both paths pay the same at the same age; the master's only costs two years of tuition and income.
   To measure a premium we need the BFS earnings table **by level of education** (not downloaded yet).
2. **The unemployed get almost no income in the dataset** (median 530 CHF, 7 % of an employee). Real Swiss
   unemployment insurance pays 70-80 % for months; it is not in our data, so job loss looks harsher than in reality.
3. **Moving does not change pay** (no significant difference between cities), so moving to Zurich or Geneva only pays
   off together with a promotion. That is why S021/S022 are merged into the promotion events.
4. **Families spend like singles** in the ledger: only rent depends on the household. Partners and children cannot be
   measured (no partner income, no child costs).
5. **Retirement is only observed at 67+.** Nobody younger is retired in the dataset, so the pension does not depend on
   the age you retire, and taking it as a share of your own pay is an assumption.
6. **Gap lengths** (3/6/12 months for job loss and job search) are the only assumptions left in the effects: 6 months
   is the catalogue's own definition (S014), the other two are our pessimistic/optimistic cases.
7. **Candidates if needed**: refinancing and early repayment of a loan (S073, S074) are measurable (rates are in the
   data) but need a new effect; budget cuts (S041) need a way to scale the learned spending.

## 6. All 120 scenarios

| Id | Category | Scenario | Decision | Event, or why not |
|---|---|---|---|---|
| S001 | Education & skills | Full-time master’s | Event | E-master |
| S002 | Education & skills | Part-time master’s | Event | E-part-time-master |
| S003 | Education & skills | Scholarship award | Merged | E-master, optimistic case (tuition p25) |
| S004 | Education & skills | Tuition increase | Merged | E-master, pessimistic case (tuition p75) |
| S005 | Education & skills | Internship year | Dropped | No data for the size or the outcome of the change (no internship pay) |
| S006 | Education & skills | Bootcamp reskilling | Dropped | No data for the size or the outcome of the change (no bootcamp cost or outcome) |
| S007 | Education & skills | Doctoral track | Dropped | No data for the size or the outcome of the change (no PhD pay, no wages by education) |
| S008 | Education & skills | Study abroad | Dropped | Another country: our data (rents, living costs, pay, taxes) only cover 12 Swiss cities |
| S009 | Education & skills | Student loan | Merged | E-financing (personal loan at dataset rates; no student-loan data) |
| S010 | Education & skills | Delayed graduation | Dropped | No data for the size or the outcome of the change (study length is always 2 years in scenario-assumptions) |
| S011 | Career & employment | Early job start | Merged | E-master, option B |
| S012 | Career & employment | Salary negotiation | Dropped | No data for the size or the outcome of the change (no data on negotiation outcomes) |
| S013 | Career & employment | Promotion | Event | E-promotion-zurich / E-promotion-geneva |
| S014 | Career & employment | Unemployment gap | Event | E-job-loss; the way back: E-job-offer |
| S015 | Career & employment | Freelancing | Event | E-self-employed; the way back: E-back-to-employee |
| S016 | Career & employment | Career switch | Dropped | No data for the size or the outcome of the change (no field or sector in the data) |
| S017 | Career & employment | Four-day week | Event | E-four-day-week; the way back: E-back-to-full-time |
| S018 | Career & employment | Equity compensation | Dropped | No data for the size or the outcome of the change (no equity compensation) |
| S019 | Career & employment | Contract work | Dropped | No data for the size or the outcome of the change (no contract types) |
| S020 | Career & employment | Second income | Dropped | No data for the size or the outcome of the change (nobody has two jobs in the data) |
| S021 | Relocation & geography | Move to Zurich | Merged | E-promotion-zurich (moving for the job); E-move-cheaper the other way |
| S022 | Relocation & geography | Move to Geneva | Merged | E-promotion-geneva; E-move-cheaper the other way |
| S023 | Relocation & geography | Move to Berlin | Dropped | Another country: our data (rents, living costs, pay, taxes) only cover 12 Swiss cities |
| S024 | Relocation & geography | Move to London | Dropped | Another country: our data (rents, living costs, pay, taxes) only cover 12 Swiss cities |
| S025 | Relocation & geography | Move to Singapore | Dropped | Another country: our data (rents, living costs, pay, taxes) only cover 12 Swiss cities |
| S026 | Relocation & geography | Remote work abroad | Dropped | Another country: our data (rents, living costs, pay, taxes) only cover 12 Swiss cities |
| S027 | Relocation & geography | Return migration | Dropped | Another country: our data (rents, living costs, pay, taxes) only cover 12 Swiss cities |
| S028 | Relocation & geography | Currency depreciation | Dropped | Another country: our data (rents, living costs, pay, taxes) only cover 12 Swiss cities |
| S029 | Relocation & geography | Dual-currency income | Dropped | Another country: our data (rents, living costs, pay, taxes) only cover 12 Swiss cities |
| S030 | Relocation & geography | High relocation cost | Dropped | No data for the size or the outcome of the change (no moving costs) |
| S031 | Housing & property | Rent alone | Event | E-own-flat |
| S032 | Housing & property | Move in with flatmates | Event | E-shared-flat |
| S033 | Housing & property | Buy starter home | Dropped | Owning a home: the dataset has no property, mortgage or house prices by city |
| S034 | Housing & property | Higher down payment | Dropped | Owning a home: the dataset has no property, mortgage or house prices by city |
| S035 | Housing & property | Mortgage rate reset | Dropped | Owning a home: the dataset has no property, mortgage or house prices by city |
| S036 | Housing & property | Home renovation | Dropped | Owning a home: the dataset has no property, mortgage or house prices by city |
| S037 | Housing & property | Rent increase | Dropped | Already a monthly trend (BFS rent index) |
| S038 | Housing & property | Buy later | Dropped | Owning a home: the dataset has no property, mortgage or house prices by city |
| S039 | Housing & property | Property price decline | Dropped | Owning a home: the dataset has no property, mortgage or house prices by city |
| S040 | Housing & property | Unexpected maintenance | Dropped | Owning a home: the dataset has no property, mortgage or house prices by city |
| S041 | Spending & lifestyle | Budget reduction | Dropped | A saving or spending rule, not a life event, and the model has no mechanism for it (how much people cut is not in the data) |
| S042 | Spending & lifestyle | Car purchase | Dropped | No data for the size or the outcome of the change (no car costs: transport is flat in the ledger) |
| S043 | Spending & lifestyle | Public transport switch | Dropped | No data for the size or the outcome of the change (no car costs: transport is flat in the ledger) |
| S044 | Spending & lifestyle | Travel sabbatical | Dropped | No data for the size or the outcome of the change (no cost of a long trip) |
| S045 | Spending & lifestyle | Subscription audit | Dropped | No data for the size or the outcome of the change (no subscriptions category) |
| S046 | Spending & lifestyle | Lifestyle inflation | Dropped | The data contradict it (spending does not rise with income) |
| S047 | Spending & lifestyle | Large purchase | Dropped | No data for the size or the outcome of the change (price of the purchase) |
| S048 | Spending & lifestyle | Emergency expense | Dropped | Already a monthly flow learned from the data (an event would count it twice) (7 % of months, CHF 120-1,800) |
| S049 | Spending & lifestyle | Shared household | Merged | E-shared-flat |
| S050 | Spending & lifestyle | Energy cost spike | Dropped | No data for the size or the outcome of the change (no energy prices; the size of the spike) |
| S051 | Savings & liquidity | Emergency fund target | Dropped | A saving or spending rule, not a life event, and the model has no mechanism for it |
| S052 | Savings & liquidity | Automated saving | Dropped | A saving or spending rule, not a life event, and the model has no mechanism for it |
| S053 | Savings & liquidity | Savings rate increase | Dropped | A saving or spending rule, not a life event, and the model has no mechanism for it |
| S054 | Savings & liquidity | Short-term cash goal | Dropped | A saving or spending rule, not a life event, and the model has no mechanism for it |
| S055 | Savings & liquidity | Liquidity lockup | Dropped | A saving or spending rule, not a life event, and the model has no mechanism for it |
| S056 | Savings & liquidity | Interest on deposits | Dropped | A saving or spending rule, not a life event, and the model has no mechanism for it (no interest on cash in the model (SNB policy rate 0 %)) |
| S057 | Savings & liquidity | Unexpected windfall | Dropped | No data for the size or the outcome of the change (no gifts or inheritances) |
| S058 | Savings & liquidity | Income interruption | Merged | E-job-loss |
| S059 | Savings & liquidity | Buffer depletion | Dropped | Already a monthly flow learned from the data (an event would count it twice) (unexpected expenses) |
| S060 | Savings & liquidity | Multiple goals | Dropped | A saving or spending rule, not a life event, and the model has no mechanism for it |
| S061 | Investments & allocation | Start investing | Dropped | Investment returns: no market data (only one portfolio value per person, return assumed) |
| S062 | Investments & allocation | Higher equity share | Dropped | Investment returns: no market data (only one portfolio value per person, return assumed) |
| S063 | Investments & allocation | Bond allocation | Dropped | Investment returns: no market data (only one portfolio value per person, return assumed) |
| S064 | Investments & allocation | Fee reduction | Dropped | Investment returns: no market data (only one portfolio value per person, return assumed) |
| S065 | Investments & allocation | Market drawdown | Dropped | Investment returns: no market data (only one portfolio value per person, return assumed) |
| S066 | Investments & allocation | Recovery sequence | Dropped | Investment returns: no market data (only one portfolio value per person, return assumed) |
| S067 | Investments & allocation | Contribution holiday | Dropped | Investment returns: no market data (only one portfolio value per person, return assumed) |
| S068 | Investments & allocation | Rebalancing rule | Dropped | Investment returns: no market data (only one portfolio value per person, return assumed) |
| S069 | Investments & allocation | Concentrated holding | Dropped | Investment returns: no market data (only one portfolio value per person, return assumed) |
| S070 | Investments & allocation | Currency exposure | Dropped | Investment returns: no market data (only one portfolio value per person, return assumed) |
| S071 | Debt & credit | Student loan repayment | Merged | E-financing |
| S072 | Debt & credit | Credit-card balance | Dropped | Credit products not in the data (cards, floating rates, several loans per person) |
| S073 | Debt & credit | Refinance loan | Dropped | Credit products not in the data (cards, floating rates, several loans per person) (rates are in the data, but refinancing needs a new effect: candidate) |
| S074 | Debt & credit | Early repayment | Dropped | Credit products not in the data (cards, floating rates, several loans per person) (needs a repayment effect: candidate) |
| S075 | Debt & credit | Interest rate shock | Dropped | Credit products not in the data (cards, floating rates, several loans per person) |
| S076 | Debt & credit | Debt consolidation | Dropped | Credit products not in the data (cards, floating rates, several loans per person) |
| S077 | Debt & credit | New personal loan | Event | E-financing |
| S078 | Debt & credit | Missed payment | Dropped | Credit products not in the data (cards, floating rates, several loans per person) |
| S079 | Debt & credit | Borrowing cap | Dropped | A saving or spending rule, not a life event, and the model has no mechanism for it |
| S080 | Debt & credit | Variable amortization | Dropped | Credit products not in the data (cards, floating rates, several loans per person) |
| S081 | Family & life events | Partner cohabitation | Dropped | Partner or children: no partner income or child costs in the data |
| S082 | Family & life events | Childcare costs | Dropped | The data contradict it (families spend the same as singles in the ledger) |
| S083 | Family & life events | Parental leave | Dropped | Partner or children: no partner income or child costs in the data |
| S084 | Family & life events | Family support | Dropped | No data for the size or the outcome of the change (no family transfers) |
| S085 | Family & life events | Marriage costs | Dropped | No data for the size or the outcome of the change (cost of a wedding) |
| S086 | Family & life events | Caregiving break | Dropped | No data for the size or the outcome of the change (no caregiving data) |
| S087 | Family & life events | Divorce scenario | Dropped | Partner or children: no partner income or child costs in the data |
| S088 | Family & life events | Dependent education | Dropped | Partner or children: no partner income or child costs in the data |
| S089 | Family & life events | Insurance claim | Dropped | No data for the size or the outcome of the change (no deductibles or claims) |
| S090 | Family & life events | Disability interruption | Dropped | No data for the size or the outcome of the change (no disability benefits) |
| S091 | Retirement & pensions | Retire at 60 | Event | E-retirement |
| S092 | Retirement & pensions | Retire at 67 | Merged | E-retirement, option B |
| S093 | Retirement & pensions | Pension contribution increase | Dropped | Pensions and old-age costs: not in the data |
| S094 | Retirement & pensions | Career gap effect | Dropped | Pensions and old-age costs: not in the data |
| S095 | Retirement & pensions | Retirement relocation | Dropped | Another country: our data (rents, living costs, pay, taxes) only cover 12 Swiss cities |
| S096 | Retirement & pensions | Inflation in retirement | Dropped | Changes the whole economy, not the person: belongs to the sensitivity analysis of the trends |
| S097 | Retirement & pensions | Withdrawal strategy | Dropped | Pensions and old-age costs: not in the data |
| S098 | Retirement & pensions | Longer lifespan | Dropped | Pensions and old-age costs: not in the data |
| S099 | Retirement & pensions | Pension lump sum | Dropped | Pensions and old-age costs: not in the data |
| S100 | Retirement & pensions | Healthcare costs later | Dropped | Pensions and old-age costs: not in the data |
| S101 | Macroeconomic & systemic shocks | High inflation | Dropped | Changes the whole economy, not the person: belongs to the sensitivity analysis of the trends |
| S102 | Macroeconomic & systemic shocks | Deflation episode | Dropped | Changes the whole economy, not the person: belongs to the sensitivity analysis of the trends |
| S103 | Macroeconomic & systemic shocks | Rate hike | Dropped | Changes the whole economy, not the person: belongs to the sensitivity analysis of the trends |
| S104 | Macroeconomic & systemic shocks | Recession | Merged | E-job-loss (the job-risk part of a recession) |
| S105 | Macroeconomic & systemic shocks | FX volatility | Dropped | Changes the whole economy, not the person: belongs to the sensitivity analysis of the trends |
| S106 | Macroeconomic & systemic shocks | Energy shock | Dropped | Changes the whole economy, not the person: belongs to the sensitivity analysis of the trends |
| S107 | Macroeconomic & systemic shocks | Tax policy change | Dropped | No data for the size or the outcome of the change (taxes are not modelled) |
| S108 | Macroeconomic & systemic shocks | Housing downturn | Dropped | Owning a home: the dataset has no property, mortgage or house prices by city |
| S109 | Macroeconomic & systemic shocks | Market liquidity shock | Dropped | Investment returns: no market data (only one portfolio value per person, return assumed) |
| S110 | Macroeconomic & systemic shocks | Compound crisis | Dropped | Investment returns: no market data (only one portfolio value per person, return assumed) (job loss + market drop: the market part) |
| S111 | Entrepreneurship & innovation | Start a business | Event | E-self-employed |
| S112 | Entrepreneurship & innovation | Side project | Dropped | Inside a business (funding, staff, shares): not in the data |
| S113 | Entrepreneurship & innovation | Seed funding | Dropped | Inside a business (funding, staff, shares): not in the data |
| S114 | Entrepreneurship & innovation | Startup failure | Dropped | Inside a business (funding, staff, shares): not in the data |
| S115 | Entrepreneurship & innovation | Revenue delay | Dropped | Inside a business (funding, staff, shares): not in the data |
| S116 | Entrepreneurship & innovation | Hire employee | Dropped | Inside a business (funding, staff, shares): not in the data |
| S117 | Entrepreneurship & innovation | Equity dilution | Dropped | Inside a business (funding, staff, shares): not in the data |
| S118 | Entrepreneurship & innovation | Founder salary | Dropped | Inside a business (funding, staff, shares): not in the data |
| S119 | Entrepreneurship & innovation | Business loan | Dropped | Inside a business (funding, staff, shares): not in the data |
| S120 | Entrepreneurship & innovation | Exit event | Dropped | Inside a business (funding, staff, shares): not in the data |
