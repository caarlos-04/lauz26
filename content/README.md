# content/ — game content

| File | What it is | Contract |
|---|---|---|
| `events.json` | The pool of actions the player chooses from (median effects, uncertain numbers drawn at the luck of the decision, an outcome sentence; `NOTHING` = carry on as you are) and the shocks nobody chooses (job loss) | [`docs/decision-tree.md`](../docs/decision-tree.md), `docs/README.md` 5.3 |
| `facts.json` | Life facts (education, occupation, city, housing, family), milestones and initial-fact rules | `docs/README.md` 5.4 |
| `assumptions.json` | Engine rules without person data: financing rate, investment return, penalties per objective, sensitivity ranges (every number with its source). **Generated** by `python tools/assumptions.py --build` (after `tools/macro.py`); inflation and wage/rent growth live in `macro.json` and the sensitivity copies their range | `docs/README.md` 5.7, `Assumptions` (Kotlin) |
| `chronicle.json` | Chronicle templates (P11, plan B without network): one ES/EN sentence per `StoryBeat` kind, life-change variants and labels. Only uses the numbers the beats carry. Check: `python tools/chronicle.py --check` | `ChronicleTemplates` (Kotlin, `chronicle/Chronicle.kt`) |
| `flow-model.json` | Simulator input: monthly flows learned from the ledger (aggregates only: factors, quantiles). **Generated** by `tools/flow_model.py` (Dev A), copied here with `python tools/ship_resources.py --from-out` | `FlowModel` (`model/Simulation.kt`) |
| `macro.json` | Simulator input: Swiss trends and the yearly public series behind them. **Generated** by `tools/macro.py` (Dev A), copied the same way | `MacroFile` (`model/Simulation.kt`) |
| `interview.json` | The start-of-app interview (P1): 13 questions. Choices (city, household, work, studies, goal, priority) fill `ProfileField`s with their options; numbers (age, income, rent, cash, investments, debt, cushion) are typed, within the dataset range (`input`: min, max, step, typical value shown as a hint), most with "I don't know". Numbers come from the dataset. **Generated** by `python tools/interview.py --build` | `docs/README.md` 5.7, `InterviewCatalog` (Kotlin) |
| `interview-defaults.json` | Dataset medians for "I don't know" answers (income by employment, rent by city x household, cash and investments by age, debt), the loan a typed debt becomes (median rate of the dataset, 60-month annuity) and the goal sizes. **Generated** with the file above | `InterviewDefaults` (Kotlin) |

Every `*.json` here is shipped with the app: `python tools/ship_resources.py` copies them to
`shared/src/commonMain/composeResources/files/` and `AppContent.load()` (`shared/.../content/AppContent.kt`) decodes them on
Android, Desktop and Web. A test fails if a shipped copy is older than its source, so run the script after editing a file here.
Action, shock and fact texts are written in both languages (`{"ES": "...", "EN": "..."}`) because they are shown to the user.

## Before pushing content
```
python tools/flow_model.py && python tools/macro.py            # once: generates tools/out/ (simulator inputs)
python tools/validate_events.py --preview P00001                # every action available to Alexia at the start, at luck p10 / p50 / p90
python tools/validate_events.py --play P00001 --path A-master,NOTHING --mode SAME_LEVEL   # a life: life line
```

## The idea: the player decides
At every decision point the player sees **every action whose requirements match their situation** and picks one
(`docs/decision-tree.md`). Two players should be able to build **two different lives**, so actions open other actions:
- **By facts**: `requires.facts` (e.g. only people with `EDUCATION: BACHELOR` can study a master's part time).
- **By milestones**: `requires.flagsAll` / `flagsNone` (e.g. only if `took_loan`).
- **By cash**: `requires.minCashChf` / `maxCashChf` (e.g. `A-loan` only with negative cash).
- An action is taken **once per life** unless `repeatable`. `NOTHING` is always available.
- What nobody chooses goes in `shocks` (with a `probability` per turn of 24 months), not in the pool.

## Rules for writing an action
1. **A real choice.** It must have something for and something against compared with carrying on. Use `--preview`.
2. **One action per alternative.** "Do a master's" and "Start working" are two actions, not two options of one.
3. **Every number with a source** in `sources`. If it comes from the dataset, cite the file; if not, say it is an assumption.
4. **Life changes too**: each action should touch some fact or milestone (`set_fact`, `add_flag`, `set_employment`), not only money.
   Studying and working are independent: `requires.employment` filters on work, `requires.facts.EDUCATION` on studies.
   A working student has `employment: PART_TIME` and `EDUCATION: ..._IN_PROGRESS` at the same time.
5. **Uncertain numbers, not cases.** Put the median in `effects` and describe what varies in `uncertain`: three values of
   the data (`low`, `median`, `high`, e.g. p25 / p50 / p75), the law (`LOGNORMAL` for amounts, factors and durations,
   `NORMAL` for rates, `QUANTILE` for the rent of a new flat), which side good luck gets (`better`), the rounding
   (`step`), how the story tells it (`format`) and the effect fields it sets (`targets`). Every uncertain number of an
   action is drawn at the same percentile. Use `min` / `max` when a value must stay meaningful.
6. **Absolute monthly amounts in CHF**, designed for the profile the action targets.
7. **Offsets (`startOffsetMonths`)** relative to the start of the turn. A turn lasts 24 months; effects that start
   later stay pending and carry on whatever the player does next.
8. **Outcome** in the second person, one or two sentences, with `{name}` for each uncertain number (the validator checks them).
9. New facts and milestones: **declare them first in `facts.json`** (with ES and EN) or the validator rejects them.
10. `set_income` replaces the person's single monthly income; `add_liability` adds a personal loan (merged with an existing one).

## Effect types
| type | What for |
|---|---|
| `set_income` | Change an income permanently (new job) |
| `income_factor` | Multiply the income, temporary or permanent (part time × 0.56, self-employed, promotion, pension): use a ratio from `event_data.py` |
| `income_delta` | Extra income or a cut, temporary or permanent (scholarship, reduced hours) |
| `expense_delta` | Extra expense or a saving, temporary or permanent (tuition, childcare) |
| `one_off` | One-time cost or income (moving house, family help: negative = income) |
| `add_liability` | New loan, with the payment computed from the term |
| `set_employment` | Change how the person earns money (`NOT_WORKING`, `PART_TIME`, `EMPLOYED`...). Does **not** end their studies: use `set_fact EDUCATION` for that. Affects the December bonus and which events appear |
| `set_fact` | Change a life fact (education, occupation, city, housing, family) |
| `set_household` | Change the household (`SINGLE`, `SHARED_HOUSING`, `COUPLE`, `FAMILY`); add the `HOUSING` / `FAMILY` facts it implies (the validator checks it) |
| `market_rent` | A new home: the rent becomes the market rent for the new city and household (`quantile` 0.25 cheap … 0.75 expensive). Put it after the city / household change |
| `add_flag` | Add a milestone to the story |

## Current state
17 actions and 2 shocks, converted from the 15 events measurable with our data (which catalogue scenarios they cover
and why the other 98 were dropped: [`docs/events-mapping.md`](../docs/events-mapping.md)): the "stay as you are"
options became `NOTHING`, the master's became two actions, the promotions became "Go for a management job in Zurich /
Geneva", and the job loss became a shock. Every number comes from `tools/event_data.py` (`tools/out/event-data.json`).
At a decision point a person usually has 2 to 5 actions plus `NOTHING`: the pool should grow.

## Data sources and attribution
The shipped files hold aggregates only, never a row of the synthetic dataset (`tools/ship_resources.py --check` looks for row
identifiers). Sources and reuse terms (checked 2026-11-22; the repository is private):
- **LauzHack 2026 AI Financial Twin Challenge, synthetic dataset** (concept and framework: Stefania Stan): used as the
  organisers permit for the challenge; `flow-model.json` keeps only fitted factors and quantiles.
- **Swiss Federal Statistical Office (BFS)**: rent price index, Swiss wage index, earnings structure survey (T9_b, T11).
  Terms OPEN-BY: free use, commercial included, **citing the source is mandatory**.
- **Swiss National Bank (SNB)**, data.snb.ch: exchange rates (`devkum`), Confederation bond yields (`rendoblid`).
  Non-commercial use with the source cited; commercial use needs SNB consent.
- **Bank for International Settlements (BIS)**: central bank policy rates (`WS_CBPOL`), residential property prices (`WS_SPP`).
  Free reproduction, **BIS cited as the source**, without implying endorsement.
- **World Bank**, World Development Indicators (inflation, GDP growth, unemployment): reuse with attribution (CC BY 4.0).
- **Eurostat** (`une_rt_m`): © European Union, reuse with acknowledgement of the source.

The UI must show these sources (e.g. in an "About the data" screen); `macro.json` carries provider, dataset, URL and retrieval
date for every series.

## The interview (`interview.json`)
`python tools/interview.py --build` rebuilds it from the CSVs, `--check` validates it (against the Kotlin enums in `Enums.kt`,
`facts.json` and the dataset ranges), `--demo 5` prints five random people, `--strings-into` replaces the interview strings (`q_*`, `goal_*`) of the app's Compose resources, and
`alexia.json` (the demo profile: Alexia's opening statement in the dataset) is rebuilt with `--build`. Texts live in `interview.json` as `{"EN","ES"}`; the UI reads them through
`textKey` / `labelKey`.
- **What is asked** is what the simulator reads from a person: age, city, household, employment, studies (the `EDUCATION` fact: studying is
  independent from working), income, rent, cash, plus the goal and the preferences (`OBJECTIVE`, `MIN_CASH`). `tools/flow_model.py` found that
  only employment, city and studying drive the flows; income and rent are the bases of the amounts.
- **Bands** come from the data (`AGE_EDGES`... in `tools/interview.py`); each option stands for the dataset median of the people in its band.
- **"I don't know"** (an option with empty `sets`) takes the dataset median, see `interview-defaults.json`.
- **Goals** are the six `goal_name` values of `synthetic-goals.csv`; each carries its dataset date and priority (Emergency savings and Long-term
  financial resilience: 2029, HIGH; the rest: 2032, MEDIUM) and a target of about 9-21 monthly incomes.
- **Not asked**: investments and loans (no `ProfileField` for them: they start at 0) and the name. Nobody in the dataset lives abroad, so `ABROAD` is not offered.
