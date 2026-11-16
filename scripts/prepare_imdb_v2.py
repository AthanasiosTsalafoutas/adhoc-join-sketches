"""IMDB (JOB): movie_keyword JOIN movie_companies on movie_id, as binary-join TSVs with integer attributes.

Reads title.csv, company_name.csv, movie_keyword.csv and movie_companies.csv from the first directory and writes
four datasets into the second:
  imdb2_raw_mined             unbinned: year = the production year, country = one value per country
  imdb2_binned_mined          binned: year = decade, country = every country with >= 1% of the movie_companies
                              rows, all others "other"
  imdb2_raw_oneside_mined     as raw, but year and kind only on movie_keyword
  imdb2_binned_oneside_mined  as binned, but year and kind only on movie_keyword
Attributes
  movie_keyword:   kw_pop (order of magnitude of the keyword's popularity: floor(log10(#movies with the keyword))),
                   year, kind
  movie_companies: company_type, country, year, kind          (oneside: company_type, country)
year and kind come from title, so they are the same for a movie on both sides. Unknown year or country = 0.

Usage: python scripts/prepare_imdb_v2.py datasets/imdb_raw datasets
"""
import csv
import math
import sys
from collections import Counter
from pathlib import Path

src, root = Path(sys.argv[1]), Path(sys.argv[2])
csv.field_size_limit(10 ** 8)

# title: id -> (year, kind)
movie = {}
with open(src / "title.csv", encoding="utf-8", errors="replace", newline="") as f:
    for r in csv.reader(f, escapechar="\\"):
        movie[int(r[0])] = (int(r[4]) if r[4] else 0, int(r[3]))
print("titles", len(movie))

# company -> country code
company_country = {}
with open(src / "company_name.csv", encoding="utf-8", errors="replace", newline="") as f:
    for r in csv.reader(f, escapechar="\\"):
        company_country[int(r[0])] = r[2]

mk = []
kw_movies = Counter()
with open(src / "movie_keyword.csv", newline="") as f:
    for r in csv.reader(f):
        m, k = int(r[1]), int(r[2])
        if m in movie:
            mk.append((m, k))
            kw_movies[k] += 1
mc = []
with open(src / "movie_companies.csv", encoding="utf-8", errors="replace", newline="") as f:
    for r in csv.reader(f, escapechar="\\"):
        m = int(r[1])
        if m in movie:
            mc.append((m, int(r[3]), company_country.get(int(r[2]), "")))
print("movie_keyword rows", len(mk), "movie_companies rows", len(mc))

def kw_pop(k):
    """Popularity tier of keyword k: floor(log10(number of movies with the keyword))."""
    return int(math.floor(math.log10(kw_movies[k])))

# countries: raw id by frequency rank (1 = most frequent), 0 = unknown
cc = Counter(c for _, _, c in mc if c)
rank = {c: i + 1 for i, (c, _) in enumerate(cc.most_common())}
frequent = [c for c, n in cc.most_common() if n >= 0.01 * len(mc)]
binned_country = {c: i + 2 for i, c in enumerate(frequent)}          # 0 = unknown, 1 = other
print("countries", len(rank), "kept individually when binned:", len(frequent), frequent)

# year: by decade (the same on both sides), 0 = unknown
def year_bin(y):
    """Decade of year y (1870s = 1), 0 for unknown."""
    if y == 0:
        return 0
    return y // 10 - 186   # 1870s = 1

def write(name, oneside, binned):
    """Write movie_keyword.tsv and movie_companies.tsv of one dataset variant into root/name."""
    out = root / name
    out.mkdir(parents=True, exist_ok=True)
    with open(out / "movie_keyword.tsv", "w", encoding="utf-8") as o:
        o.write("movie_id\tkw_pop\tyear\tkind\n")
        for m, k in mk:
            y, kd = movie[m]
            o.write(f"{m}\t{kw_pop(k)}\t{year_bin(y) if binned else y}\t{kd}\n")
    with open(out / "movie_companies.tsv", "w", encoding="utf-8") as o:
        o.write("movie_id\tcompany_type\tcountry" + ("" if oneside else "\tyear\tkind") + "\n")
        for m, t, c in mc:
            country = 0 if not c else (binned_country.get(c, 1) if binned else rank[c])
            line = f"{m}\t{t}\t{country}"
            if not oneside:
                y, kd = movie[m]
                line += f"\t{year_bin(y) if binned else y}\t{kd}"
            o.write(line + "\n")
    print("wrote", out)

write("imdb2_raw_mined", oneside=False, binned=False)
write("imdb2_binned_mined", oneside=False, binned=True)
write("imdb2_raw_oneside_mined", oneside=True, binned=False)
write("imdb2_binned_oneside_mined", oneside=True, binned=True)
