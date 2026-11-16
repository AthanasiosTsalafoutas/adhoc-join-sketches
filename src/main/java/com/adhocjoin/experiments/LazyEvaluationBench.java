package com.adhocjoin.experiments;

import com.adhocjoin.compose.ComposedJoinSketch;
import com.adhocjoin.compose.ComposedJoinSketch.HeavyGroups;
import com.adhocjoin.compose.Methods;
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

/**
 * Method 2 (Key-Partitioned Sketch, Fast-AGMS, sized as in MainExperiments) with several variants evaluated on the same built
 * sketch, so they are paired per seed. Each group takes the median of its own rows and the scaled group estimates are
 * summed ("none"). Lazy evaluation at fractions 0.25 and 0.15: the groups with the largest tuple counts (top 5% of
 * each side) are always evaluated, and the rest of the fraction is a random sample of the other groups, scaled up.
 * NONE samples the whole fraction from all groups (baseline).
 *
 * <p>args: dataDir leftTable rightTable pcts(csv) groups(csv) seeds threads out.csv
 */
public final class LazyEvaluationBench {
    static final int ROWS = 3, GROUP_BINS = 2048;
    static final double[] FRACTIONS = {0.25, 0.15};
    static final HeavyGroups[] LAZY = {HeavyGroups.COUNT_EACH_SIDE, HeavyGroups.NONE};

    public static void main(String[] a) throws Exception {
        Dataset d = Dataset.load(a[0], a[1], a[2]);
        List<BinaryTuple> left = d.left(), right = d.right();
        List<NamedQuery> wl = d.queries();
        Map<String, Long> exact = d.exact();
        int la = d.leftAttributes(), ra = d.rightAttributes();
        int seeds = Integer.parseInt(a[5]), threads = Integer.parseInt(a[6]);
        long raw = d.rawBytes();
        long tupleBytes = (4L + 4L * la) + (4L + 4L * ra);
        String ds = d.name();
        long[] masters = Runs.masterSeeds(0xFA11BAC4L, seeds);

        List<Callable<Void>> tasks = new ArrayList<>();
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(a[7])))) {
            out.println("dataset,pct,G,k,seed,query,exact,variant,estimate,query_ns");
            for (String ps : a[3].split(",")) {
                double pct = Double.parseDouble(ps);
                long budget = Math.round(raw * pct / 100.0);
                for (String gs : a[4].split(",")) {
                    int g = Integer.parseInt(gs);
                    int k = (int) ((budget - 2L * ROWS * GROUP_BINS * 4L - 2L * g * 4L) / (g * tupleBytes));
                    if (k < 1) {
                        System.out.printf(Locale.ROOT, "%s %.2f%% G=%d: does not fit, skipped%n", ds, pct, g);
                        continue;
                    }
                    for (int s = 0; s < seeds; s++) {
                        int si = s;
                        long seed = Hashing.mix64(masters[s] ^ Hashing.mix64(g) ^ Double.doubleToLongBits(pct));
                        tasks.add(() -> {
                            ComposedJoinSketch e = Methods.keyPartitionedSketch(g, GROUP_BINS / g, ROWS, k, la, ra, seed, true);
                            d.ingest(e);
                            StringBuilder sb = new StringBuilder();
                            String head = String.format(Locale.ROOT, "%s,%.2f,%d,%d,%d,", ds, pct, g, k, si);
                            for (NamedQuery nq : wl) {
                                String row = head + nq.name() + "," + exact.get(nq.name()) + ",";
                                long t0 = System.nanoTime();
                                double full = e.estimate(nq.query());
                                put(sb, row, "none", full, System.nanoTime() - t0);
                                for (double f : FRACTIONS) {
                                    for (HeavyGroups by : LAZY) {
                                        long t1 = System.nanoTime();
                                        double est = e.estimate(nq.query(), f, by);
                                        long tl = System.nanoTime() - t1;
                                        put(sb, row, String.format(Locale.ROOT, "lazy%.2f_%s", f, by), est, tl);
                                    }
                                }
                            }
                            synchronized (out) {
                                out.print(sb);
                                out.flush();
                            }
                            System.out.printf(Locale.ROOT, "%s %.2f%% G=%d k=%d seed %d done%n", ds, pct, g, k, si);
                            return null;
                        });
                    }
                }
            }
            Runs.parallel(threads, tasks);
        }
    }

    private static void put(StringBuilder sb, String row, String variant, double estimate, long ns) {
        sb.append(row).append(variant).append(',').append(String.format(Locale.ROOT, "%.6g", estimate))
                .append(',').append(ns).append('\n');
    }
}
