"""Ship the app's data as resources of the KMP project (issue #14). Python standard library only.

The app never reads CSV: it loads small JSON files packaged with it. `content/` is their source of truth:
  - written by hand:  events.json, facts.json, chronicle.json
  - generated:        assumptions.json (tools/assumptions.py), flow-model.json (tools/flow_model.py), macro.json (tools/macro.py)
Every content/*.json is copied to shared/src/commonMain/composeResources/files/, which Compose Multiplatform packages on
Android, Desktop and Web (read with `Res.readBytes("files/<name>")`, see shared/.../content/AppContent.kt).

    python tools/ship_resources.py --from-out     # tools/out/{flow-model,macro}.json -> content/, then ship
    python tools/ship_resources.py                # content/*.json -> composeResources/files/
    python tools/ship_resources.py --check        # the shipped copies are identical and hold no dataset rows

Why flow-model.json and macro.json can be committed (docs/README.md open question 4): they hold aggregates only (factors,
quantiles, yearly rates and yearly public series), no row of the synthetic dataset, and the public providers allow reuse
with attribution (content/README.md, "Data sources and attribution").
"""
import argparse
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTENT = ROOT / "content"
OUT = Path(__file__).resolve().parent / "out"
FILES = ROOT / "shared" / "src" / "commonMain" / "composeResources" / "files"
GENERATED_IN_OUT = ("flow-model.json", "macro.json")
# Identifiers of dataset rows (profiles, transactions, accounts, assets...): none may leave the dataset.
ROW_ID = re.compile(r"\b(?:P\d{5}|[A-Z]{1,2}-P\d{5}[-\w]*)\b")


def content_files() -> list[Path]:
    return sorted(CONTENT.glob("*.json"))


def from_out() -> None:
    for name in GENERATED_IN_OUT:
        src = OUT / name
        if not src.exists():
            raise SystemExit(f"{src} not found: run python tools/flow_model.py and python tools/macro.py first")
        shutil.copyfile(src, CONTENT / name)
        print(f"copied tools/out/{name} -> content/{name}")


def ship() -> None:
    FILES.mkdir(parents=True, exist_ok=True)
    for f in content_files():
        shutil.copyfile(f, FILES / f.name)
    for stale in FILES.glob("*.json"):
        if not (CONTENT / stale.name).exists():
            stale.unlink()
            print(f"removed {stale.relative_to(ROOT)} (no longer in content/)")
    print(f"shipped {len(content_files())} files to {FILES.relative_to(ROOT)}")


def check() -> list[str]:
    errors = []
    for name in GENERATED_IN_OUT:
        if not (CONTENT / name).exists():
            errors.append(f"content/{name} is missing: python tools/ship_resources.py --from-out")
    for f in content_files():
        shipped = FILES / f.name
        if not shipped.exists():
            errors.append(f"{f.name} is not shipped: python tools/ship_resources.py")
        elif shipped.read_bytes() != f.read_bytes():
            errors.append(f"{f.name}: the shipped copy differs from content/: python tools/ship_resources.py")
        ids = sorted(set(ROW_ID.findall(f.read_text(encoding="utf-8"))))
        if ids:
            errors.append(f"{f.name} contains dataset row identifiers {ids[:5]}: ship aggregates only")
    for extra in FILES.glob("*.json"):
        if not (CONTENT / extra.name).exists():
            errors.append(f"{extra.relative_to(ROOT)} has no source in content/")
    return errors


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--from-out", action="store_true", help="first copy the generated simulator inputs from tools/out")
    ap.add_argument("--check", action="store_true", help="only check, do not copy")
    a = ap.parse_args()
    if not a.check:
        if a.from_out:
            from_out()
        ship()
    errors = check()
    for e in errors:
        print("ERROR", e)
    print("resources OK" if not errors else f"{len(errors)} error(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
