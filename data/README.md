# data/

Public datasets from the challenge's `resources/data/*.md` catalogues, for calibration and stress tests.

- `download.sh` — downloads everything (re-runnable; existing files are skipped). Run: `bash data/download.sh`
- `raw/` — downloaded files, **git-ignored**: redistribution terms of each provider were not checked
  (see the challenge's `resources/data-usage-and-licenses.md` and AGENTS.md). Do not commit them.
- `MANIFEST.tsv` — provider, dataset, retrieval date, URL and HTTP status per file (the citation record the rules ask for).

Layout: `raw/swiss/`, `raw/macro/`, `raw/markets/`, `raw/household/`.

Not downloadable by script (interactive export only): Swiss FSO/BFS household budget and earnings tables
(https://www.pxweb.bfs.admin.ch/), Eurostat income/living-conditions datasets (pick a dataset first).

All figures are aggregates, not personal finances; units and geography differ per file (e.g. FRED series are US, in USD).
