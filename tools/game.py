"""Reference game loop on FinancialStatements: events change fields, the simulator moves time (docs/simulator.md).

  apply_effects(statement, effects)   an event's resolved case becomes pendingChanges (docs/README.md 5.3), applied
                                      by the simulator at the start of their month
  pick_event(...)                     the event whose `requires` match the current statement (highest priority)
  case_from_luck(option, luck)        PESSIMISTIC / NEUTRAL / OPTIMISTIC from the turn's luck
  play(...)                           5 turns x 24 months with simulate.step; life line (beats) and attribution
  preview(...)                        every option x case of the events eligible at the start, over 10 years
Python reference for the Kotlin engine (Dev A); tools/validate_events.py uses it for --preview / --play.
"""
import copy
import random

from simulate import run, step
from statement import annuity_payment, goal_state, net_worth

CASE_ORDER = ["PESSIMISTIC", "NEUTRAL", "OPTIMISTIC"]   # order used to resolve the luck
HORIZON, MONTHS_PER_TURN, MAX_TURNS = 120, 24, 5


# ---------------------------------------------------------------------------------------------- events

def apply_effects(s: dict, effects: list[dict], event_id: str) -> dict:
    """The effects as pending changes, at the start of the turn (month s) plus their offset. A change that lasts
    `months` also schedules its reversal."""
    s = copy.deepcopy(s)
    t0, pending = s["month"], s["pendingChanges"]
    add = lambda month, **change: pending.append({"month": month, "sourceEventId": event_id, **change})
    for eff in effects:
        start = t0 + eff.get("startOffsetMonths", 0)
        end = start + eff["months"] if eff.get("months") is not None else None
        kind = eff["type"]
        if kind == "set_income":
            add(start, monthlyIncomeChf=eff["monthlyChf"])
        elif kind == "income_factor":                # e.g. part time: the income is multiplied by `factor`
            add(start, incomeFactor=eff["factor"])
            if end is not None:
                add(end, incomeFactor=1 / eff["factor"])
        elif kind == "set_household":
            add(start, household=eff["household"])
        elif kind == "market_rent":                  # a new home: rent from the rent model at this quantile
            add(start, marketRentQuantile=eff.get("quantile", 0.5))
        elif kind == "income_delta":
            add(start, incomeDeltaChf=eff["deltaChf"])
            if end is not None:
                add(end, incomeDeltaChf=-eff["deltaChf"])
        elif kind == "expense_delta":
            add(start, expenseCategory=eff["category"], expenseDeltaChf=eff["deltaChf"])
            if end is not None:
                add(end, expenseCategory=eff["category"], expenseDeltaChf=-eff["deltaChf"])
        elif kind == "one_off":                       # one month only (negative = a one-time gain)
            add(start, expenseCategory="OTHER", expenseDeltaChf=eff["chf"])
            add(start + 1, expenseCategory="OTHER", expenseDeltaChf=-eff["chf"])
        elif kind == "add_liability":
            add(start, loan={"principalChf": eff["principalChf"], "annualRate": eff["annualRate"],
                             "monthlyPaymentChf": round(annuity_payment(eff["principalChf"], eff["annualRate"],
                                                                        eff["termMonths"]), 2)})
        elif kind == "set_employment":
            add(start, employment=eff["employment"])
        elif kind == "set_fact":
            add(start, factKey=eff["key"], factValue=eff["value"])
        elif kind == "add_flag":
            add(start, flag=eff["flag"])
    return s


def eligible(ev: dict, s: dict, turn: int, used: set) -> bool:
    req, person, cash = ev.get("requires") or {}, s["person"], s["cashChf"]
    return all([
        turn >= ev.get("earliestTurn", 0),
        ev.get("repeatable", False) or ev["id"] not in used,
        "employment" not in req or person["employment"] in req["employment"],
        "household" not in req or person["household"] in req["household"],
        "minAge" not in req or person["age"] >= req["minAge"],
        "maxAge" not in req or person["age"] <= req["maxAge"],
        all(person["facts"].get(k) in v for k, v in (req.get("facts") or {}).items()),
        all(f in person["flags"] for f in req.get("flagsAll") or []),
        not any(f in person["flags"] for f in req.get("flagsNone") or []),
        "minCashChf" not in req or cash >= req["minCashChf"],
        "maxCashChf" not in req or cash <= req["maxCashChf"],
    ])


def pick_event(events: list[dict], s: dict, turn: int, used: set, rng: random.Random) -> dict | None:
    cands = [e for e in events if eligible(e, s, turn, used)]
    if not cands:
        return None
    top = max(e.get("priority", 0) for e in cands)
    best = [e for e in cands if e.get("priority", 0) == top]
    return best[0] if len(best) == 1 else rng.choice(best)


def case_from_luck(option: dict, luck: float) -> str:
    acc = 0.0
    for c in CASE_ORDER:
        acc += option["cases"][c]["prob"]
        if luck < acc:
            return c
    return CASE_ORDER[-1]


def apply_due_life_changes(s: dict) -> dict:
    """Life changes due at the start of the turn already count for picking the event (docs/README.md section 7).
    Money changes stay pending: the simulator applies them in the month's step."""
    s = copy.deepcopy(s)
    person, still = s["person"], []
    for ch in s["pendingChanges"]:
        if ch["month"] > s["month"]:
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
        else:
            still.append(ch)
    s["pendingChanges"] = still
    return s


# ---------------------------------------------------------------------------------------------- story and money

def beat(s: dict, kind: str, event_id: str | None = None, **data) -> dict:
    return {"month": s["month"], "age": s["person"]["age"], "kind": kind, "eventId": event_id,
            "data": {k: str(v) for k, v in data.items()}}


def beats_between(prev: dict, cur: dict) -> list[dict]:
    """Story moments, read from two consecutive statements (structured data, no prose)."""
    out, p0, p1 = [], prev["person"], cur["person"]
    due = [ch for ch in prev["pendingChanges"] if ch["month"] <= prev["month"]]
    src = lambda **match: next((ch["sourceEventId"] for ch in due
                                if all(ch.get(k) == v for k, v in match.items())), None)
    if p1["employment"] != p0["employment"]:
        out.append(beat(cur, "LIFE_CHANGE", src(employment=p1["employment"]), employment=p1["employment"]))
    if p1["household"] != p0["household"]:
        out.append(beat(cur, "LIFE_CHANGE", src(household=p1["household"]), household=p1["household"]))
    for k, v in p1["facts"].items():
        if p0["facts"].get(k) != v:
            out.append(beat(cur, "LIFE_CHANGE", src(factKey=k, factValue=v), fact=k, value=v))
    for f in p1["flags"]:
        if f not in p0["flags"]:
            out.append(beat(cur, "LIFE_CHANGE", src(flag=f), flag=f))
    if prev["cashChf"] >= 0 > cur["cashChf"]:
        out.append(beat(cur, "CASH_NEGATIVE", cash=round(cur["cashChf"])))
    elif prev["cashChf"] < 0 <= cur["cashChf"]:
        out.append(beat(cur, "CASH_RECOVERED", cash=round(cur["cashChf"])))
    for g in cur["goals"]:
        before, now = goal_state(g, prev), goal_state(g, cur)
        if now != before and now in ("REACHED", "MISSED") and before == "IN_PROGRESS":
            out.append(beat(cur, f"GOAL_{now}", goal=g["name"], target=round(g["targetChf"])))
    return out


def add_attribution(acc: dict, prev: dict, cur: dict) -> None:
    """Where the net worth change of one month comes from: start + sum(items) = end."""
    def add(key, chf):
        acc[key] = acc.get(key, 0.0) + chf
    cf = cur["cashFlow"]
    for k, v in cf["incomeChf"].items():
        if k != "loan":                               # a loan paid out raises cash and debt alike
            add(f"income:{k}", v)
    for k, v in cf["expensesChf"].items():
        add(f"expense:{k}", -v)
    principal = lambda s: s["loan"]["principalChf"] if s["loan"] else 0.0
    repaid = principal(prev) + cf["incomeChf"].get("loan", 0.0) - principal(cur)
    if cf["loanPaymentChf"]:
        add("loan_interest", -(cf["loanPaymentChf"] - repaid))
    if cf["financingCostChf"]:
        add("financing_cost", -cf["financingCostChf"])
    growth = cur["investmentsChf"] - prev["investmentsChf"]
    if growth:
        add("investment_growth", growth)


def advance(s: dict, months: int, model: dict, macro: dict | None, beats: list, attribution: dict) -> tuple[dict, list]:
    monthly = [s]
    for _ in range(months):
        nxt = step(monthly[-1], model, macro=macro)
        beats.extend(beats_between(monthly[-1], nxt))
        add_attribution(attribution, monthly[-1], nxt)
        monthly.append(nxt)
    return monthly[-1], monthly[1:]


def play(events: list[dict], s0: dict, model: dict, macro: dict | None, choices: list[str], lucks: list[float],
         seed: int, lang: str = "EN") -> dict:
    """A whole game. Returns one statement per turn (and every month), the life line and the attribution."""
    rng, used, attribution = random.Random(seed), set(), {}
    beats = [beat(s0, "START", cash=round(s0["cashChf"]))]
    s, per_turn, monthly = s0, [s0], [s0]
    for turn in range(MAX_TURNS):
        before, s = s, apply_due_life_changes(s)
        beats.extend(beats_between(before, s))
        ev = pick_event(events, s, turn, used, rng)
        luck = lucks[turn] if turn < len(lucks) else rng.random()
        if ev is None:
            beats.append(beat(s, "QUIET_PERIOD"))
        else:
            used.add(ev["id"])
            opt_id = choices[turn] if turn < len(choices) else "A"
            opt = next(o for o in ev["options"] if o["id"] == opt_id)
            case = case_from_luck(opt, luck)
            beats.append(beat(s, "DECISION", ev["id"], option=opt_id, label=opt["label"][lang]))
            beats.append(beat(s, "CASE_RESOLVED", ev["id"], case=case, luck=f"{luck:.2f}",
                              narrative=opt["cases"][case]["narrative"][lang]))
            s = apply_effects(s, opt["cases"][case]["effects"], ev["id"])
        s, months = advance(s, MONTHS_PER_TURN, model, macro, beats, attribution)
        per_turn.append(s)
        monthly.extend(months)
    beats.append(beat(s, "END", netWorth=round(net_worth(s)), cash=round(s["cashChf"])))
    start_nw, end_nw = net_worth(s0), net_worth(s)
    return {"statements": per_turn, "monthly": monthly, "beats": beats, "attribution": attribution,
            "startNetWorth": start_nw, "endNetWorth": end_nw,
            "attributionGap": round(start_nw + sum(attribution.values()) - end_nw, 2)}


def preview(events: list[dict], s0: dict, model: dict, macro: dict | None, horizon: int = HORIZON) -> list[dict]:
    """Every option x case of the events eligible at the start, simulated for `horizon` months."""
    def summary(sts):
        cash = [x["cashChf"] for x in sts]
        low = min(range(len(cash)), key=cash.__getitem__)
        return {"netWorth": net_worth(sts[-1]), "minCash": cash[low], "monthOfMinCash": sts[low]["month"],
                "monthsNegative": sum(c < 0 for c in cash[1:])}

    rows = [{"eventId": None, "optionId": None, "case": None, "prob": 1.0,
             **summary(run(s0, horizon, model, macro=macro))}]
    for ev in events:
        if not eligible(ev, s0, ev.get("earliestTurn", 0), set()):
            continue
        for opt in ev["options"]:
            for cname in CASE_ORDER:
                case = opt["cases"][cname]
                s1 = apply_effects(s0, case["effects"], ev["id"])
                rows.append({"eventId": ev["id"], "optionId": opt["id"], "case": cname, "prob": case["prob"],
                             **summary(run(s1, horizon, model, macro=macro))})
    return rows
