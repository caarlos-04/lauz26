"""Reference simulator: FinancialStatement(t) -> FinancialStatement(t+1) (docs/simulator.md).

One monthly step:
  0. changes decided by events that are due are applied   (person, income, event expenses, a new loan)
  1. trends, every month from the second one                (macro.json: wages x career for age and occupation,
                                                             sitting-tenant rent, prices; 1/12 of the yearly rate)
  2. the flow model says what comes in and goes out         (flow-model.json, from every field of the state)
  3. loan payment, interest on negative cash, investment growth
  4. accounting: cash(t+1) = cash(t) + net of the month
The model never writes cash directly: it only produces the month's flows.

Modes: "expected" (deterministic, used by the game: one state per decision) and "sample" (draws occurrences and
amounts from the learned distributions; for future uncertainty features).

    python tools/simulate.py --profile P00001 [--months 120] [--zero-trends]   # opening statement -> N months
    python tools/simulate.py --validate                                       # 24-month check on every profile
"""
import argparse
import copy
import json
import random
import statistics as st
from pathlib import Path

from common import default_data_dir, load_dataset
from flow_model import FIXED_EDGES, person_features, predict_raw, statement_numbers
from statement import calendar_month, check_statement, dataset_opening, net_worth, next_period, statement

OUT = Path(__file__).resolve().parent / "out"
DEFAULT_FACTS = Path(__file__).resolve().parents[1] / "content" / "facts.json"

# Engine rules that are not in the data (ASSUMED, documented in docs/simulator.md). Owner of the final values:
# content/assumptions.json (Dev C); these are defaults until that file exists.
DEFAULTS = {
    "financingRateAnnual": 0.085,       # interest on negative cash = max rate in synthetic-liabilities.csv
    "investmentReturnAnnual": 0.0,      # no returns in the dataset; pending Assumptions
}
TREND_KEYS = ("priceInflationAnnual", "wageGrowthAnnual", "rentGrowthSittingTenantAnnual")
WORKING = {"EMPLOYED", "PART_TIME", "SELF_EMPLOYED"}      # the career curve only moves the pay of people who work


# ---------------------------------------------------------------------------------------------- trends

def trend_rates(macro: dict | None) -> dict:
    if macro is None:
        return {k: 0.0 for k in TREND_KEYS}
    return {k: macro["trends"][k]["value"] or 0.0 for k in TREND_KEYS}


def career_curves(macro: dict | None) -> dict | None:
    return (macro or {}).get("trends", {}).get("careerCurveByAge", {}).get("value")


def career_level(curve: dict, age: float) -> float:
    """Median wage at `age`: linear between the survey points, flat before the first and after flatAfterAge."""
    pts = curve["points"]
    age = min(age, curve.get("flatAfterAge", pts[-1][0]))
    if age <= pts[0][0]:
        return pts[0][1]
    for (a0, v0), (a1, v1) in zip(pts, pts[1:]):
        if age <= a1:
            return v0 + (v1 - v0) * (age - a0) / (a1 - a0)
    return pts[-1][1]


def career_factor(curves: dict | None, occupation: str | None, age_from: float, age_to: float) -> float:
    """Pay change from age_from to age_to on the career curve of the person's occupation (default curve if the
    occupation has none)."""
    if not curves:
        return 1.0
    name = curves.get("occupationCurve", {}).get(occupation or "", curves.get("defaultCurve"))
    curve = curves["curves"].get(name) or curves["curves"][curves["defaultCurve"]]
    return career_level(curve, age_to) / career_level(curve, age_from)


# ---------------------------------------------------------------------------------------------- flows

def features_of(s: dict, model: dict) -> dict:
    """Every field that describes the person: categorical fields and facts, numeric fields as bands."""
    edges = model.get("meta", {}).get("bandEdges", FIXED_EDGES)
    return person_features(s["person"], statement_numbers(s), model["cityCanton"], edges)


def flow_amount(flow: dict, feats: dict, mode: str, rng: random.Random | None) -> float:
    """Unsigned amount per unit of basis: expected value, or one draw (occurrence x residual decile)."""
    p = min(predict_raw(flow["probability"]["base"], flow["probability"]["factors"], feats,
                        flow["probability"].get("unknown")), 1.0)
    a = predict_raw(flow["amount"]["base"], flow["amount"]["factors"], feats, flow["amount"].get("unknown"))
    if mode == "expected":
        return p * a
    if rng.random() >= p:
        return 0.0
    d, u = flow["amount"]["residualDeciles"], rng.random() * 10     # interpolate between the 11 deciles
    i = min(int(u), 9)
    return a * (d[i] + (d[i + 1] - d[i]) * (u - i))


# ---------------------------------------------------------------------------------------------- step

def merge_loan(old: dict | None, new: dict) -> dict:
    """The data have at most one loan per person: a new one is merged (principal and payment add up, the rate is
    the principal-weighted mean)."""
    if not old or old["principalChf"] <= 0:
        return dict(new)
    p = old["principalChf"] + new["principalChf"]
    rate = (old["principalChf"] * old["annualRate"] + new["principalChf"] * new["annualRate"]) / p
    return {"principalChf": round(p, 2), "annualRate": round(rate, 6),
            "monthlyPaymentChf": round(old["monthlyPaymentChf"] + new["monthlyPaymentChf"], 2)}


def residual_quantile(deciles: list[float], q: float) -> float:
    """Value at quantile q (0..1) of a distribution given by its 11 deciles (linear in between)."""
    u = min(max(q, 0.0), 1.0) * 10
    i = min(int(u), 9)
    return deciles[i] + (deciles[i + 1] - deciles[i]) * (u - i)


def market_rent(model: dict, s: dict, quantile: float) -> float:
    """Rent of a new home for the person's city and household (rent model of flow-model.json), in 2025 CHF."""
    rm = model["rentModel"]
    return predict_raw(rm["base"], rm["factors"], features_of(s, model), rm.get("unknown")) * \
        residual_quantile(rm["residualDeciles"], quantile)


def apply_due_changes(s: dict, t: int) -> tuple[dict, float, float | None]:
    """Apply the pending changes with month <= t. Returns the new statement, the cash paid out by new loans and the
    quantile of a requested market rent (None if no move this month)."""
    s = copy.deepcopy(s)
    person, still, loan_cash, rent_quantile = s["person"], [], 0.0, None
    for ch in s["pendingChanges"]:
        if ch["month"] > t:
            still.append(ch)
        elif ch.get("employment"):
            person["employment"] = ch["employment"]
        elif ch.get("factKey"):
            person["facts"][ch["factKey"]] = ch["factValue"]
        elif ch.get("flag"):
            if ch["flag"] not in person["flags"]:
                person["flags"].append(ch["flag"])
        elif ch.get("household"):
            person["household"] = ch["household"]
        elif ch.get("incomeFactor") is not None:
            s["monthlyIncomeChf"] = round(s["monthlyIncomeChf"] * ch["incomeFactor"], 2)
        elif ch.get("marketRentQuantile") is not None:
            rent_quantile = ch["marketRentQuantile"]      # computed once every change of this month is applied
        elif ch.get("monthlyIncomeChf") is not None:
            s["monthlyIncomeChf"] = ch["monthlyIncomeChf"]
        elif ch.get("incomeDeltaChf") is not None:
            s["monthlyIncomeChf"] = round(s["monthlyIncomeChf"] + ch["incomeDeltaChf"], 2)
        elif ch.get("expenseCategory"):
            cat = ch["expenseCategory"]
            s["eventExpensesChf"][cat] = round(s["eventExpensesChf"].get(cat, 0.0) + ch["expenseDeltaChf"], 2)
            if abs(s["eventExpensesChf"][cat]) < 0.005:
                del s["eventExpensesChf"][cat]
        elif ch.get("loan"):
            s["loan"] = merge_loan(s["loan"], ch["loan"])
            loan_cash += ch["loan"]["principalChf"]
    s["pendingChanges"] = still
    return s, loan_cash, rent_quantile


def step(s: dict, model: dict, *, macro: dict | None = None, mode: str = "expected",
         rng: random.Random | None = None, rules: dict | None = None) -> dict:
    rules = {**DEFAULTS, **(rules or {})}
    rates = trend_rates(macro)
    t = s["month"]                                   # this step simulates the month that ends at statement t+1
    period = next_period(s["period"])
    cm = calendar_month(period)
    price_index = (1 + rates["priceInflationAnnual"]) ** (t / 12)     # learned flows, in 2025 CHF, t months later

    # 0. changes decided by events
    cur, loan_cash, rent_quantile = apply_due_changes(s, t)
    person = cur["person"]

    # 1. trends, every month from the second one, 1/12 of the yearly rate (compounded). Event expenses are not
    #    indexed: they are the amounts the event sets, and their reversal (end of a tuition) must cancel exactly.
    income, rent = cur["monthlyIncomeChf"], cur["monthlyRentChf"]
    event_expenses = dict(cur["eventExpensesChf"])
    if t > 0:
        monthly = lambda rate: (1 + rate) ** (1 / 12)
        career = 1.0
        if person["employment"] in WORKING:
            age_now = person["age"] + (t % 12) / 12                    # age in years and months at statement t
            career = career_factor(career_curves(macro), person["facts"].get("OCCUPATION"), age_now - 1 / 12, age_now)
        income = round(income * monthly(rates["wageGrowthAnnual"]) * career, 2)
        rent = round(rent * monthly(rates["rentGrowthSittingTenantAnnual"]), 2)
    if rent_quantile is not None:       # a new home: market rent for the new city / household, at today's rent level
        rent_index = (1 + rates["rentGrowthSittingTenantAnnual"]) ** (t / 12)
        rent = round(market_rent(model, cur, rent_quantile) * rent_index, 2)
    cur = {**cur, "monthlyIncomeChf": income, "monthlyRentChf": rent, "eventExpensesChf": event_expenses}

    # 2. flows of the month, from the state at its start
    feats = features_of(cur, model)
    flows = {f["key"]: f for f in model["flows"] if cm in f["months"]}
    inc, exp = {}, {}
    inc["salary"] = income * (flow_amount(flows["salary"], feats, mode, rng) if "salary" in flows else 1.0)
    exp["rent"] = rent * (flow_amount(flows["rent"], feats, mode, rng) if "rent" in flows else 1.0)
    for cat, v in event_expenses.items():
        exp[cat] = v
    for key, f in flows.items():
        # an event's TUITION replaces the learned tuition of the dataset students (never counted twice)
        if key in ("salary", "rent") or (f["expenseCategory"] == "TUITION" and "TUITION" in event_expenses):
            continue
        amt = flow_amount(f, feats, mode, rng) * (income if f["basis"] == "INCOME" else price_index)
        if amt:
            (inc if f["direction"] == "IN" else exp)[key] = amt
    if loan_cash:
        inc["loan"] = loan_cash

    # 3. loan, financing, investments
    loan, payment = cur["loan"], 0.0
    if loan and loan["principalChf"] > 0:
        interest = loan["principalChf"] * loan["annualRate"] / 12
        payment = round(min(loan["monthlyPaymentChf"], loan["principalChf"] + interest), 2)
        loan = {**loan, "principalChf": round(loan["principalChf"] + interest - payment, 2)}
    financing = round(-s["cashChf"] * rules["financingRateAnnual"] / 12, 2) if s["cashChf"] < 0 else 0.0
    investments = cur["investmentsChf"] * (1 + rules["investmentReturnAnnual"] / 12)

    # 4. accounting
    inc = {k: round(v, 2) for k, v in inc.items()}
    exp = {k: round(v, 2) for k, v in exp.items()}
    net = round(sum(inc.values()) - sum(exp.values()) - payment - financing, 2)
    if (t + 1) % 12 == 0:
        person = {**person, "age": person["age"] + 1}
    return statement(profile_id=s["profileId"], month=t + 1, period=period, person=person, cash=s["cashChf"] + net,
                     investments=investments, loan=loan, income=income, rent=rent, event_expenses=event_expenses,
                     cash_flow={"incomeChf": inc, "expensesChf": exp, "loanPaymentChf": payment,
                                "financingCostChf": financing, "netChf": net},
                     goals=cur["goals"], pending=cur["pendingChanges"])


def run(s: dict, months: int, model: dict, *, macro: dict | None = None, mode: str = "expected", seed: int = 0,
        rules: dict | None = None) -> list[dict]:
    rng = random.Random(seed)
    out = [s]
    for _ in range(months):
        out.append(step(out[-1], model, macro=macro, mode=mode, rng=rng, rules=rules))
    return out


# ---------------------------------------------------------------------------------------------- validation

def validate(ds: dict, model: dict, facts_catalog: dict) -> dict:
    """Every profile, 24 months, expected mode, no trends (the ledger has none), no loan payments (the ledger does
    not pay them). In-sample: the model was trained on these profiles (flow-backtest.json is the out-of-sample one)."""
    errs, identity_errors = [], 0
    for pid in sorted(ds["profiles"]):
        s0 = dataset_opening(ds, pid, facts_catalog)
        if s0["loan"]:
            s0["loan"]["monthlyPaymentChf"] = 0.0
        sts = run(s0, len(ds["series"][pid]), model)
        for prev, s, row in zip(sts, sts[1:], ds["series"][pid]):
            identity_errors += len(check_statement(s, prev))
            errs.append(abs(s["cashChf"] - row["closing"]))
    return {"profiles": len(ds["profiles"]), "identityErrors": identity_errors,
            "medianAbsErrorClosingChf": round(st.median(errs), 2), "meanAbsErrorClosingChf": round(st.mean(errs), 2)}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data-dir", type=Path, default=default_data_dir())
    ap.add_argument("--model", type=Path, default=OUT / "flow-model.json")
    ap.add_argument("--macro", type=Path, default=OUT / "macro.json")
    ap.add_argument("--facts", type=Path, default=DEFAULT_FACTS)
    ap.add_argument("--profile", default="P00001")
    ap.add_argument("--months", type=int, default=120)
    ap.add_argument("--mode", choices=("expected", "sample"), default="expected")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--zero-trends", action="store_true", help="ignore macro.json (no growth of wages or prices)")
    ap.add_argument("--validate", action="store_true")
    ap.add_argument("--dump", type=Path, help="write every statement to this JSON file")
    args = ap.parse_args()

    model = json.loads(args.model.read_text(encoding="utf-8"))
    facts_catalog = json.loads(args.facts.read_text(encoding="utf-8"))
    ds = load_dataset(args.data_dir)
    if args.validate:
        print(json.dumps(validate(ds, model, facts_catalog), indent=2))
        return
    macro = None if args.zero_trends else json.loads(args.macro.read_text(encoding="utf-8"))
    sts = run(dataset_opening(ds, args.profile, facts_catalog), args.months, model, macro=macro, mode=args.mode,
              seed=args.seed)
    for s in sts:
        if s["month"] % 12 == 0 or s["month"] in (1, 2):
            net = s["cashFlow"]["netChf"] if s["cashFlow"] else 0.0
            print(f"month {s['month']:3d} {s['period']:<10} age {s['person']['age']}  cash {s['cashChf']:>11,.2f}  "
                  f"net worth {net_worth(s):>11,.2f}  net {net:>9,.2f}  income {s['monthlyIncomeChf']:>9,.2f}")
    bad = [e for prev, s in zip(sts, sts[1:]) for e in check_statement(s, prev)]
    print(f"{len(sts)} statements, {len(bad)} identity error(s)")
    if args.dump:
        args.dump.write_text(json.dumps({"profileId": args.profile, "statements": sts}, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
