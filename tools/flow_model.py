"""Learn the monthly cash-flow model from the transaction ledger (Python standard library only).

The simulator advances a FinancialStatement one month at a time. This script learns WHAT flows a person has in a
month from WHO they are: for every ledger category it learns
    - in which calendar months it can happen        (e.g. travel only in February and August)
    - the probability that it happens that month    (e.g. a December bonus 56 % of the time, only if employed)
    - its size, relative to a basis                 (CHF, the person's income or the person's rent)
and which person features change the probability and the size. Both are multiplicative models:

    probability(x) = baseProbability * prod_k probabilityFactor_k[x_k]
    amount(x)      = baseAmount      * prod_k amountFactor_k[x_k]      * basis(x)

Features are chosen by forward selection with a partial F-test at profile level (one row per profile, so the 24
months of a person do not count as 24 independent observations). A feature is kept only if it is significant AND
changes the value by at least MIN_EFFECT. Unknown levels (a canton not in the data, a person abroad) use factor 1.

Outputs (in --out-dir, default tools/out):
  flow-model.json       the model: flows, factors, residual deciles, feature selection report, city -> canton
  flow-backtest.json    80/20 out-of-sample test: predict 24 months of unseen profiles from their opening state

    python tools/flow_model.py [--data-dir DIR] [--out-dir DIR] [--seed N] [--test-frac F]
"""
import argparse
import csv
import io
import json
import math
import random
import statistics as st
import zipfile
from collections import defaultdict
from pathlib import Path

from common import deciles, default_data_dir, load_dataset
from statement import dataset_opening

TRANSACTIONS_ZIP = "synthetic-transactions.zip"
TRANSACTIONS_CSV = "synthetic-transactions.csv"

# Dataset category -> how the simulator uses it. Basis: what the learned amount multiplies.
FLOW_SPECS = {
    "salary_or_support":  {"key": "salary",       "direction": "IN",  "basis": "INCOME", "category": None},
    "annual_bonus":       {"key": "bonus",        "direction": "IN",  "basis": "INCOME", "category": None},
    "housing_rent":       {"key": "rent",         "direction": "OUT", "basis": "RENT",   "category": "RENT"},
    "health_insurance":   {"key": "health_insurance", "direction": "OUT", "basis": "CHF", "category": "INSURANCE"},
    "groceries":          {"key": "groceries",    "direction": "OUT", "basis": "CHF",    "category": "LIVING"},
    "restaurants":        {"key": "restaurants",  "direction": "OUT", "basis": "CHF",    "category": "LIVING"},
    "retail":             {"key": "retail",       "direction": "OUT", "basis": "CHF",    "category": "LIVING"},
    "leisure":            {"key": "leisure",      "direction": "OUT", "basis": "CHF",    "category": "LIVING"},
    "pharmacy":           {"key": "pharmacy",     "direction": "OUT", "basis": "CHF",    "category": "LIVING"},
    "utilities":          {"key": "utilities",    "direction": "OUT", "basis": "CHF",    "category": "LIVING"},
    "transport":          {"key": "transport",    "direction": "OUT", "basis": "CHF",    "category": "LIVING"},
    "telecom":            {"key": "telecom",      "direction": "OUT", "basis": "CHF",    "category": "LIVING"},
    "education":          {"key": "tuition",      "direction": "OUT", "basis": "CHF",    "category": "TUITION"},
    "travel":             {"key": "travel",       "direction": "OUT", "basis": "CHF",    "category": "TRAVEL"},
    "unexpected_expense": {"key": "unexpected",   "direction": "OUT", "basis": "CHF",    "category": "OTHER"},
}

# Candidate features: EVERY field of the FinancialStatement that describes the person, categorical and numeric,
# in tie-break order. Numeric fields enter as bands (edges in flow-model.json meta.bandEdges). The selection keeps
# only the ones the data support; the report in flow-model.json says why each one is in or out.
FEATURES = ("studying", "employment", "canton", "household", "ageBand", "incomeBand", "rentBand", "cashBand",
            "debtBand", "investmentsBand", "city", "education", "occupation", "housing", "family")
NUMERIC_FEATURES = {"ageBand": "age", "incomeBand": "income", "rentBand": "rent", "cashBand": "cash",
                    "debtBand": "debt", "investmentsBand": "investments"}
# Readable fixed edges for age and income; the others are quintiles of the dataset (band_edges).
FIXED_EDGES = {"age": [18, 25, 35, 45, 55, 65], "income": [2500, 5000, 7500, 10000]}
DEFAULT_FACTS = Path(__file__).resolve().parents[1] / "content" / "facts.json"

MIN_MONTH_SHARE = 0.01     # a calendar month is "eligible" if it holds at least 1 % of the flow's occurrences
MAX_P_VALUE = 1e-6         # significance needed to keep a feature
MIN_EFFECT = 0.03          # ... and it must move the value by at least 3 % for some level with >= 2 % of the weight
MIN_LEVEL_SHARE = 0.02
BACKFIT_ITERATIONS = 30
Z90 = 1.2816


# ---------------------------------------------------------------------------------------------- features

def band_label(value: float, edges: list[float] | None) -> str:
    """'<e1', 'e1-e2', ..., 'ek+' (edges sorted). No edges -> 'UNKNOWN'."""
    if not edges:
        return "UNKNOWN"
    fmt = lambda x: f"{x:g}"
    if value < edges[0]:
        return f"<{fmt(edges[0])}"
    for lo, hi in zip(edges, edges[1:]):
        if value < hi:
            return f"{fmt(lo)}-{fmt(hi)}"
    return f"{fmt(edges[-1])}+"


def statement_numbers(s: dict) -> dict:
    """Numeric fields of a statement that describe the person's situation."""
    return {"income": s["monthlyIncomeChf"], "rent": s["monthlyRentChf"], "cash": s["cashChf"],
            "debt": s["loan"]["principalChf"] if s["loan"] else 0.0, "investments": s["investmentsChf"]}


def person_features(person: dict, numbers: dict, city_canton: dict, edges: dict) -> dict:
    """Every field that describes the person, as the model reads it (same function for training and for the
    simulator): the person's categorical fields and facts, plus numeric fields as bands. Missing -> "UNKNOWN"."""
    facts = person.get("facts") or {}
    education = facts.get("EDUCATION", "UNKNOWN")
    city = facts.get("CITY")
    values = {**numbers, "age": person["age"]}
    return {"studying": "YES" if education.endswith("_IN_PROGRESS") else "NO",
            "employment": person["employment"], "canton": city_canton.get(city, "UNKNOWN"),
            "household": person["household"],
            **{f: band_label(values[n], edges.get(n)) for f, n in NUMERIC_FEATURES.items()},
            "city": city or "UNKNOWN", "education": education,
            "occupation": facts.get("OCCUPATION", "UNKNOWN"), "housing": facts.get("HOUSING", "UNKNOWN"),
            "family": facts.get("FAMILY", "UNKNOWN")}


def dataset_people(ds: dict, facts_catalog: dict) -> dict:
    """pid -> (opening person, opening numbers): the same fields the simulator reads from a statement."""
    out = {}
    for pid in ds["profiles"]:
        s0 = dataset_opening(ds, pid, facts_catalog)
        out[pid] = (s0["person"], statement_numbers(s0))
    return out


def band_edges(people: dict) -> dict:
    """Fixed edges for age and income; quintiles of the dataset for rent, cash and investments; debt: none vs the
    quartiles of the people who have debt (most have none)."""
    def quintiles(values, step):
        xs = sorted(values)
        return sorted({round(xs[int(len(xs) * q)] / step) * step for q in (0.2, 0.4, 0.6, 0.8)})
    nums = [n for _, n in people.values()]
    debts = [n["debt"] for n in nums if n["debt"] > 0]
    return {**FIXED_EDGES,
            "rent": quintiles([n["rent"] for n in nums], 50),
            "cash": quintiles([n["cash"] for n in nums], 1000),
            "investments": quintiles([n["investments"] for n in nums], 1000),
            "debt": [1] + quintiles(debts, 1000)[1::2]}


# ---------------------------------------------------------------------------------------------- data

def load_ledger(data_dir: Path) -> dict:
    """(profile_id, 'YYYY-MM') -> {dataset category: signed CHF} from the zipped transaction ledger."""
    out = defaultdict(lambda: defaultdict(float))
    with zipfile.ZipFile(data_dir / TRANSACTIONS_ZIP) as z, z.open(TRANSACTIONS_CSV) as f:
        for r in csv.DictReader(io.TextIOWrapper(f, encoding="utf-8")):
            out[(r["profile_id"], r["date"][:7])][r["category"]] += float(r["amount_chf"])
    return out


def basis_value(basis: str, profile: dict) -> float:
    return {"CHF": 1.0, "INCOME": profile["income"], "RENT": profile["rent"]}[basis]


def profile_rows(cat: str, spec: dict, months: set[int], pids: list[str], ds: dict, ledger: dict,
                 feats: dict) -> tuple[list, list, list]:
    """Profile-level rows for the probability and the amount models, plus every occurrence's (pid, multiplier)."""
    prob_rows, amount_rows, occurrences = [], [], []
    for pid in pids:
        pr = ds["profiles"][pid]
        basis = basis_value(spec["basis"], pr)
        eligible = [r["month"] for r in ds["series"][pid] if int(r["month"][5:7]) in months]
        if not eligible:
            continue
        values = [abs(ledger[(pid, m)].get(cat, 0.0)) for m in eligible]
        hits = [v for v in values if v > 0]
        prob_rows.append((feats[pid], len(hits) / len(eligible), len(eligible)))
        if hits and basis > 0:
            ys = [v / basis for v in hits]
            amount_rows.append((feats[pid], st.mean(ys), len(ys)))
            occurrences.extend((pid, y) for y in ys)
    return prob_rows, amount_rows, occurrences


# ---------------------------------------------------------------------------------------------- fitting

def fit_multiplicative(rows: list, features: list[str]) -> dict:
    """Weighted backfitting of y ~ base * prod_k f_k[x_k] with ratio updates (exact zeros allowed).
    rows: (features dict, y, weight). Factors are normalised to a weighted mean of 1."""
    factors = {k: defaultdict(lambda: 1.0) for k in features}
    sw = sum(w for _, _, w in rows)
    base = sum(w * y for _, y, w in rows) / sw
    for _ in range(BACKFIT_ITERATIONS):
        for k in features:
            num, den = defaultdict(float), defaultdict(float)
            for x, y, w in rows:
                others = base * math.prod(factors[j][x[j]] for j in features if j != k)
                num[x[k]] += w * y
                den[x[k]] += w * others
            for lvl in num:
                factors[k][lvl] = num[lvl] / den[lvl] if den[lvl] > 0 else 1.0
            # normalise: weighted mean factor = 1, absorb the scale into base
            mean_f = sum(w * factors[k][x[k]] for x, _, w in rows) / sw
            if mean_f > 0:
                for lvl in factors[k]:
                    factors[k][lvl] /= mean_f
                base *= mean_f
    sse = sum(w * (y - predict_raw(base, factors, x)) ** 2 for x, y, w in rows)
    return {"base": base, "factors": {k: dict(v) for k, v in factors.items()}, "sse": sse}


def predict_raw(base: float, factors: dict, x: dict, unknown: dict | None = None) -> float:
    """base x product of the factors of x's levels; a level not in a factor map uses unknown[feature] (or 1)."""
    unknown = unknown or {}
    return base * math.prod(f.get(x[k], unknown.get(k, 1.0)) for k, f in factors.items())


def chi2_tail(x: float, k: int) -> float:
    """P(chi2_k > x), Wilson-Hilferty normal approximation (good enough for a selection threshold)."""
    if x <= 0:
        return 1.0
    z = ((x / k) ** (1 / 3) - (1 - 2 / (9 * k))) / math.sqrt(2 / (9 * k))
    return 1 - st.NormalDist().cdf(z)


def bic(sse: float, n: int, k: int) -> float:
    """Bayesian information criterion of a least-squares fit with k parameters (lower is better)."""
    return n * math.log(max(sse, 1e-300) / n) + k * math.log(n)


def effect_size(rows: list, factor: dict, feature: str) -> float:
    """Largest |factor - 1| among levels holding at least MIN_LEVEL_SHARE of the weight."""
    sw = sum(w for _, _, w in rows)
    share = defaultdict(float)
    for x, _, w in rows:
        share[x[feature]] += w / sw
    return max((abs(f - 1) for lvl, f in factor.items() if share[lvl] >= MIN_LEVEL_SHARE), default=0.0)


def forward_select(rows: list, features: tuple = FEATURES) -> tuple[dict, list]:
    """Add features one by one while they are significant and relevant. Returns the fit and a selection report."""
    selected, report = [], []
    current = fit_multiplicative(rows, [])
    n = len(rows)
    levels = {k: len({x[k] for x, _, _ in rows}) for k in features}
    for k in features:                      # every field is reported, even when the data cannot test it
        if levels[k] < 2:
            report.append({"feature": k, "kept": False, "pValue": 1.0, "maxEffect": 0.0, "fStat": None,
                           "note": "a single value among the people with this flow: cannot be tested"})
    while True:
        p_used = 1 + sum(levels[k] - 1 for k in selected)
        candidates = []
        for k in features:
            if k in selected or levels[k] < 2:
                continue
            fit = fit_multiplicative(rows, selected + [k])
            df1, df2 = levels[k] - 1, max(n - p_used - (levels[k] - 1), 1)
            if fit["sse"] <= 0:
                f_stat = math.inf
            else:
                f_stat = max((current["sse"] - fit["sse"]) / df1, 0.0) / (fit["sse"] / df2)
            p = 0.0 if math.isinf(f_stat) else chi2_tail(f_stat * df1, df1)
            eff = effect_size(rows, fit["factors"][k], k)
            candidates.append({"feature": k, "fStat": f_stat, "pValue": p, "effect": eff, "fit": fit})
        passing = [c for c in candidates if c["pValue"] < MAX_P_VALUE and c["effect"] >= MIN_EFFECT]
        if not passing:
            for c in candidates:
                report.append(_report_entry(c, kept=False))
            break
        # Among the significant candidates, the lowest BIC wins: fit minus a penalty per extra level. In the dataset
        # some fields carry the same information (students are exactly the NOT_WORKING; occupation is a function of
        # employment), but the game can change them separately. BIC then picks the simplest explanation (studying,
        # 2 levels, rather than employment, 6) unless the richer one really fits better (the bonus needs employment:
        # part-time workers get none). Exact ties go to the earlier field in FEATURES.
        best = min(passing, key=lambda c: (bic(c["fit"]["sse"], n, p_used + levels[c["feature"]] - 1),
                                           features.index(c["feature"])))
        report.append(_report_entry(best, kept=True))
        selected.append(best["feature"])
        current = best["fit"]
    return current, report


def _report_entry(c: dict, kept: bool) -> dict:
    return {"feature": c["feature"], "kept": kept, "pValue": float(f"{c['pValue']:.3g}"),
            "maxEffect": round(c["effect"], 4),
            "fStat": None if math.isinf(c["fStat"]) else round(c["fStat"], 2)}


def rereference(fit: dict, rows: list) -> dict:
    """Express each feature relative to its most common level with a non-zero factor (factor 1), so that `base`
    is the value of a reference person and every factor reads "x % more or less than the reference"."""
    base, factors, reference, unknown = fit["base"], {}, {}, {}
    for k, levels in fit["factors"].items():
        weight = defaultdict(float)
        for x, _, w in rows:
            weight[x[k]] += w
        ref = max((lvl for lvl, f in levels.items() if f > 0), key=lambda lvl: weight[lvl])
        base *= levels[ref]
        factors[k] = {lvl: f / levels[ref] for lvl, f in levels.items()}
        reference[k] = ref
        # a level never seen (a city abroad) gets the weighted average of the known levels: the national average
        unknown[k] = sum(weight[lvl] * f for lvl, f in factors[k].items()) / sum(weight.values())
    return {**fit, "base": base, "factors": factors, "reference": reference, "unknown": unknown}


def rounded_factors(factors: dict) -> dict:
    return {k: {lvl: round(f, 4) for lvl, f in sorted(v.items())} for k, v in factors.items()}


# ---------------------------------------------------------------------------------------------- model

def eligible_months(cat: str, pids: list[str], ledger: dict) -> list[int]:
    counts = defaultdict(int)
    pidset = set(pids)
    for (pid, m), cats in ledger.items():
        if pid in pidset and cats.get(cat, 0.0) != 0.0:
            counts[int(m[5:7])] += 1
    total = sum(counts.values())
    return sorted(m for m, c in counts.items() if total and c / total >= MIN_MONTH_SHARE)


def person_level_spread(occurrences: list, a_fit: dict, feats: dict) -> float:
    """Sd of a person's own level around the prediction (unknown for a new user), as a fraction of the amount.
    The spread of per-person averages also contains month-to-month noise averaged over few occurrences
    (travel: ~2 per person), so that part is removed: var_between = var(means) - mean(pooled_within / n_i)."""
    by_pid = defaultdict(list)
    for pid, y in occurrences:
        by_pid[pid].append(y / predict_raw(a_fit["base"], a_fit["factors"], feats[pid]))
    means = [st.mean(v) for v in by_pid.values()]
    multi = [v for v in by_pid.values() if len(v) > 1]
    if len(means) < 2 or not multi:
        return 0.0
    pooled_within = sum(st.pvariance(v) * len(v) for v in multi) / sum(len(v) - 1 for v in multi)
    noise = st.mean(pooled_within / len(v) for v in by_pid.values())
    return math.sqrt(max(st.pvariance(means) - noise, 0.0))


def train(ds: dict, ledger: dict, pids: list[str], people: dict, city_canton: dict, edges: dict) -> dict:
    feats = {pid: person_features(*people[pid], city_canton, edges) for pid in pids}
    flows = []
    for cat, spec in FLOW_SPECS.items():
        months = eligible_months(cat, pids, ledger)
        if not months:
            continue
        prob_rows, amount_rows, occurrences = profile_rows(cat, spec, set(months), pids, ds, ledger, feats)
        p_fit, p_report = forward_select(prob_rows)
        a_fit, a_report = forward_select(amount_rows)
        p_fit, a_fit = rereference(p_fit, prob_rows), rereference(a_fit, amount_rows)
        # residual multiplier of each occurrence around the person's predicted amount (for sampling later)
        resid = [y / predict_raw(a_fit["base"], a_fit["factors"], feats[pid]) for pid, y in occurrences]
        person_level_sd = person_level_spread(occurrences, a_fit, feats)
        flows.append({
            "key": spec["key"], "datasetCategory": cat, "direction": spec["direction"],
            "expenseCategory": spec["category"], "basis": spec["basis"],
            "months": months,
            "probability": {"base": round(p_fit["base"], 4), "reference": p_fit["reference"],
                            "factors": rounded_factors(p_fit["factors"]),
                            "unknown": {k: round(v, 4) for k, v in p_fit["unknown"].items()},
                            "selection": p_report},
            "amount": {"base": round(a_fit["base"], 4), "reference": a_fit["reference"],
                       "factors": rounded_factors(a_fit["factors"]),
                       "unknown": {k: round(v, 4) for k, v in a_fit["unknown"].items()},
                       "selection": a_report,
                       "residualDeciles": [round(v, 4) for v in deciles(resid)],
                       "residualMeanSquare": round(st.mean(r * r for r in resid), 4),
                       "personLevelSd": round(person_level_sd, 4)},
            "nProfiles": len(prob_rows), "nOccurrences": len(occurrences),
        })
    return {"flows": flows, "rentModel": train_rent(pids, feats, people)}


def train_rent(pids: list[str], feats: dict, people: dict) -> dict:
    """Market rent of a home for the person's situation, learned from the reference rents of the dataset (one row per
    profile). Used when an event moves the person (new city or household): the rent becomes
    base x factors x residual quantile (the quantile sets how lucky the person is with the new flat).
    The rent band itself is not a candidate (it would explain the rent by the rent)."""
    rows = [(feats[pid], people[pid][1]["rent"], 1) for pid in pids]
    fit, report = forward_select(rows, tuple(f for f in FEATURES if f != "rentBand"))
    fit = rereference(fit, rows)
    resid = [y / predict_raw(fit["base"], fit["factors"], x) for x, y, _ in rows]
    return {"base": round(fit["base"], 2), "reference": fit["reference"], "factors": rounded_factors(fit["factors"]),
            "unknown": {k: round(v, 4) for k, v in fit["unknown"].items()}, "selection": report,
            "residualDeciles": [round(v, 4) for v in deciles(resid)], "nProfiles": len(rows),
            "source": "synthetic-profiles.csv reference_monthly_rent_chf (CHF per month, 2025)"}


def expected_flows(model: dict, feats: dict, calendar_month: int, income: float, rent: float) -> list[dict]:
    """Expected value and monthly sd of every flow for one person in one calendar month (reference for the engine).
    Signed: IN positive, OUT negative."""
    basis = {"CHF": 1.0, "INCOME": income, "RENT": rent}
    out = []
    for f in model["flows"]:
        if calendar_month not in f["months"]:
            continue
        p = min(predict_raw(f["probability"]["base"], f["probability"]["factors"], feats,
                            f["probability"].get("unknown")), 1.0)
        a = predict_raw(f["amount"]["base"], f["amount"]["factors"], feats, f["amount"].get("unknown")) * basis[f["basis"]]
        sign = 1 if f["direction"] == "IN" else -1
        mean = p * a
        var = max(p * a * a * f["amount"]["residualMeanSquare"] - mean * mean, 0.0)
        if mean == 0:
            continue
        out.append({"key": f["key"], "expenseCategory": f["expenseCategory"], "expectedChf": round(sign * mean, 2),
                    "sdChf": round(math.sqrt(var), 2), "personLevelSdChf": round(mean * f["amount"]["personLevelSd"], 2)})
    return out


# ---------------------------------------------------------------------------------------------- backtest

def backtest(ds: dict, model: dict, test: list[str], people: dict) -> dict:
    """Predict the 24 months of each test profile from its opening state only (no ledger)."""
    inside = total = 0
    abs_err_close, abs_err_net = [], []
    for pid in test:
        pr = ds["profiles"][pid]
        feats = person_features(*people[pid], model["cityCanton"], model["meta"]["bandEdges"])
        cash, var_noise, level_sd = pr["openingCash"], 0.0, 0.0
        for k, row in enumerate(ds["series"][pid], start=1):
            flows = expected_flows(model, feats, int(row["month"][5:7]), pr["income"], pr["rent"])
            net = sum(f["expectedChf"] for f in flows)
            cash += net
            var_noise += sum(f["sdChf"] ** 2 for f in flows)
            # person-level error: the same sign every month, so it grows linearly with k
            level_sd += math.sqrt(sum(f["personLevelSdChf"] ** 2 for f in flows))
            sd = math.sqrt(var_noise + level_sd ** 2)
            inside += (cash - Z90 * sd) <= row["closing"] <= (cash + Z90 * sd)
            total += 1
            abs_err_close.append(abs(cash - row["closing"]))
            abs_err_net.append(abs(net - row["net"]))
    return {"coverageP10P90": round(inside / total, 4), "medianAbsErrorClosingChf": round(st.median(abs_err_close), 2),
            "meanAbsErrorClosingChf": round(st.mean(abs_err_close), 2),
            "medianAbsErrorMonthlyNetChf": round(st.median(abs_err_net), 2)}


# ---------------------------------------------------------------------------------------------- main

def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data-dir", type=Path, default=default_data_dir())
    ap.add_argument("--out-dir", type=Path, default=Path(__file__).resolve().parent / "out")
    ap.add_argument("--seed", type=int, default=2026)
    ap.add_argument("--test-frac", type=float, default=0.2)
    ap.add_argument("--facts", type=Path, default=DEFAULT_FACTS)
    args = ap.parse_args()

    ds = load_dataset(args.data_dir)
    ledger = load_ledger(args.data_dir)
    people = dataset_people(ds, json.loads(args.facts.read_text(encoding="utf-8")))
    city_canton = {r["city"]: r["canton"] for r in ds["raw_profiles"].values()}
    edges = band_edges(people)

    pids = sorted(ds["profiles"])
    shuffled = pids[:]
    random.Random(args.seed).shuffle(shuffled)
    n_test = int(len(shuffled) * args.test_frac)
    test, train_pids = sorted(shuffled[:n_test]), sorted(shuffled[n_test:])

    # 1) out-of-sample check, 2) final model trained on every profile
    report = {
        "seed": args.seed, "trainProfiles": len(train_pids), "testProfiles": len(test),
        "flowModel": backtest(ds, {**train(ds, ledger, train_pids, people, city_canton, edges),
                                   "cityCanton": city_canton, "meta": {"bandEdges": edges}}, test, people),
        "targetCoverage": 0.8,
        "note": "Predicted from the opening state only. Synthetic data: a good score means we recovered the "
                "generator, not real-world accuracy.",
    }
    model = train(ds, ledger, pids, people, city_canton, edges)
    model = {
        "meta": {
            "generator": "tools/flow_model.py", "version": 1,
            "source": "LauzHack 2026 synthetic dataset: synthetic-transactions.zip, synthetic-profiles.csv, "
                      "synthetic-cash-balances.csv",
            "nProfiles": len(pids), "calibratedRange": "2025-01..2026-12",
            "model": "flow = Bernoulli(probability(x)) * amount(x) * basis; probability and amount are "
                     "multiplicative in the selected features; unknown levels use factor 1",
            "features": list(FEATURES), "numericFeatures": NUMERIC_FEATURES, "bandEdges": edges,
            "selectionRule": {"maxPValue": MAX_P_VALUE, "minEffect": MIN_EFFECT, "minLevelShare": MIN_LEVEL_SHARE},
            "caveats": [
                "Synthetic data: the model recovers the generator, not real behaviour.",
                "The ledger has no trend (2026 = 2025): growth of wages, rents and prices comes from macro.json.",
                "Loan payments and asset returns are not in the ledger: they are engine rules and assumptions.",
                "The 'tuition' flow is the observed cost of the dataset students; events must not add it twice.",
            ],
        },
        "cityCanton": dict(sorted(city_canton.items())),
        **model,
    }
    args.out_dir.mkdir(parents=True, exist_ok=True)
    (args.out_dir / "flow-model.json").write_text(json.dumps(model, indent=2), encoding="utf-8")
    (args.out_dir / "flow-backtest.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    for f in model["flows"]:
        kept_p = [s["feature"] for s in f["probability"]["selection"] if s["kept"]]
        kept_a = [s["feature"] for s in f["amount"]["selection"] if s["kept"]]
        print(f"{f['key']:17s} months={f['months']} p={f['probability']['base']:.3f} {kept_p} "
              f"amount={f['amount']['base']:.3f}x{f['basis']} {kept_a}")


if __name__ == "__main__":
    main()
