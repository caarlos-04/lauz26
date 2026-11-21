"""Validate the game content and run it with a reference implementation of the engine rules.

    python tools/validate_events.py                          # validate content/events.json + content/facts.json
    python tools/validate_events.py --preview P00001         # each option x case of the events eligible at start
    python tools/validate_events.py --play P00001 --choices A,A --luck 0.5,0.1
                                                             # play a whole game and print the life line

The engine here follows docs/README.md sections 5.3 and 7 WITHOUT inflation, salary growth or asset returns
(those come from Assumptions, not written yet). It includes the December bonus (from calibration.json) and
interest on negative cash. Use it to balance event content and as expected values for Dev A's engine tests.
Needs tools/out/profiles-sample.json and calibration.json (run calibrate.py first).
"""
import argparse
import copy
import json
import random
import sys
from pathlib import Path

from calibrate import annuity_payment
from statement import GOAL_BASIS, assemble, check_statement

ROOT = Path(__file__).resolve().parents[1]
OUT = Path(__file__).resolve().parent / "out"
DEFAULT_EVENTS = ROOT / "content" / "events.json"
DEFAULT_FACTS = ROOT / "content" / "facts.json"

LANGS = {"ES", "EN"}
CASE_ORDER = ["PESSIMISTIC", "NEUTRAL", "OPTIMISTIC"]   # order used to resolve the luck
EMPLOYMENT = {"STUDENT", "EMPLOYED", "PART_TIME", "SELF_EMPLOYED", "RETIRED", "UNEMPLOYED"}
EXPENSE_CATEGORIES = {"RENT", "LIVING", "TUITION", "INSURANCE", "TRAVEL", "OTHER"}
ASSET_TYPES = {"CASH", "INVESTMENT_PORTFOLIO"}
LIABILITY_TYPES = {"PERSONAL_LOAN"}
BONUS_EMPLOYMENT = {"EMPLOYED", "SELF_EMPLOYED"}
HORIZON, MONTHS_PER_TURN, MAX_TURNS = 120, 24, 5
FINANCING_RATE = 0.085   # max rate in synthetic-liabilities.csv; will move to content/assumptions.json
PROB_TOLERANCE = 1e-6

EVENT_FIELDS = {"id", "category", "title", "description", "requires", "priority", "repeatable",
                "earliestTurn", "sources", "options"}
REQUIRE_FIELDS = {"employment", "minAge", "maxAge", "facts", "flagsAll", "flagsNone", "minCashChf", "maxCashChf"}
# type -> (required fields, optional fields)
EFFECTS = {
    "set_income": ({"streamId", "monthlyChf"}, {"startOffsetMonths"}),
    "income_delta": ({"streamId", "deltaChf"}, {"startOffsetMonths", "months"}),
    "expense_delta": ({"category", "deltaChf"}, {"startOffsetMonths", "months"}),
    "one_off": ({"chf"}, {"startOffsetMonths"}),
    "add_liability": ({"liabilityType", "principalChf", "annualRate", "termMonths"}, {"disburseToCash"}),
    "asset_return_delta": ({"assetType", "deltaAnnualRate"}, set()),
    "set_employment": ({"employment"}, {"startOffsetMonths"}),
    "set_fact": ({"key", "value"}, {"startOffsetMonths"}),
    "add_flag": ({"flag"}, {"startOffsetMonths"}),
}


# ================================================================ validation

def _is_text(value) -> bool:
    return isinstance(value, dict) and set(value) == LANGS and all(isinstance(v, str) and v.strip() for v in value.values())


def validate_facts(cat: dict) -> list[str]:
    errors = []
    for key, values in cat.get("facts", {}).items():
        for code, label in values.items():
            if not _is_text(label):
                errors.append(f"facts.{key}.{code}: label must have ES and EN")
    for flag, label in cat.get("flags", {}).items():
        if not _is_text(label):
            errors.append(f"flags.{flag}: label must have ES and EN")
    for key, rule in cat.get("initialFromProfile", {}).items():
        allowed = cat["facts"].get(key, {})
        for v in list(rule.get("map", {}).values()) + ([rule["default"]] if "default" in rule else []):
            if v not in allowed:
                errors.append(f"initialFromProfile.{key}: {v!r} is not a {key} value")
    return errors


def _check_effect(errors: list, where: str, eff: dict, cat: dict) -> None:
    etype = eff.get("type")
    if etype not in EFFECTS:
        errors.append(f"{where}: unknown effect type {etype!r}")
        return
    required, optional = EFFECTS[etype]
    keys = set(eff) - {"type"}
    if missing := required - keys:
        errors.append(f"{where}: {etype} missing {sorted(missing)}")
    if extra := keys - required - optional:
        errors.append(f"{where}: {etype} has unknown fields {sorted(extra)}")
    if eff.get("startOffsetMonths", 0) < 0:
        errors.append(f"{where}: startOffsetMonths must be >= 0")
    if eff.get("months") is not None and eff["months"] <= 0:
        errors.append(f"{where}: months must be > 0 or null")
    checks = [("category", EXPENSE_CATEGORIES), ("employment", EMPLOYMENT),
              ("assetType", ASSET_TYPES), ("liabilityType", LIABILITY_TYPES)]
    for field, allowed in checks:
        if field in eff and eff[field] not in allowed:
            errors.append(f"{where}: unknown {field} {eff[field]!r}")
    if etype == "add_liability" and eff.get("termMonths", 1) <= 0:
        errors.append(f"{where}: termMonths must be > 0")
    if etype == "set_fact" and eff.get("value") not in cat["facts"].get(eff.get("key"), {}):
        errors.append(f"{where}: fact {eff.get('key')}={eff.get('value')!r} not in content/facts.json")
    if etype == "add_flag" and eff.get("flag") not in cat["flags"]:
        errors.append(f"{where}: flag {eff.get('flag')!r} not declared in content/facts.json")


def _check_requires(errors: list, where: str, req: dict, cat: dict) -> None:
    if extra := set(req) - REQUIRE_FIELDS:
        errors.append(f"{where}: unknown fields {sorted(extra)}")
    for e in req.get("employment", []):
        if e not in EMPLOYMENT:
            errors.append(f"{where}: unknown employment {e!r}")
    for key, values in (req.get("facts") or {}).items():
        for v in values:
            if v not in cat["facts"].get(key, {}):
                errors.append(f"{where}: fact {key}={v!r} not in content/facts.json")
    for f in (req.get("flagsAll") or []) + (req.get("flagsNone") or []):
        if f not in cat["flags"]:
            errors.append(f"{where}: flag {f!r} not declared in content/facts.json")


def validate(doc: dict, cat: dict) -> list[str]:
    """Return a list of human-readable errors (empty = valid)."""
    errors = validate_facts(cat)
    events = doc.get("events")
    if not isinstance(events, list) or not events:
        return errors + ["root: 'events' must be a non-empty list"]
    seen = set()
    for i, ev in enumerate(events):
        eid = ev.get("id", f"#{i}")
        w = f"event {eid}"
        if eid in seen:
            errors.append(f"{w}: duplicated id")
        seen.add(eid)
        if extra := set(ev) - EVENT_FIELDS:
            errors.append(f"{w}: unknown fields {sorted(extra)}")
        for field in ("category", "title", "description", "options"):
            if field not in ev:
                errors.append(f"{w}: missing {field}")
        for field in ("title", "description"):
            if not _is_text(ev.get(field)):
                errors.append(f"{w}.{field}: must be {{'ES': '...', 'EN': '...'}} with non-empty text")
        _check_requires(errors, f"{w}.requires", ev.get("requires") or {}, cat)
        if not ev.get("sources"):
            errors.append(f"{w}: 'sources' is empty (every number needs a source or 'assumption')")
        options = ev.get("options", [])
        if [o.get("id") for o in options] != ["A", "B"]:
            errors.append(f"{w}: must have exactly 2 options with ids 'A' and 'B'")
        for opt in options:
            ow = f"{w}.option {opt.get('id')}"
            if not _is_text(opt.get("label")):
                errors.append(f"{ow}.label: must have ES and EN")
            cases = opt.get("cases", {})
            if set(cases) != set(CASE_ORDER):
                errors.append(f"{ow}: cases must be exactly {CASE_ORDER}")
            total = sum(c.get("prob", 0) for c in cases.values())
            if abs(total - 1.0) > PROB_TOLERANCE:
                errors.append(f"{ow}: probabilities sum to {total:.4f}, expected 1.0")
            for cname, case in cases.items():
                cw = f"{ow}.{cname}"
                if not 0 < case.get("prob", 0) < 1:
                    errors.append(f"{cw}: prob must be in (0, 1)")
                if not _is_text(case.get("narrative")):
                    errors.append(f"{cw}.narrative: must have ES and EN")
                if not case.get("effects"):
                    errors.append(f"{cw}: no effects")
                for j, eff in enumerate(case.get("effects", [])):
                    _check_effect(errors, f"{cw}.effects[{j}]", eff, cat)
    return errors


# ================================================================ reference engine

def new_twin(item: dict) -> dict:
    """Internal engine state at month 0 from the opening FinancialStatement of a profiles-sample.json item."""
    st = item["initialStatement"]
    finance = {
        "cash": st["balanceSheet"]["cashChf"],
        "assets": [{"id": i["id"], "type": i["type"], "valueChf": i["valueChf"], "annualReturn": i["annualReturn"]}
                   for i in st["investments"]],
        "liabilities": copy.deepcopy(st["liabilities"]),
        "income": copy.deepcopy(st["incomeStreams"]),
        "expenses": copy.deepcopy(st["expenseStreams"]),
    }
    life = {"employment": st["person"]["employment"], "facts": dict(st["person"]["facts"]),
            "flags": list(st["person"]["flags"]), "pending": []}
    return {"month": 0, "finance": finance, "life": life}


def to_statement(eng: "Engine", item: dict, twin: dict) -> dict:
    """FinancialStatement (same shape as tools/statement.py) of the engine state at twin['month']."""
    st0, fin, life, t = item["initialStatement"], twin["finance"], twin["life"], twin["month"]
    city = life["facts"].get("CITY", st0["person"]["city"])
    person = {**st0["person"], "age": eng.age(t), "employment": life["employment"], "facts": dict(life["facts"]),
              "flags": list(life["flags"]), "city": city,
              "canton": st0["person"]["canton"] if city == st0["person"]["city"] else None}
    accounts = [{**a, "balanceChf": round(fin["cash"], 2),
                 "status": "NEGATIVE_REQUIRES_FINANCING" if fin["cash"] < 0 else "OK"} for a in st0["accounts"]]
    inv0 = {i["id"]: i for i in st0["investments"]}
    investments = [{**inv0.get(a["id"], {"accountId": None, "valuationBasis": "simulated"}), "id": a["id"],
                    "type": a["type"], "valueChf": round(a["valueChf"], 2), "annualReturn": a.get("annualReturn")}
                   for a in fin["assets"]]
    return assemble(profile_id=st0["profileId"], month=t, start_month=eng.profile["startMonth"],
                    origin="OBSERVED" if t == 0 else "SIMULATED", person=person, accounts=accounts,
                    investments=investments, liabilities=copy.deepcopy(fin["liabilities"]),
                    income=copy.deepcopy(fin["income"]), expenses=copy.deepcopy(fin["expenses"]),
                    cash_flow=copy.deepcopy(eng.last_cash_flow) if t > 0 else None, goals=eng.profile["goals"],
                    scenario_parameters=st0["scenarioParameters"], pending=copy.deepcopy(life["pending"]))


def _active(stream: dict, t: int) -> bool:
    return stream.get("fromMonth", 0) <= t and (stream.get("untilMonth") is None or t < stream["untilMonth"])


def net_worth(twin: dict) -> float:
    f = twin["finance"]
    return f["cash"] + sum(a["valueChf"] for a in f["assets"]) - sum(l["principalChf"] for l in f["liabilities"])


def apply_effects(twin: dict, effects: list[dict], event_id: str) -> dict:
    """New twin with the effects applied at the start of the turn (s = twin month). Docs section 5.3."""
    tw = copy.deepcopy(twin)
    s, fin, life = tw["month"], tw["finance"], tw["life"]
    for n, eff in enumerate(effects):
        start = s + eff.get("startOffsetMonths", 0)
        end = start + eff["months"] if eff.get("months") is not None else None
        kind = eff["type"]
        stream = {"fromMonth": start, "untilMonth": end, "sourceEventId": event_id, "origin": "SIMULATED",
                  "indexation": "WAGES" if kind in ("set_income", "income_delta") else "CPI"}
        if kind == "set_income":
            for inc in fin["income"]:
                if inc["id"] == eff["streamId"] and _active(inc, start):
                    inc["untilMonth"] = start
            fin["income"].append({**stream, "id": eff["streamId"], "label": eff["streamId"],
                                  "monthlyChf": eff["monthlyChf"], "untilMonth": None})
        elif kind == "income_delta":
            fin["income"].append({**stream, "id": eff["streamId"], "label": eff["streamId"], "monthlyChf": eff["deltaChf"]})
        elif kind == "expense_delta":
            fin["expenses"].append({**stream, "id": f"{event_id}-{n}", "category": eff["category"],
                                    "monthlyChf": eff["deltaChf"]})
        elif kind == "one_off":
            fin["expenses"].append({**stream, "id": f"{event_id}-{n}", "category": "OTHER",
                                    "monthlyChf": eff["chf"], "untilMonth": start + 1, "indexation": "NONE"})
        elif kind == "add_liability":
            fin["liabilities"].append({"id": f"{event_id}-{n}", "type": eff["liabilityType"],
                                       "principalChf": eff["principalChf"], "annualRate": eff["annualRate"],
                                       "monthlyPaymentChf": annuity_payment(eff["principalChf"], eff["annualRate"],
                                                                            eff["termMonths"]),
                                       "paymentIsAssumed": False, "sourceEventId": event_id})
            if eff.get("disburseToCash", True):
                fin["cash"] += eff["principalChf"]
        elif kind == "asset_return_delta":
            for a in fin["assets"]:
                if a["type"] == eff["assetType"]:
                    a["annualReturn"] = (a.get("annualReturn") or 0.0) + eff["deltaAnnualRate"]
        elif kind in ("set_employment", "set_fact", "add_flag"):
            change = {"month": start, "sourceEventId": event_id}
            if kind == "set_employment":
                change["employment"] = eff["employment"]
            elif kind == "set_fact":
                change.update(factKey=eff["key"], factValue=eff["value"])
            else:
                change["flag"] = eff["flag"]
            life["pending"].append(change)
    return tw


class Engine:
    """Month-by-month rules of docs section 7 (no inflation / salary growth / asset returns yet)."""

    def __init__(self, profile: dict, bonus_ratio: dict, financing_rate: float = FINANCING_RATE):
        self.profile = profile
        self.bonus_ratio = bonus_ratio          # employment -> December bonus ratio
        self.financing_rate = financing_rate
        self.start_cal_month = int(profile["startMonth"][5:7])   # 1..12
        self.last_cash_flow = None                                # cash flow of the last simulated month

    def age(self, month: int) -> int:
        return self.profile["ageAtStart"] + month // 12

    def is_december(self, t: int) -> bool:
        return (self.start_cal_month - 1 + t) % 12 == 11

    def apply_life(self, tw: dict, beats: list) -> None:
        """Apply (in place) the pending LifeChange whose month <= tw['month'] and emit LIFE_CHANGE beats."""
        t, life, still = tw["month"], tw["life"], []
        for ch in life["pending"]:
            if ch["month"] > t:
                still.append(ch)
                continue
            if "employment" in ch:
                life["employment"] = ch["employment"]
                beats.append(self.beat(t, "LIFE_CHANGE", ch["sourceEventId"], employment=ch["employment"]))
            elif "factKey" in ch:
                life["facts"][ch["factKey"]] = ch["factValue"]
                beats.append(self.beat(t, "LIFE_CHANGE", ch["sourceEventId"], fact=ch["factKey"], value=ch["factValue"]))
            else:
                if ch["flag"] not in life["flags"]:
                    life["flags"].append(ch["flag"])
                beats.append(self.beat(t, "LIFE_CHANGE", ch["sourceEventId"], flag=ch["flag"]))
        life["pending"] = still

    def step(self, twin: dict, attribution: dict, beats: list) -> dict:
        tw = copy.deepcopy(twin)
        t, fin, life = tw["month"], tw["finance"], tw["life"]

        # Life changes scheduled for this month apply first (so employment drives this month's bonus).
        self.apply_life(tw, beats)

        def add(key, chf, src=None):
            k = (key, src)
            attribution[k] = attribution.get(k, 0.0) + chf

        def line(key, amount, src, origin):
            return {"key": key, "amountChf": round(amount, 2), "origin": origin, "sourceEventId": src}

        income, inc_lines = 0.0, []
        for i in fin["income"]:
            if _active(i, t):
                income += i["monthlyChf"]
                add(f"income:{i['id']}", i["monthlyChf"], i.get("sourceEventId"))
                inc_lines.append(line(f"income:{i['id']}", i["monthlyChf"], i.get("sourceEventId"), i.get("origin", "SIMULATED")))
        if self.is_december(t) and life["employment"] in BONUS_EMPLOYMENT:
            bonus = self.bonus_ratio.get(life["employment"], 0.0) * income
            income += bonus
            add("income:december_bonus", bonus)
            inc_lines.append(line("income:december_bonus", bonus, None, "DERIVED"))
        expense, exp_lines = 0.0, []
        for e in fin["expenses"]:
            if _active(e, t):
                expense += e["monthlyChf"]
                add(f"expense:{e['category']}", -e["monthlyChf"], e.get("sourceEventId"))
                exp_lines.append(line(f"expense:{e['category']}", e["monthlyChf"], e.get("sourceEventId"), e.get("origin", "SIMULATED")))
        paid, debt_lines = 0.0, []
        for l in fin["liabilities"]:
            if l["principalChf"] <= 0:
                continue
            interest = l["principalChf"] * l["annualRate"] / 12
            pay = min(l["monthlyPaymentChf"], l["principalChf"] + interest)
            l["principalChf"] = l["principalChf"] + interest - pay
            paid += pay
            add("debt_interest", -interest, l.get("sourceEventId"))
            debt_lines.append({"liabilityId": l["id"], "paymentChf": round(pay, 2), "interestChf": round(interest, 2),
                               "principalRepaidChf": round(pay - interest, 2), "sourceEventId": l.get("sourceEventId")})
        financing = -fin["cash"] * self.financing_rate / 12 if fin["cash"] < 0 else 0.0
        if financing:
            add("financing_cost", -financing)
        for a in fin["assets"]:
            growth = a["valueChf"] * (a.get("annualReturn") or 0.0) / 12
            a["valueChf"] += growth
            if growth:
                add("asset_growth", growth)

        before = fin["cash"]
        fin["cash"] += income - expense - paid - financing
        tw["month"] = t + 1
        self.last_cash_flow = {
            "openingCashChf": round(before, 2), "income": inc_lines, "expenses": exp_lines,
            "debtService": debt_lines, "otherChf": 0.0, "financingCostChf": round(financing, 2),
            "totalIncomeChf": round(income, 2), "totalExpensesChf": round(expense, 2),
            "totalDebtServiceChf": round(paid, 2),
            "netCashFlowChf": round(fin["cash"] - before, 2), "closingCashChf": round(fin["cash"], 2)}
        if before >= 0 > fin["cash"]:
            beats.append(self.beat(t + 1, "CASH_NEGATIVE", cash=round(fin["cash"])))
        elif before < 0 <= fin["cash"]:
            beats.append(self.beat(t + 1, "CASH_RECOVERED", cash=round(fin["cash"])))

        return tw

    def beat(self, month: int, kind: str, event_id: str | None = None, **data) -> dict:
        return {"month": month, "age": self.age(month), "kind": kind, "eventId": event_id,
                "data": {k: str(v) for k, v in data.items()}}

    def run(self, twin: dict, months: int, attribution: dict, beats: list, goals_state: dict) -> tuple[dict, dict]:
        """Advance `months`; returns (twin, stats for this stretch)."""
        min_cash, min_month, neg = twin["finance"]["cash"], twin["month"], 0
        for _ in range(months):
            twin = self.step(twin, attribution, beats)
            cash = twin["finance"]["cash"]
            if cash < min_cash:
                min_cash, min_month = cash, twin["month"]
            neg += cash < 0
            self.check_goals(twin, goals_state, beats)
        return twin, {"minCash": min_cash, "monthOfMinCash": min_month, "monthsNegative": neg}

    def check_goals(self, twin: dict, goals_state: dict, beats: list) -> None:
        nw, cash, m = net_worth(twin), twin["finance"]["cash"], twin["month"]
        for g in self.profile["goals"]:
            if goals_state.get(g["id"]):
                continue
            value = cash if GOAL_BASIS.get(g["name"], "NET_WORTH") == "CASH" else nw
            if value >= g["targetChf"] and m <= g["targetMonth"]:
                goals_state[g["id"]] = "REACHED"
                beats.append(self.beat(m, "GOAL_REACHED", goal=g["name"], target=round(g["targetChf"])))
            elif m == g["targetMonth"]:
                goals_state[g["id"]] = "MISSED"
                beats.append(self.beat(m, "GOAL_MISSED", goal=g["name"], target=round(g["targetChf"])))

    def eligible(self, ev: dict, twin: dict, turn: int, used: set) -> bool:
        req, life, cash = ev.get("requires") or {}, twin["life"], twin["finance"]["cash"]
        age = self.age(twin["month"])
        return all([
            turn >= ev.get("earliestTurn", 0),
            ev.get("repeatable", False) or ev["id"] not in used,
            "employment" not in req or life["employment"] in req["employment"],
            "minAge" not in req or age >= req["minAge"],
            "maxAge" not in req or age <= req["maxAge"],
            all(life["facts"].get(k) in v for k, v in (req.get("facts") or {}).items()),
            all(f in life["flags"] for f in req.get("flagsAll") or []),
            not any(f in life["flags"] for f in req.get("flagsNone") or []),
            "minCashChf" not in req or cash >= req["minCashChf"],
            "maxCashChf" not in req or cash <= req["maxCashChf"],
        ])

    def pick_event(self, events: list[dict], twin: dict, turn: int, used: set, rng: random.Random) -> dict | None:
        cands = [e for e in events if self.eligible(e, twin, turn, used)]
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


# ================================================================ CLI modes

def load_context(profile_id: str) -> tuple[dict, dict]:
    prof_file, calib_file = OUT / "profiles-sample.json", OUT / "calibration.json"
    for f in (prof_file, calib_file):
        if not f.exists():
            sys.exit(f"{f} not found: run tools/calibrate.py first")
    items = {p["profile"]["id"]: p for p in json.loads(prof_file.read_text(encoding="utf-8"))["profiles"]}
    if profile_id not in items:
        sys.exit(f"profile {profile_id} not in {prof_file}")
    segs = json.loads(calib_file.read_text(encoding="utf-8"))["segments"]
    bonus = {s["segment"].split("|")[0]: s["decemberBonusRatio"] for s in segs if s["segment"].endswith("|ANY")}
    return items[profile_id], bonus


def preview(events: list[dict], item: dict, bonus: dict, horizon: int = HORIZON) -> None:
    eng = Engine(item["profile"], bonus)
    base_twin, stats = eng.run(new_twin(item), horizon, {}, [], {})
    p = item["profile"]
    print(f"\nProfile {p['id']} {p['displayName']} ({item['segment']}), cash {item['initialFinance']['cash']:,.0f} CHF")
    print(f"  baseline (no event): net worth m{horizon} {net_worth(base_twin):>10,.0f} | min cash {stats['minCash']:>10,.0f}"
          f" (m{stats['monthOfMinCash']}) | months < 0: {stats['monthsNegative']}")
    for ev in events:
        if not eng.eligible(ev, new_twin(item), ev.get("earliestTurn", 0), set()):
            print(f"\n  {ev['id']}: not eligible at the start for this profile (requires {ev.get('requires')})")
            continue
        print(f"\n  {ev['id']}: {ev['title']['EN']}")
        for opt in ev["options"]:
            expected = 0.0
            for cname in CASE_ORDER:
                case = opt["cases"][cname]
                tw, st = eng.run(apply_effects(new_twin(item), case["effects"], ev["id"]), horizon, {}, [], {})
                nw = net_worth(tw)
                expected += case["prob"] * nw
                print(f"    {opt['id']} {cname:<11} p={case['prob']:.2f} | net worth m{horizon} {nw:>10,.0f}"
                      f" | min cash {st['minCash']:>10,.0f} (m{st['monthOfMinCash']}) | months < 0: {st['monthsNegative']}")
            print(f"    {opt['id']} expected net worth: {expected:,.0f}  [{opt['label']['EN']}]")


def play(events: list[dict], item: dict, bonus: dict, cat: dict, choices: list[str], lucks: list[float],
         seed: int, lang: str = "EN") -> dict:
    """Play a whole game; returns the result (and prints the life line)."""
    eng, rng = Engine(item["profile"], bonus), random.Random(seed)
    twin, used, attribution, beats, goals = new_twin(item), set(), {}, [], {}
    start_nw = net_worth(twin)
    statements = [to_statement(eng, item, twin)]
    beats.append(eng.beat(0, "START", cash=round(twin["finance"]["cash"])))
    for turn in range(MAX_TURNS):
        twin = copy.deepcopy(twin)
        eng.apply_life(twin, beats)          # changes due this month count for the event requirements
        ev = eng.pick_event(events, twin, turn, used, rng)
        luck = lucks[turn] if turn < len(lucks) else rng.random()
        if ev is None:
            beats.append(eng.beat(twin["month"], "QUIET_PERIOD"))
        else:
            used.add(ev["id"])
            opt_id = choices[turn] if turn < len(choices) else "A"
            opt = next(o for o in ev["options"] if o["id"] == opt_id)
            case = case_from_luck(opt, luck)
            beats.append(eng.beat(twin["month"], "DECISION", ev["id"], option=opt_id, label=opt["label"][lang]))
            beats.append(eng.beat(twin["month"], "CASE_RESOLVED", ev["id"], case=case, luck=f"{luck:.2f}",
                                  narrative=opt["cases"][case]["narrative"][lang]))
            twin = apply_effects(twin, opt["cases"][case]["effects"], ev["id"])
        twin, _ = eng.run(twin, MONTHS_PER_TURN, attribution, beats, goals)
        statements.append(to_statement(eng, item, twin))
    beats.append(eng.beat(twin["month"], "END", netWorth=round(net_worth(twin)), cash=round(twin["finance"]["cash"])))
    total = sum(attribution.values())
    return {"twin": twin, "beats": beats, "attribution": attribution, "startNetWorth": start_nw,
            "endNetWorth": net_worth(twin), "attributionGap": start_nw + total - net_worth(twin),
            "statements": statements,
            "statementErrors": [f"month {s['month']}: {e}" for s in statements for e in check_statement(s)]}


def print_play(result: dict, cat: dict, lang: str = "EN") -> None:
    def label(beat):
        d = beat["data"]
        if "fact" in d:
            return f"{d['fact']} -> {cat['facts'][d['fact']][d['value']][lang]}"
        if "flag" in d:
            return f"milestone: {cat['flags'][d['flag']][lang]}"
        return ", ".join(f"{k}={v}" for k, v in d.items())
    print("\nLife line")
    for b in result["beats"]:
        ev = f" [{b['eventId']}]" if b["eventId"] else ""
        print(f"  m{b['month']:>3} age {b['age']:>2}  {b['kind']:<15}{ev} {label(b)}")
    print("\nAttribution (where the net worth comes from)")
    print(f"  start net worth {result['startNetWorth']:>12,.0f}")
    for (key, src), chf in sorted(result["attribution"].items(), key=lambda kv: kv[1]):
        print(f"  {key:<28} {chf:>12,.0f}  {'(' + src + ')' if src else ''}")
    print(f"  end net worth   {result['endNetWorth']:>12,.0f}   (identity gap {result['attributionGap']:.6f})")
    life = result["twin"]["life"]
    facts = ", ".join(f"{k}: {cat['facts'][k][v][lang]}" for k, v in life["facts"].items())
    print(f"\nFinal twin: {facts} | milestones: {', '.join(life['flags']) or '-'}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--events", type=Path, default=DEFAULT_EVENTS)
    ap.add_argument("--facts", type=Path, default=DEFAULT_FACTS)
    ap.add_argument("--preview", metavar="PROFILE_ID", help="simulate each option x case on this profile")
    ap.add_argument("--play", metavar="PROFILE_ID", help="play a whole game on this profile")
    ap.add_argument("--choices", default="", help="comma-separated options per turn, e.g. A,B (default A)")
    ap.add_argument("--luck", default="", help="comma-separated luck values 0..1 per turn (default from --seed)")
    ap.add_argument("--seed", type=int, default=2026)
    ap.add_argument("--lang", default="EN", choices=sorted(LANGS))
    ap.add_argument("--dump", type=Path, help="with --play: write the FinancialStatement of every turn to this JSON file")
    args = ap.parse_args()

    doc = json.loads(args.events.read_text(encoding="utf-8"))
    cat = json.loads(args.facts.read_text(encoding="utf-8"))
    errors = validate(doc, cat)
    if errors:
        print(f"{len(errors)} error(s):")
        for e in errors:
            print(f"  - {e}")
        sys.exit(1)
    print(f"OK: {len(doc['events'])} event(s) and the facts catalog are valid")

    if args.preview:
        item, bonus = load_context(args.preview)
        preview(doc["events"], item, bonus)
    if args.play:
        item, bonus = load_context(args.play)
        choices = [c.strip() for c in args.choices.split(",") if c.strip()]
        lucks = [float(x) for x in args.luck.split(",") if x.strip()]
        result = play(doc["events"], item, bonus, cat, choices, lucks, args.seed, args.lang)
        print_play(result, cat, args.lang)
        print(f"\nStatements: {len(result['statements'])} (one per turn), identity errors: {len(result['statementErrors'])}")
        for e in result["statementErrors"]:
            print(f"  - {e}")
        if args.dump:
            args.dump.write_text(json.dumps({"profileId": args.play, "statements": result["statements"]},
                                            indent=2, ensure_ascii=False), encoding="utf-8")
            print(f"  -> {args.dump}")


if __name__ == "__main__":
    main()
