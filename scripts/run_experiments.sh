#!/bin/sh
# Runs the experiments of the thesis on the datasets built by scripts/prepare_datasets.sh.
#
#   sh scripts/run_experiments.sh                    all experiments
#   sh scripts/run_experiments.sh method1 timing     only the named ones
#
# Experiments: conj accuracy timing lazy method1 method1_oracle joincorr rho m3_fallback hashed_bias
# Results go to $RESULTS (default results/thesis), one CSV and one log per run.
# THREADS sets the parallel runs of the accuracy experiments (default 12). Timing runs always use one thread.
# Every run is seeded, so the same data gives the same numbers.
set -e
cd "$(dirname "$0")/.."
mvn -q compile
O=${RESULTS:-results/thesis}
T=${THREADS:-12}
X=com.adhocjoin.experiments
J="java -Xmx12g -cp target/classes"
P=0.25,0.5,1,5
mkdir -p "$O"

# Methods 2 and 3 against reservoir and two-level sampling, 10 seeds, with the variants of the ablation tables
# (number of groups, sample share, rho on/off, two-level q)
accuracy() {
  for ds in "tpcds_sf1_mined store_sales catalog_sales" "gharchive pushes events" \
            "imdb2_binned_mined movie_keyword movie_companies" \
            "imdb2_binned_oneside_mined movie_keyword movie_companies"; do
    set -- $ds
    java -Xmx12g -Dtl.q=0.2,0.1,0.5,1 -Dm2.groups=64,1,16,256,512 -cp target/classes $X.MainExperiments \
        datasets/$1 $2 $3 $P 10 $T $O/$1.csv 0,0.10,0.05,0.20 true > $O/$1.log 2>&1
  done
}

# ingestion and query time on GH Archive, one thread: Methods 2 and 3 and the baselines, then Method 1
timing() {
  java -Xmx12g -Dm2.groups=512 -cp target/classes $X.MainExperiments datasets/gharchive pushes events $P 10 1 \
      $O/gharchive_timing_all.csv 0,0.10 false > $O/gharchive_timing_all.log 2>&1
  $J $X.Method1Bench datasets/gharchive pushes events $P 4096 3 10 1 \
      $O/method1_gh_timing.csv false > $O/method1_gh_timing.log 2>&1
}

# Method 2 with and without lazy evaluation, GH Archive at 1 %, 512 groups, one thread
lazy() {
  $J $X.LazyEvaluationBench datasets/gharchive pushes events 1 512 10 1 \
      $O/gharchive_lazy_g512_timing.csv > $O/gharchive_lazy_g512_timing.log 2>&1
}

# Method 1: comparison below 1 % and the width x depth sweep at 5 %, 10 seeds
method1() {
  for ds in "tpcds_sf1_mined store_sales catalog_sales tpcds" "gharchive pushes events gh"; do
    set -- $ds
    $J $X.Method1Bench datasets/$1 $2 $3 0.25,0.5,1 1024,4096 3 10 $T $O/method1_$4_low.csv > $O/method1_$4_low.log 2>&1
    $J $X.Method1Bench datasets/$1 $2 $3 5 256,512,1024,2048,4096,8192,16384 3,5 10 $T \
        $O/method1_$4_5pct.csv > $O/method1_$4_5pct.log 2>&1
  done
}

# Method 1 against the oracle sketch (same width, built on the filtered tuples), CMM and Fast-AGMS, TPC-DS at 1 and
# 5 %, at the widths of the 5 % sweep, 10 seeds
method1_oracle() {
  $J -Doracle=true $X.Method1Bench datasets/tpcds_sf1_mined store_sales catalog_sales 1,5 256,512,1024,2048,4096,8192,16384 3 10 $T \
      $O/method1_oracle_tpcds.csv false > $O/method1_oracle_tpcds.log 2>&1
}

# predicate on one stream correlated with the join size on the other (synthetic), 5 %
joincorr() {
  mkdir -p $O/joincorr
  java -Xmx12g -Dm2.groups=512 -cp target/classes $X.MainExperiments datasets/synthetic_joincorr left right 5 10 $T \
      $O/joincorr/synthetic_joincorr.csv 0,0.10 false > $O/joincorr/synthetic_joincorr.log 2>&1
}

# correlated predicates within one stream, the case rho is for (synthetic), 5 %
rho() {
  mkdir -p $O/rho
  java -Xmx12g -Dm2.groups=512 -cp target/classes $X.MainExperiments datasets/synthetic_rho left right 5 10 $T \
      $O/rho/synthetic_rho.csv 0,0.10 true > $O/rho/synthetic_rho.log 2>&1
}

# Method 3 with 3 or 5 rows, with and without the smallest-positive-row fallback (how often the median row is
# not positive), 0.5 and 1 %, 10 seeds
m3_fallback() {
  mkdir -p $O/m3_fallback
  for ds in "tpcds_sf1_mined store_sales catalog_sales" "gharchive pushes events" \
            "imdb2_binned_mined movie_keyword movie_companies"; do
    set -- $ds
    java -Xmx12g -cp target/classes $X.Method3FallbackBench datasets/$1 $2 $3 0.5,1 10 $T \
        $O/m3_fallback/$1.csv > $O/m3_fallback/$1.log 2>&1
  done
}

# conjunction workload (queries_conj.csv, mined with mine_queries.py --conj 15): 15 two-sided queries per kind
# 2+2, 3+1, 3+2, 3+3 predicates, Methods 2 (G = 512) and 3 (with and without rho) and the baselines, 10 seeds, and the exact form of Method 2's estimate
conj() {
  mkdir -p $O/conj
  for ds in "gharchive pushes events" "imdb2_binned_mined movie_keyword movie_companies"; do
    set -- $ds
    java -Xmx12g -Dqueries=queries_conj.csv -Dm2.groups=512 -cp target/classes $X.MainExperiments \
        datasets/$1 $2 $3 0.5,1 10 $T $O/conj/$1.csv 0,0.10 true > $O/conj/$1.log 2>&1
    # Method 2's estimate computed exactly (exact group selectivities and joins, G = 512, 10 random groupings):
    # its error without sampling or sketch noise
    python scripts/m2_oracle.py datasets/$1 $2 $3 queries_conj.csv 512 10 $O/conj/$1_m2oracle.csv > $O/conj/$1_m2oracle.log 2>&1
  done
}

# bias of hashed cells against the independence assumption (Method 2 with G = 1) and one cell per value, on the
# 150-value synthetic with Zipf keys, 1 and 5 %, with 10 % and 30 % of the space for the witnesses, 10 seeds
hashed_bias() {
  mkdir -p $O/hashed_bias
  for share in 0.10 0.30; do
    java -Xmx12g -Dm2.groups=1 -Dm3.hashed=16x3 -Dm3.hashedShare=$share -cp target/classes $X.MainExperiments \
        datasets/synthetic_domain150_zipf left right 1,5 10 $T $O/hashed_bias/zipf_w$share.csv 0.10 false \
        > $O/hashed_bias/zipf_w$share.log 2>&1
  done
}

ALL="conj accuracy timing lazy method1 method1_oracle joincorr rho m3_fallback hashed_bias"
for step in ${*:-$ALL}; do
  case " $ALL " in
    *" $step "*) echo "$step: start $(date)"; $step; echo "$step: done $(date)" ;;
    *) echo "unknown experiment: $step (choose from: $ALL)"; exit 1 ;;
  esac
done
