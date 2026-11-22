"""Golden games for the Kotlin engine: the Python reference plays them, the Kotlin tests must reproduce them.

Each file holds the inputs (opening statement, seed, choices) and every simulated month (statements), the story
beats and the attribution. Kotlin reads the same flow-model.json, macro.json, events.json and facts.json.

Output: tools/out/golden/<name>.json      python tools/golden.py   (needs flow_model.py, macro.py first)
"""
import json
from pathlib import Path

from common import default_data_dir, load_dataset
from game import play
from statement import dataset_opening

ROOT = Path(__file__).resolve().parents[1]
OUT = Path(__file__).resolve().parent / "out"
# profile, choices per turn, seed: a student, employees in Lausanne and Zurich, a shared flat, a loan, an old worker
GAMES = [
    ("P00001", "AAAAA", 2026), ("P00001", "BBBBB", 7), ("P00746", "AAAAA", 2026), ("P00746", "BABAB", 11),
    ("P00148", "ABABA", 3), ("P00055", "AAAAA", 5), ("P00013", "BBBBB", 2026), ("P00028", "ABBBB", 2026),
    ("P00002", "AAAAA", 13),
    # the way-back events and the Geneva offer
    ("P00004", "AAAAA", 1), ("P00014", "AAAAA", 1), ("P00045", "AAAAA", 1), ("P00203", "AAAAA", 2),
]


def main() -> None:
    facts = json.loads((ROOT / "content/facts.json").read_text(encoding="utf-8"))
    events = json.loads((ROOT / "content/events.json").read_text(encoding="utf-8"))["events"]
    model = json.loads((OUT / "flow-model.json").read_text(encoding="utf-8"))
    macro = json.loads((OUT / "macro.json").read_text(encoding="utf-8"))
    ds = load_dataset(default_data_dir())
    (OUT / "golden").mkdir(parents=True, exist_ok=True)
    for pid, choices, seed in GAMES:
        s0 = dataset_opening(ds, pid, facts)
        res = play(events, s0, model, macro, list(choices), [], seed)
        name = f"{pid}-{choices}-{seed}"
        doc = {"name": name, "seed": seed, "choices": list(choices), "opening": s0, "monthly": res["monthly"],
               "statements": res["statements"], "beats": res["beats"],
               "attribution": [{"key": k, "chf": v} for k, v in res["attribution"].items()],
               "startNetWorth": res["startNetWorth"], "endNetWorth": res["endNetWorth"]}
        (OUT / "golden" / f"{name}.json").write_text(json.dumps(doc, ensure_ascii=False), encoding="utf-8")
        decisions = [b["eventId"] for b in res["beats"] if b["kind"] == "DECISION"]
        print(f"{name}: {len(res['monthly']) - 1} months, decisions {decisions}, net worth {res['endNetWorth']:,.0f}")


if __name__ == "__main__":
    main()
