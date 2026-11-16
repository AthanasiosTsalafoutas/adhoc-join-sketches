package com.adhocjoin.compose;

import com.adhocjoin.estimator.LogicalPayload;
import com.adhocjoin.estimator.PayloadCell;
import com.adhocjoin.estimator.ReservoirCell;
import com.adhocjoin.model.BinaryTuple;
import com.adhocjoin.model.TuplePredicate;

import java.util.List;

/** One reservoir sample (Algorithm R) of tuples per part, shared by every row; each part has its own random stream
 *  derived from the side's seed. */
final class GroupSampleSide implements GroupSamples.CounterMasses, GroupSamples.PartSelectivities {
    private final int parts;
    private final int depth;
    private final int partWidth;
    private final int rowWidth;

    private final Selectivity.GroupSample spec;
    private final long recordSeed;
    private final PayloadCell<BinaryTuple>[] samples;

    @SuppressWarnings({"unchecked", "rawtypes"})
    GroupSampleSide(Selectivity.GroupSample spec, boolean leftSide, GroupSamples.Grid grid) {
        this.parts = grid.parts();
        this.depth = grid.depth();
        this.partWidth = grid.partWidth();
        this.rowWidth = grid.rowWidth();
        this.spec = spec;
        this.recordSeed = leftSide ? spec.leftRecordSeed() : spec.rightRecordSeed();
        this.samples = new PayloadCell[parts];
        // one independent random stream per part, derived from the side's seed
        java.util.SplittableRandom seeds = new java.util.SplittableRandom(recordSeed);
        for (int p = 0; p < samples.length; p++) {
            samples[p] = new ReservoirCell<>(spec.capacity(), seeds.nextLong());
        }
    }

    @Override
    public void add(BinaryTuple tuple, int part, int[] slots) {
        // reservoir sample of the part, shared by all rows: count the tuple, then let the reservoir decide to keep it
        PayloadCell<BinaryTuple> cell = samples[part];
        cell.touch();
        cell.retain(0L, tuple);
    }

    @Override
    public long partCount(int part) {
        return samples[part].count();
    }

    @Override
    public double[] partSelectivity(TuplePredicate predicate, int[] evaluated) {
        // fraction of each part's sample that matches the predicate
        double[] out = new double[evaluated.length];
        for (int i = 0; i < evaluated.length; i++) {
            out[i] = matchingFraction(samples[evaluated[i]].payloads(), predicate);
        }
        return out;
    }

    /** Every counter of a part times the part's matching fraction. */
    @Override
    public double[][] mass(TuplePredicate predicate, long[][] counterRows) {
        double[][] mass = new double[depth][rowWidth];
        for (int p = 0; p < parts; p++) {
            List<BinaryTuple> ts = samples[p].payloads();
            if (ts.isEmpty()) {
                continue;
            }
            // the sample gives the matching fraction of the whole part: scale every counter of the part by it
            double partFraction = matchingFraction(ts, predicate);
            int base = p * partWidth;
            for (int row = 0; row < depth; row++) {
                for (int c = base; c < base + partWidth; c++) {
                    mass[row][c] = counterRows[row][c] * partFraction;
                }
            }
        }
        return mass;
    }

    private static double matchingFraction(List<BinaryTuple> ts, TuplePredicate predicate) {
        if (ts.isEmpty()) {
            return 0.0;
        }
        int matched = 0;
        for (BinaryTuple t : ts) {
            if (predicate.test(t)) {
                matched++;
            }
        }
        return matched / (double) ts.size();
    }

    @Override
    public long retained() {
        long total = 0L;
        for (PayloadCell<BinaryTuple> cell : samples) {
            total += cell.sampleSize();
        }
        return total;
    }

    @Override
    public void payload(LogicalPayload.Builder b, String side, int attributeCount) {
        long perTuple = 4L + 4L * attributeCount;
        b.add("part tuple counts (" + side + ")", parts * Counters.BYTES);
        b.add("part tuple samples (" + side + ")", (long) parts * spec.capacity() * perTuple);
    }
}
