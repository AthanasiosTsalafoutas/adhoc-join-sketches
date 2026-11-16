package com.adhocjoin.experiments;

import com.adhocjoin.compose.Methods;
import com.adhocjoin.compose.Partition;
import com.adhocjoin.estimator.JoinEstimator;
import com.adhocjoin.harness.Dataset;
import com.adhocjoin.harness.NamedQuery;
import com.adhocjoin.harness.Runs;
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
 * Method 3 (Attribute-Partitioned Sketch: one cell per value, 10% of the space for the witnesses, rho) with 3 or 5 rows,
 * with and without the smallest-positive-row fallback. The same seeds are used with and without the fallback, so the
 * two differ only in the answers whose median row is not positive. Shows how often that happens and what the
 * fallback does to them (the "rows and fallback" ablation).
 *
 * <p>args: dataDir leftTable rightTable pcts(csv) seeds threads out.csv
 */
public final class Method3FallbackBench {
    static final double SAMPLE_SHARE = 0.10;

    public static void main(String[] a) throws Exception {
        Dataset d = Dataset.load(a[0], a[1], a[2]);
        List<NamedQuery> wl = d.queries();
        Map<String, Long> exact = d.exact();
        int seeds = Integer.parseInt(a[4]), threads = Integer.parseInt(a[5]);
        long raw = d.rawBytes();
        Partition.AttributeCells cells = Partition.AttributeCells.observed(d.left(), d.leftAttributes(), d.right(), d.rightAttributes());
        int nCells = cells.left().cellCount() + cells.right().cellCount();
        int predCells = nCells - 2;   // the two unfiltered cells keep no samples
        long[] masters = Runs.masterSeeds(0xF17A1L, seeds);
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(a[6])))) {
            out.println("dataset,pct,rows,fallback,seed,query,exact,estimate");
            List<Callable<Void>> tasks = new ArrayList<>();
            for (String ps : a[3].split(",")) {
                double pct = Double.parseDouble(ps);
                long budget = Math.round(raw * pct / 100.0);
                int k = (int) (Math.round(budget * SAMPLE_SHARE) / (4L * predCells));
                for (int rows : new int[]{3, 5}) {
                    // same budget accounting as MainExperiments: samples, one counter per cell, then the join sketches
                    int w = (int) ((budget - 4L * k * predCells - 4L * nCells) / (4L * rows * nCells));
                    for (boolean fallback : new boolean[]{true, false}) {
                        for (int s = 0; s < seeds; s++) {
                            long seed = Hashing.mix64(masters[s] ^ Hashing.mix64(("m3r" + rows).hashCode())
                                    ^ Double.doubleToLongBits(pct));
                            int si = s;
                            tasks.add(() -> {
                                var sketch = Methods.attributePartitionedSketch(cells, w, rows, k, seed);
                                JoinEstimator e = fallback ? sketch.withSmallestPositiveFallback() : sketch;
                                d.ingest(e);
                                StringBuilder sb = new StringBuilder();
                                for (NamedQuery nq : wl) {
                                    sb.append(String.format(Locale.ROOT, "%s,%.2f,%d,%b,%d,%s,%d,%.6g%n", d.name(), pct,
                                            rows, fallback, si, nq.name(), exact.get(nq.name()), e.estimate(nq.query())));
                                }
                                synchronized (out) { out.print(sb); out.flush(); }
                                System.out.printf(Locale.ROOT, "%s %.2f%% rows=%d fallback=%b seed %d done%n", d.name(),
                                        pct, rows, fallback, si);
                                return null;
                            });
                        }
                    }
                }
            }
            Runs.parallel(threads, tasks);
        }
    }
}
