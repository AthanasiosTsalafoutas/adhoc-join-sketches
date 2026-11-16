#!/usr/bin/env python3
"""Query miner for the binary-join datasets (GH Archive, TPC-DS, IMDB): picks queries with exact join sizes.

Steps:
  1. (TPC-DS only) rebin the numeric attributes into roughly equal-frequency buckets, so that single-value
     predicates land in a usable selectivity band, and write the result to a new dataset directory.
  2. List every single-attribute equality predicate aN=v per side with its filtered row count and selectivity,
     and keep those inside a selectivity band.
  3. Build conjunctions of 2 and 3 predicates on different attributes from the kept singles and keep the
     least selective ones.
  4. For every (left predicate, right predicate) pair compute the exact join size
     sum over keys k of L_k(left predicate) * R_k(right predicate), as one matrix product over the join keys.
  5. Keep pairs whose join size reaches a threshold and select about 100 that span the join-size range
     log-uniformly (always keeping the full join and the single-predicate x ALL / ALL x single queries).
  6. Write <datadir>/queries.csv (id,name,left_predicate,right_predicate,exact) and <datadir>/query_candidates.csv
     (all viable pairs with per-side selectivities and filtered row counts).

Usage:
  python scripts/mine_queries.py gharchive
  python scripts/mine_queries.py tpcds
  python scripts/mine_queries.py imdb2_raw   (also imdb2_binned, imdb2_raw_oneside, imdb2_binned_oneside)
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import duckdb
import numpy as np

ROOT = Path(__file__).resolve().parents[1]

# sel_band: single predicates keep 0.3 % to 90 % of a stream; conj_min_sel: conjunctions keep at least 0.1 %,
# so that the filtered streams, and their joins, are not trivially small
DATASETS = {
    "gharchive": dict(
        src_dir=ROOT / "datasets" / "gharchive",
        out_dir=ROOT / "datasets" / "gharchive",           # attributes are already binned, mined in place
        left=("pushes.tsv", "repo_id", ["hour", "weekday", "default_branch", "bot"]),
        right=("events.tsv", "repo_id", ["type_id", "hour", "weekday", "bot"]),
        rebin={},                                            # none
        sel_band=(0.003, 0.9),
        conj_min_sel=0.001,
        join_threshold=1_000,
    ),
    "tpcds": dict(
        src_dir=ROOT / "datasets" / "tpcds_sf1_equality",
        out_dir=ROOT / "datasets" / "tpcds_sf1_mined",
        left=("store_sales.tsv", "join_key", ["sold_date_bin", "location_key", "promotion_key", "quantity_bin"]),
        right=("catalog_sales.tsv", "join_key", ["sold_date_bin", "location_key", "promotion_key", "quantity_bin"]),
        rebin={"sold_date_bin": 16, "promotion_key": 12, "quantity_bin": 16},   # location_key already ~7 even buckets
        sel_band=(0.003, 0.9),
        conj_min_sel=0.001,
        join_threshold=500,
    ),
    # IMDB (prepare_imdb_v2.py): keyword popularity tier, raw year/country.
    "imdb2_raw": dict(
        src_dir=ROOT / "datasets" / "imdb2_raw_mined",
        out_dir=ROOT / "datasets" / "imdb2_raw_mined",
        left=("movie_keyword.tsv", "movie_id", ["kw_pop", "year", "kind"]),
        right=("movie_companies.tsv", "movie_id", ["company_type", "country", "year", "kind"]),
        rebin={},
        sel_band=(0.003, 0.9),
        conj_min_sel=0.001,
        join_threshold=500,
    ),
    # IMDB (prepare_imdb_v2.py): keyword popularity tier, binned year/country.
    "imdb2_binned": dict(
        src_dir=ROOT / "datasets" / "imdb2_binned_mined",
        out_dir=ROOT / "datasets" / "imdb2_binned_mined",
        left=("movie_keyword.tsv", "movie_id", ["kw_pop", "year", "kind"]),
        right=("movie_companies.tsv", "movie_id", ["company_type", "country", "year", "kind"]),
        rebin={},
        sel_band=(0.003, 0.9),
        conj_min_sel=0.001,
        join_threshold=500,
    ),
    # IMDB (prepare_imdb_v2.py): keyword popularity tier, raw year/country, year/kind on one side only.
    "imdb2_raw_oneside": dict(
        src_dir=ROOT / "datasets" / "imdb2_raw_oneside_mined",
        out_dir=ROOT / "datasets" / "imdb2_raw_oneside_mined",
        left=("movie_keyword.tsv", "movie_id", ["kw_pop", "year", "kind"]),
        right=("movie_companies.tsv", "movie_id", ["company_type", "country"]),
        rebin={},
        sel_band=(0.003, 0.9),
        conj_min_sel=0.001,
        join_threshold=500,
    ),
    # IMDB (prepare_imdb_v2.py): keyword popularity tier, binned year/country, year/kind on one side only.
    "imdb2_binned_oneside": dict(
        src_dir=ROOT / "datasets" / "imdb2_binned_oneside_mined",
        out_dir=ROOT / "datasets" / "imdb2_binned_oneside_mined",
        left=("movie_keyword.tsv", "movie_id", ["kw_pop", "year", "kind"]),
        right=("movie_companies.tsv", "movie_id", ["company_type", "country"]),
        rebin={},
        sel_band=(0.003, 0.9),
        conj_min_sel=0.001,
        join_threshold=500,
    ),
}


def rebin_and_write(con, cfg):
    """Rebin the attributes listed in cfg["rebin"] on both sides with shared bucket edges and write the TSVs
    to cfg["out_dir"]."""
    src, out = cfg["src_dir"], cfg["out_dir"]
    out.mkdir(parents=True, exist_ok=True)
    (lf, lkey, lattrs), (rf, rkey, rattrs) = cfg["left"], cfg["right"]

    for fname, key, attrs in (cfg["left"], cfg["right"]):
        con.execute(f"CREATE OR REPLACE VIEW raw AS SELECT * FROM read_csv('{(src / fname).as_posix()}', "
                    f"delim='\t', header=true)")
        select_parts = [key]
        for a in attrs:
            n = cfg["rebin"].get(a)
            if n:
                # equal-frequency edges pooled over both files, so a bin id means the same on both sides
                edges = con.execute(
                    f"SELECT quantile_cont(v, [{', '.join(str(i / n) for i in range(1, n))}]) FROM ("
                    f"  SELECT {a} v FROM read_csv('{(src / lf).as_posix()}', delim='\t', header=true)"
                    f"  UNION ALL SELECT {a} v FROM read_csv('{(src / rf).as_posix()}', delim='\t', header=true))"
                ).fetchone()[0]
                edges = sorted(set(float(e) for e in edges))
                # bucket index = number of edges at or below the value
                cond = " + ".join(f"({a} >= {e})::INT" for e in edges) or "0"
                select_parts.append(f"({cond}) AS {a}")
            else:
                select_parts.append(a)
        con.execute(f"COPY (SELECT {', '.join(select_parts)} FROM raw) TO '{(out / fname).as_posix()}' "
                    f"(DELIMITER '\t', HEADER)")
    print(f"  wrote rebinned dataset -> {out}")


def load_side(con, path, key, attrs, alias):
    """Load one side into a table with columns k (join key) and a0, a1, ...; returns its row count."""
    con.execute(f"CREATE OR REPLACE TABLE {alias} AS SELECT {key} AS k, "
                + ", ".join(f"{a} AS a{i}" for i, a in enumerate(attrs))
                + f" FROM read_csv('{path.as_posix()}', delim='\t', header=true)")
    n = con.execute(f"SELECT count(*) FROM {alias}").fetchone()[0]
    return n


def single_predicates(con, alias, n_attrs, total, band):
    """List (text, selectivity, rows) for every aN=v inside the selectivity band."""
    lo, hi = band
    out = []
    for i in range(n_attrs):
        rows = con.execute(f"SELECT a{i}, count(*) c FROM {alias} GROUP BY 1").fetchall()
        for v, c in rows:
            sel = c / total
            if lo <= sel <= hi:
                out.append((f"a{i}={v}", sel, c, i))
    out.sort(key=lambda t: -t[1])
    return out


def conj_predicates(con, alias, singles, total, min_sel, cap):
    """Conjunctions of 2 or 3 singles on different attributes, least selective first, at most cap of them.
    Only the 3 least selective singles of each attribute are combined, to bound the number of candidates."""
    by_attr = {}
    for t, s, c, ax in singles:
        by_attr.setdefault(ax, []).append((t, s, c))
    pool = []
    for ax, lst in by_attr.items():
        pool.extend(lst[:3])                       # singles are sorted least selective first
    out = []
    from itertools import combinations
    for r in (2, 3):
        for combo in combinations(pool, r):
            axset = {p[0].split("=")[0] for p in combo}
            if len(axset) != r:                    # all on distinct attributes
                continue
            texts = [p[0] for p in combo]
            c = con.execute(f"SELECT count(*) FROM {alias} WHERE " + " AND ".join(texts)).fetchone()[0]
            sel = c / total
            if sel >= min_sel:
                out.append(("&".join(texts), sel, c))
    out.sort(key=lambda t: -t[1])
    return out[:cap]


def key_vector(con, alias, keys_index, pred):
    """Vector over the shared join keys with the number of rows of the table that satisfy pred."""
    where = "" if pred == "ALL" else "WHERE " + pred.replace("&", " AND ")
    rows = con.execute(f"SELECT k, count(*) c FROM {alias} {where} GROUP BY 1").fetchall()
    v = np.zeros(len(keys_index), dtype=np.float64)
    for k, c in rows:
        idx = keys_index.get(k)
        if idx is not None:
            v[idx] = c
    return v


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dataset", choices=list(DATASETS))
    ap.add_argument("--target", type=int, default=100)
    ap.add_argument("--conj", type=int, default=0,
                    help="instead of queries.csv, write queries_conj.csv: about this many two-sided queries per "
                         "conjunction kind (2+2, 3+1, 3+2, 3+3 predicates; either side order), picked log-uniformly "
                         "over the join size within each kind")
    args = ap.parse_args()
    cfg = DATASETS[args.dataset]
    con = duckdb.connect()

    if cfg["rebin"]:
        print("rebinning numeric attributes (equal-frequency, shared edges)...")
        rebin_and_write(con, cfg)
        data_dir = cfg["out_dir"]
    else:
        data_dir = cfg["src_dir"]

    (lf, lkey, lattrs), (rf, rkey, rattrs) = cfg["left"], cfg["right"]
    nL = load_side(con, data_dir / lf, lkey, lattrs, "L")
    nR = load_side(con, data_dir / rf, rkey, rattrs, "R")
    print(f"left rows={nL:,}  right rows={nR:,}  attrs={lattrs} / {rattrs}")

    # shared key index: only keys that appear on both sides can contribute to the join
    keys = [k for (k,) in con.execute(
        "SELECT DISTINCT L.k FROM L SEMI JOIN R USING (k)").fetchall()]
    keys_index = {k: i for i, k in enumerate(keys)}
    print(f"overlapping join keys: {len(keys):,}")

    lsingles = single_predicates(con, "L", len(lattrs), nL, cfg["sel_band"])
    rsingles = single_predicates(con, "R", len(rattrs), nR, cfg["sel_band"])
    lconj = conj_predicates(con, "L", lsingles, nL, cfg["conj_min_sel"], cap=25)
    rconj = conj_predicates(con, "R", rsingles, nR, cfg["conj_min_sel"], cap=25)
    print(f"kept single predicates: L={len(lsingles)} R={len(rsingles)} | conjunctions: L={len(lconj)} R={len(rconj)}")

    lpreds = [("ALL", 1.0, nL)] + [(t, s, c) for (t, s, c, _) in lsingles] + lconj
    rpreds = [("ALL", 1.0, nR)] + [(t, s, c) for (t, s, c, _) in rsingles] + rconj

    Lmat = np.vstack([key_vector(con, "L", keys_index, t) for (t, _, _) in lpreds])
    Rmat = np.vstack([key_vector(con, "R", keys_index, t) for (t, _, _) in rpreds])
    joincard = Lmat @ Rmat.T   # exact join size of every (left predicate, right predicate) pair

    cands = []
    for li, (lt, ls, lc) in enumerate(lpreds):
        for ri, (rt, rs, rc) in enumerate(rpreds):
            jc = int(round(joincard[li, ri]))
            if lt == "ALL" and rt == "ALL":
                name = "full_join"
            elif rt == "ALL":
                name = f"L_{lt.replace('=', '_').replace('&', '_')}"
            elif lt == "ALL":
                name = f"R_{rt.replace('=', '_').replace('&', '_')}"
            else:
                name = f"pair_L_{lt.replace('=', '').replace('&', '_')}_R_{rt.replace('=', '').replace('&', '_')}"
            cands.append(dict(name=name, left=lt, right=rt, left_sel=ls, right_sel=rs,
                              left_rows=lc, right_rows=rc, exact=jc))

    thr = cfg["join_threshold"]
    viable = [c for c in cands if c["exact"] >= thr]
    viable.sort(key=lambda c: -c["exact"])
    print(f"combinations with join cardinality >= {thr:,}: {len(viable)} / {len(cands)}")

    keep, seen = [], set()
    def take(c):
        if c["name"] not in seen:
            seen.add(c["name"]); keep.append(c); return True
        return False

    def log_uniform_pick(pool, n):
        """Add n entries of pool (sorted by descending join size) to keep, spread log-uniformly over its join sizes."""
        if not pool or n <= 0:
            return
        lo, hi = np.log10(max(pool[-1]["exact"], 1)), np.log10(max(pool[0]["exact"], 1))
        targets = np.logspace(hi, lo, n) if hi > lo else [pool[0]["exact"]] * n
        used = set()
        for tgt in targets:
            best = min((i for i in range(len(pool)) if i not in used),
                       key=lambda i: abs(np.log10(max(pool[i]["exact"], 1)) - np.log10(tgt)), default=None)
            if best is not None and take(pool[best]):
                used.add(best)

    if args.conj:
        # conjunction workload: stratified by the number of predicates per side, so that the error can be compared
        # per kind of conjunction (the main workload has only a few 3-predicate queries)
        def npred(p):
            return 0 if p == "ALL" else p.count("&") + 1
        kinds = {"2+2": {(2, 2)}, "3+1": {(3, 1), (1, 3)}, "3+2": {(3, 2), (2, 3)}, "3+3": {(3, 3)}}
        for kind, shapes in kinds.items():
            pool = [c for c in viable if (npred(c["left"]), npred(c["right"])) in shapes]
            before = len(keep)
            log_uniform_pick(pool, args.conj)
            print(f"  {kind}: {len(keep) - before} picked out of {len(pool)} candidates")
        keep.sort(key=lambda c: -c["exact"])
        qcsv = data_dir / "queries_conj.csv"
        with open(qcsv, "w", newline="") as f:
            f.write("id,name,left_predicate,right_predicate,exact\n")
            for i, c in enumerate(keep):
                f.write(f"{i},{c['name']},{c['left']},{c['right']},{c['exact']}\n")
        print(f"wrote {qcsv}  ({len(keep)} queries)")
        return

    take(next(c for c in viable if c["name"] == "full_join"))
    lstar = [c for c in viable if c["name"].startswith("L_")]
    rstar = [c for c in viable if c["name"].startswith("R_")]
    pairs = [c for c in viable if c["name"].startswith("pair_")]
    # about 15 queries per single-sided kind, the rest goes to two-sided pairs
    log_uniform_pick(sorted(lstar, key=lambda c: -c["left_sel"]), 15)
    log_uniform_pick(sorted(rstar, key=lambda c: -c["right_sel"]), 15)
    log_uniform_pick(pairs, args.target - len(keep))
    # if there are too few pairs, fill up with the remaining single-sided queries
    if len(keep) < args.target:
        log_uniform_pick([c for c in lstar + rstar if c["name"] not in seen], args.target - len(keep))
    keep.sort(key=lambda c: -c["exact"])
    keep = keep[:args.target]

    qcsv = data_dir / "queries.csv"
    with open(qcsv, "w", newline="") as f:
        f.write("id,name,left_predicate,right_predicate,exact\n")
        for i, c in enumerate(keep):
            f.write(f"{i},{c['name']},{c['left']},{c['right']},{c['exact']}\n")
    ccsv = data_dir / "query_candidates.csv"
    with open(ccsv, "w", newline="") as f:
        f.write("name,left_predicate,right_predicate,left_sel,right_sel,left_filtered_rows,right_filtered_rows,join_cardinality\n")
        for c in sorted(viable, key=lambda c: -c["exact"]):
            f.write(f"{c['name']},{c['left']},{c['right']},{c['left_sel']:.4f},{c['right_sel']:.4f},"
                    f"{c['left_rows']},{c['right_rows']},{c['exact']}\n")
    print(f"wrote {qcsv}  ({len(keep)} queries)")
    print(f"wrote {ccsv}  ({len(viable)} viable candidates)")

    print("\n  cardinality span of selected queries:")
    ex = [c["exact"] for c in keep]
    print(f"    min={min(ex):,}  median={int(np.median(ex)):,}  max={max(ex):,}  count={len(ex)}")


if __name__ == "__main__":
    main()
