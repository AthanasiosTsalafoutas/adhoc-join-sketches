package com.adhocjoin.experiments;

import com.adhocjoin.compose.ComposedJoinSketch;
import com.adhocjoin.compose.Counters;
import com.adhocjoin.compose.Methods;
import com.adhocjoin.estimator.CellSampler;
import com.adhocjoin.estimator.JoinEstimator;
import com.adhocjoin.estimator.PreFilteredSketchEstimator;
import com.adhocjoin.estimator.ReservoirSamplingEstimator;
import com.adhocjoin.estimator.TwoLevelSamplingEstimator;
import com.adhocjoin.harness.Budget;
import com.adhocjoin.harness.Dataset;
import com.adhocjoin.harness.NamedQuery;
import com.adhocjoin.harness.Runs;
import com.adhocjoin.model.BinaryJoinQuery;
import com.adhocjoin.model.BinaryTuple;
import com.adhocjoin.sketch.Hashing;
import com.adhocjoin.sketch.JoinRows;
import com.adhocjoin.sketch.SketchConfig;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.LongFunction;

/**
 * Method 1 (Sampled-Cell Sketch) evaluation: the sampled sketch (reservoir cells, CMM or Fast-AGMS)
 * at every (width, depth) that fits the budget, against reservoir sampling and two-level sampling at the same budget.
 * With {@code -Doracle=true} also Method 1 with Count-Min ("m1-countmin") and the oracle (pre-filtered sketch) at every (width, depth):
 * a sketch of the same size built per query on the tuples that pass its predicates, so it has no sampling error. Its
 * counters give three rows per query: Fast-AGMS ("oracle"), Count-Min ("oracle-countmin") and CMM ("oracle-cmm").
 * Method 1 then runs at every (width, depth), with k = 1 where even that does not fit the budget.
 *
 * <p>args: dataDir leftTable rightTable pcts(csv) widths(csv) depths(csv) seeds threads out.csv [baselines=true]
 */
public final class Method1Bench {
    static final double TWO_LEVEL_Q = 0.2;

    /** One method at one size; {@code factory} is null for the oracle, which is built per query. */
    record Run(String method, double pct, int width, int depth, int k, LongFunction<JoinEstimator> factory) {
    }

    public static void main(String[] a) throws Exception {
        Dataset data = Dataset.load(a[0], a[1], a[2]);
        List<BinaryTuple> left = data.left(), right = data.right();
        List<NamedQuery> wl = data.queries();
        Map<String, Long> exact = data.exact();
        int la = data.leftAttributes(), ra = data.rightAttributes();
        long raw = data.rawBytes();
        int seeds = Integer.parseInt(a[6]), threads = Integer.parseInt(a[7]);
        boolean baselines = a.length <= 9 || Boolean.parseBoolean(a[9]);
        boolean oracle = Boolean.getBoolean("oracle");
        long[] masters = Runs.masterSeeds(0x1A1A2026L, seeds);

        List<Run> runs = new ArrayList<>();
        for (String ps : a[3].split(",")) {
            double pct = Double.parseDouble(ps);
            long budget = Math.round(raw * pct / 100.0);
            if (baselines) {
                int capL = (int) Math.round(left.size() * pct / 100.0), capR = (int) Math.round(right.size() * pct / 100.0);
                runs.add(new Run("reservoir", pct, 0, 0, Math.min(capL, capR), s -> new ReservoirSamplingEstimator(capL, capR, s)));
                double p = TwoLevelSamplingEstimator.rateForBudget(budget, pct / 100.0, TWO_LEVEL_Q, left, right);
                runs.add(new Run("two-level", pct, 0, 0, 0, s -> new TwoLevelSamplingEstimator(p, TWO_LEVEL_Q, s)));
            }
            for (String ws : a[4].split(",")) for (String ds : a[5].split(",")) {
                int w = Integer.parseInt(ws), d = Integer.parseInt(ds);
                List<Counters> variants = oracle ? List.of(Counters.CMM, Counters.FAST_AGMS_SPLIT, Counters.COUNT_MIN)
                        : List.of(Counters.CMM, Counters.FAST_AGMS_SPLIT);
                for (Counters counters : variants) {
                    int k = Budget.largestFittingPayload(budget, 1_000_000, c -> build(counters, w, d, c, la, ra, 1L));
                    // with the oracle every width keeps Method 1, at k = 1 even when that overruns the budget
                    if (oracle) k = Math.max(1, k);
                    else if (k < 1) continue;
                    int kRun = k;
                    runs.add(new Run(M1_NAMES.get(counters), pct, w, d, kRun, s -> build(counters, w, d, kRun, la, ra, s)));
                }
                if (oracle) runs.add(new Run("oracle", pct, w, d, 0, null));
            }
        }
        List<Callable<Void>> tasks = new ArrayList<>();
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(a[8])))) {
            // estimate = median of the rows; estimate_sp = the same sketch with the smallest positive row when that
            // median is <= 0 (paired: one sketch, both combinations); baselines repeat their estimate
            out.println("dataset,pct,method,width,depth,k,seed,query,nL,nR,exact,estimate,estimate_sp,ingest_ns,query_ns");
            for (Run r : runs) for (int s = 0; s < seeds; s++) {
                long seed = Hashing.mix64(masters[s] ^ Hashing.mix64(r.method.hashCode() * 31L + r.width * 7919L + r.depth)
                        ^ Double.doubleToLongBits(r.pct));
                int si = s;
                tasks.add(() -> {
                    JoinEstimator shared = r.factory == null ? null : r.factory.apply(seed);
                    long sharedIngest = shared == null ? 0 : data.ingest(shared);
                    StringBuilder sb = new StringBuilder();
                    for (NamedQuery nq : wl) {
                        if (shared == null) {   // the oracle: a new sketch on this query's filtered tuples
                            var o = oracle(nq.query(), r.width, r.depth, la, ra, seed);
                            long ingest = data.ingest(o);
                            long q0 = System.nanoTime();
                            double est = o.estimate(nq.query());
                            long qns = System.nanoTime() - q0;
                            row(sb, data, r, "oracle", si, nq, exact, est, est, ingest, qns);
                            double cm = o.countMinEstimate(), cmm = o.cmmEstimate();
                            row(sb, data, r, "oracle-countmin", si, nq, exact, cm, cm, ingest, qns);
                            row(sb, data, r, "oracle-cmm", si, nq, exact, cmm, cmm, ingest, qns);
                            continue;
                        }
                        long q0 = System.nanoTime();
                        double est = shared.estimate(nq.query());
                        long qns = System.nanoTime() - q0;
                        double sp = shared instanceof ComposedJoinSketch m1
                                ? JoinRows.medianOrSmallestPositive(m1.rowEstimates(nq.query())) : est;
                        row(sb, data, r, r.method, si, nq, exact, est, sp, sharedIngest, qns);
                    }
                    synchronized (out) { out.print(sb); out.flush(); }
                    System.out.printf(Locale.ROOT, "%s %.2f%% %s w%d d%d k=%d seed %d done%n", data.name(), r.pct,
                            r.method, r.width, r.depth, r.k, si);
                    return null;
                });
            }
            Runs.parallel(threads, tasks);
        }
    }

    /** Method 1's name per counter type in the output. */
    static final Map<Counters, String> M1_NAMES = Map.of(Counters.CMM, "m1-cmm", Counters.FAST_AGMS_SPLIT, "m1-fastagms",
            Counters.COUNT_MIN, "m1-countmin");

    /** One output line for one query. */
    private static void row(StringBuilder sb, Dataset data, Run r, String method, int seed, NamedQuery nq,
                            Map<String, Long> exact, double est, double sp, long ingest, long qns) {
        sb.append(String.format(Locale.ROOT, "%s,%.2f,%s,%d,%d,%d,%d,%s,%d,%d,%d,%.6g,%.6g,%d,%d%n", data.name(),
                r.pct, method, r.width, r.depth, r.k, seed, nq.name(),
                nq.query().leftPredicate().attributePredicates().size(),
                nq.query().rightPredicate().attributePredicates().size(), exact.get(nq.name()), est, sp, ingest, qns));
    }

    /** {@code -Dfallback=true}: smallest positive row when the median of the rows is <= 0 (as Methods 2 and 3). */
    static final boolean FALLBACK = Boolean.getBoolean("fallback");

    /** Method 1: one reservoir sample per cell, hash and sample seed both {@code seed}. */
    static JoinEstimator build(Counters counters, int w, int d, int k, int la, int ra, long seed) {
        var e = Methods.sampledCellSketch(new SketchConfig(w, d), la, ra, seed, seed, new CellSampler.Reservoir(k, seed),
                counters);
        return FALLBACK ? e.withSmallestPositiveFallback() : e;
    }

    /** The oracle sketch for {@code query}: {@code d x w} Fast-AGMS, hash seed {@code seed}. */
    static PreFilteredSketchEstimator oracle(BinaryJoinQuery query, int w, int d, int la, int ra, long seed) {
        return new PreFilteredSketchEstimator(query, new SketchConfig(w, d), la, ra, seed);
    }
}
