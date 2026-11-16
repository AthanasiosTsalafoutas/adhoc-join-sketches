"""Synthetic join with large predicate domains, so a sketch cannot give every attribute value its own cell.

Each side has 4 attributes with DOMAIN values and Zipf(VAL_ZIPF) value frequencies (heavy values plus a long tail).
a0 and a1 are correlated with the join key (the same key-to-value map on both sides, followed with probability
RHO), a2 and a3 are independent. Queries have 0-2 equality predicates per side, stratified by join selectivity.
Writes left.tsv, right.tsv and queries.csv (with exact join sizes) to outDir.
Usage: python scripts/gen_bigdomain.py outDir [keyZipf] [domain] [valueZipf]
keyZipf > 0 draws join keys from Zipf(keyZipf) with the same ranking on both sides (heavy keys are heavy on both),
the default 0 gives uniform keys.
"""
import sys
from pathlib import Path
import numpy as np

KEYS, MULT, DOMAIN, VAL_ZIPF, RHO, ATTRS = 200_000, 10, 5000, 1.0, 0.8, 4
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
rng = np.random.default_rng(20261018)
KEY_ZIPF = float(sys.argv[2]) if len(sys.argv) > 2 else 0.0
DOMAIN = int(sys.argv[3]) if len(sys.argv) > 3 else DOMAIN
VAL_ZIPF = float(sys.argv[4]) if len(sys.argv) > 4 else VAL_ZIPF
pk = None
if KEY_ZIPF > 0:
    pk = 1.0 / np.arange(1, KEYS + 1) ** KEY_ZIPF; pk /= pk.sum()
pv = 1.0 / np.arange(1, DOMAIN + 1) ** VAL_ZIPF; pv /= pv.sum()
perm = [rng.permutation(DOMAIN) for _ in range(ATTRS)]          # value id of each frequency rank
key_class = [perm[i][rng.choice(DOMAIN, size=KEYS, p=pv)] for i in range(2)]  # zipf-distributed per-key value


def side(n):
    """Return n rows (join_key, a0..a3) as an int64 matrix; a0 and a1 follow the per-key value with prob RHO."""
    keys = rng.integers(0, KEYS, size=n) if pk is None else rng.choice(KEYS, size=n, p=pk)
    cols = [keys]
    for i in range(ATTRS):
        v = perm[i][rng.choice(DOMAIN, size=n, p=pv)]
        if i < 2:
            v = np.where(rng.random(n) < RHO, key_class[i][keys], v)
        cols.append(v)
    return np.column_stack(cols).astype(np.int64)


L, R = side(KEYS * MULT), side(KEYS * MULT)
hdr = "join_key\t" + "\t".join(f"a{i}" for i in range(ATTRS))
np.savetxt(out / "left.tsv", L, fmt="%d", delimiter="\t", header=hdr, comments="")
np.savetxt(out / "right.tsv", R, fmt="%d", delimiter="\t", header=hdr, comments="")


def pick_t(t, m):
    """m distinct attributes, values taken from tuple t (so the conjunction is non-empty)."""
    return [(a, int(t[1 + a])) for a in sorted(rng.choice(ATTRS, size=m, replace=False))]


r_order = np.argsort(R[:, 0], kind="stable")
r_start = np.searchsorted(R[r_order, 0], np.arange(KEYS + 1))


def counts(T, ps):
    """Per-key count of the rows of T that satisfy all equality predicates in ps."""
    mask = np.ones(len(T), bool)
    for a, v in ps:
        mask &= T[:, 1 + a] == v
    return np.bincount(T[mask, 0], minlength=KEYS).astype(np.float64)


def fmt(ps):
    return "ALL" if not ps else "&".join(f"a{a}={v}" for a, v in ps)


# Stratified workload by join selectivity J/J_all: hi >= 1e-2, mid [1e-4, 1e-2), lo < 1e-4 (with J >= 10), PER_BAND
# queries each and at most PER_SHAPE per (band, shape), so every band mixes one-sided, two-sided and conjunctive
# queries. Values come from a pair of tuples that share a key (so two-sided queries are non-empty) or, half the
# time, from the top-20 frequency ranks (so high-selectivity conjunctions exist).
PER_BAND, PER_SHAPE = 40, 8
J_ALL = int(counts(L, []) @ counts(R, []))


def band_of(j):
    """Selectivity band (hi, mid or lo) of a join size j."""
    s = j / J_ALL
    return "hi" if s >= 1e-2 else ("mid" if s >= 1e-4 else "lo")


def pick_heavy(m):
    return [(a, int(perm[a][rng.integers(0, 20)])) for a in sorted(rng.choice(ATTRS, size=m, replace=False))]


rows, seen, quota = [], set(), {}
tries = 0
while any(sum(1 for r in rows if r[0] == b) < PER_BAND for b in ("hi", "mid", "lo")) and tries < 20_000:
    tries += 1
    m1, m2 = rng.integers(0, 3), rng.integers(0, 3)
    if m1 + m2 == 0:
        continue
    if rng.random() < 0.5:
        lp, rp = pick_heavy(m1), pick_heavy(m2)
    else:
        tl = L[rng.integers(len(L))]
        lo, hi = r_start[tl[0]], r_start[tl[0] + 1]
        if hi == lo:
            continue
        lp, rp = pick_t(tl, m1), pick_t(R[r_order[rng.integers(lo, hi)]], m2)
    if (fmt(lp), fmt(rp)) in seen:
        continue
    j = int(counts(L, lp) @ counts(R, rp))  # exact join size = per-key left count times right count
    if j < 10:
        continue
    b, sh = band_of(j), (m1, m2)
    if sum(1 for r in rows if r[0] == b) >= PER_BAND or quota.get((b, sh), 0) >= PER_SHAPE:
        continue
    quota[(b, sh)] = quota.get((b, sh), 0) + 1
    seen.add((fmt(lp), fmt(rp)))
    rows.append((b, fmt(lp), fmt(rp), j))
for b in ("hi", "mid", "lo"):
    js = sorted(r[3] for r in rows if r[0] == b)
    if not js:
        print(f"{b}: 0 queries")
        continue
    print(f"{b}: {len(js)} queries, join sel {js[0] / J_ALL:.1e} .. {js[-1] / J_ALL:.1e}; shapes",
          {f"{k[1][0]}|{k[1][1]}": v for k, v in sorted(quota.items()) if k[0] == b})
print("tries", tries)
rows.append(("all", "ALL", "ALL", J_ALL))
rows.sort(key=lambda r: r[3])
with open(out / "queries.csv", "w") as f:
    f.write("id,name,left_predicate,right_predicate,exact\n")
    for i, (band, lp, rp, j) in enumerate(rows):
        f.write(f"{i},q{i}_{band}_L[{lp}]_R[{rp}],{lp},{rp},{j}\n")
print("queries", len(rows))
print("distinct values per attr L:", [len(np.unique(L[:, 1 + i])) for i in range(ATTRS)])
