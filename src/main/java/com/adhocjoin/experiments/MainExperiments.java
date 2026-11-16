package com.adhocjoin.experiments;

import com.adhocjoin.compose.Methods;
import com.adhocjoin.compose.Partition;
import com.adhocjoin.estimator.JoinEstimator;
import com.adhocjoin.estimator.ReservoirSamplingEstimator;
import com.adhocjoin.estimator.TwoLevelSamplingEstimator;
import com.adhocjoin.harness.Dataset;
import com.adhocjoin.harness.NamedQuery;
import com.adhocjoin.harness.Runs;
import com.adhocjoin.model.BinaryTuple;
import com.adhocjoin.sketch.Hashing;

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
 * Comparison of Methods 2 and 3 against reservoir and two-level sampling at equal byte
 * budgets, with ingestion and per-query timing. Method 3 (Attribute-Partitioned Sketch): direct (observed) cells, {@value #M3_ROWS} rows, smallest
 * positive row when the median is <= 0. Variants of two-level (q) and Method 2 (G) via the system properties
 * {@code tl.q} and {@code m2.groups}. Method 2: {@value #M2_ROWS} rows, each group the median of its rows, groups summed.
 *
 * Method 3 runs once per sample share in {@code m3Shares} (share of the budget for the witnesses, with rho; 0 = no
 * samples). Share 0 is named "method3" (all of the budget for the join sketches, no rho), the others
 * "method3-sNN" (NN = percent). With {@code noRho=true} the first share with samples is also run with its
 * samples kept but rho never applied ("method3-sNN-norho"), so the width lost to the samples and the effect of rho can
 * be separated.
 *
 * <p>args: dataDir leftTable rightTable pcts(csv) seeds threads out.csv [m3Shares(csv)=0] [noRho=false]
 */
public final class MainExperiments {
    static final int M3_ROWS = 5, M2_ROWS = 3, GROUP_BINS = 2048;
    /** Two-level per-key tuple rates q (p then follows from the budget); the first is the main "two-level", the
     *  others "two-level-qNN" (NN = percent; q = 1 is correlated sampling). {@code -Dtl.q=0.2,0.1,0.5,1}. */
    static final String[] TWO_LEVEL_QS = System.getProperty("tl.q", "0.2").split(",");
    /** Method 2 group counts (2048 bins per row in total); the first is the main "method2", the others "method2-gNN".
     *  {@code -Dm2.groups=64,16,256,512}. */
    static final String[] M2_GROUPS = System.getProperty("m2.groups", "64").split(",");
    /** Method 3 with hashed cells with several values per cell, as "method3-hWOxRO" (WO predicate cells per hashed row,
     *  RO rows, for every attribute); {@code -Dm3.hashed=16x3,32x3}, none by default. Share of the budget for the k-min
     *  samples that give the value counts: {@code -Dm3.hashedShare=0.10}. Each runs without rho and with rho
     *  ("method3-hWOxRO-rho"). */
    static final String[] M3_HASHED = System.getProperty("m3.hashed", "").isEmpty() ? new String[0]
            : System.getProperty("m3.hashed").split(",");
    static final double M3_HASHED_SHARE = Double.parseDouble(System.getProperty("m3.hashedShare", "0.10"));

    record Run(String method, double pct, String detail, LongFunction<JoinEstimator> factory) {
    }

    public static void main(String[] a) throws Exception {
        Dataset d = Dataset.load(a[0], a[1], a[2]);
        List<BinaryTuple> left = d.left(), right = d.right();
        List<NamedQuery> wl = d.queries();
        Map<String, Long> exact = d.exact();
        int la = d.leftAttributes(), ra = d.rightAttributes();
        int seeds = Integer.parseInt(a[4]), threads = Integer.parseInt(a[5]);
        long raw = d.rawBytes();
        Partition.AttributeCells cells = Partition.AttributeCells.observed(left, la, right, ra);
        int nCells = cells.left().cellCount() + cells.right().cellCount();
        int predCells = nCells - 2;   // the two unfiltered cells keep no samples
        String[] m3Shares = (a.length > 7 ? a[7] : "0").split(",");
        boolean noRho = a.length > 8 && Boolean.parseBoolean(a[8]);
        String ds = d.name();
        long[] masters = Runs.masterSeeds(0xF17A1L, seeds);

        List<Run> runs = new ArrayList<>();
        for (String ps : a[3].split(",")) {
            boolean noRhoDone = false;
            double pct = Double.parseDouble(ps);
            long budget = Math.round(raw * pct / 100.0);
            for (int si = 0; si < m3Shares.length; si++) {
                String ss = m3Shares[si];
                // share of the budget for the k-min record-hash samples of the predicate cells (4 B per hash);
                // with samples the estimate also applies the witness overlap rho
                double share = Double.parseDouble(ss);
                int k = share > 0 ? (int) (Math.round(budget * share) / (4L * predCells)) : 0;
                long sketchBytes = budget - 4L * k * predCells - 4L * nCells;
                int w3 = (int) (sketchBytes / (4L * M3_ROWS * nCells));
                if (w3 < 1 || (share > 0 && k < 1)) {
                    System.out.printf(Locale.ROOT, "skip method3 %.2f%% share %.2f (w=%d k=%d)%n", pct, share, w3, k);
                    continue;
                }
                String name = k == 0 ? "method3" : String.format(Locale.ROOT, "method3-s%d", Math.round(share * 100));
                String detail = "w=" + w3 + " d=" + M3_ROWS + " k=" + k;
                runs.add(new Run(name, pct, detail, s ->
                        Methods.attributePartitionedSketch(cells, w3, M3_ROWS, k, s).withSmallestPositiveFallback()));
                if (noRho && k > 0 && !noRhoDone) {
                    noRhoDone = true;
                    runs.add(new Run(name + "-norho", pct, detail, s ->
                            Methods.attributePartitionedSketch(cells, w3, M3_ROWS, k, s).withSmallestPositiveFallback().withoutRho()));
                }
            }
            for (String hc : M3_HASHED) {
                // hashed cells: every attribute gets ro hashed rows of wo cells; the value counts need the k-min
                // samples (witness intersection over the rows), so part of the budget goes to them; the same samples give rho
                String[] p = hc.split("x");
                int wo = Integer.parseInt(p[0]), ro = Integer.parseInt(p[1]);
                int hCells = 2 + (la + ra) * wo * ro;
                int hk = (int) (Math.round(budget * M3_HASHED_SHARE) / (4L * (hCells - 2)));
                int hw = (int) ((budget - 4L * hk * (hCells - 2) - 4L * hCells) / (4L * M3_ROWS * hCells));
                if (hk < 1 || hw < 1) {
                    System.out.printf(Locale.ROOT, "skip method3-h%s %.2f%% (w=%d k=%d)%n", hc, pct, hw, hk);
                    continue;
                }
                String hd = "omni=" + hc + " w=" + hw + " d=" + M3_ROWS + " k=" + hk;
                runs.add(new Run("method3-h" + hc, pct, hd, s ->
                        Methods.hashedAttributePartitionedSketch(la, ra, wo, ro, hw, M3_ROWS, hk, s)
                                .withSmallestPositiveFallback().withoutRho()));
                runs.add(new Run("method3-h" + hc + "-rho", pct, hd, s ->
                        Methods.hashedAttributePartitionedSketch(la, ra, wo, ro, hw, M3_ROWS, hk, s)
                                .withSmallestPositiveFallback()));
            }
            long tupleBytes = (4L + 4L * la) + (4L + 4L * ra);
            for (int gi = 0; gi < M2_GROUPS.length; gi++) {
                int g = Integer.parseInt(M2_GROUPS[gi]);
                int sub = GROUP_BINS / g;
                int k = (int) ((budget - 2L * M2_ROWS * GROUP_BINS * 4L - 2L * g * 4L) / (g * tupleBytes));
                if (k >= 1 && sub >= 2) {
                    runs.add(new Run(gi == 0 ? "method2" : "method2-g" + g, pct, "G=" + g + " k=" + k, s ->
                            Methods.keyPartitionedSketch(g, sub, M2_ROWS, k, la, ra, s, true)));
                }
            }
            int capL = (int) Math.round(left.size() * pct / 100.0), capR = (int) Math.round(right.size() * pct / 100.0);
            runs.add(new Run("reservoir", pct, "capL=" + capL + " capR=" + capR,
                    s -> new ReservoirSamplingEstimator(capL, capR, s)));
            for (int qi = 0; qi < TWO_LEVEL_QS.length; qi++) {
                double q = Double.parseDouble(TWO_LEVEL_QS[qi]);
                double p = TwoLevelSamplingEstimator.rateForBudget(budget, pct / 100.0, q, left, right);
                runs.add(new Run(qi == 0 ? "two-level" : "two-level-q" + Math.round(q * 100), pct,
                        String.format(Locale.ROOT, "q=%s p=%.5f", TWO_LEVEL_QS[qi], p),
                        s -> new TwoLevelSamplingEstimator(p, q, s)));
            }
        }

        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(a[6])))) {
            out.println("dataset,pct,method,detail,seed,ingest_ns,query,nL,nR,exact,estimate,query_ns");
            List<Callable<Void>> tasks = new ArrayList<>();
            for (Run r : runs) for (int s = 0; s < seeds; s++) {
                long seed = Hashing.mix64(masters[s] ^ Hashing.mix64(r.method.hashCode()) ^ Double.doubleToLongBits(r.pct));
                int si = s;
                tasks.add(() -> {
                    JoinEstimator e = r.factory.apply(seed);
                    long ingest = d.ingest(e);
                    StringBuilder sb = new StringBuilder();
                    for (NamedQuery nq : wl) {
                        long q0 = System.nanoTime();
                        double est = e.estimate(nq.query());
                        long qns = System.nanoTime() - q0;
                        sb.append(String.format(Locale.ROOT, "%s,%.2f,%s,\"%s\",%d,%d,%s,%d,%d,%d,%.6g,%d%n", ds, r.pct,
                                r.method, r.detail, si, ingest, nq.name(),
                                nq.query().leftPredicate().attributePredicates().size(),
                                nq.query().rightPredicate().attributePredicates().size(), exact.get(nq.name()), est, qns));
                    }
                    synchronized (out) { out.print(sb); out.flush(); }
                    System.out.printf(Locale.ROOT, "%s %.2f%% %s %s seed %d done (ingest %.1f s)%n", ds, r.pct, r.method,
                            r.detail, si, ingest / 1e9);
                    return null;
                });
            }
            Runs.parallel(threads, tasks);
        }
    }
}
