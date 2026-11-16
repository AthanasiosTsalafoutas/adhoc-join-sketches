"""Figures and tables of the Experiments chapter, from the result CSVs of scripts/run_experiments.sh.

Usage: python scripts/plot_final.py   (reads $RESULTS, default results/thesis)

Accuracy runs: 10 seeds x 100 queries per dataset and budget; Method 1 runs: 10 seeds; GH timing: 10 seeds, one
thread; lazy: GH 1 %, 10 seeds. A miss (estimate <= 0) is counted with the estimate clipped at 1 (q-error against
the exact size) and drawn as a red cross. Main configurations: Method 3 with 10 % samples and rho ("method3-s10"),
Method 2 with G = 512 (G = 64 on TPC-DS), "two-level" (q = 0.2), "reservoir".

Writes the figures (pdf + png) and tables (.tex) to thesis/LaTeX_tuemscthesis2021/figures/results and prints the
numbers behind every table.
"""
import os
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import seaborn as sns

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / os.environ.get("RESULTS", "results/thesis")
OUT = Path(os.environ["PLOT_OUT"]) if "PLOT_OUT" in os.environ else ROOT / "thesis" / "LaTeX_tuemscthesis2021" / "figures" / "results"
# the figures are drawn wider than the text and scaled down in LaTeX, so every font is scaled up by FS to end up
# close to the size of the main text
FS = float(os.environ.get("PLOT_FONT_SCALE", "1.6"))   # 1.6 gives about the size of the main text
plt.rcParams.update({"font.size": 10 * FS, "axes.titlesize": 10 * FS, "axes.labelsize": 10 * FS,
                     "xtick.labelsize": 9 * FS, "ytick.labelsize": 9 * FS, "legend.fontsize": 9 * FS})
SPACE = "Space (% of stream)"
MAIN = {"m2main": "Method 2",   # G = 512, G = 64 on TPC-DS, see acc()
        "m3main": "Method 3", "two-level": "Two-level", "reservoir": "Reservoir"}
ORDER = list(MAIN.values())
COLORS = {"Method 2": "0.15", "Method 3": "#c62828", "Two-level": "#2ca02c", "Reservoir": "#1f77b4"}
MARKERS = {"Method 2": "o", "Method 3": "D", "Two-level": "^", "Reservoir": "s"}
BANDS = [("large", 1e-1, np.inf), ("medium", 1e-3, 1e-1), ("small", 0, 1e-3)]
_cache = {}


def qerr(df):
    """Add the columns miss (estimate <= 0) and q (q-error, estimate clipped at 1) to a copy of df."""
    df = df.copy()
    df["miss"] = df.estimate <= 0
    est = df.estimate.clip(lower=1.0)
    df["q"] = np.maximum(est / df.exact, df.exact / est)
    return df


def acc(name):
    """Load one accuracy file (numeric budgets only) with q, miss and sel (exact size relative to the largest query)."""
    if name not in _cache:
        d = pd.read_csv(RES / f"{name}.csv", dtype={"pct": str})
        d = d[d.pct != "all"].copy()
        d["pct"] = d.pct.astype(float)
        d = qerr(d[d.exact > 0])
        d["sel"] = d.exact / d.exact.max()
        # main Method 2 ("m2main"): G = 512, except on TPC-DS where G = 64
        main_m2 = "method2" if name.startswith("tpcds") else "method2-g512"
        d = pd.concat([d, d[d.method == main_m2].assign(method="m2main"),  # main Method 3: 10% samples + rho
                       d[d.method == "method3-s10"].assign(method="m3main")], ignore_index=True)
        _cache[name] = d
    return _cache[name]


def fmt(x, digits=2):
    """Format a number for a LaTeX table: fixed digits below 100, thousands separated by \\, above."""
    if not np.isfinite(x):
        return "--"
    return f"{x:.{digits}f}" if x < 100 else f"{x:,.0f}".replace(",", "\\,")


def triple(g, d1=2, d2=1):
    """Table cell "median q / p90 q / number of misses" of the rows in g."""
    return "--" if g.empty else f"{fmt(g.q.median(), d1)} / {fmt(g.q.quantile(0.9), d2)} / {int(g.miss.sum())}"


# ---------------------------------------------------------------------------------------------------- box plots
def panel(ax, sub, order, notes=None):
    """Box plot of q per method label on ax. Outliers are circles, misses red crosses, with a miss count on top."""
    methods = [m for m in order if m in set(sub.label)]
    sns.boxplot(data=sub, x="label", y="q", order=methods, color="0.88", linecolor="0.15", showfliers=False,
                log_scale=True, ax=ax)
    for k, m in enumerate(methods):
        g = sub[sub.label == m]
        lq = np.log10(g.q)
        q1, q3 = lq.quantile(0.25), lq.quantile(0.75)
        lo, hi = q1 - 1.5 * (q3 - q1), q3 + 1.5 * (q3 - q1)
        out = g[~g.miss & ((lq < lo) | (lq > hi))]
        ms = g[g.miss]
        rng = np.random.default_rng(k)
        ax.scatter(k + rng.uniform(-0.15, 0.15, len(out)), out.q, s=12, facecolors="white", edgecolors="0.15",
                   linewidths=0.8, zorder=3, label="positive estimate, outlier")
        ax.scatter(k + rng.uniform(-0.15, 0.15, len(ms)), ms.q, s=16, marker="x", color="#c62828", linewidths=1.1,
                   zorder=4, label="miss (estimate ≤ 0)")
        note = f"miss {int(g.miss.sum())}" + (notes.get(m, "") if notes else "")
        ax.annotate(note, (k, 1.0), xycoords=("data", "axes fraction"), ha="center", va="bottom", fontsize=8 * FS)
    ax.set_xlabel("")
    ax.tick_params(axis="x", rotation=25)
    for lab in ax.get_xticklabels():
        lab.set_horizontalalignment("right")


def grid(panels, ncols, name, height=3.9, width=5.5, pad=16):
    """Draw panels (title, ylabel, data, method order[, notes]) as box plots in a grid and save the figure."""
    nrows = (len(panels) + ncols - 1) // ncols
    fig, axes = plt.subplots(nrows, ncols, figsize=(width * ncols, height * nrows + 0.6), sharey=True, squeeze=False)
    for i, (title, ylabel, df, order, *notes) in enumerate(panels):
        ax = axes[i // ncols][i % ncols]
        panel(ax, df, order, notes[0] if notes else None)
        ax.set_title(title, pad=pad)
        ax.set_ylabel(ylabel if i % ncols == 0 else "")
    for j in range(len(panels), nrows * ncols):
        axes[j // ncols][j % ncols].axis("off")
    handles, labels = axes[0][0].get_legend_handles_labels()
    keep = dict(zip(labels, handles))
    fig.legend(keep.values(), keep.keys(), loc="lower center", ncol=2, frameon=False, bbox_to_anchor=(0.5, -0.02))
    fig.tight_layout(rect=(0, 0.05, 1, 1))
    save(fig, name)


def save(fig, name):
    """Write the figure as pdf to the thesis figure folder and as png to the results folder."""
    OUT.mkdir(parents=True, exist_ok=True)
    (RES / "figures").mkdir(parents=True, exist_ok=True)
    fig.savefig(RES / "figures" / f"{name}.png", dpi=170, bbox_inches="tight")
    fig.savefig(OUT / f"{name}.pdf", bbox_inches="tight")
    plt.close(fig)


# the caption of every table
CAPTIONS = {
    'table_bands': 'Methods 2 and 3 against the sampling baselines by query size (10 runs): median q-error / p90 q-error / misses, number of queries in brackets.',
    'table_joincorr': 'Synthetic join-correlated dataset at 5\\% (10 runs): median estimate / true join.',
    'table_lazy': 'Method 2 with lazy evaluation on GH Archive at 1\\% (10 runs): median q-error / p90 q-error / misses and median query time.',
    'table_m2_groups': 'Method 2 with different numbers of groups $G$ (10 runs): median q-error / p90 q-error / misses.',
    'table_m3_samples': 'Method 3 with different shares of k-min samples and $\\rho$ (10 runs): median q-error / p90 q-error / misses.',
    'table_method1_fallback': 'Method 1 with and without a fallback row at 1\\% (3 rows, 10 runs): median q-error / p90 q-error / misses.',
    'table_rho': 'Synthetic within-stream correlation at 5\\% (10 runs): median estimate / true join.',
    'table_m3_fallback': 'Method 3 with 3 or 5 rows, with and without the smallest positive row (10 runs): median q-error / p90 q-error / misses.',
    'table_conj': 'Conjunction workload at 1\\% by number of predicates per stream, 15 queries each (10 runs): median q-error / p90 q-error / misses. Method 2, exact: its estimate computed from the full data.',
    'table_m2_attrs': 'Method 2 on GH Archive at 1\\% by the attribute a query filters on (10 runs): median q-error.',
    'table_hashed_bias':'Hashed cells against independence on the 150-value Zipf synthetic by query size (10 runs): misses / median q-error above zero / median estimate over true join.',
}


def latex_table(name, label, colspec, body, wide=False):
    """Write table `name` with its caption from CAPTIONS. `body` holds the lines after the first \\hline (rows end
    with \\\\, separators are \\hline). Wide tables are scaled to the text width."""
    tabular = rf"\begin{{tabular}}{{{colspec}}}"
    lines = [r"\begin{table}[tbp]", r"\centering", rf"\caption{{{CAPTIONS[name]}}}", rf"\label{{{label}}}", r"\small",
             (r"\resizebox{\textwidth}{!}{" if wide else "") + tabular, r"\hline", *body,
             r"\end{tabular}" + ("}" if wide else ""), r"\end{table}"]
    (OUT / f"{name}.tex").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\n== {name}\n" + "\n".join(lines))


def main_methods(name, pct):
    """Rows of the main methods (MAIN) on one dataset at one budget, with their display label."""
    d = acc(name)
    d = d[d.method.isin(MAIN) & np.isclose(d.pct, pct)]
    return d.assign(label=d.method.map(MAIN))


# ---------------------------------------------------------------------------------------------------- Method 1
M1 = {"m1-cmm": "Method 1 CMM", "m1-fastagms": "Method 1 Fast-AGMS", "two-level": "Two-level",
      "reservoir": "Reservoir"}
M1_DATA = [("TPC-DS SF1", "tpcds"), ("GH Archive", "gh")]


def m1(tag, part):
    """Load a Method 1 result file for dataset tag and part (low or 5pct budgets), with q and miss."""
    return qerr(pd.read_csv(RES / f"method1_{tag}_{part}.csv"))


def method1_boxes(pct=1.0):
    """Box plots of Method 1 (with and without fallback row) against the baselines at one budget."""
    panels = []
    for dname, tag in M1_DATA:
        d = m1(tag, "low")
        d = d[np.isclose(d.pct, pct)]
        keep, notes = [d[~d.method.str.startswith("m1")]], {}
        short = {"m1-cmm": "CMM", "m1-fastagms": "Fast-AGMS"}   # Method 1 variants, the width is in the caption
        for meth in ("m1-cmm", "m1-fastagms"):
            g = d[d.method == meth]
            w = g.groupby("width").miss.sum().idxmin()      # width with the fewest misses is shown
            g = g[g.width == w]
            keep.append(g.assign(label=short[meth]))
            keep.append(qerr(g.assign(estimate=g.estimate_sp)).assign(label=short[meth] + ", fallback"))
        keep[0] = keep[0].assign(label=keep[0].method.map(M1))
        sub = pd.concat(keep)
        order = ["CMM", "CMM, fallback", "Fast-AGMS", "Fast-AGMS, fallback", "Two-level", "Reservoir"]
        panels.append((dname, "q-error (log)", sub, order, notes))
    grid(panels, 2, "method1_boxes_1pct", pad=30, width=6.5)


def method1_sweep():
    """2 x 2: rows TPC-DS / GH, columns misses / p90 q-error, against the width at 5 %."""
    fig, axes = plt.subplots(2, 2, figsize=(11, 7.4))
    styles = {("m1-cmm", 3): ("Method 1 CMM, 3 rows", "-", "o"), ("m1-cmm", 5): ("Method 1 CMM, 5 rows", "--", "o"),
              ("m1-fastagms", 3): ("Method 1 Fast-AGMS, 3 rows", "-", "s"),
              ("m1-fastagms", 5): ("Method 1 Fast-AGMS, 5 rows", "--", "s")}
    colors = {"m1-cmm": "0.15", "m1-fastagms": "#c62828"}
    for i, (dname, tag) in enumerate(M1_DATA):
        d = m1(tag, "5pct")
        sk = d[d.method.str.startswith("m1")]
        agg = sk.groupby(["method", "depth", "width", "k"]).agg(miss=("miss", "mean"),
                                                                 p90=("q", lambda s: s.quantile(0.9))).reset_index()
        for (meth, dep), (lab, ls, mk) in styles.items():
            g = agg[(agg.method == meth) & (agg.depth == dep)].sort_values("width")
            axes[i][0].plot(g.width, 100 * g.miss, ls=ls, marker=mk, color=colors[meth], label=lab, ms=4)
            axes[i][1].plot(g.width, g.p90, ls=ls, marker=mk, color=colors[meth], label=lab, ms=4)
            if meth == "m1-cmm" and dep == 3:
                for _, r in g.iterrows():
                    axes[i][0].annotate(f"k={int(r.k)}", (r.width, 100 * r.miss), textcoords="offset points",
                                        xytext=(0, 6), ha="center", fontsize=7 * FS, color="0.3")
        for base in ("reservoir", "two-level"):
            b = d[d.method == base]
            col = COLORS[M1[base]]
            axes[i][0].axhline(100 * b.miss.mean(), color=col, lw=1.2, ls=":", label=M1[base])
            axes[i][1].axhline(b.q.quantile(0.9), color=col, lw=1.2, ls=":", label=M1[base])
        for ax in axes[i]:
            ax.set_xscale("log", base=2)
            ax.set_xlabel("Width")
        axes[i][0].set_ylabel("Misses (% of answers)")
        axes[i][1].set_ylabel("p90 q-error (log)")
        axes[i][1].set_yscale("log")
        axes[i][0].set_title(f"{dname}: misses", fontsize=10 * FS)
        axes[i][1].set_title(f"{dname}: p90 q-error", fontsize=10 * FS)
    handles, labels = axes[0][1].get_legend_handles_labels()
    fig.legend(handles, labels, loc="lower center", ncol=3, frameon=False, bbox_to_anchor=(0.5, -0.04))
    fig.tight_layout(rect=(0, 0.07, 1, 1))
    save(fig, "method1_sweep_compact")


def method1_fallback_table():
    """Plain median of the rows against the smallest positive row when the median is <= 0 (paired, same runs), 1 %."""
    body = [r"Dataset & Space & Sketch, width & Median of rows & Smallest positive row \\", r"\hline"]
    for dname, tag in M1_DATA:
        d = m1(tag, "low")
        d = d[d.method.str.startswith("m1") & np.isclose(d.pct, 1.0)]
        for (pct, meth, w), g in d.groupby(["pct", "method", "width"]):
            sp = qerr(g.assign(estimate=g.estimate_sp))
            body.append(rf"{dname} & {pct:g}\% & {M1[meth][9:]}, {w} & {triple(g)} & {triple(sp)} \\")
        body.append(r"\hline")
    latex_table("table_method1_fallback", "tab:method1-fallback", "@{}lllcc@{}", body)


# ---------------------------------------------------------------------------------------------------- Methods 2 / 3
HEAD = [("TPC-DS SF1", "tpcds_sf1_mined", 0.5), ("GH Archive", "gharchive", 0.5), ("GH Archive", "gharchive", 0.25),
        ("IMDB", "imdb2_binned_mined", 0.5)]


def headline():
    """Box plots of the main methods at the low budgets in HEAD."""
    panels = [(f"{dn}, {p:g}%", "q-error (log)", main_methods(f, p), ORDER) for dn, f, p in HEAD]
    grid(panels, 2, "headline_boxes_low", width=5.5)


def bands():
    """Table of the main methods by query size band (share of the largest join size)."""
    body = []
    for dn, f, pct in HEAD:
        d = main_methods(f, pct)
        nq = {b: d[(d.sel >= lo) & (d.sel < hi)]["query"].nunique() for b, lo, hi in BANDS}
        body.append(rf"\multicolumn{{2}}{{@{{}}l}}{{\textbf{{{dn}, {pct:g}\%}}}} & "
                     rf"\makecell{{large ($\geq 10\%$)\\ {{[}}{nq['large']}{{]}}}} & "
                     rf"\makecell{{medium (0.1--10\%)\\ {{[}}{nq['medium']}{{]}}}} & "
                     rf"\makecell{{small ($< 0.1\%$)\\ {{[}}{nq['small']}{{]}}}} \\")
        for m in ORDER:
            g = d[d.label == m]
            body.append(rf" & {m} & " + " & ".join(triple(g[(g.sel >= lo) & (g.sel < hi)]) for _, lo, hi in BANDS)
                        + r" \\")
        body.append(r"\hline")
    latex_table("table_bands", "tab:bands", "@{}llccc@{}", body)


def budget_lines():
    """Median q-error, p90 q-error and misses against the space, main methods, TPC-DS, GH and IMDB."""
    data = [("TPC-DS SF1", "tpcds_sf1_mined"), ("GH Archive", "gharchive"), ("IMDB", "imdb2_binned_mined")]
    fig, axes = plt.subplots(3, 3, figsize=(13, 10.6))
    for i, (dn, f) in enumerate(data):
        d = acc(f)
        d = d[d.method.isin(MAIN)].assign(label=lambda x: x.method.map(MAIN))
        agg = d.groupby(["label", "pct"]).agg(med=("q", "median"), p90=("q", lambda s: s.quantile(0.9)),
                                              miss=("miss", "sum")).reset_index()
        for m in ORDER:
            g = agg[agg.label == m].sort_values("pct")
            for j, col in enumerate(("med", "p90", "miss")):
                axes[i][j].plot(g.pct, g[col], marker=MARKERS[m], color=COLORS[m], label=m, ms=5)
        for j, yl in enumerate(("Median q-error (log)", "p90 q-error (log)", "Misses (of 1000 answers)")):
            ax = axes[i][j]
            ax.set_xscale("log")
            ax.set_xticks([0.25, 0.5, 1, 5])
            ax.set_xticklabels(["0.25", "0.5", "1", "5"])
            ax.set_xlabel(SPACE)
            ax.set_ylabel(yl)
            if j < 2:
                ax.set_yscale("log")
            if j == 0:
                fm = plt.FuncFormatter(lambda v, _: f"{v:g}")
                ax.yaxis.set_major_formatter(fm)
                ax.yaxis.set_minor_formatter(fm)
            ax.set_title(dn, fontsize=10 * FS)
    handles, labels = axes[0][0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="lower center", ncol=4, frameon=False, bbox_to_anchor=(0.5, -0.02))
    fig.tight_layout(rect=(0, 0.035, 1, 1))
    save(fig, "accuracy_budget")


def timing():
    """Ingestion and query time against space on GH, and print the numbers behind the figure."""
    t = pd.read_csv(RES / "gharchive_timing_all.csv")   # all methods from one run, so timings are comparable
    t = t.assign(method=t.method.replace({"method2": "m2main", "method3-s10": "m3main"}))
    labels = {**MAIN, "method3": "Method 3, no samples"}
    t = t[t.method.isin(labels)].assign(label=lambda x: x.method.map(labels))
    m1t = pd.read_csv(RES / "method1_gh_timing.csv")   # Method 1, w = 4096, 3 rows, 10 seeds, one thread
    m1t = m1t[m1t.method == "m1-cmm"].assign(label="Method 1 (CMM)")
    t = pd.concat([t, m1t[["label", "pct", "seed", "ingest_ns", "query_ns"]]], ignore_index=True)
    COLORS["Method 1 (CMM)"], MARKERS["Method 1 (CMM)"] = "#8e6bb3", "v"
    COLORS["Method 3, no samples"], MARKERS["Method 3, no samples"] = "#e8846b", "d"
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(11, 3.9))
    for m in ["Method 1 (CMM)", "Method 2", "Method 3", "Method 3, no samples", "Two-level", "Reservoir"]:
        if m not in set(t.label):
            continue
        g = t[t.label == m]
        r = g.groupby(["pct", "seed"]).ingest_ns.first().groupby("pct").median() / 1e9
        a1.plot(r.index, r.values, marker=MARKERS[m], color=COLORS[m], label=m)
        q = g.groupby("pct").query_ns
        a2.plot(q.median().index, q.median().values / 1e6, marker=MARKERS[m], color=COLORS[m], label=f"{m}, median")
        a2.plot(q.quantile(0.9).index, q.quantile(0.9).values / 1e6, marker=MARKERS[m], color=COLORS[m], ls="--",
                label=f"{m}, p90")
    a1.set_ylabel("Ingestion Time (s)")
    a2.set_ylabel("Query Time (ms, log)")
    a2.set_yscale("log")
    for ax in (a1, a2):
        ax.set_xscale("log")
        ax.set_xticks([0.25, 0.5, 1, 5])
        ax.set_xticklabels(["0.25", "0.5", "1", "5"])
        ax.set_xlabel(SPACE)
    from matplotlib.lines import Line2D
    handles, labels = a1.get_legend_handles_labels()
    handles += [Line2D([], [], color="0.3", ls="-"), Line2D([], [], color="0.3", ls="--")]
    labels += ["median query time", "p90 query time"]
    fig.legend(handles, labels, loc="lower center", ncol=4, frameon=False, fontsize=9 * FS, bbox_to_anchor=(0.5, -0.02))
    fig.tight_layout(rect=(0, 0.14, 1, 1))
    save(fig, "timing_gh")
    print("\n== timing (median ingest s / median query ms / p90 query ms)")
    for (m, p), g in t.groupby(["label", "pct"]):
        ing = g.groupby("seed").ingest_ns.first().median() / 1e9
        print(f"  {m:10} {p:>5}%  {ing:6.2f} s  {g.query_ns.median() / 1e6:8.3f}  {g.query_ns.quantile(0.9) / 1e6:8.3f}")


# ---------------------------------------------------------------------------------------------------- ablations
BIAS = [("IMDB, year and kind on both relations", "imdb2_binned_mined"),
        ("IMDB, year and kind on movie_keyword only", "imdb2_binned_oneside_mined")]


def bias():
    """Box plots on IMDB with year and kind on both relations against on one relation only."""
    panels = [(dn, "q-error (log)", main_methods(f, 1.0), ORDER) for dn, f in BIAS]
    grid(panels, 2, "bias_boxes", width=5.5)


ABL = [("TPC-DS", "tpcds_sf1_mined"), ("GH Archive", "gharchive"), ("IMDB", "imdb2_binned_mined")]


def variant_table(name, label, variants, pcts=(0.5, 1.0), data=ABL):
    """Rows: dataset x space. Columns: variants (method name -> column title)."""
    body = ["Dataset & Space & " + " & ".join(variants.values()) + r" \\", r"\hline"]
    for dn, f in data:
        d = acc(f)
        for p in pcts:
            dp = d[np.isclose(d.pct, p)]
            body.append(rf"{dn} & {p:g}\% & " + " & ".join(triple(dp[dp.method == v]) for v in variants) + r" \\")
        body.append(r"\hline")
    # only wide tables are scaled to the text width
    latex_table(name, label, "@{}ll" + "c" * len(variants) + "@{}", body, wide=len(variants) >= 4)


def ablation_tables():
    """Tables for the sample share of Method 3 and the group count of Method 2."""
    variant_table("table_m3_samples", "tab:m3-samples",
                  {"method3": "0\\%", "method3-s5": "5\\%, $\\rho$", "method3-s10": "10\\%, $\\rho$",
                   "method3-s10-norho": "10\\%, no $\\rho$", "method3-s20": "20\\%, $\\rho$"})
    variant_table("table_m2_groups", "tab:groups-datasets",
                  {"method2-g1": "$G=1$", "method2-g16": "$G=16$", "method2": "$G=64$", "method2-g256": "$G=256$",
                   "method2-g512": "$G=512$"})


def lazy_table():
    """Table of Method 2 with lazy evaluation variants on GH: accuracy and median query time."""
    d = qerr(pd.read_csv(RES / "gharchive_lazy_g512_timing.csv"))
    names = {"none": "all groups (main)",
             "lazy0.25_COUNT_EACH_SIDE": "25\\% of groups: heavy groups + random sample",
             "lazy0.25_NONE": "25\\% of groups: random sample only",
             "lazy0.15_COUNT_EACH_SIDE": "15\\% of groups: heavy groups + random sample",
             "lazy0.15_NONE": "15\\% of groups: random sample only"}
    body = [r"Variant & Accuracy & Query time \\", r"\hline"]
    for v, lab in names.items():
        g = d[d.variant == v]
        body.append(rf"{lab} & {triple(g)} & {g.query_ns.median() / 1e6:.2f} ms \\")
    latex_table("table_lazy", "tab:lazy", "@{}lcc@{}", body + [r"\hline"])


def joincorr_table():
    """Join-correlated synthetic data (gen_joincorr.py): a0 = the bin of the key's number of matching tuples in the
    other stream, 10 queries a0 = 1..10, 5 %, 10 runs. Median estimate / true join per query."""
    d = pd.read_csv(RES / "joincorr" / "synthetic_joincorr.csv")
    d["ratio"] = d.estimate / d.exact
    ranges = ["1", "2--5", "6--10", "11--25", "26--50", "51--100", "101--150", "151--200", "201--300", "301--500"]
    meth = {"method2": "Method 2", "method3-s10": "Method 3", "two-level": "Two-level"}
    body = [r"$a_0$ & Matching tuples & True join & Method 2 & Method 3 & Two-level \\", r"\hline"]
    for b in range(1, 11):
        g = d[d["query"] == f"L_a0_{b}"]
        cells = [f"{g[g.method == m].ratio.median():.2f}" for m in meth]
        body.append(rf"{b} & {ranges[b - 1]} & {int(g.exact.iloc[0]):,} & ".replace(",", r"\,")
                    + " & ".join(cells) + r" \\")
    latex_table("table_joincorr", "tab:joincorr", "@{}cccccc@{}", body + [r"\hline"])


def rho_table():
    """Within-stream predicate correlation (gen_rho.py): a1 = a0 for 80 % of the tuples, 10 conjunction queries,
    5 %, 10 runs; median estimate / true join per query."""
    d = pd.read_csv(RES / "rho" / "synthetic_rho.csv")
    d["ratio"] = d.estimate / d.exact
    meth = {"method3": "No samples", "method3-s10": r"10\% + $\rho$", "method3-s10-norho": r"10\%, no $\rho$",
            "method2": "Method 2", "two-level": "Two-level"}
    body = ["Query & True join & " + " & ".join(meth.values()) + r" \\", r"\hline"]
    order = [(f"same_{v}", rf"$a_0={v} \wedge a_1={v}$") for v in range(1, 6)] + \
            [(f"diff_{v}_{v + 5}", rf"$a_0={v} \wedge a_1={v + 5}$") for v in range(1, 6)]
    for name, label in order:
        g = d[d["query"] == name]
        cells = [f"{g[g.method == m].ratio.median():.2f}" for m in meth]
        exact = f"{int(g.exact.iloc[0]):,}".replace(",", r"\,")
        body.append(f"{label} & {exact} & " + " & ".join(cells) + r" \\")
    latex_table("table_rho", "tab:rho", "@{}lrccccc@{}", body + [r"\hline"])


# ---------------------------------------------------------------------------------------------------- Method 3 rows
def m3_fallback_table():
    """Method 3 with 3 or 5 rows, with and without the smallest-positive-row fallback (RES/m3_fallback)."""
    cols = [(3, False, "3 rows"), (3, True, "3 rows, fallback"), (5, False, "5 rows"), (5, True, "5 rows, fallback")]
    body = ["Dataset & Space & " + " & ".join(c[2] for c in cols) + r" \\", r"\hline"]
    for dn, f in ABL:
        path = RES / "m3_fallback" / f"{f}.csv"
        if not path.exists():
            continue
        d = pd.read_csv(path)
        d = qerr(d[d.exact > 0])
        for p in sorted(d.pct.unique()):
            dp = d[np.isclose(d.pct, p)]
            cells = [triple(dp[(dp.rows == r) & (dp.fallback == fb)]) for r, fb, _ in cols]
            body.append(rf"{dn} & {p:g}\% & " + " & ".join(cells) + r" \\")
        body.append(r"\hline")
    latex_table("table_m3_fallback", "tab:m3-fallback", "@{}llcccc@{}", body, wide=True)


CONJ_DATA = [("GH Archive", "gharchive"), ("IMDB", "imdb2_binned_mined")]


def conj_table(pct=1.0):
    """Conjunction workload (RES/conj): error per kind of conjunction (predicates on the larger + smaller side)."""
    cols = [("method2", "Method 2"), ("method2-oracle", "Method 2, exact"), ("method3-s10", r"Method 3"), ("method3-s10-norho", r"Method 3, no $\rho$"),
            ("two-level", "Two-level"), ("reservoir", "Reservoir")]
    body = ["Dataset & Predicates & " + " & ".join(c[1] for c in cols) + r" \\", r"\hline"]
    for dn, f in CONJ_DATA:
        path = RES / "conj" / f"{f}.csv"
        if not path.exists():
            continue
        d = pd.read_csv(path, dtype={"pct": str})
        oracle = RES / "conj" / f"{f}_m2oracle.csv"   # Method 2's estimate computed exactly (scripts/m2_oracle.py)
        if oracle.exists():
            d = pd.concat([d, pd.read_csv(oracle, dtype={"pct": str})], ignore_index=True)
        d = d[d.pct != "all"].copy()
        d["pct"] = d.pct.astype(float)
        d = qerr(d[(d.exact > 0) & np.isclose(d.pct, pct)])
        d["kind"] = [f"{max(a, b)}+{min(a, b)}" for a, b in zip(d.nL, d.nR)]
        for kind in ["2+2", "3+1", "3+2", "3+3"]:
            dk = d[d.kind == kind]
            body.append(rf"{dn} & {kind} & " + " & ".join(triple(dk[dk.method == m]) for m, _ in cols) + r" \\")
        body.append(r"\hline")
    latex_table("table_conj", "tab:conj", "@{}llcccccc@{}", body, wide=True)


def m2_attrs_table(pct=1.0):
    """Method 2 (G = 1 and G = 512) and Method 3 on GH by the attribute a query filters on: groups help when the
    predicate's correlation sits in a few heavy keys, not when it is spread over many keys."""
    q = pd.read_csv(ROOT / "datasets" / "gharchive" / "queries.csv")
    names = {"L": ["hour", "weekday", "default_branch", "bot"], "R": ["type_id", "hour", "weekday", "bot"]}
    stream = {"L": "pushes", "R": "events"}

    def attrs(row):
        s = set()
        for side, col in (("L", "left_predicate"), ("R", "right_predicate")):
            if row[col] != "ALL":
                for c in row[col].split("&"):
                    s.add(f"{stream[side]}.{names[side][int(c.split('=')[0][1:])]}")
        return s

    amap = dict(zip(q["name"], q.apply(attrs, axis=1)))
    d = acc("gharchive")
    d = d[np.isclose(d.pct, pct)]
    rows = [("pushes.default_branch", "pushes: default branch"), ("pushes.bot", "pushes: bot"),
            ("events.hour", "events: hour"), ("events.type_id", "events: type"),
            ("pushes.hour", "pushes: hour"), ("pushes.weekday", "pushes: weekday")]
    cols = [("method2-g1", "Method 2, $G=1$"), ("method2-g512", "Method 2, $G=512$"), ("method3-s10", "Method 3")]
    body = ["Predicate on & Queries & " + " & ".join(c[1] for c in cols) + r" \\", r"\hline"]
    for a, label in rows:
        x = d[d["query"].map(lambda n: a in amap[n])]
        cells = []
        for m, _ in cols:
            y = x[x.method == m]
            cells.append(fmt(y.q.median(), 2))
        body.append(rf"{label.replace('_', ' ')} & {x['query'].nunique()} & " + " & ".join(cells) + r" \\")
    body.append(r"\hline")
    latex_table("table_m2_attrs", "tab:m2-attrs", "@{}lcccc@{}", body)


def hashed_bias_table():
    """Hashed cells vs independence (Method 2, G = 1) vs one cell per value, Zipf synthetic (RES/hashed_bias):
    misses kept apart from the error of the answers above zero, and the direction of the error."""
    files = {"0.10": RES / "hashed_bias" / "zipf_w0.10.csv", "0.30": RES / "hashed_bias" / "zipf_w0.30.csv"}
    if not all(p.exists() for p in files.values()):
        return
    a, b = (pd.read_csv(files[k], dtype={"pct": str}) for k in ("0.10", "0.30"))
    d = pd.concat([a[a.method == "method2"].assign(m="Independence"),
                   a[a.method == "method3-h16x3-rho"].assign(m="Hashed, 10\\% witnesses"),
                   b[b.method == "method3-h16x3-rho"].assign(m="Hashed, 30\\% witnesses"),
                   a[a.method == "method3-s10"].assign(m="One cell per value")])
    d = d[(d.pct != "all") & (d.exact > 0)].copy()
    d["pct"] = d.pct.astype(float)
    d["band"] = pd.cut(d.exact / d.exact.max(), [0, 1e-3, 1e-1, 1.01], labels=["small", "medium", "large"])
    methods = ["Independence", "Hashed, 10\\% witnesses", "Hashed, 30\\% witnesses", "One cell per value"]

    def cell(x):
        ok = x[x.estimate > 0]
        r = ok.estimate / ok.exact
        q = np.maximum(r, 1 / r)
        return f"{int((x.estimate <= 0).sum())} / {fmt(q.median(), 2)} / {fmt(r.median(), 2)}"

    body = [r"Space & Queries & " + " & ".join(methods) + r" \\", r"\hline"]
    for p in (1.0, 5.0):
        for band in ("large", "medium", "small"):
            x = d[np.isclose(d.pct, p) & (d.band == band)]
            nq = x["query"].nunique()
            body.append(rf"{p:g}\% & {band} [{nq}] & " + " & ".join(cell(x[x.m == m]) for m in methods) + r" \\")
        body.append(r"\hline")
    latex_table("table_hashed_bias", "tab:hashed-bias", "@{}llcccc@{}", body, wide=True)



def reservoir_positive_p90():
    """Prints the p90 q-error of reservoir sampling over its answers above zero (misses left out), per dataset and
    space consumption: the numbers quoted in the text next to the miss counts."""
    print("\nreservoir sampling, p90 q-error over answers above zero:")
    for label, name in (("TPC-DS", "tpcds_sf1_mined"), ("GH Archive", "gharchive"), ("IMDB", "imdb2_binned_mined")):
        d = acc(name)
        d = d[(d.method == "reservoir") & (d.estimate > 0)]
        row = ", ".join(f"{p:g}%: {d[np.isclose(d.pct, p)].q.quantile(0.9):.2f}" for p in sorted(d.pct.unique()))
        print(f"  {label}: {row}")


def main():
    sns.set_theme(style="whitegrid", font="serif")
    plt.rcParams.update({"font.size": 10 * FS, "axes.titlesize": 10 * FS, "axes.labelsize": 10 * FS,
                         "xtick.labelsize": 9 * FS, "ytick.labelsize": 9 * FS, "legend.fontsize": 9 * FS})
    method1_boxes()
    method1_sweep()
    method1_fallback_table()
    headline()
    bands()
    budget_lines()
    timing()
    bias()
    ablation_tables()
    lazy_table()
    joincorr_table()
    rho_table()
    m3_fallback_table()
    conj_table()
    hashed_bias_table()
    reservoir_positive_p90()
    m2_attrs_table()


if __name__ == "__main__":
    main()
