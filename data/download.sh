#!/usr/bin/env bash
# Downloads the public datasets listed in the challenge's resources/data/*.md.
# Raw files go to data/raw/ (git-ignored: redistribution terms not checked, see data/README.md).
# Usage: bash data/download.sh      (re-run to refresh; existing files are skipped)
set -u
cd "$(dirname "$0")/raw"
UA="Mozilla/5.0 (compatible; lauzhack-research)"
MANIFEST=../MANIFEST.tsv
[ -f "$MANIFEST" ] || printf "file\tprovider\tdataset\tretrieved\turl\tstatus\n" > "$MANIFEST"

get() { # dir file provider dataset url
  local dir=$1 file=$2 prov=$3 name=$4 url=$5 code
  mkdir -p "$dir"
  if [ -s "$dir/$file" ]; then echo "skip   $dir/$file"; return; fi
  code=$(curl -sSL --http1.1 -A "$UA" --max-time "${T:-120}" -w '%{http_code}' -o "$dir/$file" "$url")
  if [ "$code" = 200 ] && [ -s "$dir/$file" ]; then
    echo "ok     $dir/$file"
    printf "%s/%s\t%s\t%s\t%s\t%s\tHTTP %s\n" "$dir" "$file" "$prov" "$name" "$(date +%F)" "$url" "$code" >> "$MANIFEST"
  else echo "FAIL   $dir/$file (HTTP $code)"; rm -f "$dir/$file"; fi
}

WB=https://api.worldbank.org/v2/country/CHE/indicator
get swiss wb_che_cpi_inflation.zip   "World Bank" "CHE FP.CPI.TOTL.ZG inflation"     "$WB/FP.CPI.TOTL.ZG?downloadformat=csv"
get swiss wb_che_gdp_growth.zip      "World Bank" "CHE NY.GDP.MKTP.KD.ZG GDP growth" "$WB/NY.GDP.MKTP.KD.ZG?downloadformat=csv"
get swiss wb_che_unemployment.zip    "World Bank" "CHE SL.UEM.TOTL.ZS unemployment"  "$WB/SL.UEM.TOTL.ZS?downloadformat=csv"
get swiss wb_che_population.zip      "World Bank" "CHE SP.POP.TOTL population"       "$WB/SP.POP.TOTL?downloadformat=csv"
get household wb_che_gdp_per_capita.zip "World Bank" "CHE NY.GDP.PCAP.CD GDP per capita" "$WB/NY.GDP.PCAP.CD?downloadformat=csv"

FRED=https://fred.stlouisfed.org/graph/fredgraph.csv?id
# FRED is optional: it times out on some networks, so fail fast (T=seconds). Skip with: SKIP_FRED=1 bash data/download.sh
if [ -z "${SKIP_FRED:-}" ]; then export T=20
get macro   fred_cpiaucsl.csv "FRED" "US CPI (CPIAUCSL)"            "$FRED=CPIAUCSL"
get macro   fred_unrate.csv   "FRED" "US unemployment (UNRATE)"     "$FRED=UNRATE"
get macro   fred_fedfunds.csv "FRED" "US fed funds rate (FEDFUNDS)" "$FRED=FEDFUNDS"
get markets fred_dgs10.csv    "FRED" "US 10y Treasury (DGS10)"      "$FRED=DGS10"
get markets fred_dexszus.csv  "FRED" "CHF per USD (DEXSZUS)"        "$FRED=DEXSZUS"
get markets fred_dexuseu.csv  "FRED" "USD per EUR (DEXUSEU)"        "$FRED=DEXUSEU"
get markets fred_usSthpi.csv  "FRED" "US house price index (USSTHPI)" "$FRED=USSTHPI"

unset T
fi

EU=https://ec.europa.eu/eurostat/api/dissemination/sdmx/2.1/data
get macro eurostat_nama_10_gdp.tsv.gz "Eurostat" "nama_10_gdp (GDP and main components)" "$EU/nama_10_gdp?format=TSV&compressed=true"
get macro eurostat_une_rt_m.tsv.gz    "Eurostat" "une_rt_m (monthly unemployment)"       "$EU/une_rt_m?format=TSV&compressed=true"

BIS=https://data.bis.org/static/bulk
get markets bis_residential_property_prices.zip "BIS" "WS_SPP residential property prices" "$BIS/WS_SPP_csv_flat.zip"
get markets bis_central_bank_policy_rates.zip   "BIS" "WS_CBPOL central bank policy rates"  "$BIS/WS_CBPOL_csv_flat.zip"
get markets bis_credit_nonfinancial_sector.zip  "BIS" "WS_TC credit to non-financial sector" "$BIS/WS_TC_csv_flat.zip"

SNB=https://data.snb.ch/api/cube
get swiss snb_exchange_rates_monthly.csv "SNB" "devkum: exchange rates (monthly)"    "$SNB/devkum/data/csv/en"
get swiss snb_confederation_bond_yields.csv "SNB" "rendoblid: Confederation bond yields" "$SNB/rendoblid/data/csv/en"

get household fed_scf_2022_summary_extract.zip "US Federal Reserve" "SCF 2022 summary extract (Excel/CSV)" "https://www.federalreserve.gov/econres/files/scfp2022excel.zip"

# Swiss Federal Statistical Office (BFS). Each table has a stable reference code; its page links to the current
# file (the numeric asset id changes with every release), so we resolve the id first.
get_bfs() { # dir file provider dataset ref
  local dir=$1 file=$2 prov=$3 name=$4 ref=$5 page id
  if [ -s "$dir/$file" ]; then echo "skip   $dir/$file"; return; fi
  page="https://statistik.admin.ch/en/asset/$ref"
  id=$(curl -sSL --http1.1 -A "$UA" --max-time 60 "$page" | grep -o 'assets/[0-9]*/master' | head -1 | grep -o '[0-9]*')
  if [ -z "$id" ]; then echo "FAIL   $dir/$file (could not resolve $ref)"; return; fi
  get "$dir" "$file" "$prov" "$name [$ref, asset $id]" "https://dam-api.bfs.admin.ch/hub/api/dam/assets/$id/master"
}

get_bfs swiss bfs_rent_price_index.xlsx          "BFS" "Rent price index (LIK Wohnungsmiete, monthly/annual, multi-base)" cc-d-05.06.01.17
get_bfs swiss bfs_wage_index_quarterly.csv       "BFS" "Swiss wage index: quarterly estimate of nominal wage change (annual %)" ts-x-03.04.03.01.01
get_bfs swiss bfs_wage_by_age_lse.xlsx           "BFS" "Earnings structure survey T9_b: gross monthly wage by age, position, gender" je-e-03.04.01.01.09
