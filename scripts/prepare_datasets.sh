#!/bin/sh
# Builds every dataset the experiments use, in datasets/<name>/ (<left>.tsv, <right>.tsv, queries.csv).
# Raw inputs (see README.md): TPC-DS dsdgen output in $TPCDS_RAW, GH Archive hourly
# files in datasets/gharchive_raw, IMDB (JOB) CSVs in datasets/imdb_raw.
# A dataset whose raw input is missing is skipped. Every step is deterministic.
set -e
cd "$(dirname "$0")/.."
TPCDS_RAW=${TPCDS_RAW:-datasets/tpcds_sf1_raw}

raw() {
  if [ -e "$1" ]; then return 0; fi
  echo "skipped: $1 is missing"
  return 1
}

if raw "$TPCDS_RAW/store_sales.dat"; then
  python scripts/prepare_tpcds_binary.py "$TPCDS_RAW" datasets/tpcds_sf1_equality
  python scripts/mine_queries.py tpcds
fi
if raw datasets/gharchive_raw; then
  python scripts/prepare_gharchive.py datasets/gharchive_raw datasets/gharchive
  python scripts/mine_queries.py gharchive
  python scripts/mine_queries.py gharchive --conj 15   # conjunction workload (queries_conj.csv)
fi
if raw datasets/imdb_raw/title.csv; then
  # binned year/country, two-sided and one-sided year/kind (the raw variants it also writes are not used)
  python scripts/prepare_imdb_v2.py datasets/imdb_raw datasets
  for v in imdb2_binned imdb2_binned_oneside; do
    python scripts/mine_queries.py $v
  done
  python scripts/mine_queries.py imdb2_binned --conj 15   # conjunction workload (queries_conj.csv)
fi

# synthetic
python scripts/gen_bigdomain.py datasets/synthetic_domain150_zipf 0.8 150
python scripts/gen_joincorr.py
python scripts/gen_rho.py
