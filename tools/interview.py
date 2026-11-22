"""The interview shown when the app starts (P1) and the opening statement it produces (P2). Standard library only.

Two kinds of questions. Choices (city, household, work, studies, goal, priority): every option fills fields of the person
(`ProfileField`). Numbers (age, income, rent, cash, investments, debt, cushion): the person types the amount in a field
bounded by the range of the dataset (`input`: min, max, step and the typical value of the data as a hint), and most of
them also offer "I don't know" (the dataset median). So the person the interview builds stays inside what the
simulator was fitted on.

    python tools/interview.py --build               # CSVs -> content/interview.json + content/interview-defaults.json
    python tools/interview.py --check               # validate the committed content against the Kotlin enums, facts.json and the data limits
    python tools/interview.py --demo 5 [--seed N]   # answer at random, print the opening statement of each person
    python tools/interview.py --strings DIR         # write DIR/values/strings.xml and DIR/values-es/strings.xml (Compose resources)
    python tools/interview.py --strings-into        # replace the q_* strings of the app's resources (shared/.../composeResources)

What the simulator reads from a person (tools/flow_model.py FEATURES, tools/simulate.py) decides what we ask: employment,
studying, city, household, age, income, rent and cash. The flow model keeps only employment, city and studying as drivers
of the flows (the rest of the person's fields do not move them significantly), plus income and rent as bases of the amounts.

Investments and debt: the amount invested (synthetic-assets.csv, everything but cash) and the debt still owed
(synthetic-liabilities.csv: one personal loan per person at most). The debt becomes a loan at the dataset's median rate,
repaid over 60 months (the dataset never pays its loans: the same assumption as statement.dataset_opening).
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
from statement import annuity_payment, new_person  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
CONTENT = ROOT / "content"
ENUMS_KT = ROOT / "shared" / "src" / "commonMain" / "kotlin" / "com" / "lauzhack" / "twin" / "model" / "Enums.kt"
INTERVIEW_JSON = CONTENT / "interview.json"
DEFAULTS_JSON = CONTENT / "interview-defaults.json"
FACTS_JSON = CONTENT / "facts.json"

# Band edges. Age and income are the ones tools/flow_model.py uses (FIXED_EDGES); rent and cash are readable edges chosen
# from the quantiles of the dataset (rent p20/p40/p60/p80 = 1.4k/1.8k/2.1k/2.6k; cash p10/p20/p40 = 15k/26k/44k).
AGE_EDGES = [18, 25, 35, 45, 55, 65]
START_PERIOD = "2025-01-01"          # the dataset's as_of_date: month 0 of every game
ASSUMPTIONS_JSON = CONTENT / "assumptions.json"     # objectivePenalties: CHF per month below the cushion (single source)
ALEXIA_JSON = CONTENT / "alexia.json"
ALEXIA_ID, ALEXIA_PUBLIC_ID = "P00001", "DEMO-ALEXIA"  # the challenge's demo profile; row ids never leave the dataset

REQUIRED_FIELDS = ["AGE", "CITY", "EMPLOYMENT", "EDUCATION", "HOUSEHOLD", "GOAL_TYPE", "OBJECTIVE", "MIN_CASH"]
OPTIONAL_FIELDS = ["MONTHLY_INCOME", "MONTHLY_RENT", "OPENING_CASH", "INVESTMENTS", "DEBT"]   # "I don't know" -> data default
LOAN_TERM_MONTHS = 60                 # the dataset never pays its loans: the same assumption as statement.dataset_opening
# Bounds of the numeric answers: the range of the dataset (what the simulator saw), rounded outwards to a round step.
# Amounts start at 0 (nothing saved, no debt); the age range is the dataset's.


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


def band_label_key(value: float, edges: list[float]) -> int:
    return sum(1 for e in edges if value >= e)


def round_to(x: float, step: int) -> int:
    return int(round(x / step) * step)


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
    invested: dict[str, float] = defaultdict(float)
    for a_ in read_csv(data_dir / "synthetic-assets.csv"):
        if a_["asset_type"] != "cash":
            invested[a_["profile_id"]] += float(a_["value_chf"])
    loans = read_csv(data_dir / "synthetic-liabilities.csv")
    owed: dict[str, float] = defaultdict(float)
    for l_ in loans:
        owed[l_["profile_id"]] += float(l_["opening_principal_chf"])
    investments = [invested[r["profile_id"]] for r in profiles]
    debts = [owed[r["profile_id"]] for r in profiles]

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
    cash_by_age, inv_by_age = defaultdict(list), defaultdict(list)
    for r in profiles:
        band = band_label_key(int(r["age_at_start"]), AGE_EDGES) - 1
        cash_by_age[band].append(float(r["opening_cash_chf"]))
        inv_by_age[band].append(invested[r["profile_id"]])
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
                   "investmentsChf": [round(min(investments)), round(max(investments))],
                   "debtChf": [round(min(debts)), round(max(debts))],
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
        "investmentsByAgeBand": [med(inv_by_age[i]) for i in range(len(age_bands))],
        "investmentsOverall": med(investments),
        "debtOverall": med(debts),
        "loan": {"annualRate": round(st.median(float(l_["annual_interest_rate"]) for l_ in loans), 4),
                 "termMonths": LOAN_TERM_MONTHS, "n": len(loans),
                 "note": "median rate of synthetic-liabilities.csv; the term is an assumption (the dataset never pays its loans)"},
        "goals": goal_defaults,
        "monthlyOutflowChf": round_to(outflow, 10),
    }

    # ---- questions
    def q(qid, text_en, text_es, options, number=None):
        out = {"id": qid, "textKey": f"q_{qid}", "text": {"EN": text_en, "ES": text_es},
               "options": [{"id": oid, "labelKey": f"q_{qid}_{oid}", "label": label, "sets": sets} for oid, label, sets in options]}
        if number:
            out["input"] = number
        return out

    dont_know = {"EN": "I don’t know", "ES": "No lo sé"}
    dk = [("dk", dont_know, {})]

    def number(field, values, step, unit, low=0):
        """A typed answer: from `low` to the dataset maximum rounded up to `step`; the hint is the dataset median."""
        hi = int(-(-max(values) // step) * step)
        return {"field": field, "min": low, "max": hi, "step": step, "unit": unit, "typical": round_to(st.median(values), step)}

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

    cushion_typical = round_to(outflow, 500)          # about one month of the median expenses
    questions = [
        q("age", "How old are you?", "¿Cuántos años tienes?", [],
          {"field": "AGE", "min": min(ages), "max": max(ages), "step": 1, "unit": "YEARS", "typical": round(st.median(ages))}),
        q("city", "Where do you live?", "¿Dónde vives?", city_opts),
        q("household", "Who do you live with?", "¿Con quién vives?",
          [(h.lower().replace("_housing", ""), {"EN": e, "ES": s}, {"HOUSEHOLD": h}) for h, (e, s) in hh_labels.items()]),
        q("employment", "How do you earn your living?", "¿Cómo ganas tu dinero?",
          [(k.lower(), {"EN": e, "ES": s}, {"EMPLOYMENT": k}) for k, (e, s) in emp_labels.items()]),
        q("education", "And your studies?", "¿Y tus estudios?",
          [(k.lower(), {"EN": e, "ES": s}, {"EDUCATION": k}) for k, (e, s) in edu_labels.items()]),
        q("income", "What is your monthly net income?", "¿Cuál es tu ingreso neto mensual?", dk,
          number("MONTHLY_INCOME", income, 50, "CHF_MONTH")),
        q("rent", "How much rent do you pay per month? (your share, if you share)", "¿Cuánto alquiler pagas al mes? (tu parte, si compartes)", dk,
          number("MONTHLY_RENT", rent, 50, "CHF_MONTH")),
        q("cash", "How much cash do you have saved (current and savings accounts)?", "¿Cuánto dinero tienes ahorrado (cuentas corrientes y de ahorro)?", dk,
          number("OPENING_CASH", cash, 500, "CHF")),
        q("investments", "How much do you have invested (funds, shares, pension savings you can access)?",
          "¿Cuánto tienes invertido (fondos, acciones, ahorro para la jubilación disponible)?", dk,
          number("INVESTMENTS", investments, 500, "CHF")),
        q("debt", "How much do you still owe on loans (personal loan, car, studies)?",
          "¿Cuánto debes todavía en préstamos (personal, coche, estudios)?", dk,
          number("DEBT", debts, 500, "CHF")),
        q("goal", "What is your main financial goal?", "¿Cuál es tu principal meta financiera?",
          [(re.sub(r"\W+", "_", n.lower()), {"EN": e, "ES": s}, {"GOAL_TYPE": n}) for n, (e, s) in goal_labels.items()]),
        q("objective", "What matters most to you?", "¿Qué es lo más importante para ti?",
          [(k.lower(), {"EN": e, "ES": s}, {"OBJECTIVE": k}) for k, (e, s) in objective_labels.items()]),
        q("mincash", "What is the minimum cash cushion you want to keep? (one month of expenses is typical)",
          "¿Qué colchón mínimo de efectivo quieres mantener? (lo habitual es un mes de gastos)", [],
          {"field": "MIN_CASH", "min": 0, "max": round_to(6 * outflow, 500) * 2, "step": 500, "unit": "CHF", "typical": cushion_typical}),
    ]
    interview = {
        "version": 1,
        "meta": {"generator": "tools/interview.py --build",
                 "source": "Options and values come from the LauzHack 2026 synthetic dataset; texts are ours (ES/EN).",
                 "note": "Each option fills fields of the person (ProfileField). A question with `input` is answered with a number "
                         "typed by the person, between min and max (the dataset range); `typical` (the dataset median) is shown as a hint. "
                         "'dk' options fill nothing: the value comes from interview-defaults.json. "
                         "textKey / labelKey are the keys of the UI strings (python tools/interview.py --strings DIR)."},
        "questions": questions,
    }
    return interview, defaults


# ------------------------------------------------------------------------------------------------ the person (P2)
def number_text(x: float) -> str:
    """How a typed number is stored in a field: 1500.0 -> "1500", 12.5 -> "12.5" (the same in Kotlin)."""
    return str(int(x)) if float(x).is_integer() else repr(float(x))


def fields_from_answers(interview: dict, answers: list[tuple]) -> dict:
    """(question id, option id) for a choice, (question id, number) for a typed answer. A typed number must be inside
    the question's range."""
    fields: dict[str, str] = {}
    for qid, answer in answers:
        question = next(x for x in interview["questions"] if x["id"] == qid)
        if isinstance(answer, (int, float)):
            spec = question["input"]
            if not spec["min"] <= answer <= spec["max"]:
                raise ValueError(f"{qid}: {answer} outside [{spec['min']}, {spec['max']}]")
            fields[spec["field"]] = number_text(answer)
        else:
            fields.update(next(o for o in question["options"] if o["id"] == answer)["sets"])
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
    band = max(i for i, b in enumerate(defaults["ageBands"]) if age >= b["from"])
    cash = float(fields["OPENING_CASH"]) if "OPENING_CASH" in fields else float(defaults["cashByAgeBand"][band]["chf"])
    investments = float(fields["INVESTMENTS"]) if "INVESTMENTS" in fields else float(defaults["investmentsByAgeBand"][band]["chf"])
    debt = float(fields["DEBT"]) if "DEBT" in fields else float(defaults["debtOverall"]["chf"])
    rate, term = defaults["loan"]["annualRate"], defaults["loan"]["termMonths"]
    loan = ({"principalChf": debt, "annualRate": rate, "monthlyPaymentChf": round(annuity_payment(debt, rate, term), 2)}
            if debt > 0 else None)
    g = defaults["goals"][fields["GOAL_TYPE"]]
    lo, hi = defaults["limits"]["goalTargetChf"]
    target = min(max(round_to(g["incomeRatio"] * income, 100), lo), hi)
    goal = {"name": fields["GOAL_TYPE"], "targetChf": float(target), "targetDate": g["targetDate"], "priority": g["priority"]}
    s = new_person(profile_id=profile_id, display_name="", age=age, city=city, employment=employment, household=household,
                   education=education, cash=cash, income=income, rent=rent, facts_catalog=facts, goals=[goal],
                   investments=investments, loan=loan)
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
    cushion = next(q for q in interview["questions"] if q["id"] == "mincash")["input"]["typical"]
    prefs = {"objective": "BALANCED", "minCashChf": float(cushion),
             "penaltyChfPerMonthBelowMin": objective_penalties()["BALANCED"]}
    return {"meta": {"generator": "tools/interview.py --build",
                     "note": "Alexia, the challenge's demo profile (20, student in Lausanne, CHF 8,000): her opening statement in the "
                             "dataset (statement.dataset_opening), with BALANCED priorities and a one-month cushion."},
            "statement": s, "preferences": prefs}


# ------------------------------------------------------------------------------------------------ check
UNITS = {"YEARS", "CHF", "CHF_MONTH"}           # InputUnit in Kotlin


def check(interview: dict, defaults: dict, facts: dict) -> list[str]:
    errors: list[str] = []
    profile_fields = kotlin_enum("ProfileField")
    allowed = {"EMPLOYMENT": kotlin_enum("Employment"), "HOUSEHOLD": kotlin_enum("Household"),
               "OBJECTIVE": kotlin_enum("Objective"), "EDUCATION": list(facts["facts"]["EDUCATION"]),
               "CITY": [c for c in facts["facts"]["CITY"] if c != "ABROAD"], "GOAL_TYPE": list(defaults["goals"])}
    setters: dict[str, set] = defaultdict(set)
    seen_q, seen_keys = set(), set()
    for qn in interview["questions"]:
        if qn["id"] in seen_q:
            errors.append(f"duplicate question id {qn['id']}")
        seen_q.add(qn["id"])
        if qn["textKey"] in seen_keys:
            errors.append(f"duplicate key {qn['textKey']}")
        seen_keys.add(qn["textKey"])
        if not qn["text"].get("EN") or not qn["text"].get("ES"):
            errors.append(f"{qn['textKey']}: missing EN or ES text")
        if len({o["id"] for o in qn["options"]}) != len(qn["options"]):
            errors.append(f"{qn['id']}: duplicate option ids")
        spec = qn.get("input")
        if spec:
            field = spec["field"]
            if field not in profile_fields:
                errors.append(f"{qn['id']}: {field} is not a ProfileField in Enums.kt")
            setters[field].add(qn["id"])
            if spec["unit"] not in UNITS:
                errors.append(f"{qn['id']}: unknown unit {spec['unit']}")
            if not spec["min"] <= spec["typical"] <= spec["max"] or spec["step"] <= 0:
                errors.append(f"{qn['id']}: needs min <= typical <= max and step > 0: {spec}")
            if field in OPTIONAL_FIELDS and not any(not o["sets"] for o in qn["options"]):
                errors.append(f"{qn['id']}: offer an \"I don't know\" option (empty sets) for {field}")
        elif len(qn["options"]) < 2:
            errors.append(f"{qn['id']}: a choice needs at least two options")
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
    for field in REQUIRED_FIELDS + OPTIONAL_FIELDS:
        if field not in setters:
            errors.append(f"no question fills {field}")
    unused = [f for f in profile_fields if f not in setters]
    if unused:
        errors.append(f"ProfileField values no question fills: {unused}")
    # every answer (every option, and the bounds and typical value of every number) must build a valid person
    def base_answer(q):
        return q["input"]["typical"] if q.get("input") else q["options"][0]["id"]
    base = {q["id"]: base_answer(q) for q in interview["questions"]}
    for qn in interview["questions"]:
        tries = [o["id"] for o in qn["options"]] + ([qn["input"]["min"], qn["input"]["max"]] if qn.get("input") else [])
        for value in tries:
            answers = [(q, value if q == qn["id"] else base[q]) for q in base]
            try:
                person_from_fields(fields_from_answers(interview, answers), defaults, facts)
            except Exception as e:                      # noqa: BLE001
                errors.append(f"{qn['id']}={value}: cannot build the person: {e}")
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
            answers = [(q["id"], rng.choice(q["options"])["id"] if q["options"] and (not q.get("input") or rng.random() < 0.2)
                        else round(rng.uniform(q["input"]["min"], q["input"]["max"]) / q["input"]["step"]) * q["input"]["step"])
                       for q in interview["questions"]]
            s, prefs = person_from_fields(fields_from_answers(interview, answers), defaults, facts, f"USER-{i + 1}")
            p = s["person"]
            print(f"\n#{i + 1} answers: {' '.join(f'{q}={o}' for q, o in answers)}")
            print(f"   {p['age']} y, {p['employment']}, {p['household']}, facts {p['facts']}")
            debt = s["loan"]["principalChf"] if s["loan"] else 0
            print(f"   income {s['monthlyIncomeChf']:,.0f}  rent {s['monthlyRentChf']:,.0f}  cash {s['cashChf']:,.0f}  "
                  f"investments {s['investmentsChf']:,.0f}  debt {debt:,.0f}  goal {s['goals'][0]}")
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
