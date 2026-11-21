"""Validate the game content, and play it with the simulator to balance it.

    python tools/validate_events.py                          # validate content/events.json + content/facts.json
    python tools/validate_events.py --preview P00001         # each option x case of the events eligible at start
    python tools/validate_events.py --play P00001 --choices A,A --luck 0.5,0.1
                                                             # play a whole game and print the life line

--preview and --play use the game loop of tools/game.py and the monthly step of tools/simulate.py (learned flows +
macro trends, docs/simulator.md). They need tools/out/flow-model.json and macro.json (flow_model.py, macro.py).
"""
import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = Path(__file__).resolve().parent / "out"
DEFAULT_EVENTS = ROOT / "content" / "events.json"
DEFAULT_FACTS = ROOT / "content" / "facts.json"

LANGS = {"ES", "EN"}
CASE_ORDER = ["PESSIMISTIC", "NEUTRAL", "OPTIMISTIC"]
EMPLOYMENT = {"NOT_WORKING", "EMPLOYED", "PART_TIME", "SELF_EMPLOYED", "UNEMPLOYED", "RETIRED"}
EXPENSE_CATEGORIES = {"RENT", "LIVING", "TUITION", "INSURANCE", "TRAVEL", "OTHER"}
PROB_TOLERANCE = 1e-6

EVENT_FIELDS = {"id", "category", "title", "description", "requires", "priority", "repeatable",
                "earliestTurn", "sources", "options"}
REQUIRE_FIELDS = {"employment", "minAge", "maxAge", "facts", "flagsAll", "flagsNone", "minCashChf", "maxCashChf"}
# type -> (required fields, optional fields)
EFFECTS = {
    "set_income": ({"monthlyChf"}, {"startOffsetMonths"}),
    "income_delta": ({"deltaChf"}, {"startOffsetMonths", "months"}),
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
    checks = [("category", EXPENSE_CATEGORIES), ("employment", EMPLOYMENT)]
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


def print_preview(rows: list[dict], events: list[dict], s0: dict) -> None:
    p = s0["person"]
    print(f"\nProfile {s0['profileId']} {p['displayName']}, age {p['age']}, {p['employment']}, {p['facts']['CITY']}, "
          f"cash {s0['cashChf']:,.0f} CHF")
    titles = {e["id"]: e["title"]["EN"] for e in events}
    expected = {}
    for r in rows:
        label = "baseline (no event)" if r["eventId"] is None else f"{r['eventId']} {r['optionId']} {r['case']:<11}"
        print(f"  {label:<36} p={r['prob']:.2f} | net worth {r['netWorth']:>11,.0f} | min cash {r['minCash']:>11,.0f}"
              f" (m{r['monthOfMinCash']}) | months < 0: {r['monthsNegative']}")
        if r["eventId"]:
            expected[(r["eventId"], r["optionId"])] = expected.get((r["eventId"], r["optionId"]), 0) + r["prob"] * r["netWorth"]
    for (ev, opt), nw in expected.items():
        print(f"  {ev} {opt} expected net worth: {nw:,.0f}  [{titles[ev]}]")
    eligible_ids = {r["eventId"] for r in rows}
    for e in events:
        if e["id"] not in eligible_ids:
            print(f"  {e['id']}: not eligible at the start for this profile (requires {e.get('requires')})")


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

    from game import play, preview
    from statement import check_statement
    if args.preview:
        s0, model, macro = load_context(args.preview, cat)
        print_preview(preview(doc["events"], s0, model, macro), doc["events"], s0)
    if args.play:
        s0, model, macro = load_context(args.play, cat)
        choices = [c.strip() for c in args.choices.split(",") if c.strip()]
        lucks = [float(x) for x in args.luck.split(",") if x.strip()]
        result = play(doc["events"], s0, model, macro, choices, lucks, args.seed, args.lang)
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
