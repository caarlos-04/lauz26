"""content/assumptions.json: the engine rules that do NOT come straight from the person's data (Python standard library only).

Contract: `Assumptions` / `SensitivityParam` in shared/.../model/Content.kt. Every number says where it comes from: the
challenge dataset, the public Swiss series behind tools/out/macro.json, or our own assumption (marked ASSUMED).

    python tools/macro.py                     # first: tools/out/macro.json (needs bash data/download.sh)
    python tools/assumptions.py --build       # dataset + macro.json -> content/assumptions.json
    python tools/assumptions.py --check       # validate the committed file (Kotlin enums, ranges, macro.json, engine defaults)

Sensitivity (docs/README.md section 9): for the first decision the engine moves each parameter to its low and high value
and looks for the break-even point. Ranges come from the data whenever there is data:
  - salary / living cost: spread between people of the dataset (p25-p75 and p10-p90 around the median);
  - tuition: range of scenario-assumptions.csv (S001) around its median;
  - inflation, wage growth, rent growth: the p10-p90 of the Swiss yearly series 2000-2025 (macro.json), same base as the engine;
  - interest on negative cash: range of the loan rates in synthetic-liabilities.csv;
  - investment return: 0 (no return data, the engine's default) up to the mean 10-year Confederation bond yield;
  - months of job search: ASSUMED (no data), 0 to 6 extra months on top of the events' start dates.
"""
import argparse
import csv
import io
import json
import re
import statistics as st
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import default_data_dir, quantile, read_csv  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
OUT_JSON = ROOT / "content" / "assumptions.json"
MACRO_JSON = Path(__file__).resolve().parent / "out" / "macro.json"
ENUMS_KT = ROOT / "shared" / "src" / "commonMain" / "kotlin" / "com" / "lauzhack" / "twin" / "model" / "Enums.kt"

# Value judgements and engine rules without data (docs/README.md 5.7 and content/README.md rule 4).
DEFAULT_CASE_PROBS = {"PESSIMISTIC": 0.2, "NEUTRAL": 0.6, "OPTIMISTIC": 0.2}
OBJECTIVE_PENALTIES = {"GROWTH": 0.0, "BALANCED": 500.0, "SAFE": 3000.0}   # CHF per month below the minimum cushion
JOB_SEARCH_EXTRA_MONTHS = (0.0, 0.0, 6.0)                                   # base, low, high
MACRO_PARAMS = {   # sensitivity id -> macro.json trend
    "price_inflation_annual": "priceInflationAnnual",
    "wage_growth_annual": "wageGrowthAnnual",
    "rent_growth_annual": "rentGrowthSittingTenantAnnual",
}


def kotlin_enum(name: str) -> list[str]:
    text = ENUMS_KT.read_text(encoding="utf-8")
    m = re.search(r"enum class " + name + r"\s*\{(.*?)\}", text, re.S)
    if not m:
        raise SystemExit(f"enum {name} not found in {ENUMS_KT}")
    return [x.strip() for x in re.sub(r"//.*", "", m.group(1)).replace("\n", " ").split(",") if x.strip()]


def read_transactions(data_dir: Path):
    csv_path, zip_path = data_dir / "synthetic-transactions.csv", data_dir / "synthetic-transactions.zip"
    f = (open(csv_path, newline="", encoding="utf-8") if csv_path.exists() else
         io.TextIOWrapper(zipfile.ZipFile(zip_path).open("synthetic-transactions.csv"), encoding="utf-8", newline=""))
    with f:
        yield from csv.DictReader(f)


def r4(x: float) -> float:
    return round(x, 4)


def param(pid, en, es, base, low, high, unit, origin, source):
    return {"id": pid, "label": {"EN": en, "ES": es}, "base": base, "low": low, "high": high,
            "unit": unit, "origin": origin, "source": source}


def build(data_dir: Path, macro: dict) -> dict:
    profiles = read_csv(data_dir / "synthetic-profiles.csv")
    loans = [float(r["annual_interest_rate"]) for r in read_csv(data_dir / "synthetic-liabilities.csv")]
    tuition = [float(r["value"]) for r in read_csv(data_dir / "scenario-assumptions.csv") if r["parameter"] == "annual_tuition_chf"]

    employed_income = [float(r["reference_monthly_net_income_chf"]) for r in profiles if r["employment_status"] == "employed"]
    m_inc = st.median(employed_income)

    living: dict[str, float] = defaultdict(float)          # monthly spending without rent, per person
    for t in read_transactions(data_dir):
        if t["transaction_type"] == "expense" and t["category"] != "housing_rent":
            living[t["profile_id"]] += abs(float(t["amount_chf"])) / 24
    lv = list(living.values())
    m_lv = st.median(lv)
    m_tu = st.median(tuition)
    financing = round(max(loans), 3)                        # 0.0848 -> 0.085, the engine's default
    trends = macro["trends"]
    bond = macro["series"]["confederationBond10yYieldPct"]["summary"]

    sensitivity = [
        param("salary_multiplier", "Salary after the decision (x typical)", "Salario tras la decisión (x típico)",
              1.0, r4(quantile(employed_income, 0.25) / m_inc), r4(quantile(employed_income, 0.75) / m_inc), "multiplier", "OBSERVED",
              f"synthetic-profiles.csv, employed (n={len(employed_income)}): p25 / p75 of the monthly net income over the median CHF {m_inc:,.0f}"),
        param("tuition_multiplier", "Tuition (x typical)", "Matrícula (x típica)",
              1.0, r4(min(tuition) / m_tu), r4(max(tuition) / m_tu), "multiplier", "OBSERVED",
              f"scenario-assumptions.csv S001 annual_tuition_chf (n={len(tuition)}): min / max over the median CHF {m_tu:,.0f}"),
        param("living_cost_multiplier", "Living costs, rent excluded (x typical)", "Coste de vida sin alquiler (x típico)",
              1.0, r4(quantile(lv, 0.1) / m_lv), r4(quantile(lv, 0.9) / m_lv), "multiplier", "OBSERVED",
              f"synthetic-transactions, every expense except housing_rent, per person and month (n={len(lv)}): p10 / p90 over the median CHF {m_lv:,.0f}"),
        param("job_search_delay_months", "Extra months to find a job", "Meses extra para encontrar trabajo",
              *JOB_SEARCH_EXTRA_MONTHS, "months", "ASSUMED",
              "No data on job-search time. The events start the new job right when the studies end (startOffsetMonths 24); "
              "0-6 extra months is our range to test how much a slower start would change the decision"),
    ]
    for pid, key in MACRO_PARAMS.items():
        t = trends[key]
        en, es = {"price_inflation_annual": ("Inflation (per year)", "Inflación (anual)"),
                  "wage_growth_annual": ("Wage growth (per year)", "Crecimiento salarial (anual)"),
                  "rent_growth_annual": ("Rent growth if you do not move (per year)", "Subida del alquiler si no te mudas (anual)")}[pid]
        sensitivity.append(param(pid, en, es, t["value"], t["low"], t["high"], "fraction per year", t["origin"],
                                 f"tools/out/macro.json trends.{key}: {t['basis']}"))
    sensitivity.append(param("financing_rate_annual", "Interest on negative cash (per year)", "Interés por saldo negativo (anual)",
                             financing, r4(min(loans)), financing, "fraction per year", "OBSERVED",
                             f"synthetic-liabilities.csv annual_interest_rate (n={len(loans)}): base = max (the engine's default), low = min"))
    sensitivity.append(param("investment_return_annual", "Return of investments (per year)", "Rentabilidad de las inversiones (anual)",
                             0.0, 0.0, r4(bond["mean"] / 100), "fraction per year", "ASSUMED",
                             f"No return data in the dataset: base 0 (the engine's default). High = mean 10-year Confederation bond yield "
                             f"{bond['from']}-{bond['to']} ({bond['mean']} %, SNB via macro.json); the last 10 years averaged {bond['mean10y']} %"))

    return {
        "version": 1,
        "meta": {"generator": "tools/assumptions.py --build",
                 "note": "Engine rules and value judgements that do not come from the person's data. Growth of prices, wages and "
                         "rents is in macro.json; the sensitivity copies its base and range so the engine and the analysis agree."},
        "investmentReturnAnnual": 0.0,
        "financingRateAnnual": financing,
        "defaultCaseProbs": DEFAULT_CASE_PROBS,
        "objectivePenalties": OBJECTIVE_PENALTIES,
        "sensitivity": sensitivity,
        "sourceNotes": [
            f"financingRateAnnual {financing}: interest on negative cash = the highest loan rate in synthetic-liabilities.csv "
            f"({max(loans)}), so going negative is never cheaper than a loan.",
            "investmentReturnAnnual 0: the dataset has portfolio values but no returns; equity returns were not downloaded "
            f"(FRED failed). Conservative reference: 10-year Confederation bonds averaged {bond['mean10y']} % over the last 10 years.",
            "defaultCaseProbs 0.2 / 0.6 / 0.2: our convention for events whose cases have no data (content/README.md rule 4).",
            "objectivePenalties CHF 0 / 500 / 3000 per month below the minimum cushion for GROWTH / BALANCED / SAFE: value "
            "judgements, shown to the user (docs/README.md section 9).",
            "sensitivity: low/high are the data spread when there is data (see each parameter's source); ASSUMED otherwise.",
        ],
    }


def check(a: dict, macro: dict | None) -> list[str]:
    errors = []
    for field in ("investmentReturnAnnual", "financingRateAnnual", "defaultCaseProbs", "objectivePenalties", "sensitivity", "sourceNotes"):
        if field not in a:
            errors.append(f"missing {field} (Assumptions in model/Content.kt)")
    if errors:
        return errors
    if set(a["defaultCaseProbs"]) != set(kotlin_enum("CaseType")):
        errors.append(f"defaultCaseProbs keys {sorted(a['defaultCaseProbs'])} != CaseType {kotlin_enum('CaseType')}")
    if abs(sum(a["defaultCaseProbs"].values()) - 1) > 1e-9 or any(p < 0 for p in a["defaultCaseProbs"].values()):
        errors.append("defaultCaseProbs must be >= 0 and add up to 1")
    if set(a["objectivePenalties"]) != set(kotlin_enum("Objective")):
        errors.append(f"objectivePenalties keys {sorted(a['objectivePenalties'])} != Objective {kotlin_enum('Objective')}")
    if any(v < 0 for v in a["objectivePenalties"].values()):
        errors.append("objectivePenalties must be >= 0")
    ids = [p["id"] for p in a["sensitivity"]]
    if len(ids) != len(set(ids)):
        errors.append("duplicate sensitivity ids")
    for p in a["sensitivity"]:
        if not (p["low"] <= p["base"] <= p["high"]) or p["low"] == p["high"]:
            errors.append(f"{p['id']}: needs low <= base <= high and low < high ({p['low']}, {p['base']}, {p['high']})")
        if not p["label"].get("EN") or not p["label"].get("ES"):
            errors.append(f"{p['id']}: label needs EN and ES")
        if not p.get("source"):
            errors.append(f"{p['id']}: every number needs a source")
    if not 0 <= a["financingRateAnnual"] < 1 or not -1 < a["investmentReturnAnnual"] < 1:
        errors.append("rates are annual fractions (0.085 = 8.5 %)")
    # same values as the engine uses today (tools/simulate.py DEFAULTS) until it reads this file
    from simulate import DEFAULTS
    for k, v in DEFAULTS.items():
        if abs(a[k] - v) > 1e-12:
            errors.append(f"{k}={a[k]} differs from tools/simulate.py DEFAULTS ({v}): change both")
    if macro is not None:
        for pid, key in MACRO_PARAMS.items():
            p = next((x for x in a["sensitivity"] if x["id"] == pid), None)
            t = macro["trends"][key]
            if p is None or (p["base"], p["low"], p["high"]) != (t["value"], t["low"], t["high"]):
                errors.append(f"{pid} must match macro.json trends.{key}: rebuild with --build")
    return errors


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--build", action="store_true")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--data-dir", type=Path, default=None)
    a = ap.parse_args()
    if not (a.build or a.check):
        ap.print_help()
        return 0
    macro = json.loads(MACRO_JSON.read_text(encoding="utf-8")) if MACRO_JSON.exists() else None
    if a.build:
        if macro is None:
            raise SystemExit("tools/out/macro.json is missing: run python tools/macro.py first")
        OUT_JSON.write_text(json.dumps(build(a.data_dir or default_data_dir(), macro), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"wrote {OUT_JSON.relative_to(ROOT)}")
    errors = check(json.loads(OUT_JSON.read_text(encoding="utf-8")), macro)
    if macro is None:
        print("note: tools/out/macro.json not found, the macro-derived ranges were not compared")
    for e in errors:
        print("ERROR", e)
    print("assumptions OK" if not errors else f"{len(errors)} error(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
