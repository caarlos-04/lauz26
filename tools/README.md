# tools/ — offline data pipeline (Dev C)

Python scripts, **standard library only** (Python 3.10+). They read the challenge CSVs and produce JSON
files that the app loads. The app never reads CSV.

```
python tools/calibrate.py     # -> tools/out/calibration.json + tools/out/profiles-sample.json
python tools/backtest.py      # -> tools/out/backtest-report.json
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
- `noise` has sd ≈ CHF 450-620, but ≈ CHF 1,600 for self-employed.
- In December, employed and self-employed get ≈ 0.35 × monthly income extra (a 13th salary); others ≈ 0.
- Students and unemployed have income < rent, so they burn cash every month. Alexia: ≈ -CHF 1,000/month,
  so her CHF 8,000 lasts under a year unless an event changes something.
- The debt ledger has `monthly_payment = 0` for every loan (not amortized) and the dataset has no asset returns.

## calibration.json

```jsonc
{ "meta": { "model": "...", "caveats": ["..."] },
  "segments": [ {                      // 24 "EMPLOYMENT|HOUSEHOLD" + 6 "EMPLOYMENT|ANY" fallbacks
    "segment": "EMPLOYED|FAMILY",
    "n": 141,
    "lowConfidence": false,            // n < 20: use the "EMPLOYMENT|ANY" fallback
    "medianIncomeChf": 7736.0,
    "medianRentChf": 1954.0,
    "livingCostChf": { "mean": 0, "p10": 0, "p50": 0, "p90": 0, "deciles": [ /* 11 values */ ] },
    "monthlyNoiseSdChf": 589.0,
    "decemberUpliftChf": 2653.0,
    "decemberBonusRatio": 0.39,        // of monthly income, median over profiles with income >= 500
    "negativeMonthRate": 0.01,         // fraction of months flagged negative_balance_requires_financing
    "residualsChf": [ /* up to 600 within-profile monthly deviations, CHF, for bootstrap */ ]
  } ] }
```

How the app should use it (Monte Carlo, Dev A):
`net_t = income - rent - livingCost + resample(residualsChf) [+ decemberBonusRatio * income in December]`,
with `livingCost` drawn by interpolating `deciles` once per path (parameter uncertainty).
The three game cases map to `livingCostChf`: **optimistic = p10, neutral = p50, pessimistic = p90**.

## profiles-sample.json

~56 profiles (stratified: at least one per segment, always includes Alexia `P00001`). Each item:
`{ profile, initialState, segment }`, shaped like the Kotlin `UserProfile` and `FinancialState`.

Things the loader must know:
- `initialState.expenses` has `rent` (from the profile) and `living` (implied from that profile's ledger).
- `assets[].annualReturn` is `null`: fill it from `Assumptions.investmentReturn`. Cash is `initialState.cash`
  (not duplicated in `assets`).
- `liabilities[].monthlyPaymentChf` is an **assumption** (60-month annuity); the dataset has 0.
- The dataset ledger excludes debt service, so debt payments are an extra outflow on top of calibrated `net`.
- `goals[].targetMonth` is months since `startMonth` (2025-01 = 0).

## backtest-report.json

Calibrates on 80 % of the profiles and simulates the 24 months of the other 20 % from the opening balance,
without looking at their ledger. Current result (seed 2026): **p10-p90 coverage 83 %** (target 80 %),
median absolute error ≈ CHF 1,360 on the closing balance. Coverage is high for students/retired
(too-wide band) and ≈ 80 % for employed.

## Caveats (say them in the demo)
- Synthetic data: good backtest scores mean we recovered the generator, not real-world accuracy.
- Only 24 months are calibrated; everything after is assumption.
- Small segments (students: 39 profiles, unemployed: 37) have low confidence.
