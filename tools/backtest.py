"""Out-of-sample backtest of the calibration.

Calibrate on a train split of profiles, then for each test profile simulate its 24 months of cash
from the opening balance (NOT looking at its own ledger) and compare with the real closing balances.
Reports the p10-p90 coverage (ideal ~80%) and the median absolute error in CHF.

    python tools/backtest.py [--data-dir DIR] [--out-dir DIR] [--seed N] [--paths N] [--test-frac F]
"""
import argparse
import json
import random
import statistics as st
from collections import defaultdict
from pathlib import Path

from calibrate import build_calibration
from common import default_data_dir, employment_fallback_key, is_december, load_dataset, quantile, segment_key


def pick_segment(calib: dict, profile: dict) -> dict:
    seg = calib.get(segment_key(profile["employment"], profile["household"]))
    if seg is None or seg["lowConfidence"]:
        seg = calib[employment_fallback_key(profile["employment"])]
    return seg


def sample_living(seg: dict, rng: random.Random) -> float:
    """Draw a living cost by interpolating the segment deciles (parameter uncertainty)."""
    d = seg["livingCostChf"]["deciles"]
    k = rng.random() * 10
    lo = int(k)
    hi = min(lo + 1, 10)
    return d[lo] + (d[hi] - d[lo]) * (k - lo)


def simulate_paths(profile: dict, months: list[str], seg: dict, n_paths: int, rng: random.Random) -> list[list[float]]:
    """n_paths x len(months) closing cash balances."""
    paths = []
    for _ in range(n_paths):
        living = sample_living(seg, rng)
        cash, path = profile["openingCash"], []
        for m in months:
            net = profile["income"] - profile["rent"] - living + rng.choice(seg["residualsChf"])
            if is_december(m):
                net += seg["decemberBonusRatio"] * profile["income"]
            cash += net
            path.append(cash)
        paths.append(path)
    return paths


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data-dir", type=Path, default=default_data_dir())
    ap.add_argument("--out-dir", type=Path, default=Path(__file__).resolve().parent / "out")
    ap.add_argument("--seed", type=int, default=2026)
    ap.add_argument("--paths", type=int, default=300)
    ap.add_argument("--test-frac", type=float, default=0.2)
    args = ap.parse_args()

    ds = load_dataset(args.data_dir)
    rng = random.Random(args.seed)
    pids = sorted(ds["profiles"])
    rng.shuffle(pids)
    n_test = int(len(pids) * args.test_frac)
    test, train = pids[:n_test], pids[n_test:]
    calib = {s["segment"]: s for s in build_calibration(ds, train, args.seed)}

    inside, abs_err, per_emp = 0, [], defaultdict(lambda: [0, 0])
    total = 0
    for pid in test:
        pr, rows = ds["profiles"][pid], ds["series"][pid]
        months = [r["month"] for r in rows]
        paths = simulate_paths(pr, months, pick_segment(calib, pr), args.paths, rng)
        for t, row in enumerate(rows):
            vals = [p[t] for p in paths]
            lo, med, hi = quantile(vals, 0.10), quantile(vals, 0.50), quantile(vals, 0.90)
            hit = lo <= row["closing"] <= hi
            inside += hit
            total += 1
            per_emp[pr["employment"]][0] += hit
            per_emp[pr["employment"]][1] += 1
            abs_err.append(abs(med - row["closing"]))

    report = {
        "seed": args.seed, "paths": args.paths, "trainProfiles": len(train), "testProfiles": len(test),
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
