package com.adhocjoin.estimator;

import com.adhocjoin.model.BinaryJoinQuery;
import com.adhocjoin.model.BinaryTuple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Baseline: one uniform reservoir sample of whole tuples per relation. Predicates and the join are evaluated on the
 * samples at query time and the sampled join size is scaled up by the inverse sampling fraction of each side.
 * It needs no predicate knowledge at ingestion, but few sampled tuples survive a selective predicate.
 */
public final class ReservoirSamplingEstimator implements JoinEstimator {
    private final Reservoir left;
    private final Reservoir right;

    public ReservoirSamplingEstimator(int capacityPerSide, long seed) {
        this(capacityPerSide, capacityPerSide, seed);
    }

    /** Separate capacities (in tuples) for the two sides, for relations of different size. */
    public ReservoirSamplingEstimator(int leftCapacity, int rightCapacity, long seed) {
        if (leftCapacity < 0 || rightCapacity < 0) {
            throw new IllegalArgumentException("capacities must be non-negative");
        }
        this.left = new Reservoir(leftCapacity, seed ^ 0x51EEDL);
        this.right = new Reservoir(rightCapacity, seed ^ 0xB16B00B5L);
    }

    @Override
    public String name() {
        return "reservoir-sampling";
    }


    /** Offers the tuple to the left reservoir. Algorithm R: the first capacity tuples are kept, then the n-th tuple
     *  replaces a random slot with probability capacity / n. */
    @Override
    public void updateLeft(BinaryTuple tuple) {
        left.add(tuple);
    }

    /** Offers the tuple to the right reservoir (see {@link #updateLeft}). */
    @Override
    public void updateRight(BinaryTuple tuple) {
        right.add(tuple);
    }

    /**
     * Applies each predicate to its sample, joins the surviving sampled tuples on the join key and scales the count
     * by (tuples seen / sample size) of each side. Returns 0 if either side has no qualifying sampled tuple.
     */
    @Override
    public double estimate(BinaryJoinQuery query) {
        if (left.seen == 0 || right.seen == 0 || left.sample.isEmpty() || right.sample.isEmpty()) {
            return 0.0;
        }
        // hash join of the samples: count the qualifying right sample tuples per join key,
        // then look up each qualifying left sample tuple
        Map<Integer, Integer> rightQualifyingByKey = new HashMap<>();
        int rightQualifying = 0;
        for (BinaryTuple r : right.sample) {
            if (query.rightPredicate().test(r)) {
                rightQualifying++;
                rightQualifyingByKey.merge(r.joinKey(), 1, Integer::sum);
            }
        }
        long sampledJoin = 0L;
        int leftQualifying = 0;
        for (BinaryTuple l : left.sample) {
            if (query.leftPredicate().test(l)) {
                leftQualifying++;
                sampledJoin += rightQualifyingByKey.getOrDefault(l.joinKey(), 0);
            }
        }
        if (leftQualifying == 0 || rightQualifying == 0) {
            return 0.0;
        }
        // each sampled tuple stands for seen / sample size tuples of its relation
        double leftScale = left.seen / (double) left.sample.size();
        double rightScale = right.seen / (double) right.sample.size();
        return sampledJoin * leftScale * rightScale;
    }

    @Override
    public long logicalPayloadBytes() {
        return logicalPayload().totalBytes();
    }

    @Override
    public LogicalPayload logicalPayload() {
        return LogicalPayload.builder()
                .add("left retained join keys and attributes", left.tupleBytes())
                .add("right retained join keys and attributes", right.tupleBytes())
                .build();
    }

    @Override
    public long retainedTupleCount() {
        return left.sample.size() + right.sample.size();
    }

    private static final class Reservoir {
        private final int capacity;
        private final SplittableRandom random;
        private final List<BinaryTuple> sample = new ArrayList<>();
        private long seen;

        private Reservoir(int capacity, long seed) {
            this.capacity = capacity;
            this.random = new SplittableRandom(seed);
        }

        private void add(BinaryTuple tuple) {
            seen++;
            if (capacity == 0) {
                return;
            }
            if (sample.size() < capacity) {
                sample.add(tuple);
                return;
            }
            // full: keep this tuple with probability capacity / seen, in a uniformly chosen slot
            long slot = random.nextLong(seen);
            if (slot < capacity) {
                sample.set((int) slot, tuple);
            }
        }

        private long tupleBytes() {
            long bytes = 0L;
            for (BinaryTuple tuple : sample) {
                bytes += tuple.logicalPayloadBytes();
            }
            return bytes;
        }
    }
}
