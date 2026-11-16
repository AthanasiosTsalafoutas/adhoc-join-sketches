"""Synthetic within-stream predicate correlation: two attributes of one stream are correlated with each other.

Stream 1 (left): KEYS keys, LEFT_PER_KEY tuples each. Two attributes a0, a1 in 1..10, drawn per tuple and
independent of the key: a0 uniform, a1 = a0 with probability CORR, otherwise uniform.
Stream 2 (right): every key has RIGHT_PER_KEY tuples, one constant attribute, no predicates.

Queries (10, all conjunctions on stream 1, nothing on stream 2):
  same value       a0=v & a1=v      v = 1..5   (true share 8.2%, independence predicts 1%)
  different value  a0=v & a1=v+5    v = 1..5   (true share 0.2%, independence predicts 1%)

Usage: python scripts/gen_rho.py   (writes left.tsv, right.tsv, queries.csv to datasets/synthetic_rho)
"""
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
KEYS, LEFT_PER_KEY, RIGHT_PER_KEY, CORR, SEED = 50_000, 10, 10, 0.8, 20261109

rng = np.random.default_rng(SEED)
left_keys = np.repeat(np.arange(1, KEYS + 1), LEFT_PER_KEY)
a0 = rng.integers(1, 11, len(left_keys))
a1 = np.where(rng.random(len(left_keys)) < CORR, a0, rng.integers(1, 11, len(left_keys)))
left = np.column_stack([left_keys, a0, a1])
rng.shuffle(left)
right_keys = np.repeat(np.arange(1, KEYS + 1), RIGHT_PER_KEY)
rng.shuffle(right_keys)

out = ROOT / "datasets" / "synthetic_rho"
out.mkdir(parents=True, exist_ok=True)
np.savetxt(out / "left.tsv", left, fmt="%d", delimiter="\t", header="join_key\ta0\ta1", comments="")
np.savetxt(out / "right.tsv", np.column_stack([right_keys, np.zeros_like(right_keys)]), fmt="%d", delimiter="\t",
           header="join_key\ta0", comments="")
queries = [(f"same_{v}", v, v) for v in range(1, 6)] + [(f"diff_{v}_{v + 5}", v, v + 5) for v in range(1, 6)]
with open(out / "queries.csv", "w", encoding="utf-8") as f:
    f.write("id,name,left_predicate,right_predicate,exact\n")
    for i, (name, v, w) in enumerate(queries):
        exact = int(((left[:, 1] == v) & (left[:, 2] == w)).sum()) * RIGHT_PER_KEY  # every key has RIGHT_PER_KEY matches
        f.write(f"{i},{name},a0={v}&a1={w},ALL,{exact}\n")
        # estimate under independence of a0 and a1: product of the two marginal selectivities
        indep = ((left[:, 1] == v).mean() * (left[:, 2] == w).mean()) * len(left) * RIGHT_PER_KEY
        print(f"{name:10} exact {exact:8}  independence {indep:10.0f}  ratio {indep / exact:5.2f}")
print("left", len(left), "right", len(right_keys), "full join", len(left) * RIGHT_PER_KEY)
