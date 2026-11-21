# tools/ — offline data pipeline and content checks (Dev C)

Python scripts, **standard library only** (Python 3.10+). They read the challenge CSVs and the game content
and produce JSON files that the app loads. The app never reads CSV.

```
python tools/calibrate.py                          # -> tools/out/calibration.json + profiles-sample.json
python tools/backtest.py                           # -> tools/out/backtest-report.json
python tools/validate_events.py                    # validate content/events.json + content/facts.json
python tools/validate_events.py --preview P00001   # each option x case on Alexia (10 years)
python tools/validate_events.py --play P00001 --choices A,B --luck 0.5,0.5   # whole game: life line + attribution
python tools/build_statements.py --profile P00001  # FinancialStatements from the dataset (opening + 24 months)
cd tools && python -m unittest -v
```

The CSVs live outside this repo. By default the scripts look in `../ai-financial-twin-lauzhack-2026/Data`
(sibling of the repo folder); override with `--data-dir` or the `LAUZ_DATA_DIR` env var.
Generated files go to `tools/out/` (git-ignored).

## What the data actually look like

For every profile and every non-December month the ledger follows (R² ≈ 0.98-0.99 per employment type):

```
net = income - rent - livingCost + noise
```

- `livingCost` ≈ CHF 1,500-1,600 per month for **everyone**; household type barely changes it.
- `noise` has sd ≈ CHF 450-620 (≈ CHF 1,600 for self-employed) and **no memory** between months (autocorrelation −0.07).
- In December, employed and self-employed get ≈ 0.35 × monthly income extra (a 13th salary); others ≈ 0.
- Students and unemployed have income < rent, so they burn cash every month. Alexia: ≈ −CHF 1,000/month.
- The debt ledger has `monthly_payment = 0` for every loan (not amortized) and the dataset has no asset returns.
- More findings (seasonality, heavy tails, rent by city) and postponed improvements: `docs/README.md` section 13.

## calibration.json

```jsonc
{ "meta": { "model": "...", "caveats": ["..."] },
  "segments": [ {                      // 24 "EMPLOYMENT|HOUSEHOLD" + 6 "EMPLOYMENT|ANY" fallbacks
    "segment": "EMPLOYED|FAMILY",
    "n": 141,
    "lowConfidence": false,            // n < 20: use the "EMPLOYMENT|ANY" fallback
    "medianIncomeChf": 7736.0,
    "medianRentChf": 1954.0,
    "livingCostChf": { "mean": 0, "sd": 0, "p10": 0, "p50": 0, "p90": 0, "deciles": [ /* 11 values */ ] },
    "monthlyNoiseSdChf": 589.0,        // for the analytic band
    "decemberBonusRatio": 0.39,        // of monthly income, median over profiles with income >= 500
    "negativeMonthRate": 0.01          // fraction of months flagged negative_balance_requires_financing
  } ] }
```

## profiles-sample.json and FinancialStatements

~56 profiles (stratified: at least one per segment, always includes Alexia `P00001`). Each item:
`{ profile, initialStatement, segment }`: the static `UserProfile` and the opening **FinancialStatement** (month 0).
Schema, field-by-field mapping to the CSVs and design decisions: `docs/financial-statement.md`.

`build_statements.py` builds the statements straight from the dataset (opening + the 24 observed months) and checks
the accounting identities; `validate_events.py --play ... --dump FILE` writes one simulated statement per turn.

Things the loader must know:
- Cash lives only in `accounts` (the dataset repeats it as a `cash` asset; we do not double count it).
- `expenseStreams` has `rent` (observed) and `living` (derived from that profile's ledger).
- `person.facts` follows the `initialFromProfile` rules of `content/facts.json` (same rules the app uses in P2).
- `investments[].annualReturn` is `null`: fill it from `Assumptions`.
- `liabilities[].monthlyPaymentChf` is an **assumption** (60-month annuity, `paymentIsAssumed: true`); the dataset has 0.
- `goals[].targetMonth` is the statement month at the end of the target month (2029-12-31 -> 60).

## backtest-report.json — analytic band, no Monte Carlo

Calibrates on 80 % of the profiles and predicts the 24 months of the other 20 % from the opening balance,
without looking at their ledger, with a closed-form band: `mean ± 1.2816 × sqrt(k²·sd(L)² + k·σ²)`
(`L` = living cost, unknown for a new profile; `σ` = monthly noise). In the app the user's living cost is known,
so only `k·σ²` applies. Current result (seed 2026): **p10-p90 coverage 82.7 %** (target 80 %),
median absolute error ≈ CHF 1,330 on the closing balance.

## validate_events.py — content checks and reference engine

- **Validation**: `content/events.json` against the contract (`docs/README.md` 5.3) and `content/facts.json`
  (every fact value and flag must exist in the catalog, texts in ES and EN, probabilities sum to 1...).
- **`--preview PROFILE_ID`**: each option × case of the events eligible at the start, 120 months.
- **`--play PROFILE_ID`**: a whole game (event selection by `requires`/`priority`, luck → case, life changes,
  story beats, goals, attribution waterfall with its identity check).
- The engine follows `docs/README.md` section 7 **without inflation, salary growth or asset returns** (pending
  `content/assumptions.json`). It includes the December bonus and interest on negative cash (8.5 %).
  Dev A can use its numbers as expected values; `test_events.py` has small hand-checked cases.

## Caveats (say them in the demo)
- Synthetic data: good backtest scores mean we recovered the generator, not real-world accuracy.
- Only 24 months are calibrated; everything after is assumption.
- Small segments (students: 39 profiles, unemployed: 37) have low confidence.
