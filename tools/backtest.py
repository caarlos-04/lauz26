"""Out-of-sample backtest of the analytic uncertainty band (no Monte Carlo).

Calibrate on a train split of profiles. For each test profile, predict its 24 months of cash from the opening
balance WITHOUT looking at its own ledger, with a closed-form band:

    cash_k = cash_0 + sum_{j<=k} (income - rent - L + bonus_j) + sum of k iid noises
    mean_k = cash_0 + k * (income - rent - mean(L)) + bonuses
    var_k  = k^2 * sd(L)^2 + k * sigma^2          (L unknown for a new profile, noise independent across months)
    p10/p90 = mean_k -/+ 1.2816 * sqrt(var_k)

In the app the living cost of the user is known, so only the k * sigma^2 term applies.
Reports the p10-p90 coverage (ideal ~80%) and the median absolute error in CHF.

    python tools/backtest.py [--data-dir DIR] [--out-dir DIR] [--seed N] [--test-frac F]
"""
import argparse
import json
import math
import random
import statistics as st
from collections import defaultdict
from pathlib import Path

from calibrate import build_calibration
from common import default_data_dir, employment_fallback_key, is_december, load_dataset, segment_key

Z90 = 1.2816  # standard normal quantile for p90
BONUS_EMPLOYMENT = {"EMPLOYED", "SELF_EMPLOYED"}


def pick_segment(calib: dict, profile: dict) -> dict:
    seg = calib.get(segment_key(profile["employment"], profile["household"]))
    if seg is None or seg["lowConfidence"]:
        seg = calib[employment_fallback_key(profile["employment"])]
    return seg


def analytic_band(profile: dict, months: list[str], seg: dict, living_known: float | None = None) -> list[tuple]:
    """(p10, p50, p90) of closing cash for each month. living_known=None means the living cost is uncertain."""
    living = seg["livingCostChf"]["mean"] if living_known is None else living_known
    sd_living = seg["livingCostChf"]["sd"] if living_known is None else 0.0
    sigma = seg["monthlyNoiseSdChf"]
    bonus_ratio = seg["decemberBonusRatio"] if profile["employment"] in BONUS_EMPLOYMENT else 0.0
    cash, out = profile["openingCash"], []
    for k, m in enumerate(months, start=1):
        cash += profile["income"] - profile["rent"] - living
        if is_december(m):
            cash += bonus_ratio * profile["income"]
        sd = math.sqrt((k * sd_living) ** 2 + k * sigma ** 2)
        out.append((cash - Z90 * sd, cash, cash + Z90 * sd))
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data-dir", type=Path, default=default_data_dir())
    ap.add_argument("--out-dir", type=Path, default=Path(__file__).resolve().parent / "out")
    ap.add_argument("--seed", type=int, default=2026)
    ap.add_argument("--test-frac", type=float, default=0.2)
    args = ap.parse_args()

    ds = load_dataset(args.data_dir)
    pids = sorted(ds["profiles"])
    random.Random(args.seed).shuffle(pids)
    n_test = int(len(pids) * args.test_frac)
    test, train = pids[:n_test], pids[n_test:]
    calib = {s["segment"]: s for s in build_calibration(ds, train)}

    inside = total = 0
    abs_err, per_emp = [], defaultdict(lambda: [0, 0])
    for pid in test:
        pr, rows = ds["profiles"][pid], ds["series"][pid]
        band = analytic_band(pr, [r["month"] for r in rows], pick_segment(calib, pr))
        for (lo, med, hi), row in zip(band, rows):
            hit = lo <= row["closing"] <= hi
            inside += hit
            total += 1
            per_emp[pr["employment"]][0] += hit
            per_emp[pr["employment"]][1] += 1
            abs_err.append(abs(med - row["closing"]))

    report = {
        "method": "analytic band (normal approximation), no sampling",
        "seed": args.seed, "trainProfiles": len(train), "testProfiles": len(test),
        "coverageP10P90": round(inside / total, 4),
        "targetCoverage": 0.8,
        "medianAbsErrorChf": round(st.median(abs_err), 2),
        "meanAbsErrorChf": round(st.mean(abs_err), 2),
        "coverageByEmployment": {k: round(v[0] / v[1], 4) for k, v in sorted(per_emp.items())},
        "note": "Synthetic data: a good score means we recovered the generator, not real-world accuracy.",
    }
    args.out_dir.mkdir(parents=True, exist_ok=True)
    (args.out_dir / "backtest-report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
