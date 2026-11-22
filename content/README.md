# content/ — game content

| File | What it is | Contract |
|---|---|---|
| `events.json` | Event catalogue (dilemmas with 2 options × 3 cases) | `docs/README.md` 5.3 |
| `facts.json` | Life facts (education, occupation, city, housing, family), milestones and initial-fact rules | `docs/README.md` 5.4 |
| `assumptions.json` | Engine rules without person data: financing rate, investment return, default case probabilities, penalties per objective, sensitivity ranges (every number with its source). **Generated** by `python tools/assumptions.py --build` (after `tools/macro.py`); inflation and wage/rent growth live in `macro.json` and the sensitivity copies their range | `docs/README.md` 5.7, `Assumptions` (Kotlin) |
| `chronicle.json` | Chronicle templates (P11, plan B without network): one ES/EN sentence per `StoryBeat` kind, life-change variants and labels. Only uses the numbers the beats carry. Check: `python tools/chronicle.py --check` | `ChronicleTemplates` (Kotlin, `chronicle/Chronicle.kt`) |
| `flow-model.json` | Simulator input: monthly flows learned from the ledger (aggregates only: factors, quantiles). **Generated** by `tools/flow_model.py` (Dev A), copied here with `python tools/ship_resources.py --from-out` | `FlowModel` (`model/Simulation.kt`) |
| `macro.json` | Simulator input: Swiss trends and the yearly public series behind them. **Generated** by `tools/macro.py` (Dev A), copied the same way | `MacroFile` (`model/Simulation.kt`) |

Every `*.json` here is shipped with the app: `python tools/ship_resources.py` copies them to
`shared/src/commonMain/composeResources/files/` and `AppContent.load()` (`shared/.../content/AppContent.kt`) decodes them on
Android, Desktop and Web. A test fails if a shipped copy is older than its source, so run the script after editing a file here.
Event and fact texts are written in both languages (`{"ES": "...", "EN": "..."}`) because they are shown to the user.

## Before pushing content
```
python tools/flow_model.py && python tools/macro.py            # once: generates tools/out/ (simulator inputs)
python tools/validate_events.py --preview P00001                # every option x case on Alexia
python tools/validate_events.py --play P00001 --choices A,B     # full game: life line
```

## The idea: stories, not loose questions
Two games should be **two different lives**. That is why events are chained:
- **By facts**: `requires.facts` (e.g. only people with `EDUCATION: MASTER`).
- **By milestones**: `requires.flagsAll` / `flagsNone` (e.g. only if `took_loan`, or never if already `did_master`).
- **By cash**: `requires.minCashChf` / `maxCashChf` (e.g. `E-financing` only with negative cash).
- **By priority**: urgent events (high `priority`) jump ahead of the rest.
- If no event fits, the turn is quiet: **design events for every stage** or the story stays empty.

## Rules for writing an event
1. **A real dilemma.** Both options must have different advantages. Use `--preview` to check that neither wins on everything.
2. **Every number with a source** in `sources`. If it comes from the dataset, cite the file; if not, say it is an assumption.
3. **Life changes too**: each case should touch some fact or milestone (`set_fact`, `add_flag`, `set_employment`), not only money.
   Studying and working are independent: `requires.employment` filters on work, `requires.facts.EDUCATION` on studies.
   A working student has `employment: PART_TIME` and `EDUCATION: ..._IN_PROGRESS` at the same time.
4. **Default probabilities** 0.2 / 0.6 / 0.2. Use 0.25 / 0.5 / 0.25 for more uncertain options.
5. **Absolute monthly amounts in CHF**, designed for the profile the event targets.
6. **Offsets (`startOffsetMonths`)** relative to the start of the turn. A turn lasts 24 months.
7. **Narrative** in the second person, one or two sentences, with the key numbers of the case. It is what the user reads during the game.
8. New facts and milestones: **declare them first in `facts.json`** (with ES and EN) or the validator rejects them.
9. `set_income` replaces the person's single monthly income; `add_liability` adds a personal loan (merged with an existing one).

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
15 events, all measurable with our data: which of the 120 catalogue scenarios they cover, who gets them, what they
change and why the other 98 were dropped: [`docs/events-mapping.md`](../docs/events-mapping.md). Every number comes
from `tools/event_data.py` (`tools/out/event-data.json`).

Coverage (one game per dataset profile, 2,400 games): about one quiet turn out of five for people who work or study;
retired people get fewer events (nothing measurable in the catalogue fits most of them).

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
