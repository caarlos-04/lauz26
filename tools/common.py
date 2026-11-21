"""Shared helpers for the offline data tools (Python standard library only)."""
import csv
import os
from collections import defaultdict
from pathlib import Path

DECEMBER = "12"
MIN_INCOME_FOR_RATIO = 500.0  # below this a ratio over income is meaningless (students, unemployed)

# repo layout: <root>/lauz26-repo/tools/common.py and <root>/ai-financial-twin-lauzhack-2026/Data
_DEFAULT_DATA_DIR = Path(__file__).resolve().parents[2] / "ai-financial-twin-lauzhack-2026" / "Data"


def default_data_dir() -> Path:
    """Data folder: LAUZ_DATA_DIR env var if set, otherwise the sibling challenge folder."""
    return Path(os.environ.get("LAUZ_DATA_DIR", _DEFAULT_DATA_DIR))


def read_csv(path: Path) -> list[dict]:
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def segment_key(employment: str, household: str) -> str:
    """Segment id used everywhere, e.g. 'EMPLOYED|FAMILY'."""
    return f"{employment.upper()}|{household.upper()}"


def work_status(dataset_employment: str) -> str:
    """How the person earns money. The dataset's employment_status also encodes studying ('student');
    in the statement studying lives in facts.EDUCATION, and a student without a job is NOT_WORKING."""
    return "NOT_WORKING" if dataset_employment == "STUDENT" else dataset_employment


def calibration_employment(employment: str, education: str | None) -> str:
    """Dataset employment status whose calibration applies to a person in the game.
    Studying and not working -> STUDENT; not working and not studying -> UNEMPLOYED (closest segment: assumption)."""
    if employment == "NOT_WORKING":
        return "STUDENT" if education and education.endswith("_IN_PROGRESS") else "UNEMPLOYED"
    return employment


def employment_fallback_key(employment: str) -> str:
    """Fallback segment when the full segment has too few profiles, e.g. 'STUDENT|ANY'."""
    return f"{employment.upper()}|ANY"


def quantile(values: list[float], p: float) -> float:
    """Quantile with linear interpolation. p in [0, 1]."""
    xs = sorted(values)
    if not xs:
        raise ValueError("quantile of empty list")
    k = (len(xs) - 1) * p
    lo = int(k)
    hi = min(lo + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def deciles(values: list[float]) -> list[float]:
    """11 values: min, p10, p20, ..., p90, max. Easy to sample by interpolation in Kotlin."""
    return [round(quantile(values, i / 10), 2) for i in range(11)]


def month_index(start_month: str, date_str: str) -> int:
    """Months between a 'YYYY-MM' start and a 'YYYY-MM[-DD]' date (0 = start month)."""
    sy, sm = int(start_month[:4]), int(start_month[5:7])
    y, m = int(date_str[:4]), int(date_str[5:7])
    return (y - sy) * 12 + (m - sm)


def load_dataset(data_dir: Path) -> dict:
    """Load the CSVs and index them by profile_id with numeric fields converted."""
    profiles, raw_profiles = {}, {}
    for r in read_csv(data_dir / "synthetic-profiles.csv"):
        raw_profiles[r["profile_id"]] = r          # every column, e.g. canton, country, synthetic
        profiles[r["profile_id"]] = {
            "id": r["profile_id"],
            "displayName": r["display_name"],
            "ageAtStart": int(r["age_at_start"]),
            "city": r["city"],
            "employment": r["employment_status"].upper(),
            "household": r["household_type"].upper(),
            "startMonth": r["as_of_date"][:7],
            "openingCash": float(r["opening_cash_chf"]),
            "income": float(r["reference_monthly_net_income_chf"]),
            "rent": float(r["reference_monthly_rent_chf"]),
        }

    series = defaultdict(list)
    for r in read_csv(data_dir / "synthetic-cash-balances.csv"):
        series[r["profile_id"]].append({
            "month": r["month"],
            "opening": float(r["opening_cash_chf"]),
            "net": float(r["net_transactions_chf"]),
            "closing": float(r["closing_cash_chf"]),
            "negative": r["status"] != "ok",
        })
    for rows in series.values():
        rows.sort(key=lambda x: x["month"])

    assets = defaultdict(list)
    for r in read_csv(data_dir / "synthetic-assets.csv"):
        assets[r["profile_id"]].append(r)
    liabilities = defaultdict(list)
    for r in read_csv(data_dir / "synthetic-liabilities.csv"):
        liabilities[r["profile_id"]].append(r)
    goals = defaultdict(list)
    for r in read_csv(data_dir / "synthetic-goals.csv"):
        goals[r["profile_id"]].append(r)
    accounts = defaultdict(list)
    for r in read_csv(data_dir / "synthetic-accounts.csv"):
        accounts[r["profile_id"]].append(r)
    scenario_assumptions = defaultdict(list)
    for r in read_csv(data_dir / "scenario-assumptions.csv"):
        scenario_assumptions[r["profile_id"]].append(r)

    return {"profiles": profiles, "raw_profiles": raw_profiles, "series": series, "assets": assets,
            "liabilities": liabilities, "goals": goals, "accounts": accounts,
            "scenario_assumptions": scenario_assumptions}


def is_december(month: str) -> bool:
    return month[5:7] == DECEMBER
