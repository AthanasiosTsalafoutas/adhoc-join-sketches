"""Synthetic join-correlated dataset: a predicate on one stream is correlated with the join size on the other.

Left stream: every key has LEFT_PER_KEY tuples, and a0 is the bin (ranges in UPPER) of the number of tuples the key
has in the right stream. Right stream: every key has a random number of tuples, log-uniform in [1, MAX_MATCHES],
and one constant attribute (no predicates are put on it).
Queries: a0 = 1..10 on the left, ALL on the right.
Usage: python scripts/gen_joincorr.py   (writes left.tsv, right.tsv, queries.csv to datasets/synthetic_joincorr)
"""
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
KEYS, LEFT_PER_KEY, MAX_MATCHES, SEED = 50_000, 10, 500, 20261109
# upper end of each bin: a0 = 1 -> 1, 2 -> 2-5, 3 -> 6-10, 4 -> 11-25, 5 -> 26-50, 6 -> 51-100,
# 7 -> 101-150, 8 -> 151-200, 9 -> 201-300, 10 -> 301-500 matching tuples
UPPER = np.array([1, 5, 10, 25, 50, 100, 150, 200, 300, 500])


def write(name, query_values):
    """Generate both streams and the queries a0 in query_values into datasets/<name>, and print a summary."""
    rng = np.random.default_rng(SEED)
    keys = np.arange(1, KEYS + 1)
    # number of right tuples of each key, log-uniform
    matches = np.floor(np.exp(rng.uniform(0, np.log(MAX_MATCHES + 1), KEYS))).astype(int).clip(1, MAX_MATCHES)
    mbin = 1 + np.searchsorted(UPPER, matches)      # a0 = the bin that holds the key's number of matches
    out = ROOT / "datasets" / name
    out.mkdir(parents=True, exist_ok=True)
    left_keys = np.repeat(keys, LEFT_PER_KEY)
    left = np.column_stack([left_keys, np.repeat(mbin, LEFT_PER_KEY)])
    rng.shuffle(left)
    right_keys = np.repeat(keys, matches)
    rng.shuffle(right_keys)
    np.savetxt(out / "left.tsv", left, fmt="%d", delimiter="\t", header="join_key\ta0", comments="")
    np.savetxt(out / "right.tsv", np.column_stack([right_keys, np.zeros_like(right_keys)]), fmt="%d",
               delimiter="\t", header="join_key\ta0", comments="")
    with open(out / "queries.csv", "w", encoding="utf-8") as f:
        f.write("id,name,left_predicate,right_predicate,exact\n")
        for i, b in enumerate(query_values):
            exact = int((LEFT_PER_KEY * matches[mbin == b]).sum())  # per key: left tuples times right tuples
            f.write(f"{i},L_a0_{b},a0={b},ALL,{exact}\n")
    print(name, "left", len(left), "right", len(right_keys), "full join", int(LEFT_PER_KEY * matches.sum()))
    for b in query_values:
        sel = mbin == b
        print(f"  a0={b:3}: keys {sel.sum():6}  mean matches {matches[sel].mean() if sel.any() else 0:7.1f}"
              f"  (overall {matches.mean():.1f})")


write("synthetic_joincorr", list(range(1, 11)))
