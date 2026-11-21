"""Every number used by the events of content/events.json, computed from the data, with its source.

The events must only contain effects we can measure (docs/events-mapping.md). This script is the single place where
those numbers come from; the `sources` of each event cite the keys printed here.

Output (in --out-dir, default tools/out):
  event-data.json   {"key": {"value": ..., "source": "..."}}

    python tools/event_data.py [--data-dir DIR]          # needs tools/out/macro.json (tools/macro.py)
"""
import argparse
import json
from pathlib import Path

from common import default_data_dir, load_dataset, quantile, read_csv
from simulate import career_level

OUT = Path(__file__).resolve().parent / "out"
PROFILES = "synthetic-profiles.csv reference_monthly_net_income_chf"
START_AGE_NOW, START_AGE_AFTER_MASTER = 22, 24      # reference ages of the master's event (requires age <= 30)
PROMOTION_AGE = 40                                   # reference age of the promotion event (requires 28..55)


def build(ds: dict, data_dir: Path, macro: dict) -> dict:
    out = {}

    def put(key, value, source, digits=4):
        out[key] = {"value": round(value, digits) if isinstance(value, float) else value, "source": source}

    income = {}
    for p in ds["profiles"].values():
        income.setdefault(p["employment"], []).append(p["income"])
    q = lambda emp, p: quantile(income[emp], p)
    emp50 = q("EMPLOYED", 0.5)
    for emp in ("EMPLOYED", "PART_TIME", "SELF_EMPLOYED", "RETIRED", "UNEMPLOYED", "STUDENT"):
        for p in (0.25, 0.5, 0.75):
            put(f"income.{emp}.p{int(p * 100)}", q(emp, p), f"{PROFILES}, employment_status={emp.lower()}, n={len(income[emp])}", 2)
    put("ratio.partTime", q("PART_TIME", 0.5) / emp50, "median part-time income / median employed income (dataset)")
    put("ratio.unemployed", q("UNEMPLOYED", 0.5) / emp50,
        "median unemployed income / median employed income (dataset: the unemployed get almost no income)")
    for p in (0.25, 0.5, 0.75):
        put(f"ratio.selfEmployed.p{int(p * 100)}", q("SELF_EMPLOYED", p) / emp50,
            f"self-employed income p{int(p * 100)} / median employed income (dataset)")
        put(f"ratio.retired.p{int(p * 100)}", q("RETIRED", p) / emp50,
            f"retired income p{int(p * 100)} / median employed income (dataset; pension as a share of pay: assumption)")

    self50 = q("SELF_EMPLOYED", 0.5)
    for p in (0.25, 0.5, 0.75):
        put(f"ratio.employedVsSelfEmployed.p{int(p * 100)}", q("EMPLOYED", p) / self50,
            f"employed income p{int(p * 100)} / median self-employed income (dataset)")

    # starting salaries: the dataset's employed incomes are flat by age (synthetic); BFS LSE gives the age profile
    lse = macro["trends"]["careerCurveByAge"]["value"]
    all_ages = macro["series"]["medianWageByAge"]["allAgesMedianChf"]
    curve = lse["curves"]["allPositions"]
    for name, age in (("startNow", START_AGE_NOW), ("afterMaster", START_AGE_AFTER_MASTER)):
        factor = career_level(curve, age) / all_ages
        put(f"ageFactor.{name}", factor, f"BFS LSE 2024: median wage at age {age} / median of all ages ({all_ages:.0f})")
        for p in (0.25, 0.5, 0.75):
            put(f"salary.{name}.p{int(p * 100)}", round(q("EMPLOYED", p) * factor / 100) * 100,
                f"employed income p{int(p * 100)} (dataset) x ageFactor.{name} (BFS), rounded to 100", 0)

    # promotion: pay of each management level relative to all positions, at the reference age (BFS LSE)
    base = career_level(curve, PROMOTION_AGE)
    for name in ("lowestManagement", "lowerManagement", "management"):
        put(f"ratio.promotion.{name}", career_level(lse["curves"][name], PROMOTION_AGE) / base,
            f"BFS LSE 2024: median wage of '{name}' / all positions at age {PROMOTION_AGE}")

    # tuition (challenge scenario S001) and loans (dataset)
    tuition = [float(r["value"]) for r in read_csv(data_dir / "scenario-assumptions.csv")
               if r["parameter"] == "annual_tuition_chf"]
    for p in (0.25, 0.5, 0.75):
        put(f"tuition.monthly.p{int(p * 100)}", round(quantile(tuition, p) / 12),
            f"scenario-assumptions.csv annual_tuition_chf p{int(p * 100)} / 12 (scenario S001, n={len(tuition)})", 0)
    loans = [l for ls in ds["liabilities"].values() for l in ls]
    rates = [float(l["annual_interest_rate"]) for l in loans]
    for p in (0.1, 0.5, 0.9):
        put(f"loan.rate.p{int(p * 100)}", quantile(rates, p), f"synthetic-liabilities.csv annual_interest_rate p{int(p * 100)}")
    put("loan.principal.p50", round(quantile([float(l["opening_principal_chf"]) for l in loans], 0.5) / 1000) * 1000,
        "synthetic-liabilities.csv opening_principal_chf median, rounded to 1,000", 0)

    by_age = macro["series"]["unemploymentRateByAgePct"]["byAge"]
    put("unemployment.under25.pct", by_age["under25"]["mean5y"], "Eurostat une_rt_m, Switzerland, under 25, 5-year mean", 2)
    put("unemployment.from25to74.pct", by_age["from25to74"]["mean5y"], "Eurostat une_rt_m, Switzerland, 25-74, 5-year mean", 2)
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data-dir", type=Path, default=default_data_dir())
    ap.add_argument("--out-dir", type=Path, default=OUT)
    args = ap.parse_args()
    macro = json.loads((args.out_dir / "macro.json").read_text(encoding="utf-8"))
    out = build(load_dataset(args.data_dir), args.data_dir, macro)
    (args.out_dir / "event-data.json").write_text(json.dumps(out, indent=2), encoding="utf-8")
    for k, v in out.items():
        print(f"{k:34s} {v['value']!s:>10}   {v['source']}")


if __name__ == "__main__":
    main()
