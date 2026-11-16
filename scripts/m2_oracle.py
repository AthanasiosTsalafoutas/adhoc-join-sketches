"""Method 2 without sampling or sketch error: the exact form of its estimate.

Method 2 estimates a join as the sum over the key groups of (selectivity on the left) x (selectivity on the right) x
(join of the group). This script computes that sum exactly, from the full data, with G random key groups: the
selectivities are the exact shares of each group's tuples that pass the predicates and the group joins are exact.
What is left is the error of Method 2's assumption alone (the bias), with no sampling or sketch noise.

Each seed draws a different random grouping of the keys. The result does not depend on the space budget.

Usage: python scripts/m2_oracle.py datasets/<name> <left table> <right table> <queries file> <groups> <seeds> out.csv
Writes rows in the format of the experiment CSVs, with method "method2-oracle".
"""
import sys
from pathlib import Path

import duckdb

data, left, right, qfile, groups, seeds, out = sys.argv[1:8]
data = Path(data)
groups, seeds = int(groups), int(seeds)
con = duckdb.connect()


def load(name, table):
    """Load a dataset table, renaming the columns to key, a0, a1, ... as in the query predicates."""
    path = (data / f"{table}.tsv").as_posix()
    cols = [c[0] for c in con.sql(f"DESCRIBE SELECT * FROM read_csv('{path}', delim='\t', header=true)").fetchall()]
    sel = ", ".join([f'"{cols[0]}" AS key'] + [f'"{c}" AS a{i}' for i, c in enumerate(cols[1:])])
    con.sql(f"CREATE TABLE {name} AS SELECT {sel} FROM read_csv('{path}', delim='\t', header=true)")


def where(pred):
    """Query predicate (a0=1&a2=3, or ALL) as SQL."""
    return "TRUE" if pred == "ALL" else " AND ".join(pred.split("&"))


load("L", left)
load("R", right)
con.sql(f"CREATE TABLE seeds AS SELECT range AS s FROM range({seeds})")
queries = con.sql(f"SELECT name, left_predicate, right_predicate, exact FROM read_csv('{(data / qfile).as_posix()}')").fetchall()
dataset = data.name

with open(out, "w") as f:
    f.write("dataset,pct,method,detail,seed,ingest_ns,query,nL,nR,exact,estimate,query_ns\n")
    for name, lp, rp, exact in queries:
        # per key: all tuples and the tuples that pass the predicate, on each side
        con.sql(f"CREATE OR REPLACE TEMP TABLE lk AS SELECT key, count(*) AS n, count(*) FILTER (WHERE {where(lp)}) AS np FROM L GROUP BY key")
        con.sql(f"CREATE OR REPLACE TEMP TABLE rk AS SELECT key, count(*) AS n, count(*) FILTER (WHERE {where(rp)}) AS np FROM R GROUP BY key")
        # per seed and group: exact selectivities and the exact join of the group, then the sum over the groups
        rows = con.sql(f"""
            WITH lg AS (SELECT s, hash(key, s) % {groups} AS g, sum(n) AS n, sum(np) AS np FROM lk, seeds GROUP BY s, g),
                 rg AS (SELECT s, hash(key, s) % {groups} AS g, sum(n) AS n, sum(np) AS np FROM rk, seeds GROUP BY s, g),
                 jg AS (SELECT s, hash(key, s) % {groups} AS g, sum(lk.n * rk.n) AS j
                        FROM lk JOIN rk USING (key), seeds GROUP BY s, g)
            SELECT jg.s, sum(lg.np / lg.n * rg.np / rg.n * jg.j)
            FROM jg JOIN lg USING (s, g) JOIN rg USING (s, g) GROUP BY jg.s ORDER BY jg.s""").fetchall()
        nl = 0 if lp == "ALL" else lp.count("=")
        nr = 0 if rp == "ALL" else rp.count("=")
        for s, est in rows:
            f.write(f'{dataset},1.00,method2-oracle,"G={groups} exact",{s},0,{name},{nl},{nr},{exact},{float(est or 0):.6g},0\n')
        print(name, "done", flush=True)
