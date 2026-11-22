"""Chronicle from templates (P11, plan B: no network). Python standard library only.

The game's life line is a list of StoryBeats (structured data: tools/game.py, model/Results.kt). This renders it as text
with content/chronicle.json, in ES or EN, using only the numbers the beats carry. Mirrors
shared/.../chronicle/Chronicle.kt (tools/test_chronicle.py and ChronicleTest.kt use the same beats).

    python tools/chronicle.py --check                                   # templates vs Kotlin enums, facts.json, events.json
    python tools/chronicle.py --play P00001 --path A-master,NOTHING --lang ES   # a life with the reference engine, told
"""
import argparse
import csv
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import default_data_dir  # noqa: E402
from game import outcome_text  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
CONTENT = ROOT / "content"
ENUMS_KT = ROOT / "shared" / "src" / "commonMain" / "kotlin" / "com" / "lauzhack" / "twin" / "model" / "Enums.kt"
START_YEAR = 2025
PLACEHOLDERS = {"age", "year", "years", "cash", "cashAbs", "target", "netWorth", "goal", "event", "narrative",
                "employment", "household", "value", "flag", "because"}
MONEY = {"cash", "target", "netWorth"}
PARAGRAPH_STARTS = {"DECISION", "QUIET_PERIOD", "END"}


def load(name: str) -> dict:
    return json.loads((CONTENT / name).read_text(encoding="utf-8"))


def kotlin_enum(name: str) -> list[str]:
    m = re.search(r"enum class " + name + r"\s*\{(.*?)\}", ENUMS_KT.read_text(encoding="utf-8"), re.S)
    return [x.strip() for x in re.sub(r"//.*", "", m.group(1)).replace("\n", " ").split(",") if x.strip()]


def chf(x: float, lang: str) -> str:
    """-5120 -> EN 'CHF -5’120', ES '-5’120 CHF' (Swiss grouping; currency placed as in events.json)."""
    n = int(round(x))
    number = ("-" if n < 0 else "") + f"{abs(n):,}".replace(",", "’")
    return f"{number} CHF" if lang == "ES" else f"CHF {number}"


def fill(template: str, values: dict) -> str:
    """Replace {name}; unknown names stay visible instead of crashing (like keyString in the UI)."""
    return re.sub(r"\{(\w+)\}", lambda m: values.get(m.group(1), m.group(0)), template)


def render(beats: list[dict], lang: str, tpl: dict, events: dict, facts: dict, start_year: int = START_YEAR) -> list[dict]:
    """One line per beat: {month, age, kind, text}. Beats without a template are skipped."""
    by_id = {e["id"]: e for e in events["actions"] + events.get("shocks", [])}
    current = None                                    # action of the decision being told
    lines = []
    for b in beats:
        d, kind, ev = b.get("data") or {}, b["kind"], by_id.get(b.get("eventId") or "")
        v = {"age": str(b["age"]), "year": str(start_year + b["month"] // 12), "years": str(b["month"] // 12)}
        for k in MONEY:
            if k in d:
                v[k] = chf(float(d[k]), lang)
        if "cash" in d:
            v["cashAbs"] = chf(abs(float(d["cash"])), lang)
        if "goal" in d:
            v["goal"] = tpl["labels"]["goal"].get(d["goal"], {}).get(lang, d["goal"])
        if kind in ("DECISION", "QUIET_PERIOD"):
            current = ev["id"] if ev else None
        v["event"] = ev["title"][lang] if ev else ""
        # the cause is told only when it is not the decision of this paragraph (e.g. the job after the master's)
        v["because"] = fill(tpl["because"][lang], v) if ev and ev["id"] != current else ""
        if ev and kind in ("OUTCOME", "SHOCK"):
            names = {s["name"] for s in ev.get("uncertain", [])}
            v["narrative"] = outcome_text(ev, {k: float(x) for k, x in d.items() if k in names}, lang)
        if kind == "LIFE_CHANGE":
            lc = tpl["lifeChange"]
            if "employment" in d:
                template = lc["employment"][lang]
                v["employment"] = tpl["labels"]["employment"].get(d["employment"], {}).get(lang, d["employment"])
            elif "household" in d:
                template = lc["household"][lang]
                v["household"] = tpl["labels"]["household"].get(d["household"], {}).get(lang, d["household"])
            elif "fact" in d:
                template = lc["fact"][d["fact"]][lang]
                v["value"] = facts["facts"].get(d["fact"], {}).get(d["value"], {}).get(lang, d["value"])
            elif "flag" in d:
                template = lc["flag"][lang]
                v["flag"] = facts["flags"].get(d["flag"], {}).get(lang, d["flag"])
            else:
                continue
        elif kind in tpl["beats"]:
            template = tpl["beats"][kind][lang]
        else:
            continue
        lines.append({"month": b["month"], "age": b["age"], "kind": kind, "text": fill(template, v)})
    return lines


def paragraphs(lines: list[dict]) -> list[str]:
    """A new paragraph at every decision, quiet period and at the end."""
    out: list[list[str]] = []
    for line in lines:
        if not out or line["kind"] in PARAGRAPH_STARTS:
            out.append([])
        out[-1].append(line["text"])
    return [" ".join(p) for p in out]


def dataset_goal_names() -> list[str]:
    path = default_data_dir() / "synthetic-goals.csv"
    if not path.exists():
        return []
    with open(path, newline="", encoding="utf-8") as f:
        return sorted({r["goal_name"] for r in csv.DictReader(f)})


def check(tpl: dict, facts: dict, events: dict) -> list[str]:
    errors = []
    langs = kotlin_enum("Lang")

    def texts(where: str, lt: dict):
        for lang in langs:
            if not lt.get(lang):
                errors.append(f"{where}: missing {lang}")
            for name in re.findall(r"\{(\w+)\}", lt.get(lang, "")):
                if name not in PLACEHOLDERS:
                    errors.append(f"{where} {lang}: unknown placeholder {{{name}}}")

    for kind in kotlin_enum("BeatKind"):
        if kind == "LIFE_CHANGE":
            continue
        if kind not in tpl["beats"]:
            errors.append(f"beats: no template for {kind}")
        else:
            texts(f"beats.{kind}", tpl["beats"][kind])
    for kind in tpl["beats"]:
        if kind not in kotlin_enum("BeatKind"):
            errors.append(f"beats.{kind} is not a BeatKind")
    lc = tpl["lifeChange"]
    texts("lifeChange.employment", lc["employment"])
    texts("lifeChange.household", lc["household"])
    texts("lifeChange.flag", lc["flag"])
    for key in kotlin_enum("FactKey"):
        if key not in lc["fact"]:
            errors.append(f"lifeChange.fact: no template for {key}")
        else:
            texts(f"lifeChange.fact.{key}", lc["fact"][key])
    texts("because", tpl["because"])
    for name, values in (("employment", kotlin_enum("Employment")), ("household", kotlin_enum("Household"))):
        for value in values:
            if value not in tpl["labels"][name]:
                errors.append(f"labels.{name}: no label for {value}")
            else:
                texts(f"labels.{name}.{value}", tpl["labels"][name][value])
    for goal in dataset_goal_names():
        if goal not in tpl["labels"]["goal"]:
            errors.append(f"labels.goal: no label for the dataset goal {goal!r}")
    for goal, lt in tpl["labels"]["goal"].items():
        texts(f"labels.goal.{goal}", lt)
    for key, values in facts["facts"].items():           # the values the templates show
        for code, lt in values.items():
            if any(not lt.get(lang) for lang in langs):
                errors.append(f"facts.json {key}.{code}: missing a language")
    for e in events["actions"] + events.get("shocks", []):
        if any(not e["title"].get(lang) for lang in langs):
            errors.append(f"events.json {e['id']}: title missing a language")
    return errors


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--play", metavar="PROFILE_ID")
    ap.add_argument("--path", default="")
    ap.add_argument("--mode", default="SAME_LEVEL", choices=["SAME_LEVEL", "FULL_RANDOM"])
    ap.add_argument("--seed", type=int, default=2026)
    ap.add_argument("--lang", default="EN", choices=["EN", "ES"])
    a = ap.parse_args()
    tpl, facts, events = load("chronicle.json"), load("facts.json"), load("events.json")
    if not (a.check or a.play):
        ap.print_help()
        return 0
    if a.check:
        errors = check(tpl, facts, events)
        for e in errors:
            print("ERROR", e)
        print("chronicle OK" if not errors else f"{len(errors)} error(s)")
        if errors:
            return 1
    if a.play:
        from game import play
        from validate_events import load_context
        s0, model, macro = load_context(a.play, facts)
        path = [c for c in a.path.split(",") if c]
        result = play(events["actions"], events.get("shocks", []), s0, model, macro, path, a.seed, a.mode, a.lang)
        for p in paragraphs(render(result["beats"], a.lang, tpl, events, facts, int(s0["period"][:4]))):
            print(p + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
