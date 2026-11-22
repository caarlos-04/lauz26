# tools/ — offline data pipeline, simulator reference and content checks

Python scripts, **standard library only** (Python 3.10+). They read the challenge CSVs, the public datasets and the
game content, and produce small JSON files that the app loads. The app never reads CSV.
Design of the simulator: [`docs/simulator.md`](../docs/simulator.md).

```
python tools/flow_model.py                         # -> out/flow-model.json + out/flow-backtest.json (learned monthly flows)
python tools/macro.py                              # -> out/macro.json (Swiss trends; needs data/download.sh first)
python tools/assumptions.py --build                # dataset + out/macro.json -> content/assumptions.json (--check to validate)
python tools/ship_resources.py --from-out         # out/{flow-model,macro}.json -> content/ -> app resources (--check)
python tools/chronicle.py --play P00001 --choices A,B --lang ES   # a whole game told with content/chronicle.json (--check)
python tools/event_data.py                         # -> out/event-data.json (every number the events use, with sources)
python tools/golden.py                             # -> out/golden/*.json (games the Kotlin engine must reproduce)
python tools/simulate.py --profile P00001          # opening statement -> 120 simulated months
python tools/simulate.py --validate                # every dataset profile, 24 months, accounting identities
python tools/simulate.py --profile P00001 --dump out/simulated-P00001.json   # for SimulationContractTest (Kotlin)
python tools/validate_events.py                    # validate content/events.json + content/facts.json
python tools/validate_events.py --preview P00001   # each option x case of the eligible events, 10 years
python tools/validate_events.py --play P00001 --choices A,B --luck 0.5,0.5   # whole game: life line + attribution
cd tools && python -m unittest -v
```

The CSVs live outside this repo. By default the scripts look in `../ai-financial-twin-lauzhack-2026/Data`
(sibling of the repo folder); override with `--data-dir` or the `LAUZ_DATA_DIR` env var. Public datasets go to
`data/raw/` (`bash data/download.sh`). Generated files go to `tools/out/` (git-ignored).

## Files

| File | What it does |
|---|---|
| `common.py` | Dataset loading (by `profile_id`) and small helpers |
| `statement.py` | The `FinancialStatement` contract: opening statement of a dataset profile (`dataset_opening`) or of a person from the interview (`new_person`), `check_statement` (identities), net worth and goal state (computed), initial facts, loan annuity |
| `flow_model.py` | Learns the monthly flows from `synthetic-transactions.zip`: for each category, months, probability and amount, with the person features that matter (forward selection). 80/20 backtest |
| `macro.py` | Swiss trends from public data: inflation, wage index, rent index, career curve by age, unemployment by age, property prices... with sources |
| `assumptions.py` | Builds and checks `content/assumptions.json`: financing rate, investment return, case probabilities, penalties and the sensitivity ranges, each with its source (dataset, `macro.json` or ASSUMED); checks it against the Kotlin enums and the engine defaults in `simulate.py` |
| `chronicle.py` | Tells the life line (beats) with `content/chronicle.json`, ES/EN; mirrors `chronicle/Chronicle.kt`; `--check` validates the templates against the Kotlin enums, `facts.json` and `events.json` |
| `ship_resources.py` | Ships the app data: copies `out/flow-model.json` and `out/macro.json` to `content/` (`--from-out`) and every `content/*.json` to `shared/src/commonMain/composeResources/files/`; `--check` verifies the copies and that no dataset row identifier leaves the dataset |
| `simulate.py` | One monthly step `statement(t) -> statement(t+1)`: pending changes, monthly trends, learned flows, loan, accounting |
| `event_data.py` | Every number of `content/events.json` computed from the data (pay ratios by work status, starting salaries, promotion ratios, tuition, loan rates), with its source |
| `golden.py` | Plays 13 reference games (every event at least once) for `GoldenGameTest` in Kotlin |
| `game.py` | Game loop on statements: event effects, event choice by `requires`, luck -> case, life line (beats), attribution, preview |
| `validate_events.py` | Validates the content contract (`docs/README.md` 5.3) and runs `--preview` / `--play` with `game.py` |
| `test_*.py`, `test_support.py` | Tests; `test_support.py` holds small hand-written models so tests need neither the dataset nor `out/` |

## Generated files

| File | Content | Read by |
|---|---|---|
| `flow-model.json` (~48 KB) | `FlowModel` (Kotlin: `model/Simulation.kt`) | Engine |
| `macro.json` (~12 KB) | `MacroFile` (Kotlin: `model/Simulation.kt`); trends + the public series behind them | Engine, UI (sources) |
| `flow-backtest.json` | Out-of-sample check of the flow model | Docs, demo |
| `simulated-P00001.json` | 120 simulated statements of Alexia | Kotlin contract test |

## Caveats (say them in the demo)
- Synthetic data: good backtest scores mean we recovered the generator, not real-world accuracy.
- The ledger covers 24 months and has no trend: growth over the 10 years comes from public Swiss data (`macro.json`).
- Loan payments (60-month annuity) and investment returns are assumptions: the dataset has neither.
