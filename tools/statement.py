"""FinancialStatement: the person at one point in time (docs/financial-statement.md).

Only what the datasets have and what the simulator needs to produce the next statement:
    profileId, month, period, person, cashChf, investmentsChf, loan, monthlyIncomeChf, monthlyRentChf,
    eventExpensesChf, cashFlow, goals, pendingChanges
Month 0 = opening statement ('2025-01-01'); month t = end of calendar month start + t - 1, with that month's cash flow.
Net worth, goal progress and ratios are computed from these fields when needed, never stored.
"""
from common import work_status

GOAL_BASIS = {"Emergency savings": "CASH", "Housing deposit": "CASH"}   # the rest: net worth (assumption, shown)
ASSUMED_LOAN_TERM_MONTHS = 60       # the dataset never pays its loans (monthly_payment_chf = 0): annuity assumed


# ---------------------------------------------------------------- time

def next_period(period: str) -> str:
    """'2025-01-01' (opening) -> '2025-01'; '2025-01' -> '2025-02'; '2025-12' -> '2026-01'."""
    if len(period) == 10:
        return period[:7]
    y, m = int(period[:4]), int(period[5:7]) + 1
    return f"{y + (m - 1) // 12:04d}-{(m - 1) % 12 + 1:02d}"


def calendar_month(period: str) -> int:
    return int(period[5:7])


# ---------------------------------------------------------------- derived values (computed, not stored)

def net_worth(s: dict) -> float:
    return s["cashChf"] + s["investmentsChf"] - (s["loan"]["principalChf"] if s["loan"] else 0.0)


def goal_value(goal: dict, s: dict) -> float:
    """Cash-like goals are measured with cash, the rest with net worth."""
    return s["cashChf"] if GOAL_BASIS.get(goal["name"], "NET_WORTH") == "CASH" else net_worth(s)


def goal_state(goal: dict, s: dict) -> str:
    """MISSED once its date has passed (hitting the target later does not count), else REACHED or IN_PROGRESS.
    The story keeps the first change from IN_PROGRESS: a goal reached in time stays reached in the life line."""
    if s["period"][:7] > goal["targetDate"][:7]:
        return "MISSED"
    return "REACHED" if goal_value(goal, s) >= goal["targetChf"] else "IN_PROGRESS"


# ---------------------------------------------------------------- building

def statement(*, profile_id: str, month: int, period: str, person: dict, cash: float, investments: float,
              loan: dict | None, income: float, rent: float, event_expenses: dict, cash_flow: dict | None,
              goals: list, pending: list) -> dict:
    return {"profileId": profile_id, "month": month, "period": period, "person": person,
            "cashChf": round(cash, 2), "investmentsChf": round(investments, 2), "loan": loan,
            "monthlyIncomeChf": round(income, 2), "monthlyRentChf": round(rent, 2),
            "eventExpensesChf": {k: round(v, 2) for k, v in event_expenses.items() if abs(v) > 0.005},
            "cashFlow": cash_flow, "goals": goals, "pendingChanges": pending}


def annuity_payment(principal: float, annual_rate: float, months: int) -> float:
    """Monthly payment that repays `principal` in `months` at `annual_rate`."""
    r = annual_rate / 12
    if r == 0:
        return principal / months
    return principal * r / (1 - (1 + r) ** -months)


def initial_facts(dataset_employment: str, household: str, city: str, facts_catalog: dict) -> dict:
    """Initial facts with the 'initialFromProfile' rules of content/facts.json. `dataset_employment` is the dataset's
    employment_status (upper case), which also encodes studying: STUDENT = studying and not working."""
    source = {"datasetEmployment": dataset_employment, "household": household, "city": city}
    facts = {}
    for key, rule in facts_catalog["initialFromProfile"].items():
        raw = source[rule["from"]]
        value = rule["map"].get(raw, rule.get("default")) if "map" in rule else raw
        if value not in facts_catalog["facts"][key]:
            raise ValueError(f"fact {key}={value!r} (from {raw!r}) is not in the facts catalog")
        facts[key] = value
    return facts


def new_person(*, profile_id: str, display_name: str, age: int, city: str, employment: str, household: str,
               education: str, cash: float, income: float, rent: float, facts_catalog: dict,
               start_period: str = "2025-01-01", investments: float = 0.0, loan: dict | None = None,
               goals: list | None = None) -> dict:
    """Opening statement (month 0) of a person from the interview. Studying (education ending in _IN_PROGRESS) is
    independent from work: a working student is PART_TIME + MASTER_IN_PROGRESS."""
    if education not in facts_catalog["facts"]["EDUCATION"]:
        raise ValueError(f"EDUCATION={education!r} is not in the facts catalog")
    studying = education.endswith("_IN_PROGRESS")
    facts = initial_facts("STUDENT" if studying and employment == "NOT_WORKING" else employment, household, city,
                          facts_catalog)
    facts["EDUCATION"] = education
    person = {"displayName": display_name, "age": age, "employment": employment, "household": household,
              "facts": facts, "flags": []}
    return statement(profile_id=profile_id, month=0, period=start_period, person=person, cash=cash,
                     investments=investments, loan=loan, income=income, rent=rent, event_expenses={},
                     cash_flow=None, goals=goals or [], pending=[])


def dataset_opening(ds: dict, pid: str, facts_catalog: dict) -> dict:
    """Opening statement of a dataset profile: every CSV joined by profile_id (docs/financial-statement.md, section 3)."""
    pr = ds["profiles"][pid]
    person = {"displayName": pr["displayName"], "age": pr["ageAtStart"], "employment": work_status(pr["employment"]),
              "household": pr["household"],
              "facts": initial_facts(pr["employment"], pr["household"], pr["city"], facts_catalog), "flags": []}
    loans = [{"principalChf": float(l["opening_principal_chf"]), "annualRate": float(l["annual_interest_rate"]),
              "monthlyPaymentChf": round(annuity_payment(float(l["opening_principal_chf"]),
                                                         float(l["annual_interest_rate"]),
                                                         ASSUMED_LOAN_TERM_MONTHS), 2)}
             for l in ds["liabilities"][pid]]
    goals = [{"name": g["goal_name"], "targetChf": float(g["target_amount_chf"]), "targetDate": g["target_date"],
              "priority": g["priority"].upper()} for g in ds["goals"][pid]]
    return statement(profile_id=pid, month=0, period=f"{pr['startMonth']}-01", person=person,
                     cash=sum(float(a["opening_balance_chf"]) for a in ds["accounts"][pid]),
                     investments=sum(float(a["value_chf"]) for a in ds["assets"][pid] if a["asset_type"] != "cash"),
                     loan=loans[0] if loans else None, income=pr["income"], rent=pr["rent"], event_expenses={},
                     cash_flow=None, goals=goals, pending=[])


# ---------------------------------------------------------------- checks

def check_statement(s: dict, prev: dict | None = None, tol: float = 0.02) -> list[str]:
    """The month's lines add up to its net, and (with the previous statement) cash(t) = cash(t-1) + net."""
    errors, cf = [], s["cashFlow"]
    if cf is not None:
        net = (sum(cf["incomeChf"].values()) - sum(cf["expensesChf"].values()) - cf["loanPaymentChf"]
               - cf["financingCostChf"])
        if abs(net - cf["netChf"]) > tol:
            errors.append(f"cash flow: lines sum {net:.2f} != net {cf['netChf']:.2f}")
        if prev is not None and abs(prev["cashChf"] + cf["netChf"] - s["cashChf"]) > tol:
            errors.append(f"cash: {prev['cashChf']:.2f} + {cf['netChf']:.2f} != {s['cashChf']:.2f}")
    return errors
