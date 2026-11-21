# content/ — game content

| File | What it is | Contract |
|---|---|---|
| `events.json` | Event catalogue (dilemmas with 2 options × 3 cases) | `docs/README.md` 5.3 |
| `facts.json` | Life facts (education, occupation, city, housing, family), milestones and initial-fact rules | `docs/README.md` 5.4 |
| `interview.json` | The start-of-app interview (P1): 11 multiple-choice questions whose options fill `ProfileField`s. Numbers come from the dataset. **Generated** by `python tools/interview.py --build` | `docs/README.md` 5.7, `InterviewCatalog` (Kotlin) |
| `interview-defaults.json` | Dataset medians for "I don't know" answers (income by employment, rent by city x household, cash by age) and to size the goal. **Generated** with the file above | `InterviewDefaults` (Kotlin) |
| `assumptions.json` | *(pending)* inflation, wage growth, financing, penalties, sensitivity ranges | `docs/README.md` 5.7 |

Once the KMP project exists, these files are copied into the `commonMain` resources.
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
| `income_delta` | Extra income or a cut, temporary or permanent (scholarship, reduced hours) |
| `expense_delta` | Extra expense or a saving, temporary or permanent (tuition, childcare) |
| `one_off` | One-time cost or income (moving house, family help: negative = income) |
| `add_liability` | New loan, with the payment computed from the term |
| `set_employment` | Change how the person earns money (`NOT_WORKING`, `PART_TIME`, `EMPLOYED`...). Does **not** end their studies: use `set_fact EDUCATION` for that. Affects the December bonus and which events appear |
| `set_fact` | Change a life fact (education, occupation, city, housing, family) |
| `add_flag` | Add a milestone to the story |

## Current state
| Event | For whom | Notes (`--preview`/`--play`, Alexia, 10 years, no inflation) |
|---|---|---|
| `E-master` | Bachelor's student ≤ 30 | Master's: expected net worth ≈ 336k, but 23-41 months negative. Working: ≈ 329k, never negative |
| `E-financing` | Negative cash at the start of the turn (priority 10) | Loan (dataset rates 2.5-8.5 %) or family help (5-30k, assumption) |

Without any event Alexia loses ≈ CHF 1,000/month and pays overdraft interest (−170k after 10 years).
**Missing**: events for turns 2-4 (first job, housing, partner, moving, investing...), otherwise the game ends with quiet turns.

## The interview (`interview.json`)
`python tools/interview.py --build` rebuilds it from the CSVs, `--check` validates it (against the Kotlin enums in `Enums.kt`,
`facts.json` and the dataset ranges), `--demo 5` prints five random people, and `--strings DIR` writes the ES/EN Compose strings
(`values/strings.xml`, `values-es/strings.xml`) for the UI. Texts live in `interview.json` as `{"EN","ES"}`; the UI reads them through
`textKey` / `labelKey`.
- **What is asked** is what the simulator reads from a person: age, city, household, employment, studies (the `EDUCATION` fact: studying is
  independent from working), income, rent, cash, plus the goal and the preferences (`OBJECTIVE`, `MIN_CASH`). `tools/flow_model.py` found that
  only employment, city and studying drive the flows; income and rent are the bases of the amounts.
- **Bands** come from the data (`AGE_EDGES`... in `tools/interview.py`); each option stands for the dataset median of the people in its band.
- **"I don't know"** (an option with empty `sets`) takes the dataset median, see `interview-defaults.json`.
- **Goals** are the six `goal_name` values of `synthetic-goals.csv`; each carries its dataset date and priority (Emergency savings and Long-term
  financial resilience: 2029, HIGH; the rest: 2032, MEDIUM) and a target of about 9-21 monthly incomes.
- **Not asked**: investments and loans (no `ProfileField` for them: they start at 0) and the name. Nobody in the dataset lives abroad, so `ABROAD` is not offered.
