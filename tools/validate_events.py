"""Validate the game content (the pool of actions and the shocks), and play it with the simulator to balance it.

    python tools/validate_events.py                          # validate content/events.json + content/facts.json
    python tools/validate_events.py --preview P00001         # each action available at the start, at luck p10 / p50 / p90
    python tools/validate_events.py --play P00001 --path A-master,NOTHING --mode FULL_RANDOM
                                                             # play a life along a path and print the life line

--preview and --play use the game loop of tools/game.py and the monthly step of tools/simulate.py (learned flows +
macro trends, docs/simulator.md). They need tools/out/flow-model.json and macro.json (flow_model.py, macro.py).
"""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = Path(__file__).resolve().parent / "out"
DEFAULT_EVENTS = ROOT / "content" / "events.json"
DEFAULT_FACTS = ROOT / "content" / "facts.json"

LANGS = {"ES", "EN"}
LAWS = {"NORMAL", "LOGNORMAL", "QUANTILE"}
FORMATS = {"chf", "change", "share", "percent", "months", "flat", "number"}
EMPLOYMENT = {"NOT_WORKING", "EMPLOYED", "PART_TIME", "SELF_EMPLOYED", "UNEMPLOYED", "RETIRED"}
EXPENSE_CATEGORIES = {"RENT", "LIVING", "TUITION", "INSURANCE", "TRAVEL", "OTHER"}
HOUSEHOLDS = {"SINGLE", "COUPLE", "FAMILY", "SHARED_HOUSING"}
PROB_TOLERANCE = 1e-6

ACTION_FIELDS = {"id", "category", "title", "description", "requires", "repeatable", "earliestTurn", "sources", "effects",
                 "uncertain", "outcome"}
SHOCK_FIELDS = {"id", "category", "title", "description", "requires", "probability", "sources", "effects", "uncertain", "outcome"}
UNCERTAIN_FIELDS = {"name", "law", "median", "low", "high", "better", "step", "format", "targets", "min", "max", "source"}
NOTHING = "NOTHING"
REQUIRE_FIELDS = {"employment", "household", "minAge", "maxAge", "facts", "flagsAll", "flagsNone", "minCashChf",
                  "maxCashChf"}
# type -> (required fields, optional fields)
EFFECTS = {
    "set_income": ({"monthlyChf"}, {"startOffsetMonths"}),
    "income_delta": ({"deltaChf"}, {"startOffsetMonths", "months"}),
    "income_factor": ({"factor"}, {"startOffsetMonths", "months"}),
    "set_household": ({"household"}, {"startOffsetMonths"}),
    "market_rent": (set(), {"quantile", "startOffsetMonths"}),
    "expense_delta": ({"category", "deltaChf"}, {"startOffsetMonths", "months"}),
    "one_off": ({"chf"}, {"startOffsetMonths"}),
    "add_liability": ({"principalChf", "annualRate", "termMonths"}, set()),
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
    checks = [("category", EXPENSE_CATEGORIES), ("employment", EMPLOYMENT), ("household", HOUSEHOLDS)]
    for field, allowed in checks:
        if field in eff and eff[field] not in allowed:
            errors.append(f"{where}: unknown {field} {eff[field]!r}")
    if etype == "add_liability" and eff.get("termMonths", 1) <= 0:
        errors.append(f"{where}: termMonths must be > 0")
    if etype == "income_factor" and not eff.get("factor", 0) > 0:
        errors.append(f"{where}: factor must be > 0")
    if etype == "market_rent" and not 0 <= eff.get("quantile", 0.5) <= 1:
        errors.append(f"{where}: quantile must be in [0, 1]")
    if etype == "set_fact" and eff.get("value") not in cat["facts"].get(eff.get("key"), {}):
        errors.append(f"{where}: fact {eff.get('key')}={eff.get('value')!r} not in content/facts.json")
    if etype == "add_flag" and eff.get("flag") not in cat["flags"]:
        errors.append(f"{where}: flag {eff.get('flag')!r} not declared in content/facts.json")


def _check_requires(errors: list, where: str, req: dict, cat: dict) -> None:
    if extra := set(req) - REQUIRE_FIELDS:
        errors.append(f"{where}: unknown fields {sorted(extra)}")
    for field, allowed in (("employment", EMPLOYMENT), ("household", HOUSEHOLDS)):
        for e in req.get(field, []):
            if e not in allowed:
                errors.append(f"{where}: unknown {field} {e!r}")
    for key, values in (req.get("facts") or {}).items():
        for v in values:
            if v not in cat["facts"].get(key, {}):
                errors.append(f"{where}: fact {key}={v!r} not in content/facts.json")
    for f in (req.get("flagsAll") or []) + (req.get("flagsNone") or []):
        if f not in cat["flags"]:
            errors.append(f"{where}: flag {f!r} not declared in content/facts.json")


def _check_household_facts(where: str, effects: list, cat: dict) -> list[str]:
    """set_household must come with the HOUSING / FAMILY facts that initialFromProfile derives from that household,
    at the same offset, so that the person's facts never contradict their household."""
    errors = []
    for eff in effects:
        if eff.get("type") != "set_household":
            continue
        for key, rule in cat["initialFromProfile"].items():
            if rule["from"] != "household":
                continue
            want = rule.get("map", {}).get(eff["household"], rule.get("default"))
            ok = any(e.get("type") == "set_fact" and e.get("key") == key and e.get("value") == want
                     and e.get("startOffsetMonths", 0) == eff.get("startOffsetMonths", 0) for e in effects)
            if want and not ok:
                errors.append(f"{where}: set_household {eff['household']} needs set_fact {key}={want}")
    return errors


def _check_outcome(errors: list, w: str, item: dict, cat: dict, empty_ok: bool) -> None:
    """The median effects, the uncertain numbers (their distribution and the effect fields they set) and the outcome text."""
    effects = item.get("effects", [])
    if not effects and not empty_ok:
        errors.append(f"{w}: no effects")
    for j, eff in enumerate(effects):
        _check_effect(errors, f"{w}.effects[{j}]", eff, cat)
    errors += _check_household_facts(w, effects, cat)
    names = set()
    for k, spec in enumerate(item.get("uncertain", [])):
        uw = f"{w}.uncertain[{k}] {spec.get('name')}"
        if extra := set(spec) - UNCERTAIN_FIELDS:
            errors.append(f"{uw}: unknown fields {sorted(extra)}")
        if spec.get("name") in names:
            errors.append(f"{uw}: duplicated name")
        names.add(spec.get("name"))
        if spec.get("law") not in LAWS:
            errors.append(f"{uw}: law must be one of {sorted(LAWS)}")
        if spec.get("better") not in ("HIGH", "LOW"):
            errors.append(f"{uw}: better must be HIGH or LOW")
        if spec.get("format") not in FORMATS:
            errors.append(f"{uw}: format must be one of {sorted(FORMATS)}")
        lo, hi, med = spec.get("low", {}), spec.get("high", {}), spec.get("median")
        try:
            if not lo["q"] < 0.5 < hi["q"]:
                errors.append(f"{uw}: needs low.q < 0.5 < high.q")
            if not lo["value"] <= med <= hi["value"]:
                errors.append(f"{uw}: needs low.value <= median <= high.value")
            if spec.get("law") == "LOGNORMAL" and lo["value"] <= 0:
                errors.append(f"{uw}: a lognormal number must be positive")
        except (KeyError, TypeError):
            errors.append(f"{uw}: needs median, low {{q, value}} and high {{q, value}}")
        if not spec.get("step", 0) > 0:
            errors.append(f"{uw}: step must be > 0")
        if not spec.get("targets"):
            errors.append(f"{uw}: sets no effect field (targets)")
        for tgt in spec.get("targets", []):
            e = tgt.get("effect")
            if not isinstance(e, int) or not 0 <= e < len(effects) or tgt.get("field") not in effects[e]:
                errors.append(f"{uw}: target {tgt} is not a field of an effect")
    text = item.get("outcome")
    if not _is_text(text):
        errors.append(f"{w}.outcome: must have ES and EN")
    else:
        for lang, s in text.items():
            for name in re.findall(r"\{(\w+)\}", s):
                if name not in names:
                    errors.append(f"{w}.outcome {lang}: {{{name}}} is not an uncertain number")


def _check_common(errors: list, w: str, item: dict, fields: set, cat: dict) -> None:
    if extra := set(item) - fields:
        errors.append(f"{w}: unknown fields {sorted(extra)}")
    for field in ("category", "title", "description", "effects", "outcome"):
        if field not in item:
            errors.append(f"{w}: missing {field}")
    for field in ("title", "description"):
        if not _is_text(item.get(field)):
            errors.append(f"{w}.{field}: must be {{'ES': '...', 'EN': '...'}} with non-empty text")
    _check_requires(errors, f"{w}.requires", item.get("requires") or {}, cat)
    if not item.get("sources"):
        errors.append(f"{w}: 'sources' is empty (every number needs a source or 'assumption')")


def validate(doc: dict, cat: dict) -> list[str]:
    """Return a list of human-readable errors (empty = valid)."""
    errors = validate_facts(cat)
    actions, shocks = doc.get("actions"), doc.get("shocks", [])
    if not isinstance(actions, list) or not actions:
        return errors + ["root: 'actions' must be a non-empty list"]
    if not isinstance(shocks, list):
        errors.append("root: 'shocks' must be a list")
        shocks = []
    if extra := set(doc) - {"version", "actions", "shocks"}:
        errors.append(f"root: unknown fields {sorted(extra)}")
    seen = set()
    for kind, items, fields in (("action", actions, ACTION_FIELDS), ("shock", shocks, SHOCK_FIELDS)):
        for i, item in enumerate(items):
            iid = item.get("id", f"#{i}")
            w = f"{kind} {iid}"
            if iid in seen:
                errors.append(f"{w}: duplicated id")
            seen.add(iid)
            _check_common(errors, w, item, fields, cat)
            _check_outcome(errors, w, item, cat, empty_ok=iid == NOTHING)
            if kind == "shock" and not 0 < item.get("probability", 0) < 1:
                errors.append(f"{w}: probability must be in (0, 1)")
    nothing = next((a for a in actions if a.get("id") == NOTHING), None)
    if nothing is None:
        errors.append(f"actions: {NOTHING!r} (carry on as you are) is missing")
    elif nothing.get("requires") or not nothing.get("repeatable") or nothing.get("effects") or nothing.get("uncertain"):
        errors.append(f"action {NOTHING}: must have no requirements, be repeatable and have no effects")
    return errors


# ================================================================ CLI modes

def load_context(profile_id: str, facts_catalog: dict) -> tuple[dict, dict, dict]:
    """Opening statement of a dataset profile, flow model and macro trends."""
    from common import default_data_dir, load_dataset
    from statement import dataset_opening
    model_file, macro_file = OUT / "flow-model.json", OUT / "macro.json"
    for f in (model_file, macro_file):
        if not f.exists():
            sys.exit(f"{f} not found: run tools/flow_model.py and tools/macro.py first")
    model = json.loads(model_file.read_text(encoding="utf-8"))
    macro = json.loads(macro_file.read_text(encoding="utf-8"))
    ds = load_dataset(default_data_dir())
    if profile_id not in ds["profiles"]:
        sys.exit(f"profile {profile_id} not in the dataset")
    return dataset_opening(ds, profile_id, facts_catalog), model, macro


def print_preview(rows: list[dict], actions: list[dict], s0: dict) -> None:
    p = s0["person"]
    print(f"\nProfile {s0['profileId']} {p['displayName']}, age {p['age']}, {p['employment']}, {p['facts']['CITY']}, "
          f"cash {s0['cashChf']:,.0f} CHF")
    for r in rows:
        values = ", ".join(f"{k}={v}" for k, v in r["values"].items())
        print(f"  {r['actionId']:<22} luck p{round(r['luck'] * 100):<3} | net worth {r['netWorth']:>11,.0f} | min cash "
              f"{r['minCash']:>11,.0f} (m{r['monthOfMinCash']}) | months < 0: {r['monthsNegative']:>3} | {values}")
    available = {r["actionId"] for r in rows}
    for a in actions:
        if a["id"] not in available:
            print(f"  {a['id']}: not available at the start for this profile (requires {a.get('requires')})")


def print_play(result: dict, cat: dict, lang: str = "EN") -> None:
    def label(b):
        d = b["data"]
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
    for key, chf in sorted(result["attribution"].items(), key=lambda kv: kv[1]):
        print(f"  {key:<32} {chf:>12,.0f}")
    print(f"  end net worth   {result['endNetWorth']:>12,.0f}   (identity gap {result['attributionGap']:.2f})")
    person = result["statements"][-1]["person"]
    facts = ", ".join(f"{k}: {cat['facts'][k][v][lang]}" for k, v in person["facts"].items() if v in cat["facts"].get(k, {}))
    print(f"\nFinal twin: {facts} | milestones: {', '.join(person['flags']) or '-'}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--events", type=Path, default=DEFAULT_EVENTS)
    ap.add_argument("--facts", type=Path, default=DEFAULT_FACTS)
    ap.add_argument("--preview", metavar="PROFILE_ID", help="simulate each option x case on this profile")
    ap.add_argument("--play", metavar="PROFILE_ID", help="play a whole game on this profile")
    ap.add_argument("--path", default="", help="comma-separated action ids per turn (default NOTHING)")
    ap.add_argument("--mode", default="SAME_LEVEL", choices=["SAME_LEVEL", "FULL_RANDOM"], help="luck mode")
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
    print(f"OK: {len(doc['actions'])} action(s), {len(doc.get('shocks', []))} shock(s) and the facts catalog are valid")

    from game import play, preview
    from statement import check_statement
    if args.preview:
        s0, model, macro = load_context(args.preview, cat)
        print_preview(preview(doc["actions"], s0, model, macro), doc["actions"], s0)
    if args.play:
        s0, model, macro = load_context(args.play, cat)
        path = [c.strip() for c in args.path.split(",") if c.strip()]
        result = play(doc["actions"], doc.get("shocks", []), s0, model, macro, path, args.seed, args.mode, args.lang)
        print_play(result, cat, args.lang)
        monthly = result["monthly"]
        errs = [f"month {s['month']}: {e}" for prev, s in zip(monthly, monthly[1:]) for e in check_statement(s, prev)]
        print(f"\nStatements: {len(result['statements'])} (one per turn), identity errors: {len(errs)}")
        for e in errs:
            print(f"  - {e}")
        if args.dump:
            args.dump.write_text(json.dumps({"profileId": args.play, "statements": result["statements"]},
                                            indent=2, ensure_ascii=False), encoding="utf-8")
            print(f"  -> {args.dump}")


if __name__ == "__main__":
    main()
