"""Hand-written flow models and helpers shared by the tests (no dataset or tools/out/ needed)."""
import json
from pathlib import Path

from statement import new_person

ROOT = Path(__file__).resolve().parents[1]
FACTS = json.loads((ROOT / "content" / "facts.json").read_text(encoding="utf-8"))
ALL_MONTHS = list(range(1, 13))
FLAT = [round(0.5 + i * 0.1, 2) for i in range(11)]   # residual deciles 0.5..1.5, mean 1
CITY_CANTON = {"Bern": "BE", "Geneva": "GE", "Lausanne": "VD"}


def flow(key, direction, basis, category, months, p_base, a_base, p_factors=None, a_factors=None, rms=1.0):
    return {"key": key, "direction": direction, "basis": basis, "expenseCategory": category, "months": months,
            "probability": {"base": p_base, "factors": p_factors or {}},
            "amount": {"base": a_base, "factors": a_factors or {}, "residualDeciles": FLAT,
                       "residualMeanSquare": rms, "personLevelSd": 0.0}}


def simple_model(living: float = 0.0, bonus_ratio: float = 0.0, tuition: float = 0.0) -> dict:
    """Salary and rent exactly as the streams, plus optional: a flat living cost, a December bonus for employed
    people, a tuition in February and September for students. Makes hand-computed expected values easy."""
    flows = [flow("salary", "IN", "INCOME", None, ALL_MONTHS, 1.0, 1.0),
             flow("rent", "OUT", "RENT", "RENT", ALL_MONTHS, 1.0, 1.0)]
    if living:
        flows.append(flow("living", "OUT", "CHF", "LIVING", ALL_MONTHS, 1.0, living))
    if bonus_ratio:
        flows.append(flow("bonus", "IN", "INCOME", None, [12], 1.0, bonus_ratio,
                          p_factors={"employment": {"EMPLOYED": 1.0, "NOT_WORKING": 0.0, "PART_TIME": 0.0}}))
    if tuition:
        flows.append(flow("tuition", "OUT", "CHF", "TUITION", [2, 9], 1.0, tuition,
                          p_factors={"studying": {"NO": 0.0, "YES": 1.0}}))
    return {"cityCanton": CITY_CANTON, "flows": flows}


# A richer model: canton and studying factors, tuition in Feb/Sep, bonus in December.
MODEL = {
    "cityCanton": CITY_CANTON,
    "flows": [
        flow("salary", "IN", "INCOME", None, ALL_MONTHS, 1.0, 1.0),
        flow("rent", "OUT", "RENT", "RENT", ALL_MONTHS, 1.0, 1.0),
        flow("groceries", "OUT", "CHF", "LIVING", ALL_MONTHS, 1.0, 300.0,
             a_factors={"canton": {"BE": 1.0, "GE": 1.25}, "studying": {"NO": 1.0, "YES": 0.8}}),
        flow("tuition", "OUT", "CHF", "TUITION", [2, 9], 1.0, 900.0, p_factors={"studying": {"NO": 0.0, "YES": 1.0}}),
        flow("bonus", "IN", "INCOME", None, [12], 0.5, 0.6,
             p_factors={"employment": {"EMPLOYED": 1.0, "NOT_WORKING": 0.0, "PART_TIME": 0.0}}),
    ],
}
MACRO = {"trends": {"priceInflationAnnual": {"value": 0.02}, "wageGrowthAnnual": {"value": 0.01},
                    "rentGrowthSittingTenantAnnual": {"value": 0.015}}}


def person(**kw) -> dict:
    """Opening statement of a new person (defaults: 20, employed, Bern, CHF 5,000 pay, CHF 1,500 rent)."""
    args = dict(profile_id="T1", display_name="Test", age=20, city="Bern", employment="EMPLOYED", household="SINGLE",
                education="UNKNOWN", cash=10000.0, income=5000.0, rent=1500.0, facts_catalog=FACTS)
    args.update(kw)
    return new_person(**args)


def income(s: dict, key: str) -> float | None:
    return s["cashFlow"]["incomeChf"].get(key)


def expense(s: dict, key: str) -> float | None:
    return s["cashFlow"]["expensesChf"].get(key)
