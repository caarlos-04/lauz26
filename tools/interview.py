"""The interview shown when the app starts (P1) and the opening statement it produces (P2). Standard library only.

The questions are not free text: every option fills fields of the person (`ProfileField`), and every number comes from
the dataset, so the person the interview builds looks like the people the simulator was fitted on.

    python tools/interview.py --build               # CSVs -> content/interview.json + content/interview-defaults.json
    python tools/interview.py --check               # validate the committed content against the Kotlin enums, facts.json and the data limits
    python tools/interview.py --demo 5 [--seed N]   # answer at random, print the opening statement of each person
    python tools/interview.py --strings DIR         # write DIR/values/strings.xml and DIR/values-es/strings.xml (Compose resources)
    python tools/interview.py --strings-into        # replace the q_* strings of the app's resources (shared/.../composeResources)

What the simulator reads from a person (tools/flow_model.py FEATURES, tools/simulate.py) decides what we ask: employment,
studying, city, household, age, income, rent and cash. The flow model keeps only employment, city and studying as drivers
of the flows (the rest of the person's fields do not move them significantly), plus income and rent as bases of the amounts.

Fields that have no `ProfileField` (investments, loan) start at 0: the interview cannot set them.
"""
import argparse
import csv
import io
import json
import random
import re
import statistics as st
import sys
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import default_data_dir, quantile, read_csv  # noqa: E402
from statement import new_person  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
CONTENT = ROOT / "content"
ENUMS_KT = ROOT / "shared" / "src" / "commonMain" / "kotlin" / "com" / "lauzhack" / "twin" / "model" / "Enums.kt"
INTERVIEW_JSON = CONTENT / "interview.json"
DEFAULTS_JSON = CONTENT / "interview-defaults.json"
FACTS_JSON = CONTENT / "facts.json"

# Band edges. Age and income are the ones tools/flow_model.py uses (FIXED_EDGES); rent and cash are readable edges chosen
# from the quantiles of the dataset (rent p20/p40/p60/p80 = 1.4k/1.8k/2.1k/2.6k; cash p10/p20/p40 = 15k/26k/44k).
AGE_EDGES = [18, 25, 35, 45, 55, 65]
INCOME_EDGES = [2500, 5000, 7500, 10000]
RENT_EDGES = [1200, 1600, 2000, 2600]
CASH_EDGES = [5000, 15000, 40000, 80000]
MIN_PEOPLE = 30             # a band's value is the dataset median only if at least this many people fall in it
MIN_CASH_MONTHS = [0, 0.5, 1, 3, 6]   # cushion options, in months of the median monthly outflow
START_PERIOD = "2025-01-01"          # the dataset's as_of_date: month 0 of every game
ASSUMPTIONS_JSON = CONTENT / "assumptions.json"     # objectivePenalties: CHF per month below the cushion (single source)
ALEXIA_JSON = CONTENT / "alexia.json"
ALEXIA_ID, ALEXIA_PUBLIC_ID = "P00001", "DEMO-ALEXIA"  # the challenge's demo profile; row ids never leave the dataset
ALEXIA_CUSHION_OPTION = "m3"                          # one month of expenses: the same cushion the interview offers

REQUIRED_FIELDS = ["AGE", "CITY", "EMPLOYMENT", "EDUCATION", "HOUSEHOLD", "GOAL_TYPE", "OBJECTIVE", "MIN_CASH"]
OPTIONAL_FIELDS = ["MONTHLY_INCOME", "MONTHLY_RENT", "OPENING_CASH"]    # "I don't know" -> default from the data


# ------------------------------------------------------------------------------------------------ code contracts
def kotlin_enum(name: str) -> list[str]:
    """Values of a Kotlin enum in model/Enums.kt: the code is the contract, not the docs."""
    text = ENUMS_KT.read_text(encoding="utf-8")
    m = re.search(r"enum class " + name + r"\s*\{(.*?)\}", text, re.S)
    if not m:
        raise SystemExit(f"enum {name} not found in {ENUMS_KT}")
    body = re.sub(r"//.*", "", m.group(1))
    return [x.strip() for x in body.replace("\n", " ").split(",") if x.strip()]


def load_facts() -> dict:
    return json.loads(FACTS_JSON.read_text(encoding="utf-8"))


# ------------------------------------------------------------------------------------------------ labels
def chf(n: float) -> str:
    """12345 -> 12’345 (Swiss grouping, as in the UI strings)."""
    return f"{int(round(n)):,}".replace(",", "’")


def band_pairs(edges: list[float], low: float = 0) -> list[tuple]:
    """[(lo, hi)] with None for an open end: edges [a, b] -> [(None, a), (a, b), (b, None)]."""
    pts = [None] + list(edges) + [None]
    return [(pts[i], pts[i + 1]) for i in range(len(pts) - 1)]


def money_label(lo, hi) -> dict:
    if lo is None:
        return {"EN": f"Less than CHF {chf(hi)}", "ES": f"Menos de CHF {chf(hi)}"}
    if hi is None:
        return {"EN": f"More than CHF {chf(lo)}", "ES": f"Más de CHF {chf(lo)}"}
    return {"EN": f"CHF {chf(lo)} – {chf(hi)}", "ES": f"CHF {chf(lo)} – {chf(hi)}"}


def age_label(lo, hi) -> dict:
    if hi is None:
        return {"EN": f"{lo} or older", "ES": f"{lo} o más"}
    return {"EN": f"{lo}–{hi - 1}", "ES": f"{lo}–{hi - 1}"}


def band_label_key(value: float, edges: list[float]) -> int:
    return sum(1 for e in edges if value >= e)


def round_to(x: float, step: int) -> int:
    return int(round(x / step) * step)


def representative(values_in_band: list[float], lo, hi, step: int, floor: float) -> tuple[int, int]:
    """The value an option stands for: the dataset median of the people in the band (if there are enough of them),
    otherwise the middle of the band (open ends: half of the upper edge, or 1.25 times the lower edge)."""
    if len(values_in_band) >= MIN_PEOPLE:
        return max(round_to(st.median(values_in_band), step), int(floor)), len(values_in_band)
    if lo is None:
        mid = hi / 2
    elif hi is None:
        mid = lo * 1.25
    else:
        mid = (lo + hi) / 2
    return max(round_to(mid, step), int(floor)), len(values_in_band)


# ------------------------------------------------------------------------------------------------ data
def read_transactions(data_dir: Path):
    csv_path, zip_path = data_dir / "synthetic-transactions.csv", data_dir / "synthetic-transactions.zip"
    if csv_path.exists():
        f = open(csv_path, newline="", encoding="utf-8")
    else:
        f = io.TextIOWrapper(zipfile.ZipFile(zip_path).open("synthetic-transactions.csv"), encoding="utf-8", newline="")
    with f:
        yield from csv.DictReader(f)


def monthly_outflow(data_dir: Path, months: int = 24) -> float:
    """Median over people of their average monthly expenses in the ledger."""
    totals: dict[str, float] = defaultdict(float)
    for r in read_transactions(data_dir):
        if r["transaction_type"] == "expense":
            totals[r["profile_id"]] += abs(float(r["amount_chf"]))
    return st.median(v / months for v in totals.values())


def load_people(data_dir: Path) -> tuple[list[dict], list[dict]]:
    return read_csv(data_dir / "synthetic-profiles.csv"), read_csv(data_dir / "synthetic-goals.csv")


def med(values: list[float]) -> dict:
    return {"chf": round_to(st.median(values), 10), "n": len(values)}


# ------------------------------------------------------------------------------------------------ build
def build(data_dir: Path) -> tuple[dict, dict]:
    facts = load_facts()
    profiles, goals = load_people(data_dir)
    outflow = monthly_outflow(data_dir)
    ages = [int(r["age_at_start"]) for r in profiles]
    income = [float(r["reference_monthly_net_income_chf"]) for r in profiles]
    rent = [float(r["reference_monthly_rent_chf"]) for r in profiles]
    cash = [float(r["opening_cash_chf"]) for r in profiles]
    income_by_pid = {r["profile_id"]: float(r["reference_monthly_net_income_chf"]) for r in profiles}

    # ---- defaults ("I don't know"): medians of the dataset
    emp_income: dict[str, list[float]] = defaultdict(list)
    for r in profiles:   # a student without a job is NOT_WORKING (docs: work only; studying is facts.EDUCATION)
        emp = "NOT_WORKING" if r["employment_status"] == "student" else r["employment_status"].upper()
        emp_income[emp].append(float(r["reference_monthly_net_income_chf"]))
    by_city_hh, by_city, by_hh = defaultdict(list), defaultdict(list), defaultdict(list)
    for r in profiles:
        x, hh = float(r["reference_monthly_rent_chf"]), r["household_type"].upper()
        by_city_hh[f"{r['city']}|{hh}"].append(x)
        by_city[r["city"]].append(x)
        by_hh[hh].append(x)
    age_bands = band_pairs(AGE_EDGES)[1:]       # the first age band starts at 18: no "less than"
    cash_by_age = defaultdict(list)
    for r in profiles:
        cash_by_age[band_label_key(int(r["age_at_start"]), AGE_EDGES) - 1].append(float(r["opening_cash_chf"]))
    goal_rows: dict[str, list[dict]] = defaultdict(list)
    for g in goals:
        goal_rows[g["goal_name"]].append(g)
    goal_defaults = {}
    for name, rows in sorted(goal_rows.items()):
        goal_defaults[name] = {
            "incomeRatio": round(st.median(float(x["target_amount_chf"]) / income_by_pid[x["profile_id"]] for x in rows), 1),
            "targetDate": Counter(x["target_date"] for x in rows).most_common(1)[0][0],
            "priority": Counter(x["priority"].upper() for x in rows).most_common(1)[0][0],
            "n": len(rows)}
    target_amounts = [float(g["target_amount_chf"]) for g in goals]
    defaults = {
        "version": 1,
        "meta": {"generator": "tools/interview.py --build", "source": "LauzHack 2026 synthetic dataset (synthetic-profiles.csv, synthetic-goals.csv, synthetic-transactions)",
                 "nProfiles": len(profiles),
                 "note": "Medians of the dataset, used when the person answers 'I don't know' or to size the goal. n = people behind each value."},
        "limits": {"age": [min(ages), max(ages)], "incomeChf": [round(min(income)), round(max(income))],
                   "rentChf": [round(min(rent)), round(max(rent))], "cashChf": [round(min(cash)), round(max(cash))],
                   "goalTargetChf": [round(min(target_amounts)), round(max(target_amounts))]},
        "ageBands": [{"from": lo, "to": hi} for lo, hi in age_bands],
        "incomeByEmployment": {k: med(v) for k, v in sorted(emp_income.items())},
        "incomeOverall": med(income),
        "rentByCityHousehold": {k: med(v) for k, v in sorted(by_city_hh.items())},
        "rentByCity": {k: med(v) for k, v in sorted(by_city.items())},
        "rentByHousehold": {k: med(v) for k, v in sorted(by_hh.items())},
        "rentOverall": med(rent),
        "cashByAgeBand": [med(cash_by_age[i]) for i in range(len(age_bands))],
        "cashOverall": med(cash),
        "goals": goal_defaults,
        "monthlyOutflowChf": round_to(outflow, 10),
    }

    # ---- questions
    def q(qid, text_en, text_es, options):
        return {"id": qid, "textKey": f"q_{qid}", "text": {"EN": text_en, "ES": text_es},
                "options": [{"id": oid, "labelKey": f"q_{qid}_{oid}", "label": label, "sets": sets} for oid, label, sets in options]}

    dont_know = {"EN": "I don’t know", "ES": "No lo sé"}

    def numeric_options(prefix, edges, values, field, step, floor):
        opts = []
        for i, (lo, hi) in enumerate(band_pairs(edges)):
            in_band = [v for v in values if (lo is None or v >= lo) and (hi is None or v < hi)]
            rep, n = representative(in_band, lo, hi, step, floor)
            opts.append((f"{prefix}{i + 1}", money_label(lo, hi), {field: str(rep)}))
        opts.append((f"{prefix}dk", dont_know, {}))
        return opts

    age_opts = []
    for i, (lo, hi) in enumerate(age_bands):
        in_band = [a for a in ages if a >= lo and (hi is None or a < hi)]
        age_opts.append((f"a{i + 1}", age_label(lo, hi), {"AGE": str(round(st.median(in_band)))}))

    cities = sorted({r["city"] for r in profiles}, key=lambda c: facts["facts"]["CITY"][c]["EN"])
    city_opts = [(re.sub(r"\W+", "_", c.lower()), facts["facts"]["CITY"][c], {"CITY": c}) for c in cities]
    hh_labels = {"SINGLE": ("Alone", "Solo/a"), "COUPLE": ("With my partner", "Con mi pareja"),
                 "FAMILY": ("With my partner and children", "Con mi pareja e hijos"),
                 "SHARED_HOUSING": ("In a shared flat", "En un piso compartido")}
    emp_labels = {"EMPLOYED": ("Employed full time", "Empleado/a a tiempo completo"), "PART_TIME": ("Part time", "Tiempo parcial"),
                  "SELF_EMPLOYED": ("Self-employed", "Autónomo/a"), "UNEMPLOYED": ("Looking for a job", "Buscando trabajo"),
                  "NOT_WORKING": ("Not working", "Sin trabajar"), "RETIRED": ("Retired", "Jubilado/a")}
    edu_labels = {"UNKNOWN": ("Not studying, no degree to report", "No estudio y no tengo título que indicar"),
                  "BACHELOR_IN_PROGRESS": ("Studying for a bachelor’s", "Estudiando un grado"),
                  "BACHELOR": ("I have a bachelor’s degree", "Tengo un grado"),
                  "MASTER_IN_PROGRESS": ("Studying for a master’s", "Estudiando un máster"),
                  "MASTER": ("I have a master’s degree", "Tengo un máster")}
    goal_labels = {"Emergency savings": ("An emergency fund", "Un fondo de emergencia"),
                   "Long-term financial resilience": ("Long-term financial security", "Seguridad financiera a largo plazo"),
                   "Housing deposit": ("A deposit for a home", "La entrada para una vivienda"),
                   "Career transition": ("Changing careers", "Cambiar de carrera"),
                   "Education flexibility": ("Being able to study later", "Poder estudiar más adelante"),
                   "Retirement reserve": ("A retirement reserve", "Una reserva para la jubilación")}
    objective_labels = {"GROWTH": ("Make my money grow, accepting more risk", "Hacer crecer mi dinero, aceptando más riesgo"),
                        "BALANCED": ("A balance between growth and safety", "Un equilibrio entre crecer y estar seguro/a"),
                        "SAFE": ("Safety first: avoid running short of cash", "Seguridad ante todo: no quedarme sin liquidez")}

    cushion = []
    for i, months in enumerate(MIN_CASH_MONTHS):
        amount = round_to(months * outflow, 500)
        if months == 0:
            label = {"EN": "No minimum", "ES": "Sin mínimo"}
        else:
            approx_en = f"about {months:g} month" + ("" if months == 1 else "s")
            approx_es = f"unos {months:g} " + ("mes" if months == 1 else "meses")
            label = {"EN": f"CHF {chf(amount)} ({approx_en} of expenses)", "ES": f"CHF {chf(amount)} ({approx_es} de gastos)"}
        cushion.append((f"m{i + 1}", label, {"MIN_CASH": str(amount)}))

    questions = [
        q("age", "How old are you?", "¿Cuántos años tienes?", age_opts),
        q("city", "Where do you live?", "¿Dónde vives?", city_opts),
        q("household", "Who do you live with?", "¿Con quién vives?",
          [(h.lower().replace("_housing", ""), {"EN": e, "ES": s}, {"HOUSEHOLD": h}) for h, (e, s) in hh_labels.items()]),
        q("employment", "How do you earn your living?", "¿Cómo ganas tu dinero?",
          [(k.lower(), {"EN": e, "ES": s}, {"EMPLOYMENT": k}) for k, (e, s) in emp_labels.items()]),
        q("education", "And your studies?", "¿Y tus estudios?",
          [(k.lower(), {"EN": e, "ES": s}, {"EDUCATION": k}) for k, (e, s) in edu_labels.items()]),
        q("income", "What is your monthly net income?", "¿Cuál es tu ingreso neto mensual?",
          numeric_options("i", INCOME_EDGES, income, "MONTHLY_INCOME", 50, 0)),
        q("rent", "How much rent do you pay per month? (your share, if you share)", "¿Cuánto alquiler pagas al mes? (tu parte, si compartes)",
          numeric_options("r", RENT_EDGES, rent, "MONTHLY_RENT", 50, 0)),
        q("cash", "How much cash do you have saved?", "¿Cuánto dinero tienes ahorrado en efectivo?",
          numeric_options("c", CASH_EDGES, cash, "OPENING_CASH", 500, 0)),
        q("goal", "What is your main financial goal?", "¿Cuál es tu principal meta financiera?",
          [(re.sub(r"\W+", "_", n.lower()), {"EN": e, "ES": s}, {"GOAL_TYPE": n}) for n, (e, s) in goal_labels.items()]),
        q("objective", "What matters most to you?", "¿Qué es lo más importante para ti?",
          [(k.lower(), {"EN": e, "ES": s}, {"OBJECTIVE": k}) for k, (e, s) in objective_labels.items()]),
        q("mincash", "What is the minimum cash cushion you want to keep?", "¿Qué colchón mínimo de efectivo quieres mantener?", cushion),
    ]
    interview = {
        "version": 1,
        "meta": {"generator": "tools/interview.py --build",
                 "source": "Options and values come from the LauzHack 2026 synthetic dataset; texts are ours (ES/EN).",
                 "note": "Each option fills fields of the person (ProfileField). Numbers are the dataset median of the people in the "
                         "band (or its middle if fewer than %d people). 'dk' options fill nothing: the value comes from interview-defaults.json. "
                         "textKey / labelKey are the keys of the UI strings (python tools/interview.py --strings DIR)." % MIN_PEOPLE},
        "questions": questions,
    }
    return interview, defaults


# ------------------------------------------------------------------------------------------------ the person (P2)
def fields_from_answers(interview: dict, answers: list[tuple[str, str]]) -> dict:
    fields: dict[str, str] = {}
    for qid, oid in answers:
        question = next(x for x in interview["questions"] if x["id"] == qid)
        fields.update(next(o for o in question["options"] if o["id"] == oid)["sets"])
    return fields


def default_rent(defaults: dict, city: str, household: str) -> float:
    for table, key in ((defaults["rentByCityHousehold"], f"{city}|{household}"), (defaults["rentByCity"], city),
                       (defaults["rentByHousehold"], household)):
        if key in table:
            return table[key]["chf"]
    return defaults["rentOverall"]["chf"]


def objective_penalties() -> dict:
    return json.loads(ASSUMPTIONS_JSON.read_text(encoding="utf-8"))["objectivePenalties"]


def person_from_fields(fields: dict, defaults: dict, facts: dict, profile_id: str = "USER-DEMO") -> tuple[dict, dict]:
    """Opening statement + Preferences. Mirrors shared/.../interview/InterviewBuilder.kt (keep both in sync)."""
    missing = [f for f in REQUIRED_FIELDS if f not in fields]
    if missing:
        raise ValueError(f"the interview left these fields empty: {missing}")
    age, city, employment = int(fields["AGE"]), fields["CITY"], fields["EMPLOYMENT"]
    household, education = fields["HOUSEHOLD"], fields["EDUCATION"]
    income = float(fields["MONTHLY_INCOME"]) if "MONTHLY_INCOME" in fields else float(
        defaults["incomeByEmployment"].get(employment, defaults["incomeOverall"])["chf"])
    rent = float(fields["MONTHLY_RENT"]) if "MONTHLY_RENT" in fields else float(default_rent(defaults, city, household))
    if "OPENING_CASH" in fields:
        cash = float(fields["OPENING_CASH"])
    else:
        bands = defaults["ageBands"]
        i = max(i for i, b in enumerate(bands) if age >= b["from"])
        cash = float(defaults["cashByAgeBand"][i]["chf"])
    g = defaults["goals"][fields["GOAL_TYPE"]]
    lo, hi = defaults["limits"]["goalTargetChf"]
    target = min(max(round_to(g["incomeRatio"] * income, 100), lo), hi)
    goal = {"name": fields["GOAL_TYPE"], "targetChf": float(target), "targetDate": g["targetDate"], "priority": g["priority"]}
    s = new_person(profile_id=profile_id, display_name="", age=age, city=city, employment=employment, household=household,
                   education=education, cash=cash, income=income, rent=rent, facts_catalog=facts, goals=[goal])
    prefs = {"objective": fields["OBJECTIVE"], "minCashChf": float(fields["MIN_CASH"]),
             "penaltyChfPerMonthBelowMin": objective_penalties()[fields["OBJECTIVE"]]}
    return s, prefs


def build_alexia(data_dir: Path, interview: dict) -> dict:
    """The demo profile: the challenge's Alexia (dataset profile P00001) as an opening statement, with BALANCED priorities
    and the one-month cushion of the interview. Written as content/alexia.json (DemoProfile in Kotlin)."""
    from common import load_dataset
    from statement import dataset_opening
    s = dataset_opening(load_dataset(data_dir), ALEXIA_ID, load_facts())
    s["profileId"] = ALEXIA_PUBLIC_ID
    cushion = next(o for q in interview["questions"] if q["id"] == "mincash" for o in q["options"] if o["id"] == ALEXIA_CUSHION_OPTION)
    prefs = {"objective": "BALANCED", "minCashChf": float(cushion["sets"]["MIN_CASH"]),
             "penaltyChfPerMonthBelowMin": objective_penalties()["BALANCED"]}
    return {"meta": {"generator": "tools/interview.py --build",
                     "note": "Alexia, the challenge's demo profile (20, student in Lausanne, CHF 8,000): her opening statement in the "
                             "dataset (statement.dataset_opening), with BALANCED priorities and a one-month cushion."},
            "statement": s, "preferences": prefs}


# ------------------------------------------------------------------------------------------------ check
def check(interview: dict, defaults: dict, facts: dict) -> list[str]:
    errors: list[str] = []
    profile_fields = kotlin_enum("ProfileField")
    allowed = {"EMPLOYMENT": kotlin_enum("Employment"), "HOUSEHOLD": kotlin_enum("Household"),
               "OBJECTIVE": kotlin_enum("Objective"), "EDUCATION": list(facts["facts"]["EDUCATION"]),
               "CITY": [c for c in facts["facts"]["CITY"] if c != "ABROAD"], "GOAL_TYPE": list(defaults["goals"])}
    lim = defaults["limits"]
    numeric_range = {"AGE": lim["age"], "MONTHLY_INCOME": lim["incomeChf"], "MONTHLY_RENT": lim["rentChf"],
                     "OPENING_CASH": [0, lim["cashChf"][1]], "MIN_CASH": [0, 10 ** 7]}
    setters: dict[str, set] = defaultdict(set)
    seen_q, seen_keys = set(), set()
    for qn in interview["questions"]:
        if qn["id"] in seen_q:
            errors.append(f"duplicate question id {qn['id']}")
        seen_q.add(qn["id"])
        for key, text in ((qn["textKey"], qn["text"]),):
            if key in seen_keys:
                errors.append(f"duplicate key {key}")
            seen_keys.add(key)
            if not text.get("EN") or not text.get("ES"):
                errors.append(f"{key}: missing EN or ES text")
        if len({o["id"] for o in qn["options"]}) != len(qn["options"]):
            errors.append(f"{qn['id']}: duplicate option ids")
        for o in qn["options"]:
            if o["labelKey"] in seen_keys:
                errors.append(f"duplicate key {o['labelKey']}")
            seen_keys.add(o["labelKey"])
            if not o["label"].get("EN") or not o["label"].get("ES"):
                errors.append(f"{o['labelKey']}: missing EN or ES text")
            for field, value in o["sets"].items():
                where = f"{qn['id']}/{o['id']}"
                if field not in profile_fields:
                    errors.append(f"{where}: {field} is not a ProfileField in Enums.kt {profile_fields}")
                    continue
                setters[field].add(qn["id"])
                if field in allowed and value not in allowed[field]:
                    errors.append(f"{where}: {field}={value!r} not in {allowed[field]}")
                if field in numeric_range:
                    try:
                        x = float(value)
                        if not numeric_range[field][0] <= x <= numeric_range[field][1] * 1.0001:
                            errors.append(f"{where}: {field}={x} outside the data range {numeric_range[field]}")
                    except ValueError:
                        errors.append(f"{where}: {field}={value!r} is not a number")
        ids = [o["id"] for o in qn["options"]]
        if len(qn["options"]) < 2:
            errors.append(f"{qn['id']}: needs at least two options")
        # numeric options must be increasing so the bands make sense to the reader
        for field in ("AGE", "MONTHLY_INCOME", "MONTHLY_RENT", "OPENING_CASH"):
            vals = [float(o["sets"][field]) for o in qn["options"] if field in o["sets"]]
            if vals and vals != sorted(vals):
                errors.append(f"{qn['id']}: {field} options are not increasing: {vals}")
    for field in REQUIRED_FIELDS + OPTIONAL_FIELDS:
        if field not in setters:
            errors.append(f"no question fills {field}")
    for qn in interview["questions"]:
        for field in OPTIONAL_FIELDS:
            if field in {f for o in qn["options"] for f in o["sets"]} and not any(not o["sets"] for o in qn["options"]):
                errors.append(f"{qn['id']}: offer an \"I don't know\" option (empty sets) for {field}")
    unused = [f for f in profile_fields if f not in setters]
    if unused:
        errors.append(f"ProfileField values no question fills: {unused}")
    # every option path must build a valid person
    base = {q["id"]: q["options"][0]["id"] for q in interview["questions"]}
    for qn in interview["questions"]:
        for o in qn["options"]:
            answers = [(q, o["id"] if q == qn["id"] else base[q]) for q in base]
            try:
                person_from_fields(fields_from_answers(interview, answers), defaults, facts)
            except Exception as e:                      # noqa: BLE001
                errors.append(f"{qn['id']}/{o['id']}: cannot build the person: {e}")
    return errors


# ------------------------------------------------------------------------------------------------ strings
# How the UI names the dataset goals (string key goal_<slug>, see goalLabel() in ui/i18n/Text.kt).
GOAL_NAMES = {"Emergency savings": "Ahorro de emergencia", "Long-term financial resilience": "Seguridad financiera a largo plazo",
              "Housing deposit": "Entrada para una vivienda", "Career transition": "Cambio de carrera",
              "Education flexibility": "Margen para estudiar más adelante", "Retirement reserve": "Reserva para la jubilación"}


def goal_key(name: str) -> str:
    return "goal_" + re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")


def xml_escape(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "\\'")


def strings_xml(interview: dict, lang: str) -> str:
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>", "    <!-- Interview: generated by tools/interview.py --strings -->"]
    for q in interview["questions"]:
        lines.append(f'    <string name="{q["textKey"]}">{xml_escape(q["text"][lang])}</string>')
        for o in q["options"]:
            lines.append(f'    <string name="{o["labelKey"]}">{xml_escape(o["label"][lang])}</string>')
    for name, es in GOAL_NAMES.items():
        lines.append(f'    <string name="{goal_key(name)}">{xml_escape(name if lang == "EN" else es)}</string>')
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def strings_into(res_dir: Path, interview: dict) -> None:
    """Replace the interview strings (every <string name="q_...">) of the app's Compose resources, keeping the rest."""
    for sub, lang in (("values", "EN"), ("values-es", "ES")):
        path = res_dir / sub / "strings.xml"
        lines = path.read_text(encoding="utf-8").splitlines()
        new = [l for l in strings_xml(interview, lang).splitlines() if l.strip().startswith("<string ")]
        names = {re.search(r'name="([^"]+)"', l).group(1) for l in new}
        q_lines = [i for i, l in enumerate(lines)
                   if l.strip().startswith('<string name="q_') or (m := re.search(r'<string name="([^"]+)"', l)) and m.group(1) in names]
        at = q_lines[0] if q_lines else next(i for i, l in enumerate(lines) if l.strip() == "</resources>")
        kept = [l for i, l in enumerate(lines) if i not in set(q_lines)]
        at -= sum(1 for i in q_lines if i < at)
        path.write_text("\n".join(kept[:at] + new + kept[at:]) + "\n", encoding="utf-8")
        print(f"{path.relative_to(ROOT)}: {len(q_lines)} interview strings replaced by {len(new)}")


# ------------------------------------------------------------------------------------------------ main
def load_committed() -> tuple[dict, dict, dict]:
    return (json.loads(INTERVIEW_JSON.read_text(encoding="utf-8")), json.loads(DEFAULTS_JSON.read_text(encoding="utf-8")), load_facts())


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--build", action="store_true")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--demo", type=int, metavar="N")
    ap.add_argument("--strings", metavar="DIR")
    ap.add_argument("--strings-into", metavar="RES_DIR", nargs="?", const=str(ROOT / "shared" / "src" / "commonMain" / "composeResources"),
                    help="replace the q_* strings of the app (default shared/src/commonMain/composeResources)")
    ap.add_argument("--data-dir", type=Path, default=None)
    ap.add_argument("--seed", type=int, default=1)
    a = ap.parse_args()
    if not (a.build or a.check or a.demo or a.strings or a.strings_into):
        ap.print_help()
        return 0
    if a.build:
        data_dir = a.data_dir or default_data_dir()
        interview, defaults = build(data_dir)
        INTERVIEW_JSON.write_text(json.dumps(interview, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        DEFAULTS_JSON.write_text(json.dumps(defaults, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        ALEXIA_JSON.write_text(json.dumps(build_alexia(data_dir, interview), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"wrote {INTERVIEW_JSON.relative_to(ROOT)} ({len(interview['questions'])} questions), {DEFAULTS_JSON.relative_to(ROOT)} and {ALEXIA_JSON.relative_to(ROOT)}")
    interview, defaults, facts = load_committed()
    if a.check or a.build:
        errors = check(interview, defaults, facts)
        for e in errors:
            print("ERROR", e)
        print("interview OK" if not errors else f"{len(errors)} error(s)")
        if errors:
            return 1
    if a.demo:
        rng = random.Random(a.seed)
        for i in range(a.demo):
            answers = [(q["id"], rng.choice(q["options"])["id"]) for q in interview["questions"]]
            s, prefs = person_from_fields(fields_from_answers(interview, answers), defaults, facts, f"USER-{i + 1}")
            p = s["person"]
            print(f"\n#{i + 1} answers: {' '.join(f'{q}={o}' for q, o in answers)}")
            print(f"   {p['age']} y, {p['employment']}, {p['household']}, facts {p['facts']}")
            print(f"   income {s['monthlyIncomeChf']:,.0f}  rent {s['monthlyRentChf']:,.0f}  cash {s['cashChf']:,.0f}  goal {s['goals'][0]}")
            print(f"   preferences {prefs}")
    if a.strings:
        out = Path(a.strings)
        for sub, lang in (("values", "EN"), ("values-es", "ES")):
            (out / sub).mkdir(parents=True, exist_ok=True)
            (out / sub / "strings.xml").write_text(strings_xml(interview, lang), encoding="utf-8")
        print(f"wrote {out}/values/strings.xml and {out}/values-es/strings.xml")
    if a.strings_into:
        strings_into(Path(a.strings_into), interview)
    return 0


if __name__ == "__main__":
    sys.exit(main())
