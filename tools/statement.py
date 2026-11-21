"""FinancialStatement: everything we know about a person at one point in time (docs/financial-statement.md).

Built by joining every dataset by profile_id. Month convention:
  month 0      = opening statement, as of startMonth-01 (2025-01-01); no cash flow yet
  month t >= 1 = statement at the END of calendar month startMonth + (t - 1), with that month's cash flow
Every flow line carries an origin: OBSERVED (dataset value), DERIVED (computed from the dataset),
ASSUMED (our assumption) or SIMULATED (engine). The same shape is produced by the reference engine
(validate_events.py) so the app, the LLM and the tests all read one structure.
"""
from common import month_index, segment_key

STATEMENT_VERSION = 1
CURRENCY = "CHF"
# Which balance counts towards each goal (assumption, documented): cash-like goals use cash, the rest net worth.
GOAL_BASIS = {"Emergency savings": "CASH", "Housing deposit": "CASH"}
EMERGENCY_FUND_LOW_MONTHS = 3.0     # rule of thumb used for liquidityStatus (assumption)
ASSUMED_LOAN_TERM_MONTHS = 60       # dataset monthly_payment_chf is 0 for every loan; we assume an annuity


def period_of(start_month: str, month: int) -> str:
    """Calendar period covered by statement `month` ('2025-01-01' for the opening statement)."""
    if month == 0:
        return f"{start_month}-01"
    y, m = int(start_month[:4]), int(start_month[5:7]) + month - 1
    y, m = y + (m - 1) // 12, (m - 1) % 12 + 1
    return f"{y:04d}-{m:02d}"


def goal_target_month(start_month: str, target_date: str) -> int:
    """Statement month at the END of the target date's calendar month (2029-12-31 -> 60 for start 2025-01)."""
    return month_index(start_month, target_date) + 1


def _active(stream: dict, t: int) -> bool:
    return stream.get("fromMonth", 0) <= t and (stream.get("untilMonth") is None or t < stream["untilMonth"])


def run_rate(income: list, expenses: list, liabilities: list, t: int) -> dict:
    """Expected monthly amounts for the month that starts after statement t (streams active at index t)."""
    inc = sum(s["monthlyChf"] for s in income if _active(s, t))
    exp = sum(s["monthlyChf"] for s in expenses if _active(s, t))
    rent = sum(s["monthlyChf"] for s in expenses if _active(s, t) and s["category"] == "RENT")
    debt = sum(l["monthlyPaymentChf"] for l in liabilities if l["principalChf"] > 0)
    return {"income": inc, "expenses": exp, "rent": rent, "debtService": debt}


def metrics(cash: float, net_worth: float, rr: dict) -> dict:
    inc, out = rr["income"], rr["expenses"] + rr["debtService"]
    net = inc - out
    months = cash / out if out > 0 else None
    if cash < 0:
        status = "NEGATIVE"
    elif months is not None and months < EMERGENCY_FUND_LOW_MONTHS:
        status = "LOW"
    else:
        status = "OK"
    ratio = (lambda x: round(x / inc, 4) if inc > 0 else None)
    return {
        "basis": "RUN_RATE",                       # computed from the streams active next month
        "monthlyIncomeChf": round(inc, 2),
        "monthlyOutflowsChf": round(out, 2),       # expenses + debt service
        "monthlyNetCashFlowChf": round(net, 2),
        "savingsRate": ratio(net),
        "debtServiceRatio": ratio(rr["debtService"]),
        "rentToIncomeRatio": ratio(rr["rent"]),
        "emergencyFundMonths": round(months, 2) if months is not None else None,
        "liquidityStatus": status,                 # NEGATIVE | LOW (< 3 months) | OK
        "netWorthChf": round(net_worth, 2),
    }


def goal_status(goals: list, month: int, cash: float, net_worth: float) -> list:
    out = []
    for g in goals:
        basis = GOAL_BASIS.get(g["name"], "NET_WORTH")
        value = cash if basis == "CASH" else net_worth
        reached = value >= g["targetChf"]
        status = "REACHED" if reached else ("MISSED" if month > g["targetMonth"] else "IN_PROGRESS")
        out.append({**g, "progressBasis": basis, "currentChf": round(value, 2),
                    "progressRatio": round(max(0.0, min(1.0, value / g["targetChf"])), 4),
                    "monthsLeft": g["targetMonth"] - month, "status": status})
    return out


def balance_sheet(accounts: list, investments: list, liabilities: list) -> dict:
    cash = sum(a["balanceChf"] for a in accounts)
    inv = sum(i["valueChf"] for i in investments)
    debt = sum(l["principalChf"] for l in liabilities)
    return {"cashChf": round(cash, 2), "investmentsChf": round(inv, 2), "totalAssetsChf": round(cash + inv, 2),
            "totalLiabilitiesChf": round(debt, 2), "netWorthChf": round(cash + inv - debt, 2)}


def assemble(*, profile_id: str, month: int, start_month: str, origin: str, person: dict, accounts: list,
             investments: list, liabilities: list, income: list, expenses: list, cash_flow: dict | None,
             goals: list, scenario_parameters: list, pending: list) -> dict:
    """Build a FinancialStatement dict from its parts (balance sheet, goals and metrics are derived here)."""
    bs = balance_sheet(accounts, investments, liabilities)
    return {
        "version": STATEMENT_VERSION,
        "profileId": profile_id,
        "month": month,
        "period": period_of(start_month, month),
        "origin": origin,                          # OBSERVED (dataset months) | SIMULATED (engine)
        "currency": CURRENCY,
        "person": person,
        "accounts": accounts,
        "investments": investments,
        "liabilities": liabilities,
        "incomeStreams": income,
        "expenseStreams": expenses,
        "cashFlow": cash_flow,
        "balanceSheet": bs,
        "goals": goal_status(goals, month, bs["cashChf"], bs["netWorthChf"]),
        "scenarioParameters": scenario_parameters,
        "metrics": metrics(bs["cashChf"], bs["netWorthChf"], run_rate(income, expenses, liabilities, month)),
        "pendingLifeChanges": pending,
    }


# ---------------------------------------------------------------- from the dataset

def dataset_parts(ds: dict, pid: str, initial_life: dict, living_chf: float, annuity) -> dict:
    """Static parts of the opening statement for one profile (every CSV joined by profile_id)."""
    pr, raw = ds["profiles"][pid], ds["raw_profiles"][pid]
    income_id = "pension" if pr["employment"] == "RETIRED" else "income"
    person = {
        "displayName": pr["displayName"], "ageAtStart": pr["ageAtStart"], "age": pr["ageAtStart"],
        "city": pr["city"], "canton": raw["canton"], "country": raw["country"],
        "employment": pr["employment"], "household": pr["household"],
        "facts": initial_life["facts"], "flags": initial_life["flags"], "synthetic": raw["synthetic"] == "true",
    }
    accounts = [{"id": a["account_id"], "type": a["account_type"].upper(), "currency": a["currency"],
                 "balanceChf": float(a["opening_balance_chf"]), "status": "OK"}
                for a in ds["accounts"][pid]]
    investments = [{"id": a["asset_id"], "accountId": a["account_id"], "type": a["asset_type"].upper(),
                    "valueChf": float(a["value_chf"]), "annualReturn": None,      # not in the dataset
                    "valuationBasis": a["valuation_basis"]}
                   for a in ds["assets"][pid] if a["asset_type"] != "cash"]      # cash asset = cash account
    liabilities = [{"id": l["liability_id"], "type": l["liability_type"].upper(),
                    "principalChf": float(l["opening_principal_chf"]),
                    "annualRate": float(l["annual_interest_rate"]),
                    "monthlyPaymentChf": round(annuity(float(l["opening_principal_chf"]),
                                                       float(l["annual_interest_rate"]), ASSUMED_LOAN_TERM_MONTHS), 2),
                    "paymentIsAssumed": True,       # dataset monthly_payment_chf = 0 (not amortized)
                    "datasetMonthlyPaymentChf": float(l["monthly_payment_chf"]),
                    "sourceEventId": None}
                   for l in ds["liabilities"][pid]]
    income = [{"id": income_id, "label": income_id, "monthlyChf": pr["income"], "fromMonth": 0,
               "untilMonth": None, "indexation": "WAGES", "origin": "OBSERVED", "sourceEventId": None}]
    expenses = [
        {"id": "rent", "category": "RENT", "monthlyChf": pr["rent"], "fromMonth": 0, "untilMonth": None,
         "indexation": "CPI", "origin": "OBSERVED", "sourceEventId": None},
        {"id": "living", "category": "LIVING", "monthlyChf": round(living_chf, 2), "fromMonth": 0,
         "untilMonth": None, "indexation": "CPI", "origin": "DERIVED", "sourceEventId": None},
    ]
    goals = [{"id": g["goal_id"], "name": g["goal_name"], "targetChf": float(g["target_amount_chf"]),
              "targetDate": g["target_date"], "targetMonth": goal_target_month(pr["startMonth"], g["target_date"]),
              "priority": g["priority"].upper()}
             for g in ds["goals"][pid]]
    params = [{"id": a["assumption_id"], "scenarioId": a["scenario_id"], "parameter": a["parameter"],
               "value": float(a["value"]), "unit": a["unit"], "frequency": a["frequency"],
               "sourceType": a["source_type"], "notes": a["notes"]}
              for a in ds["scenario_assumptions"][pid]]
    return {"person": person, "accounts": accounts, "investments": investments, "liabilities": liabilities,
            "income": income, "expenses": expenses, "goals": goals, "params": params,
            "segment": segment_key(pr["employment"], pr["household"])}


def opening_statement(ds: dict, pid: str, parts: dict) -> dict:
    pr = ds["profiles"][pid]
    return assemble(profile_id=pid, month=0, start_month=pr["startMonth"], origin="OBSERVED",
                    person=parts["person"], accounts=parts["accounts"], investments=parts["investments"],
                    liabilities=parts["liabilities"], income=parts["income"], expenses=parts["expenses"],
                    cash_flow=None, goals=parts["goals"], scenario_parameters=parts["params"], pending=[])


def observed_statements(ds: dict, pid: str, parts: dict) -> list:
    """Statements for the 24 dataset months. The ledger only has net transactions, so the cash flow is
    decomposed as income (observed) - rent (observed) - living (derived) + variance (derived, = rest)."""
    pr = ds["profiles"][pid]
    living = next(e["monthlyChf"] for e in parts["expenses"] if e["id"] == "living")
    out = []
    for t, row in enumerate(ds["series"][pid], start=1):
        variance = row["net"] - (pr["income"] - pr["rent"] - living)
        lines_in = [{"key": f"income:{parts['income'][0]['id']}", "amountChf": pr["income"], "origin": "OBSERVED",
                     "sourceEventId": None}]
        lines_out = [{"key": "expense:RENT", "amountChf": pr["rent"], "origin": "OBSERVED", "sourceEventId": None},
                     {"key": "expense:LIVING", "amountChf": living, "origin": "DERIVED", "sourceEventId": None}]
        cash_flow = {
            "openingCashChf": row["opening"],
            "income": lines_in, "expenses": lines_out, "debtService": [],   # loans are not paid in the ledger
            "otherChf": round(variance, 2),        # DERIVED: noise, December bonus, anything not itemised
            "financingCostChf": 0.0,
            "totalIncomeChf": pr["income"], "totalExpensesChf": round(pr["rent"] + living, 2),
            "totalDebtServiceChf": 0.0,
            "netCashFlowChf": row["net"],          # OBSERVED (net_transactions_chf)
            "closingCashChf": row["closing"],      # OBSERVED (closing_cash_chf)
        }
        accounts = [{**a, "balanceChf": row["closing"],
                     "status": "NEGATIVE_REQUIRES_FINANCING" if row["negative"] else "OK"} for a in parts["accounts"]]
        person = {**parts["person"], "age": pr["ageAtStart"] + t // 12}
        out.append(assemble(profile_id=pid, month=t, start_month=pr["startMonth"], origin="OBSERVED",
                            person=person, accounts=accounts, investments=parts["investments"],
                            liabilities=parts["liabilities"], income=parts["income"], expenses=parts["expenses"],
                            cash_flow=cash_flow, goals=parts["goals"], scenario_parameters=parts["params"],
                            pending=[]))
    return out


def check_statement(s: dict, tol: float = 0.02) -> list[str]:
    """Accounting identities every statement must satisfy (used by tests and the build script)."""
    errors = []
    bs = s["balanceSheet"]
    if abs(bs["cashChf"] + bs["investmentsChf"] - bs["totalLiabilitiesChf"] - bs["netWorthChf"]) > tol:
        errors.append("balance sheet: cash + investments - liabilities != net worth")
    cf = s["cashFlow"]
    if cf is not None:
        net = (cf["totalIncomeChf"] - cf["totalExpensesChf"] - cf["totalDebtServiceChf"]
               - cf["financingCostChf"] + cf.get("otherChf", 0.0))
        if abs(net - cf["netCashFlowChf"]) > tol:
            errors.append(f"cash flow: lines sum {net:.2f} != net {cf['netCashFlowChf']:.2f}")
        if abs(cf["openingCashChf"] + cf["netCashFlowChf"] - cf["closingCashChf"]) > tol:
            errors.append("cash flow: opening + net != closing")
        if abs(cf["closingCashChf"] - bs["cashChf"]) > tol:
            errors.append("cash flow closing cash != balance sheet cash")
    return errors
