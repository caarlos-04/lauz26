# data/

Public datasets from the challenge's `resources/data/*.md` catalogues, for calibration and stress tests.

- `download.sh` — downloads everything (re-runnable; existing files are skipped). Run: `bash data/download.sh`
- `raw/` — downloaded files, **git-ignored**: redistribution terms of each provider were not checked
  (see the challenge's `resources/data-usage-and-licenses.md` and AGENTS.md). Do not commit them.
- `MANIFEST.tsv` — provider, dataset, retrieval date, URL and HTTP status per file (the citation record the rules ask for).

BFS files (rent index, wage index, wages by age, wages by education) are resolved from their stable reference code to the current release,
because the numeric asset id changes with every update. Eurostat files are gzip (`.tsv.gz`). Dates in the BFS rent index are Excel serials.

Layout: `raw/swiss/`, `raw/macro/`, `raw/markets/`, `raw/household/`.

Not downloadable by script (interactive export only): Swiss FSO/BFS household budget and earnings tables
(https://www.pxweb.bfs.admin.ch/), Eurostat income/living-conditions datasets (pick a dataset first).

All figures are aggregates, not personal finances; units and geography differ per file (e.g. FRED series are US, in USD).

`swiss/bfs_wage_by_education_lse.xlsx` (Earnings structure survey, table T11; one sheet per survey year 2006-2024): median and
quartiles of the gross monthly wage by **level of education** (8 levels, from "Universities and Federal Institutes of Technology"
to "Basic vocational training, no certificate") x professional position x gender. The levels are types of institution: a
university degree is **not split into bachelor and master**, so a master's premium can only be approximated (e.g. university vs
academic upper-secondary certificate).
