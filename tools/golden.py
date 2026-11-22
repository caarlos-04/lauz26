"""Golden lives for the Kotlin engine: the Python reference plays them, the Kotlin tests must reproduce them.

Each life is a path through the decision tree (one action id per turn, missing = NOTHING), with a seed and a luck
mode. Several lives share their first nodes (branches of the same tree). Each file holds the inputs (opening
statement, seed, mode, path) and every simulated month (statements), the turns, the story beats and the attribution.
Kotlin reads the same flow-model.json, macro.json, events.json and facts.json.

Output: tools/out/golden/<name>.json      python tools/golden.py   (needs flow_model.py, macro.py first)
"""
import json
from pathlib import Path

from common import default_data_dir, load_dataset
from game import play
from statement import dataset_opening

ROOT = Path(__file__).resolve().parents[1]
OUT = Path(__file__).resolve().parent / "out"
# profile, path of actions, seed, luck mode. Every action at least once, branches from the same node, both luck modes,
# and job losses (P00746 seed 11 and P00004 seed 7: the shock of the turn).
GAMES = [
    ("P00001", ["A-master", "A-loan"], 2026, "SAME_LEVEL"),
    ("P00001", ["A-start-working", "A-part-time-master", "A-shared-flat"], 2026, "SAME_LEVEL"),
    ("P00001", ["NOTHING", "A-move-cheaper"], 2026, "FULL_RANDOM"),
    ("P00001", ["A-master", "NOTHING", "A-move-cheaper"], 2026, "FULL_RANDOM"),
    ("P00746", ["A-self-employed", "A-back-to-employee", "A-job-zurich"], 12, "SAME_LEVEL"),
    ("P00746", ["NOTHING", "A-job-zurich"], 11, "SAME_LEVEL"),
    ("P00746", ["A-four-day-week"], 11, "SAME_LEVEL"),
    ("P00055", ["NOTHING", "A-job-geneva"], 5, "FULL_RANDOM"),
    ("P00148", ["A-own-flat", "A-four-day-week"], 3, "SAME_LEVEL"),
    ("P00045", ["A-search-job"], 1, "SAME_LEVEL"),
    ("P00045", ["A-take-job", "A-own-flat"], 1, "FULL_RANDOM"),
    ("P00014", ["A-back-to-full-time"], 1, "SAME_LEVEL"),
    ("P00028", ["A-retire"], 2026, "SAME_LEVEL"),
    ("P00013", ["A-own-flat"], 2026, "SAME_LEVEL"),
    ("P00002", ["A-shared-flat"], 13, "SAME_LEVEL"),
    ("P00004", [], 7, "SAME_LEVEL"),
]


def main() -> None:
    facts = json.loads((ROOT / "content/facts.json").read_text(encoding="utf-8"))
    doc = json.loads((ROOT / "content/events.json").read_text(encoding="utf-8"))
    model = json.loads((OUT / "flow-model.json").read_text(encoding="utf-8"))
    macro = json.loads((OUT / "macro.json").read_text(encoding="utf-8"))
    ds = load_dataset(default_data_dir())
    (OUT / "golden").mkdir(parents=True, exist_ok=True)
    for old in (OUT / "golden").glob("*.json"):
        old.unlink()
    for pid, path, seed, mode in GAMES:
        s0 = dataset_opening(ds, pid, facts)
        res = play(doc["actions"], doc["shocks"], s0, model, macro, path, seed, mode)
        name = f"{pid}-{'+'.join(path) or 'NOTHING'}-{seed}-{mode}"
        turns = [{k: v for k, v in t.items() if k != "stateAfter"} for t in res["turns"]]
        out = {"name": name, "seed": seed, "luckMode": mode, "path": path, "opening": s0, "monthly": res["monthly"],
               "statements": res["statements"], "turns": turns, "beats": res["beats"],
               "attribution": [{"key": k, "chf": v} for k, v in res["attribution"].items()],
               "startNetWorth": res["startNetWorth"], "endNetWorth": res["endNetWorth"]}
        (OUT / "golden" / f"{name}.json").write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")
        shocks = [f"{t['shockId']}@turn{t['index']}" for t in res["turns"] if t["shockId"]]
        print(f"{name}: net worth {res['endNetWorth']:,.0f}, shocks {shocks}")


if __name__ == "__main__":
    main()
