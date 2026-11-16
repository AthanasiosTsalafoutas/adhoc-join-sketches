# Athanasios' Master's Thesis

Code for the thesis "Join Size Estimation over Data Streams Subject to Ad-Hoc Predicates using Sketches".

Join-size estimation under predicates over two streams, at a fixed memory budget. The three methods of the thesis
are built in `src/main/java/com/adhocjoin/compose/Methods.java`:

1. **Sampled-Cell Sketch**: CMM or Fast-AGMS with a tuple sample in every cell.
2. **Key-Partitioned Sketch**: key groups, each with a small Fast-AGMS sketch and one shared tuple sample.
3. **Attribute-Partitioned Sketch**: one Fast-AGMS sketch per attribute value, with record-hash witnesses.

The baselines (reservoir sampling, two-level sampling) are in `src/main/java/com/adhocjoin/estimator/`.

Requirements: Java 25 and Maven, Python 3 with `duckdb numpy pandas matplotlib seaborn`. Build with
`mvn -q compile`.

## Where things are

| Path | Contents |
|---|---|
| `src/main/java/com/adhocjoin/compose` | the three methods |
| `src/main/java/com/adhocjoin/sketch` | hashing, counter rows, k-min witness cells, witness intersection |
| `src/main/java/com/adhocjoin/estimator` | baselines, the oracle sketch and exact join sizes |
| `src/main/java/com/adhocjoin/experiments` | the experiment drivers (arguments in each class's Javadoc) |
| `scripts/prepare_*.py`, `scripts/gen_*.py`, `scripts/mine_queries.py` | dataset preparation, synthetic data and query workloads |
| `scripts/m2_oracle.py` | Method 2's estimate computed exactly from the full data (its error without sampling or sketch noise) |
| `scripts/plot_final.py` | figures and tables of the thesis |

## Data

| Dataset | Source | Put in |
|---|---|---|
| TPC-DS SF1 | scale factor 1 (SF1, about 1 GB), generated with `dsdgen -scale 1` from the TPC-DS kit (https://www.tpc.org/tpcds/, tools download at https://www.tpc.org/tpc_documents_current_versions/current_specifications5.asp) | `datasets/tpcds_sf1_raw/` |
| GH Archive | 168 hourly files, 1–7 June 2026, from https://data.gharchive.org/2026-06-01-0.json.gz to https://data.gharchive.org/2026-06-07-23.json.gz (https://www.gharchive.org) | `datasets/gharchive_raw/` |
| IMDB (JOB) | `title`, `company_name`, `movie_keyword`, `movie_companies` from https://event.cwi.nl/da/job/imdb.tgz | `datasets/imdb_raw/` |

Alternatively, the IMDB tables can be loaded directly with DuckDB in Python, which downloads them itself:

```python
import duckdb
url = "https://github.com/duckdb/duckdb-data/releases/download/v1.0/job_{}.parquet"
for t in ["title", "company_name", "movie_keyword", "movie_companies"]:
    duckdb.sql(f"CREATE TABLE {t} AS SELECT * FROM read_parquet('{url.format(t)}')")
```

This route was not used for the thesis and is untested with the preparation script, which reads the headerless
CSVs of `imdb.tgz`.

The GH Archive week can be downloaded with
`cd datasets/gharchive_raw && for d in 1 2 3 4 5 6 7; do for h in $(seq 0 23); do curl -sO https://data.gharchive.org/2026-06-0$d-$h.json.gz; done; done`.

TPC-DS SF1 cannot be downloaded directly: its data generator `dsdgen` has to be built and run. On Linux:

1. Download the TPC-DS tools from the link above and unpack them.
2. In the `tools` folder, build only the data generator with `make OS=LINUX dsdgen`. A plain `make` also builds the
   query generator, which needs `lex` installed.
3. Generate the data into a short path, as `dsdgen` cuts off long output paths:
   `mkdir -p /tmp/tpcds && ./dsdgen -scale 1 -dir /tmp/tpcds`.
4. Copy `store_sales.dat` and `catalog_sales.dat` to `datasets/tpcds_sf1_raw/`. Only these two tables are used.

`sh scripts/prepare_datasets.sh` turns them into the datasets of the thesis and generates the synthetic ones. Each
dataset is a folder with two tab-separated files (a header, the join key first, then integer attributes) and a
`queries.csv` (`id,name,left_predicate,right_predicate,exact`, predicates like `a0=1&a1=3` or `ALL`).

## Using the methods

Every method is a `JoinEstimator`: feed it the tuples of both streams, then ask for the join size under any
predicates. The sizes below are examples. Each estimator reports its space with `logicalPayloadBytes()`, and the
drivers in `experiments/` (`MainExperiments`, `Method1Bench`) show how the sizes are chosen to fit a byte budget.

```java
Dataset d = Dataset.load("datasets/synthetic_rho", "left", "right");
int la = d.leftAttributes(), ra = d.rightAttributes();
long seed = 1;

List<JoinEstimator> estimators = List.of(
        // Method 1: width 4096, 3 rows, 8 sampled tuples per cell, CMM (true = Fast-AGMS)
        Methods.sampledCellSketch(new SketchConfig(4096, 3), la, ra, seed, seed,
                new CellSampler.Reservoir(8, seed), false),
        // Method 2: 512 key groups, each 3 rows x 16 bins and a sample of 64 tuples, Fast-AGMS
        Methods.keyPartitionedSketch(512, 16, 3, 64, la, ra, seed, true),
        // Method 3: one cell per observed value, width 1024, 5 rows, 32 witnesses per cell
        Methods.attributePartitionedSketch(Partition.AttributeCells.observed(d.left(), la, d.right(), ra), 1024, 5, 32, seed)
                .withSmallestPositiveFallback(),
        // reservoir sampling: 20000 tuples per side
        new ReservoirSamplingEstimator(20000, seed),
        // two-level sampling: p = 0.05, q = 0.2
        new TwoLevelSamplingEstimator(0.05, 0.2, seed));

// left: a0 = 1 and a1 = 1, right: no predicate
BinaryJoinQuery q = new BinaryJoinQuery(Queries.and(Queries.eq(0, 1), Queries.eq(1, 1)), Queries.all());
for (JoinEstimator e : estimators) {
    d.ingest(e);
    System.out.println(e.name() + " " + e.estimate(q) + " (" + e.logicalPayloadBytes() + " bytes)");
}
```

## How the thesis results were produced

1. **Datasets**: `scripts/prepare_datasets.sh`, as above.
2. **Experiments**: the drivers in `experiments/` run every method on a dataset at the given space budgets (a
   percentage of the data size) and seeds, and write one CSV row per query and run. `scripts/run_experiments.sh`
   lists the runs behind the thesis.
3. **Figures**: `scripts/plot_final.py` reads those CSVs and writes the figures and tables of the Experiments chapter. It also prints
   the numbers behind every table, and the few numbers quoted only in the text (for example the p90 q-error of
   reservoir sampling over its answers above zero), to the terminal.

The two `.sh` files are shell scripts: lists of terminal commands that run in order (prepare each dataset, then run
each experiment). They run in a Linux or macOS terminal, or on Windows in Git Bash or WSL, for example
`sh scripts/run_experiments.sh` for all runs or `sh scripts/run_experiments.sh method1` for one experiment. The
commands can also be copied and run one at a time.

Every run is seeded, so the same data gives the same numbers.

In short, from a fresh checkout with the raw data in place:

```sh
sh scripts/prepare_datasets.sh
sh scripts/run_experiments.sh
RESULTS=results/thesis PLOT_OUT=results/thesis/figures python scripts/plot_final.py
```

### Reproducibility check

This repository was checked from scratch before submission. The raw data was downloaded again from the links above
(GH Archive and IMDB), and TPC-DS SF1 was generated again with `dsdgen`. From that data, `prepare_datasets.sh`
produces query workloads (`queries.csv` and `queries_conj.csv`) that are identical to the ones used in the thesis.
Every experiment in `run_experiments.sh` was then run with the first seed, and all estimates reported in the thesis
came out identical (for the joincorr and rho experiments with all 10 seeds). `plot_final.py` produces all figures
and tables from them. Timings naturally differ slightly between runs. A full rerun of all seeds was not repeated, as it takes several hours.

