"""Calibrate per-segment parameters from the synthetic dataset and export JSON for the app.

Outputs (in --out-dir, default tools/out):
  calibration.json     SegmentCalibration list (see tools/README.md for the schema)
  profiles-sample.json ~50 profiles with their initial twin (finance + life); always includes Alexia, P00001

Model found in the data (see README): for a profile and a non-December month
    net = income - rent - livingCost + noise
so we calibrate the living cost distribution, the monthly noise sd and the December bonus per segment.
    python tools/calibrate.py [--data-dir DIR] [--out-dir DIR] [--facts FILE] [--seed N] [--sample-size N]
"""
import argparse
import json
import random
import statistics as st
from collections import defaultdict
from pathlib import Path

from common import (MIN_INCOME_FOR_RATIO, deciles, default_data_dir, employment_fallback_key,
                    is_december, load_dataset, month_index, quantile, segment_key)

MIN_SEGMENT_SIZE = 20          # below this the segment is flagged lowConfidence
ASSUMED_LOAN_TERM_MONTHS = 60  # dataset has monthly_payment = 0 for every loan; we assume an annuity
DEFAULT_FACTS = Path(__file__).resolve().parents[1] / "content" / "facts.json"


def profile_stats(profile: dict, rows: list[dict]) -> dict:
    """Per-profile quantities derived from its 24-month ledger."""
    normal = [r["net"] for r in rows if not is_december(r["month"])]
    dec = [r["net"] for r in rows if is_december(r["month"])]
    mean_normal = st.mean(normal)
    dec_uplift = (st.mean(dec) - mean_normal) if dec else 0.0
    return {
        "living": profile["income"] - profile["rent"] - mean_normal,   # implied monthly living cost
        "residuals": [x - mean_normal for x in normal],                # within-profile noise (CHF)
        "decRatio": dec_uplift / profile["income"] if profile["income"] >= MIN_INCOME_FOR_RATIO else None,
        "negMonths": sum(1 for r in rows if r["negative"]),
        "months": len(rows),
    }


def calibrate_group(key: str, pids: list[str], ds: dict) -> dict:
    stats = [profile_stats(ds["profiles"][p], ds["series"][p]) for p in pids]
    living = [s["living"] for s in stats]
    residuals = [x for s in stats for x in s["residuals"]]
    ratios = [s["decRatio"] for s in stats if s["decRatio"] is not None]
    return {
        "segment": key,
        "n": len(pids),
        "lowConfidence": len(pids) < MIN_SEGMENT_SIZE,
        "medianIncomeChf": round(st.median(ds["profiles"][p]["income"] for p in pids), 2),
        "medianRentChf": round(st.median(ds["profiles"][p]["rent"] for p in pids), 2),
        # Monthly living cost = income - rent - net (non-December months).
        "livingCostChf": {
            "mean": round(st.mean(living), 2),
            "sd": round(st.pstdev(living), 2),
            "p10": round(quantile(living, 0.10), 2),
            "p50": round(quantile(living, 0.50), 2),
            "p90": round(quantile(living, 0.90), 2),
            "deciles": deciles(living),
        },
        "monthlyNoiseSdChf": round(st.pstdev(residuals), 2),           # for the analytic band
        "decemberBonusRatio": round(st.median(ratios), 4) if ratios else 0.0,   # of monthly income
        "negativeMonthRate": round(sum(s["negMonths"] for s in stats) / sum(s["months"] for s in stats), 4),
    }


def build_calibration(ds: dict, pids: list[str]) -> list[dict]:
    """Full segments (employment|household) plus employment-level fallbacks (employment|ANY)."""
    by_seg, by_emp = defaultdict(list), defaultdict(list)
    for p in pids:
        pr = ds["profiles"][p]
        by_seg[segment_key(pr["employment"], pr["household"])].append(p)
        by_emp[employment_fallback_key(pr["employment"])].append(p)
    groups = {**by_seg, **by_emp}
    return [calibrate_group(k, groups[k], ds) for k in sorted(groups)]


def annuity_payment(principal: float, annual_rate: float, months: int) -> float:
    r = annual_rate / 12
    if r == 0:
        return principal / months
    return principal * r / (1 - (1 + r) ** -months)


def initial_finance(ds: dict, pid: str) -> dict:
    """Initial FinancialState built from the dataset, shaped like the Kotlin dataclass."""
    pr = ds["profiles"][pid]
    s = profile_stats(pr, ds["series"][pid])
    income_id = "pension" if pr["employment"] == "RETIRED" else "income"
    return {
        "cash": pr["openingCash"],
        # annualReturn is null: the dataset has no returns; the loader fills it from Assumptions.
        "assets": [{"id": a["asset_id"], "type": a["asset_type"].upper(),
                    "valueChf": float(a["value_chf"]), "annualReturn": None}
                   for a in ds["assets"][pid] if a["asset_type"] != "cash"],
        "liabilities": [{"id": l["liability_id"], "type": l["liability_type"].upper(),
                         "principalChf": float(l["opening_principal_chf"]),
                         "annualRate": float(l["annual_interest_rate"]),
                         # ASSUMPTION: dataset payment is 0 (not amortized); we assume a 60-month annuity
                         "monthlyPaymentChf": round(annuity_payment(
                             float(l["opening_principal_chf"]), float(l["annual_interest_rate"]),
                             ASSUMED_LOAN_TERM_MONTHS), 2),
                         "sourceEventId": None}
                        for l in ds["liabilities"][pid]],
        "income": [{"id": income_id, "label": income_id, "monthlyChf": pr["income"],
                    "fromMonth": 0, "untilMonth": None, "sourceEventId": None}],
        "expenses": [
            {"id": "rent", "category": "RENT", "monthlyChf": pr["rent"],
             "fromMonth": 0, "untilMonth": None, "sourceEventId": None},
            {"id": "living", "category": "LIVING", "monthlyChf": round(s["living"], 2),
             "fromMonth": 0, "untilMonth": None, "sourceEventId": None},
        ],
    }


def initial_life(profile: dict, facts_catalog: dict) -> dict:
    """Initial LifeState using the 'initialFromProfile' rules of content/facts.json."""
    source = {"employment": profile["employment"], "household": profile["household"], "city": profile["city"]}
    facts = {}
    for key, rule in facts_catalog["initialFromProfile"].items():
        raw = source[rule["from"]]
        value = rule["map"].get(raw, rule.get("default")) if "map" in rule else raw
        if value not in facts_catalog["facts"][key]:
            raise ValueError(f"fact {key}={value!r} (from {raw!r}) is not in the facts catalog")
        facts[key] = value
    return {"employment": profile["employment"], "facts": facts, "flags": [], "pending": []}


def build_profile(ds: dict, pid: str, facts_catalog: dict) -> dict:
    pr = ds["profiles"][pid]
    return {
        "profile": {
            "id": pid, "displayName": pr["displayName"], "ageAtStart": pr["ageAtStart"],
            "city": pr["city"], "employment": pr["employment"], "household": pr["household"],
            "startMonth": pr["startMonth"],
            "goals": [{"id": g["goal_id"], "name": g["goal_name"], "targetChf": float(g["target_amount_chf"]),
                       "targetMonth": month_index(pr["startMonth"], g["target_date"])}
                      for g in ds["goals"][pid]],
        },
        "initialFinance": initial_finance(ds, pid),
        "initialLife": initial_life(pr, facts_catalog),
        "segment": segment_key(pr["employment"], pr["household"]),
    }


def sample_profiles(ds: dict, size: int, seed: int) -> list[str]:
    """Stratified sample: at least one profile per segment, proportional otherwise. Always has P00001."""
    rng = random.Random(seed)
    by_seg = defaultdict(list)
    for pid, pr in sorted(ds["profiles"].items()):
        by_seg[segment_key(pr["employment"], pr["household"])].append(pid)
    total = len(ds["profiles"])
    chosen = ["P00001"] if "P00001" in ds["profiles"] else []
    for seg in sorted(by_seg):
        pool = [p for p in by_seg[seg] if p not in chosen]
        quota = max(1, round(len(by_seg[seg]) * size / total)) - sum(1 for c in chosen if c in by_seg[seg])
        if quota > 0:
            chosen += rng.sample(pool, min(quota, len(pool)))
    return sorted(chosen)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data-dir", type=Path, default=default_data_dir())
    ap.add_argument("--out-dir", type=Path, default=Path(__file__).resolve().parent / "out")
    ap.add_argument("--facts", type=Path, default=DEFAULT_FACTS)
    ap.add_argument("--seed", type=int, default=2026)
    ap.add_argument("--sample-size", type=int, default=50)
    args = ap.parse_args()

    ds = load_dataset(args.data_dir)
    facts_catalog = json.loads(args.facts.read_text(encoding="utf-8"))
    args.out_dir.mkdir(parents=True, exist_ok=True)

    segments = build_calibration(ds, sorted(ds["profiles"]))
    meta = {
        "generator": "tools/calibrate.py",
        "source": "LauzHack 2026 synthetic dataset (synthetic-*.csv)",
        "nProfiles": len(ds["profiles"]), "monthsPerProfile": 24, "calibratedRange": "2025-01..2026-12",
        "model": "net = income - rent - livingCost + noise; December adds decemberBonusRatio * income (employed)",
        "caveats": [
            "Data are synthetic: calibration recovers the generator, not real behaviour.",
            f"Segments with n < {MIN_SEGMENT_SIZE} are flagged lowConfidence; use the 'EMPLOYMENT|ANY' fallback.",
            "Nothing beyond month 24 is calibrated: later months rely on explicit assumptions.",
            f"Loan payments are an assumption ({ASSUMED_LOAN_TERM_MONTHS}-month annuity): the dataset has 0.",
            "Asset returns are not in the dataset (annualReturn is null).",
        ],
    }
    (args.out_dir / "calibration.json").write_text(
        json.dumps({"meta": meta, "segments": segments}, indent=2, ensure_ascii=False), encoding="utf-8")

    sample = [build_profile(ds, p, facts_catalog) for p in sample_profiles(ds, args.sample_size, args.seed)]
    (args.out_dir / "profiles-sample.json").write_text(
        json.dumps({"meta": {"generator": "tools/calibrate.py", "seed": args.seed, "n": len(sample)},
                    "profiles": sample}, indent=2, ensure_ascii=False), encoding="utf-8")

    print(f"{len(segments)} segments -> {args.out_dir / 'calibration.json'}")
    print(f"{len(sample)} profiles -> {args.out_dir / 'profiles-sample.json'}")
    for s in segments:
        flag = " (low confidence)" if s["lowConfidence"] else ""
        print(f"  {s['segment']:<28} n={s['n']:<4} living p50={s['livingCostChf']['p50']:>8.0f} "
              f"noise sd={s['monthlyNoiseSdChf']:>6.0f} dec ratio={s['decemberBonusRatio']:.2f}{flag}")


if __name__ == "__main__":
    main()
