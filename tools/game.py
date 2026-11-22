"""Reference game loop on FinancialStatements: the player decides, the simulator moves time (docs/decision-tree.md).

A game is a tree of decisions. At each node (every 24 months, 5 times) the player picks ONE action of the pool whose
requirements match the statement (content/events.json `actions`, NOTHING included). The luck of the node (0..1) is a
percentile: every uncertain number of the action (a salary, a tuition, a pay factor, a rate...) is drawn at that
percentile of its distribution (`uncertain`: normal or lognormal, fitted to two quantiles of the data). An involuntary
shock (job loss) may happen, and 24 monthly steps give the child node. Nothing advances on
its own: a node exists only if the player took that action there.

  actions_at(...)        the actions the player can take at a node
  play_turn(...)         one decision: the case from the luck, a possible shock, 24 months; beats and attribution
  play(...)              a whole life along a path of action ids (what the golden tests replay in Kotlin)
  preview(...)           every action x case available at the start, simulated for a horizon
Python reference for the Kotlin engine (Dev A); tools/validate_events.py uses it for --preview / --play.
"""
import copy
import math

from simulate import run, step
from statement import annuity_payment, goal_state, net_worth

HORIZON, MONTHS_PER_TURN, MAX_TURNS = 120, 24, 5
MASK64 = (1 << 64) - 1
NOTHING = "NOTHING"                                     # "carry on as you are": always available, no effects
LUCK_SALT, SHOCK_SALT, SHOCK_MONTH_SALT, SHOCK_LUCK_SALT = 1, 3, 4, 5
LUCK_RANGE = (0.05, 0.95)                               # percentiles beyond p5 / p95 are not drawn (no absurd tails)
INT_FIELDS = {"startOffsetMonths", "months", "termMonths"}
SAME_LEVEL, FULL_RANDOM = "SAME_LEVEL", "FULL_RANDOM"   # luck modes, chosen before the game starts


# ---------------------------------------------------------------------------------------------- randomness

def splitmix64(x: int) -> int:
    x = (x + 0x9E3779B97F4A7C15) & MASK64
    z = ((x ^ (x >> 30)) * 0xBF58476D1CE4E5B9) & MASK64
    z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & MASK64
    return z ^ (z >> 31)


def unit(seed: int, turn: int, salt: int) -> float:
    """A number in [0, 1) that depends only on (seed, turn, salt): the same in Python and Kotlin (engine/Basics.kt),
    so a game is reproducible on every platform."""
    return (splitmix64((seed * 1_000_003 + turn * 31 + salt) & MASK64) >> 11) / 2 ** 53


def turn_luck(seed: int, turn: int) -> float:
    return unit(seed, turn, LUCK_SALT)


def fnv1a64(text: str) -> int:
    """64-bit FNV-1a of the UTF-8 bytes (portable string hash, the same in Kotlin)."""
    h = 0xCBF29CE484222325
    for byte in text.encode("utf-8"):
        h = ((h ^ byte) * 0x100000001B3) & MASK64
    return h


def child_id(parent_id: str, action_id: str) -> str:
    """Node ids are the path of action ids from the start: "" (start), "A-master", "A-master/NOTHING"..."""
    return action_id if not parent_id else f"{parent_id}/{action_id}"


def node_seed(seed: int, mode: str, node_id: str) -> int:
    """SAME_LEVEL: every node of a level shares the game's seed (whatever you do, the same luck at the same age).
    FULL_RANDOM: each node gets its own seed from its path, so each decision has its own luck."""
    if mode == SAME_LEVEL:
        return seed
    x = (seed ^ fnv1a64(node_id)) & MASK64
    return x - (1 << 64) if x >= 1 << 63 else x            # as a signed 64-bit Long, like Kotlin


def node_luck(seed: int, mode: str, node_id: str, turn: int) -> float:
    return unit(node_seed(seed, mode, node_id), turn, LUCK_SALT)


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


def eligible(act: dict, s: dict, turn: int, used: set) -> bool:
    """Do the action's (or shock's) requirements match the statement at the start of this turn? `used` = the actions
    already taken on this path of the tree (an action that is not repeatable is taken once per life)."""
    req, person, cash = act.get("requires") or {}, s["person"], s["cashChf"]
    return all([
        turn >= act.get("earliestTurn", 0),
        act.get("repeatable", False) or act["id"] not in used,
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


def actions_at(actions: list[dict], s: dict, turn: int, used: set) -> list[dict]:
    """Every action the person can take now (file order). NOTHING is always there: it has no requirements."""
    return [a for a in actions if eligible(a, s, turn, used)]


# ---------------------------------------------------------------------------------------------- distributions

_A = (-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01,
      2.506628277459239e+00)
_B = (-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01)
_C = (-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00,
      2.938163982698783e+00)
_D = (7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00)


def probit(p: float) -> float:
    """Inverse of the standard normal CDF (Acklam's rational approximation, error < 1.2e-9; the same in Kotlin)."""
    if p < 0.02425:
        q = math.sqrt(-2 * math.log(p))
        return (((((_C[0] * q + _C[1]) * q + _C[2]) * q + _C[3]) * q + _C[4]) * q + _C[5]) / \
            ((((_D[0] * q + _D[1]) * q + _D[2]) * q + _D[3]) * q + 1)
    if p > 1 - 0.02425:
        q = math.sqrt(-2 * math.log(1 - p))
        return -(((((_C[0] * q + _C[1]) * q + _C[2]) * q + _C[3]) * q + _C[4]) * q + _C[5]) / \
            ((((_D[0] * q + _D[1]) * q + _D[2]) * q + _D[3]) * q + 1)
    q = p - 0.5
    r = q * q
    return (((((_A[0] * r + _A[1]) * r + _A[2]) * r + _A[3]) * r + _A[4]) * r + _A[5]) * q / \
        (((((_B[0] * r + _B[1]) * r + _B[2]) * r + _B[3]) * r + _B[4]) * r + 1)


def draw(spec: dict, luck: float) -> float:
    """The value of an uncertain number at the percentile `luck` (high luck = the better side, `better`).
    A two-piece normal: below the median the spread comes from the `low` quantile, above it from the `high` one, so the
    distribution goes exactly through the three values of the data (low, median, high). NORMAL works on the value,
    LOGNORMAL on its logarithm (never negative, longer tail upwards); QUANTILE is the percentile itself (a rent quantile).
    Clamped to [min, max] and rounded to `step`."""
    p = min(max(luck, LUCK_RANGE[0]), LUCK_RANGE[1])
    if spec["better"] == "LOW":
        p = 1 - p
    lo, hi = spec["low"], spec["high"]
    if spec["law"] == "QUANTILE":
        v = min(max(p, lo["value"]), hi["value"])
    else:
        f, finv = (lambda x: x, lambda x: x) if spec["law"] == "NORMAL" else (math.log, math.exp)
        z = probit(p)
        if z < 0:
            sigma = (f(spec["median"]) - f(lo["value"])) / -probit(lo["q"])
        else:
            sigma = (f(hi["value"]) - f(spec["median"])) / probit(hi["q"])
        v = finv(f(spec["median"]) + sigma * z)
    if "min" in spec:
        v = max(v, spec["min"])
    if "max" in spec:
        v = min(v, spec["max"])
    v = round(v / spec["step"]) * spec["step"]
    return int(v) if spec["step"] >= 1 and float(spec["step"]).is_integer() else v


def resolve(item: dict, luck: float) -> tuple[list[dict], dict]:
    """The effects of an action or shock at this luck (every uncertain number drawn at the same percentile) and the
    drawn values by name."""
    effects = copy.deepcopy(item["effects"])
    values = {}
    for spec in item.get("uncertain", []):
        v = draw(spec, luck)
        values[spec["name"]] = v
        for tgt in spec["targets"]:
            effects[tgt["effect"]][tgt["field"]] = int(round(v)) if tgt["field"] in INT_FIELDS else v
    return effects, values


def number_text(x: float) -> str:
    """How a drawn value is written in a story beat: 6100 -> "6100", 1.2995 -> "1.2995" (the same in Kotlin)."""
    return str(int(x)) if float(x).is_integer() else repr(float(x))


FLAT_WORDS = {"EN": ("cheaper than usual", "at the usual price", "more expensive than usual"),
              "ES": ("más barato de lo normal", "a precio normal", "más caro de lo normal")}


def format_value(fmt: str, v: float, lang: str) -> str:
    """A drawn value as the story tells it (the same in Kotlin: engine/Events.kt formatValue)."""
    if fmt == "chf":
        return f"{int(round(v)):,}".replace(",", "’")
    if fmt == "change":
        r = int(round((v - 1) * 100))
        return f"{'+' if r >= 0 else '-'}{abs(r)} %"
    if fmt == "share":
        return f"{int(round(v * 100))} %"
    if fmt == "percent":
        text = f"{v * 100:.1f} %"
        return text.replace(".", ",") if lang == "ES" else text
    if fmt == "months":
        return str(int(round(v)))
    if fmt == "flat":
        return FLAT_WORDS[lang][0 if v < 0.35 else 2 if v > 0.65 else 1]
    return number_text(v)


def outcome_text(item: dict, values: dict, lang: str) -> str:
    """The outcome sentence of an action or shock with its drawn values."""
    text = item["outcome"][lang]
    for spec in item.get("uncertain", []):
        if spec["name"] in values:
            text = text.replace("{" + spec["name"] + "}", format_value(spec["format"], float(values[spec["name"]]), lang))
    return text


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


def shifted(effects: list[dict], months: int) -> list[dict]:
    """The effects starting `months` later (a shock that happens in the middle of a turn)."""
    out = []
    for e in effects:
        e = dict(e)
        if e["type"] != "add_liability":
            e["startOffsetMonths"] = e.get("startOffsetMonths", 0) + months
        out.append(e)
    return out


def draw_shock(shocks: list[dict], s: dict, turn: int, seed: int, mode: str, node_id: str) -> tuple | None:
    """The involuntary event of this turn, or None: the first shock whose requirements match [s] (the statement after
    the action's immediate changes) happens with its probability. Returns (shock, its luck, month offset in the turn)."""
    shock = next((sh for sh in shocks if eligible(sh, s, turn, set())), None)
    if shock is None:
        return None
    ns = node_seed(seed, mode, node_id)
    if unit(ns, turn, SHOCK_SALT) >= shock["probability"]:
        return None
    month = min(int(unit(ns, turn, SHOCK_MONTH_SALT) * MONTHS_PER_TURN), MONTHS_PER_TURN - 1)
    return shock, unit(ns, turn, SHOCK_LUCK_SALT), month


def play_turn(actions: list[dict], shocks: list[dict], s: dict, turn: int, parent_id: str, action_id: str,
              used: set, seed: int, mode: str, model: dict, macro: dict | None, beats: list, attribution: dict,
              lang: str = "EN") -> tuple[dict, list, dict]:
    """One decision from [s] (the statement at the start of the turn, life changes due already applied).
    Returns (statement after the turn, its months, the turn record)."""
    act = next((a for a in actions_at(actions, s, turn, used) if a["id"] == action_id), None)
    if act is None:
        raise ValueError(f"turn {turn}: action {action_id!r} is not available here")
    node = child_id(parent_id, action_id)
    luck = node_luck(seed, mode, node, turn)
    effects, values = resolve(act, luck)
    if action_id == NOTHING:
        beats.append(beat(s, "QUIET_PERIOD", NOTHING))
    else:
        beats.append(beat(s, "DECISION", action_id, label=act["title"][lang]))
        beats.append(beat(s, "OUTCOME", action_id, luck=f"{luck:.2f}", **{k: number_text(v) for k, v in values.items()}))
    s1 = apply_effects(s, effects, action_id)
    shock = draw_shock(shocks, apply_due_life_changes(s1), turn, seed, mode, node)
    shock_values = {}
    if shock:
        sh, sh_luck, month = shock
        sh_effects, shock_values = resolve(sh, sh_luck)
        beats.append(beat(s, "SHOCK", sh["id"], luck=f"{sh_luck:.2f}", inMonths=month,
                          **{k: number_text(v) for k, v in shock_values.items()}))
        s1 = apply_effects(s1, shifted(sh_effects, month), sh["id"])
    end, months = advance(s1, MONTHS_PER_TURN, model, macro, beats, attribution)
    record = {"index": turn, "actionId": action_id, "luck": luck, "values": values,
              "shockId": shock[0]["id"] if shock else None, "shockLuck": shock[1] if shock else None,
              "shockValues": shock_values, "shockMonth": shock[2] if shock else None, "stateAfter": end}
    return end, months, record


def play(actions: list[dict], shocks: list[dict], s0: dict, model: dict, macro: dict | None, path: list[str],
         seed: int, mode: str = SAME_LEVEL, lang: str = "EN") -> dict:
    """A whole life along [path] (action ids per turn; missing turns = NOTHING). Returns one statement per turn (and
    every month), the life line and the attribution."""
    used, attribution, turns = set(), {}, []
    beats = [beat(s0, "START", cash=round(s0["cashChf"]))]
    s, per_turn, monthly, node = s0, [s0], [s0], ""
    for turn in range(MAX_TURNS):
        before, s = s, apply_due_life_changes(s)
        beats.extend(beats_between(before, s))
        action_id = path[turn] if turn < len(path) else NOTHING
        s, months, record = play_turn(actions, shocks, s, turn, node, action_id, used, seed, mode, model, macro,
                                      beats, attribution, lang)
        used.add(action_id)
        node = child_id(node, action_id)
        turns.append(record)
        per_turn.append(s)
        monthly.extend(months)
    beats.append(beat(s, "END", netWorth=round(net_worth(s)), cash=round(s["cashChf"])))
    start_nw, end_nw = net_worth(s0), net_worth(s)
    return {"statements": per_turn, "monthly": monthly, "beats": beats, "attribution": attribution, "turns": turns,
            "startNetWorth": start_nw, "endNetWorth": end_nw,
            "attributionGap": round(start_nw + sum(attribution.values()) - end_nw, 2)}


def preview(actions: list[dict], s0: dict, model: dict, macro: dict | None, horizon: int = HORIZON,
            lucks: tuple = (0.1, 0.5, 0.9)) -> list[dict]:
    """Every action available at the start at a few percentiles of luck, simulated for `horizon` months (to balance the
    content; the app shows no preview of balances before deciding)."""
    def summary(sts):
        cash = [x["cashChf"] for x in sts]
        low = min(range(len(cash)), key=cash.__getitem__)
        return {"netWorth": net_worth(sts[-1]), "minCash": cash[low], "monthOfMinCash": sts[low]["month"],
                "monthsNegative": sum(c < 0 for c in cash[1:])}

    rows = []
    for act in actions_at(actions, s0, 0, set()):
        for luck in (lucks if act.get("uncertain") else (0.5,)):
            effects, values = resolve(act, luck)
            s1 = apply_effects(s0, effects, act["id"])
            rows.append({"actionId": act["id"], "luck": luck, "values": values,
                         **summary(run(s1, horizon, model, macro=macro))})
    return rows
