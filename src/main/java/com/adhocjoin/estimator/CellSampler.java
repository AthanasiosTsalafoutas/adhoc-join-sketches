package com.adhocjoin.estimator;

import com.adhocjoin.model.BinaryTuple;
import com.adhocjoin.model.TuplePredicate;

import java.util.List;

/**
 * Per-cell sampling strategy for Method 1 (Sampled-Cell Sketch). A sampler decides which tuples a sketch cell
 * retains. Every cell keeps an exact {@code count} plus its retained tuples, and a predicate's filtered mass in the cell
 * is estimated as {@code qualifying / sampleSize * count}.
 */
public interface CellSampler {
    /** A fresh, empty cell using this strategy. */
    PayloadCell<BinaryTuple> newCell();

    /** The per-cell retention cap. */
    int capacity();

    /** Short strategy tag for estimator names, e.g. {@code "reservoir"}. */
    String tag();

    /** Filtered mass of {@code predicate} in {@code cell}: {@code qualifying / sampleSize * count}, or exact
     *  {@code qualifying} when the cell never overflowed its capacity. */
    static double filteredMass(PayloadCell<BinaryTuple> cell, TuplePredicate predicate) {
        long count = cell.count();
        List<BinaryTuple> payloads = cell.payloads();
        int size = payloads.size();
        if (count == 0L || size == 0) {
            return 0.0;
        }
        int qualifying = 0;
        for (int i = 0; i < size; i++) {
            if (predicate.test(payloads.get(i))) {
                qualifying++;
            }
        }
        return scaledCount(qualifying, size, count);
    }

    /** A uniform sample's estimate of how many of the cell's {@code count} records match: {@code matched / sampled
     *  * count}, or exact {@code matched} when the cell never overflowed (the sample is the whole cell). */
    static double scaledCount(int matched, int sampled, long count) {
        if (count <= sampled) {
            return matched;
        }
        return matched * (count / (double) sampled);
    }

    /** Reservoir sample (Algorithm R) per cell. Each cell has its own random stream, seeded from {@code seed} and the
     *  cell's creation order, so samples are independent across cells. A sampled tuple costs its attributes only. */
    final class Reservoir implements CellSampler {
        private final int capacity;
        private final java.util.SplittableRandom seeds;

        public Reservoir(int capacity, long seed) {
            if (capacity < 0) {
                throw new IllegalArgumentException("capacity must be non-negative");
            }
            this.capacity = capacity;
            this.seeds = new java.util.SplittableRandom(seed ^ 0x5245534552564FL);
        }

        @Override
        public synchronized PayloadCell<BinaryTuple> newCell() {
            return new ReservoirCell<>(capacity, seeds.nextLong());
        }

        @Override
        public int capacity() {
            return capacity;
        }

        @Override
        public String tag() {
            return "reservoir";
        }
    }
}
