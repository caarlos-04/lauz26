"""Build FinancialStatements straight from the dataset (opening + the 24 observed months) and check them.

    python tools/build_statements.py --profile P00001 [--profile P00002 ...]   # -> tools/out/statements/<id>.json
    python tools/build_statements.py --all --check-only                        # verify identities for every profile
"""
import argparse
import json
from pathlib import Path

from calibrate import DEFAULT_FACTS, annuity_payment, initial_life, profile_stats
from common import default_data_dir, load_dataset
from statement import check_statement, dataset_parts, observed_statements, opening_statement


def statements_for(ds: dict, pid: str, facts_catalog: dict) -> list[dict]:
    pr = ds["profiles"][pid]
    living = profile_stats(pr, ds["series"][pid])["living"]
    parts = dataset_parts(ds, pid, initial_life(pr, facts_catalog), living, annuity_payment)
    return [opening_statement(ds, pid, parts)] + observed_statements(ds, pid, parts)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data-dir", type=Path, default=default_data_dir())
    ap.add_argument("--out-dir", type=Path, default=Path(__file__).resolve().parent / "out" / "statements")
    ap.add_argument("--facts", type=Path, default=DEFAULT_FACTS)
    ap.add_argument("--profile", action="append", default=[], help="profile id (repeatable)")
    ap.add_argument("--all", action="store_true", help="every profile in the dataset")
    ap.add_argument("--check-only", action="store_true", help="only verify the accounting identities")
    args = ap.parse_args()

    ds = load_dataset(args.data_dir)
    facts_catalog = json.loads(args.facts.read_text(encoding="utf-8"))
    pids = sorted(ds["profiles"]) if args.all else (args.profile or ["P00001"])
    n_errors = 0
    for pid in pids:
        sts = statements_for(ds, pid, facts_catalog)
        for s in sts:
            for e in check_statement(s):
                n_errors += 1
                print(f"{pid} month {s['month']}: {e}")
        if not args.check_only:
            args.out_dir.mkdir(parents=True, exist_ok=True)
            out = args.out_dir / f"{pid}.json"
            out.write_text(json.dumps({"profileId": pid, "statements": sts}, indent=2, ensure_ascii=False),
                           encoding="utf-8")
            print(f"{pid}: {len(sts)} statements -> {out}")
    print(f"checked {len(pids)} profile(s): {n_errors} identity error(s)")


if __name__ == "__main__":
    main()
