"""Swiss macro inputs for the simulator, from the public datasets downloaded by data/download.sh.

The transaction ledger has no trend (2026 = 2025), so everything that grows over the 10-year game comes from here:
prices, wages, rents. Every number carries its provider, dataset, URL and retrieval date (data/MANIFEST.tsv).

Output (in --out-dir, default tools/out):
  macro.json   {"meta", "series": {...}, "trends": {...}}
     series  what the public data say (history and summary statistics)
     trends  the yearly rates the engine applies, each with origin OBSERVED (straight from a series) or
             ASSUMED (our choice, e.g. "wages follow prices" until the BFS wage index is available)

    python tools/macro.py [--raw-dir DIR] [--out-dir DIR]
"""
import argparse
import csv
import gzip
import io
import json
import re
import statistics as st
import xml.etree.ElementTree as ET
import zipfile
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
DEFAULT_RAW = REPO / "data" / "raw"
MANIFEST = REPO / "data" / "MANIFEST.tsv"

LONG_RUN_FROM = 2000      # window for long-run averages (the euro era; includes the 2008 and 2022 shocks)
RECENT_YEARS = 10

# BFS wage structure survey (LSE) age groups -> representative age (middle of the group) for the career curve
LSE_AGE_GROUPS = {"Under 20 years": 18, "20 - 29 years": 25, "30 - 39 years": 35, "40 - 49 years": 45,
                  "50 - 64/65 years": 57, "Over 64/65 years": 67}
XLSX_NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
           "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships"}


# ---------------------------------------------------------------------------------------------- sources

def manifest() -> dict:
    """file -> {provider, dataset, retrieved, url}"""
    if not MANIFEST.exists():
        return {}
    with open(MANIFEST, newline="", encoding="utf-8") as f:
        return {r["file"]: {"provider": r["provider"], "dataset": r["dataset"], "retrieved": r["retrieved"],
                            "url": r["url"]} for r in csv.DictReader(f, delimiter="\t")}


def source(sources: dict, file: str) -> dict:
    return {"file": f"data/raw/{file}", **sources.get(file, {})}


# ---------------------------------------------------------------------------------------------- readers

def world_bank(path: Path) -> dict[int, float]:
    """World Bank API zip (one country) -> {year: value}."""
    with zipfile.ZipFile(path) as z:
        name = next(n for n in z.namelist() if n.startswith("API_"))
        rows = list(csv.reader(io.TextIOWrapper(z.open(name), encoding="utf-8-sig")))
    header = next(r for r in rows if r and r[0] == "Country Name")
    values = rows[rows.index(header) + 1]
    return {int(h): float(v) for h, v in zip(header, values) if h.isdigit() and v}


def eurostat_rows(path: Path, prefix: str) -> dict[str, float]:
    """Eurostat TSV (gz): the row whose dimension key equals `prefix` -> {period: value}. Flags ('b', 'p') dropped."""
    with gzip.open(path, "rt", encoding="utf-8") as f:
        periods = [p.strip() for p in f.readline().rstrip("\n").split("\t")[1:]]
        for line in f:
            key, *vals = line.rstrip("\n").split("\t")
            if key == prefix:
                out = {}
                for p, v in zip(periods, vals):
                    m = re.match(r"\s*(-?\d+(?:\.\d+)?)", v)
                    if m:
                        out[p] = float(m.group(1))
                return out
    return {}


def snb_cube(path: Path, keys: tuple[str, ...], d0: str | None = None) -> dict[str, dict[str, float]]:
    """SNB cube CSV ("Date";"D0"[;"D1"];"Value") -> {last dimension value: {date: value}} for the wanted keys.
    d0 filters the first dimension when there are two (exchange rates: M0 = monthly average, M1 = end of month)."""
    out = defaultdict(dict)
    with open(path, encoding="utf-8-sig") as f:
        for line in f:
            parts = [p.strip('"') for p in line.strip().split(";")]
            if len(parts) < 3 or not parts[-1] or parts[0] == "Date":
                continue
            if d0 is not None and parts[1] != d0:
                continue
            dim = parts[-2]
            if dim in keys:
                try:
                    out[dim][parts[0]] = float(parts[-1])
                except ValueError:
                    pass
    return out


def bis_series(path: Path, area: str, match: dict[str, str]) -> dict[str, float]:
    """BIS flat CSV (zip) -> {period: value} for one reference area and dimension codes (prefix match on 'CODE: label')."""
    out = {}
    with zipfile.ZipFile(path) as z, z.open(z.namelist()[0]) as f:
        r = csv.reader(io.TextIOWrapper(f, encoding="utf-8-sig"))
        header = [h.split(":")[0].strip().upper() for h in next(r)]
        idx = {h: i for i, h in enumerate(header)}
        i_area, i_time, i_val = idx["REF_AREA"], idx["TIME_PERIOD"], idx["OBS_VALUE"]
        cols = {idx[k.upper()]: v for k, v in match.items()}
        for row in r:
            if row[i_area].split(":")[0] != area or not row[i_val]:
                continue
            if all(row[i].split(":")[0] == v for i, v in cols.items()):
                out[row[i_time]] = float(row[i_val])
    return out


def xlsx_sheets(path: Path) -> dict[str, list[list[str]]]:
    """Minimal .xlsx reader (standard library): {sheet name: rows of cell texts}. Values only, no formatting."""
    def col(ref: str) -> int:
        n = 0
        for ch in re.match(r"[A-Z]+", ref).group(0):
            n = n * 26 + ord(ch) - 64
        return n - 1

    m = "{%s}" % XLSX_NS["m"]
    with zipfile.ZipFile(path) as z:
        shared = []
        if "xl/sharedStrings.xml" in z.namelist():
            shared = ["".join(t.text or "" for t in si.iter(m + "t"))
                      for si in ET.fromstring(z.read("xl/sharedStrings.xml")).findall("m:si", XLSX_NS)]
        rels = {r.get("Id"): r.get("Target") for r in ET.fromstring(z.read("xl/_rels/workbook.xml.rels"))}
        sheets = {}
        for s in ET.fromstring(z.read("xl/workbook.xml")).find("m:sheets", XLSX_NS):
            target = rels[s.get("{%s}id" % XLSX_NS["r"])].lstrip("/")
            target = target if target.startswith("xl/") else "xl/" + target
            rows = []
            for row in ET.fromstring(z.read(target)).iter(m + "row"):
                cells = {}
                for c in row.findall("m:c", XLSX_NS):
                    v = c.find("m:v", XLSX_NS)
                    if c.get("t") == "inlineStr":
                        cells[col(c.get("r"))] = "".join(x.text or "" for x in c.iter(m + "t"))
                    elif v is not None:
                        cells[col(c.get("r"))] = shared[int(v.text)] if c.get("t") == "s" else v.text
                if cells:
                    rows.append([cells.get(i, "") for i in range(max(cells) + 1)])
            sheets[s.get("name")] = rows
    return sheets


def bfs_rent_index(path: Path) -> dict[int, float]:
    """BFS rent price index (LIK Wohnungsmiete), yearly averages on the longest base (August 1939 = 100)."""
    out = {}
    for row in xlsx_sheets(path)["MPI_y"]:
        if len(row) > 1 and row[0].strip().isdigit() and row[1]:
            out[int(row[0])] = float(row[1])
    return out


def bfs_wage_index(path: Path) -> dict[int, float]:
    """BFS Swiss wage index: nominal year-on-year % change per quarter -> yearly mean over the available quarters."""
    acc = defaultdict(list)
    with open(path, encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f):
            if r["WAGE_TYPE"] == "N" and r["VALUE_P"]:
                acc[int(r["PERIOD"][:4])].append(float(r["VALUE_P"]))
    return {y: round(st.mean(v), 3) for y, v in sorted(acc.items())}


def _number(cell: str) -> float | None:
    """BFS cells: '7024', '[5000]' (few observations, kept), '*' or '-' (not published) -> None."""
    cell = cell.strip().strip("[]").strip()
    try:
        return float(cell)
    except ValueError:
        return None


def bfs_wage_by_age(path: Path) -> dict:
    """BFS earnings structure survey (LSE), table T9_b, latest survey year: median gross monthly wage by age group
    for each professional position (all genders, column 'Total / Median' of each position).
    The sheet has three blocks of age rows: [all positions | positions 1+2], [position 3 | position 4],
    [no management function]."""
    sheets = xlsx_sheets(path)
    year = max(sheets, key=lambda s: int(s) if s.isdigit() else 0)
    blocks = [("allPositions", 1, "management", 10), ("lowerManagement", 1, "lowestManagement", 10),
              ("noManagement", 1, None, None)]
    seen, out = defaultdict(int), defaultdict(dict)
    for row in sheets[year]:
        label = row[0].strip() if row else ""
        if label not in LSE_AGE_GROUPS:
            continue
        block = seen[label]
        seen[label] += 1
        if block >= len(blocks):
            continue
        for name, col in ((blocks[block][0], blocks[block][1]), (blocks[block][2], blocks[block][3])):
            if name and len(row) > col and _number(row[col]) is not None:
                out[name][label] = _number(row[col])
    return {"year": int(year), "mediansChf": dict(out)}


# ---------------------------------------------------------------------------------------------- helpers

def yearly_change(index: dict[int, float]) -> dict[int, float]:
    """% change of a yearly index."""
    return {y: round((index[y] / index[y - 1] - 1) * 100, 3) for y in sorted(index) if y - 1 in index}


def summary(by_year: dict[int, float], frm: int = LONG_RUN_FROM) -> dict:
    years = sorted(y for y in by_year if y >= frm)
    vals = [by_year[y] for y in years]
    recent = [by_year[y] for y in years[-RECENT_YEARS:]]
    ordered = sorted(vals)
    q = lambda p: ordered[min(int(p * (len(ordered) - 1) + 0.5), len(ordered) - 1)]
    return {"from": years[0], "to": years[-1], "mean": round(st.mean(vals), 3),
            f"mean{RECENT_YEARS}y": round(st.mean(recent), 3), "p10": round(q(0.1), 3), "p90": round(q(0.9), 3),
            "min": round(min(vals), 3), "max": round(max(vals), 3), "last": round(by_year[years[-1]], 3)}


def yearly_mean(daily: dict[str, float]) -> dict[int, float]:
    acc = defaultdict(list)
    for d, v in daily.items():
        acc[int(d[:4])].append(v)
    return {y: round(st.mean(v), 4) for y, v in sorted(acc.items())}


def cagr(index: dict[str, float], end: str, years: int) -> float:
    start = f"{int(end[:4]) - years}{end[4:]}"
    return round(((index[end] / index[start]) ** (1 / years) - 1) * 100, 3)


def last_items(d: dict, n: int) -> dict:
    return {k: d[k] for k in sorted(d)[-n:]}


# ---------------------------------------------------------------------------------------------- build

def build(raw: Path) -> dict:
    src = manifest()
    series, trends = {}, {}

    cpi = world_bank(raw / "swiss/wb_che_cpi_inflation.zip")
    series["cpiInflationPct"] = {"unit": "% per year", "geo": "CH", "source": source(src, "swiss/wb_che_cpi_inflation.zip"),
                                 "summary": summary(cpi), "history": {y: round(v, 3) for y, v in cpi.items() if y >= 1990}}
    gdp = world_bank(raw / "swiss/wb_che_gdp_growth.zip")
    series["realGdpGrowthPct"] = {"unit": "% per year", "geo": "CH", "source": source(src, "swiss/wb_che_gdp_growth.zip"),
                                  "summary": summary(gdp), "history": {y: round(v, 3) for y, v in gdp.items() if y >= 1990}}
    une = world_bank(raw / "swiss/wb_che_unemployment.zip")
    series["unemploymentRateAnnualPct"] = {"unit": "% of labour force (ILO)", "geo": "CH",
                                           "source": source(src, "swiss/wb_che_unemployment.zip"),
                                           "summary": summary(une), "history": {y: round(v, 3) for y, v in une.items()}}

    eu = raw / "macro/eurostat_une_rt_m.tsv.gz"
    by_age = {}
    for label, code in (("total", "TOTAL"), ("under25", "Y_LT25"), ("from25to74", "Y25-74")):
        s = eurostat_rows(eu, f"M,SA,{code},PC_ACT,T,CH")
        if s:
            recent = [s[p] for p in sorted(s)[-60:]]
            by_age[label] = {"last": s[max(s)], "lastPeriod": max(s), "mean5y": round(st.mean(recent), 2),
                             "min5y": min(recent), "max5y": max(recent)}
    series["unemploymentRateByAgePct"] = {"unit": "% of labour force, monthly, seasonally adjusted", "geo": "CH",
                                          "source": source(src, "macro/eurostat_une_rt_m.tsv.gz"), "byAge": by_age}

    fx = snb_cube(raw / "swiss/snb_exchange_rates_monthly.csv", ("EUR1", "USD1"), d0="M0")
    series["fxChfPerUnit"] = {
        "unit": "CHF per 1 unit of currency, monthly average", "source": source(src, "swiss/snb_exchange_rates_monthly.csv"),
        **{cur[:3]: {"last": fx[cur][max(fx[cur])], "lastPeriod": max(fx[cur]),
                     "min10y": min(v for d, v in fx[cur].items() if d >= f"{int(max(fx[cur])[:4]) - 10}"),
                     "max10y": max(v for d, v in fx[cur].items() if d >= f"{int(max(fx[cur])[:4]) - 10}")}
           for cur in ("EUR1", "USD1") if fx.get(cur)}}

    bonds = snb_cube(raw / "swiss/snb_confederation_bond_yields.csv", ("10J0",))["10J0"]
    y10 = yearly_mean(bonds)
    series["confederationBond10yYieldPct"] = {"unit": "% per year, yearly mean of daily yields", "geo": "CH",
                                              "source": source(src, "swiss/snb_confederation_bond_yields.csv"),
                                              "summary": summary(y10), "history": {y: v for y, v in y10.items() if y >= 2000}}

    policy = bis_series(raw / "markets/bis_central_bank_policy_rates.zip", "CH", {"FREQ": "M"})
    series["snbPolicyRatePct"] = {"unit": "% per year, monthly", "geo": "CH",
                                  "source": source(src, "markets/bis_central_bank_policy_rates.zip"),
                                  "last": policy[max(policy)], "lastPeriod": max(policy), "recent": last_items(policy, 24)}

    spp = bis_series(raw / "markets/bis_residential_property_prices.zip", "CH",
                     {"FREQ": "Q", "VALUE": "N", "UNIT_MEASURE": "628"})
    end = max(p for p in spp if p.endswith("Q4"))
    series["residentialPropertyPrices"] = {
        "unit": "nominal index 2010 = 100, quarterly (purchase prices, NOT rents)", "geo": "CH",
        "source": source(src, "markets/bis_residential_property_prices.zip"),
        "last": spp[max(spp)], "lastPeriod": max(spp),
        "cagrPct": {f"{n}y": cagr(spp, end, n) for n in (10, 20, 30)}, "cagrEnd": end}

    rent_idx = bfs_rent_index(raw / "swiss/bfs_rent_price_index.xlsx")
    rent_chg = yearly_change(rent_idx)
    series["rentPriceIndexChangePct"] = {
        "unit": "% per year (yearly average of the rent index, all rented dwellings)", "geo": "CH",
        "source": source(src, "swiss/bfs_rent_price_index.xlsx"), "summary": summary(rent_chg),
        "history": {y: v for y, v in rent_chg.items() if y >= 1990}}
    wage_chg = bfs_wage_index(raw / "swiss/bfs_wage_index_quarterly.csv")
    complete = {y: v for y, v in wage_chg.items() if y < max(wage_chg)}       # the current year is partial
    series["nominalWageGrowthPct"] = {
        "unit": "% per year (mean of the quarterly year-on-year changes)", "geo": "CH",
        "source": source(src, "swiss/bfs_wage_index_quarterly.csv"), "summary": summary(complete),
        "history": complete}
    lse = bfs_wage_by_age(raw / "swiss/bfs_wage_by_age_lse.xlsx")
    series["medianWageByAge"] = {
        "unit": "CHF per month, gross, standardised to full time (40 h/week), median, all genders, by position",
        "geo": "CH", "year": lse["year"], "source": source(src, "swiss/bfs_wage_by_age_lse.xlsx"),
        "mediansChf": lse["mediansChf"]}

    # ------------------------------------------------------------------ trends the engine applies (yearly, in January)
    cpi_s = series["cpiInflationPct"]["summary"]
    trends["priceInflationAnnual"] = {
        "value": round(cpi_s["mean"] / 100, 5), "low": round(cpi_s["p10"] / 100, 5), "high": round(cpi_s["p90"] / 100, 5),
        "origin": "OBSERVED", "appliesTo": ["learned flows (living costs, insurance, travel, tuition, unexpected)"],
        "basis": f"mean Swiss CPI inflation {cpi_s['from']}-{cpi_s['to']}; low/high = p10/p90 of the yearly values",
        "series": "cpiInflationPct"}
    w = series["nominalWageGrowthPct"]["summary"]
    trends["wageGrowthAnnual"] = {
        "value": round(w["mean"] / 100, 5), "low": round(w["p10"] / 100, 5), "high": round(w["p90"] / 100, 5),
        "origin": "OBSERVED", "appliesTo": ["monthlyIncomeChf (everyone, economy-wide)"],
        "basis": f"mean nominal growth of the BFS Swiss wage index {w['from']}-{w['to']}; low/high = p10/p90",
        "series": "nominalWageGrowthPct"}
    r = series["rentPriceIndexChangePct"]["summary"]
    trends["rentGrowthSittingTenantAnnual"] = {
        "value": round(r["mean"] / 100, 5), "low": round(r["p10"] / 100, 5), "high": round(r["p90"] / 100, 5),
        "origin": "OBSERVED", "appliesTo": ["monthlyRentChf while the person does not move"],
        "basis": f"mean yearly change of the BFS rent price index {r['from']}-{r['to']} (all rented dwellings: "
                 "mostly sitting tenants, whose rents follow the reference mortgage rate and CPI by law); "
                 "low/high = p10/p90",
        "series": "rentPriceIndexChangePct"}
    curves = {}
    for name, medians in lse["mediansChf"].items():
        points = sorted([LSE_AGE_GROUPS[g], v] for g, v in medians.items())
        peak = max(points, key=lambda pt: pt[1])[0]
        curves[name] = {"points": points, "flatAfterAge": peak}
    trends["careerCurveByAge"] = {
        "value": {"curves": curves, "defaultCurve": "allPositions",
                  "occupationCurve": {"JUNIOR": "noManagement", "PROFESSIONAL": "allPositions",
                                      "SENIOR": "management"}},
        "origin": "OBSERVED", "appliesTo": ["monthlyIncomeChf, only while the person works"],
        "basis": f"BFS LSE {lse['year']}: median wage by age group (placed at the middle of the group) for each "
                 "professional position. Every month the income is multiplied by curve(age + 1 month) / curve(age), "
                 "linear between points, using the curve of the person's OCCUPATION (occupationCurve; the mapping "
                 "of our occupation levels to BFS positions is ASSUMED). Only ratios are used (gross vs net does "
                 "not matter). Each curve is flat after its peak (ASSUMED: the lower median of older groups reflects "
                 "who still works, not pay cuts)",
        "series": "medianWageByAge"}
    trends["safeReturnAnnual"] = {
        "value": round(series["confederationBond10yYieldPct"]["summary"][f"mean{RECENT_YEARS}y"] / 100, 5),
        "origin": "OBSERVED", "appliesTo": ["reference for conservative investments; not equity returns"],
        "basis": f"mean 10-year Confederation bond yield, last {RECENT_YEARS} years",
        "series": "confederationBond10yYieldPct"}
    trends["jobLossRiskByAge"] = {
        "value": {"under25": by_age.get("under25", {}).get("mean5y"),
                  "from25to74": by_age.get("from25to74", {}).get("mean5y")},
        "unit": "% unemployment rate (stock, not a monthly hazard)", "origin": "OBSERVED",
        "appliesTo": ["probabilities of job-loss events (content), not the monthly step"],
        "series": "unemploymentRateByAgePct"}
    trends["homePriceGrowthAnnual"] = {
        "value": round(series["residentialPropertyPrices"]["cagrPct"]["20y"] / 100, 5),
        "low": round(min(series["residentialPropertyPrices"]["cagrPct"].values()) / 100, 5),
        "high": round(max(series["residentialPropertyPrices"]["cagrPct"].values()) / 100, 5),
        "origin": "OBSERVED", "appliesTo": ["house-purchase events and the 'Housing deposit' goal"],
        "basis": "20-year CAGR of Swiss residential purchase prices; low/high = range of 10/20/30-year CAGRs",
        "series": "residentialPropertyPrices"}

    return {
        "meta": {"generator": "tools/macro.py", "version": 1, "rawDir": "data/raw (git-ignored, see data/README.md)",
                 "rateUnit": "trends are annual fractions (0.01 = 1 %); series keep the provider's unit",
                 "notIncluded": ["equity returns (FRED download failed and is US data)",
                                 "market rents for new leases by city (needed for moving events)"],
                 "caveat": "Aggregates for the whole country, not personal finances. Long-run averages are "
                           "assumptions about the future, shown with their range."},
        "series": series,
        "trends": trends,
    }


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--raw-dir", type=Path, default=DEFAULT_RAW)
    ap.add_argument("--out-dir", type=Path, default=Path(__file__).resolve().parent / "out")
    args = ap.parse_args()
    out = build(args.raw_dir)
    args.out_dir.mkdir(parents=True, exist_ok=True)
    (args.out_dir / "macro.json").write_text(json.dumps(out, indent=2), encoding="utf-8")
    print(json.dumps(out["trends"], indent=2))


if __name__ == "__main__":
    main()
