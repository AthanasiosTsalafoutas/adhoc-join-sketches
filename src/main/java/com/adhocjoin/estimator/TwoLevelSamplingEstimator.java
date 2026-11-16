package com.adhocjoin.estimator;

import com.adhocjoin.model.BinaryJoinQuery;
import com.adhocjoin.model.BinaryTuple;
import com.adhocjoin.model.RelationSide;
import com.adhocjoin.model.TuplePredicate;
import com.adhocjoin.sketch.Hashing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Baseline: two-level sampling. Join keys are sampled with probability p (level one). For each sampled key and
 * relation, one "sentry" tuple (the one with the smallest sentry hash) is always kept and the other tuples are kept
 * with probability q (level two). The filtered frequency of a key is estimated as the sentry (0 or 1 after the
 * predicate) plus the number of matching sampled tuples divided by q, and the join estimate is the sum over sampled
 * keys of left times right frequency, divided by p.
 */
public final class TwoLevelSamplingEstimator implements JoinEstimator {
    private final double p;
    private final double q;
    private final long keySeed;
    private final long sentrySeed;
    private final long sampleSeed;
    private final Map<Integer, KeyState> states = new HashMap<>();

    /** @param p probability that a join key is sampled, in [0, 1]
     *  @param q probability that a non-sentry tuple of a sampled key is kept, in (0, 1] */
    public TwoLevelSamplingEstimator(double p, double q, long seed) {
        if (p < 0.0 || p > 1.0) {
            throw new IllegalArgumentException("p must be in [0,1]");
        }
        if (q <= 0.0 || q > 1.0) {
            throw new IllegalArgumentException("q must be in (0,1]");
        }
        this.p = p;
        this.q = q;
        this.keySeed = seed ^ 0xA11CE5EEDL;
        this.sentrySeed = seed ^ 0xC0FFEE5EEDL;
        this.sampleSeed = seed ^ 0x1234ABCD5678EF90L;
    }

    @Override
    public String name() {
        return "uniform-two-level-sampling";
    }


    @Override
    public void updateLeft(BinaryTuple tuple) {
        update(tuple, RelationSide.LEFT);
    }

    @Override
    public void updateRight(BinaryTuple tuple) {
        update(tuple, RelationSide.RIGHT);
    }

    /** Sums, over the sampled keys, the estimated filtered left frequency times right frequency and divides by p. */
    @Override
    public double estimate(BinaryJoinQuery query) {
        if (p == 0.0) {
            return 0.0;
        }
        double sampled = 0.0;
        for (KeyState state : states.values()) {
            double leftFrequency = state.left.estimateFrequency(query.leftPredicate(), q);
            double rightFrequency = state.right.estimateFrequency(query.rightPredicate(), q);
            sampled += leftFrequency * rightFrequency;
        }
        return sampled / p;
    }

    /** The estimate restricted to keys for which {@code skipKey} is false. */
    public double estimate(BinaryJoinQuery query, java.util.function.IntPredicate skipKey) {
        if (p == 0.0) {
            return 0.0;
        }
        double sampled = 0.0;
        for (Map.Entry<Integer, KeyState> entry : states.entrySet()) {
            if (skipKey.test(entry.getKey())) {
                continue;
            }
            KeyState state = entry.getValue();
            sampled += state.left.estimateFrequency(query.leftPredicate(), q)
                    * state.right.estimateFrequency(query.rightPredicate(), q);
        }
        return sampled / p;
    }

    @Override
    public long logicalPayloadBytes() {
        return logicalPayload().totalBytes();
    }

    @Override
    public LogicalPayload logicalPayload() {
        LogicalPayload.Builder builder = LogicalPayload.builder()
                .add("selected join keys", states.size() * 4L);
        for (Map.Entry<Integer, KeyState> entry : states.entrySet()) {
            builder.add("per-key relation state", entry.getValue().stateBytes());
            builder.add("retained sentry attributes", entry.getValue().sentryTupleBytes());
            builder.add("sampled non-sentry attributes", entry.getValue().sampledTupleBytes());
        }
        return builder.build();
    }

    @Override
    public long retainedTupleCount() {
        long count = 0L;
        for (KeyState state : states.values()) {
            count += state.retainedTupleCount();
        }
        return count;
    }

    private void update(BinaryTuple tuple, RelationSide side) {
        // level one: ignore tuples whose join key was not sampled (the decision depends only on the key)
        if (!selectedKey(tuple.joinKey())) {
            return;
        }
        KeyState state = states.computeIfAbsent(tuple.joinKey(), ignored -> new KeyState());
        state.relation(side).add(tuple, sentryHash(side, tuple), sampleSelected(side, tuple));
    }

    private boolean selectedKey(int key) {
        return Hashing.unitInterval(Hashing.hash(keySeed, key)) < p;
    }

    // the tuple with the smallest sentry hash of a (key, side) is its sentry
    private long sentryHash(RelationSide side, BinaryTuple tuple) {
        return Hashing.hash(sentrySeed ^ side.ordinal(), tuple.recordId());
    }

    // level two: keep the tuple with probability q, decided by a hash of its record id
    private boolean sampleSelected(RelationSide side, BinaryTuple tuple) {
        long hash = Hashing.hash(sampleSeed ^ side.ordinal(), tuple.recordId());
        return Hashing.unitInterval(hash) < q;
    }

    private static final class KeyState {
        private final RelationState left = new RelationState();
        private final RelationState right = new RelationState();

        private RelationState relation(RelationSide side) {
            return side == RelationSide.LEFT ? left : right;
        }

        private long stateBytes() {
            return left.stateBytes() + right.stateBytes();
        }

        private long sentryTupleBytes() {
            return left.sentryTupleBytes() + right.sentryTupleBytes();
        }

        private long sampledTupleBytes() {
            return left.sampledTupleBytes() + right.sampledTupleBytes();
        }

        private long retainedTupleCount() {
            return left.retainedTupleCount() + right.retainedTupleCount();
        }
    }

    private static final class RelationState {
        private long seen;
        private long sentryHash = Long.MAX_VALUE;
        private BinaryTuple sentry;
        private boolean sentrySampled;
        private final List<BinaryTuple> sampledNonSentries = new ArrayList<>();

        private void add(BinaryTuple tuple, long tupleSentryHash, boolean sampled) {
            seen++;
            if (sentry == null || Long.compareUnsigned(tupleSentryHash, sentryHash) < 0) {
                // new sentry. The old one goes back to the ordinary sample only if the q-sample had selected it
                if (sentry != null && sentrySampled) {
                    sampledNonSentries.add(sentry);
                }
                sentry = tuple;
                sentryHash = tupleSentryHash;
                sentrySampled = sampled;
                return;
            }
            if (sampled) {
                sampledNonSentries.add(tuple);
            }
        }

        // sentry counts 1 if it matches, sampled tuples count 1 / q each
        private double estimateFrequency(TuplePredicate predicate, double q) {
            double frequency = sentry != null && predicate.test(sentry) ? 1.0 : 0.0;
            if (q == 0.0) {
                return frequency;
            }
            long sampledMatches = 0L;
            for (BinaryTuple tuple : sampledNonSentries) {
                if (predicate.test(tuple)) {
                    sampledMatches++;
                }
            }
            return frequency + sampledMatches / q;
        }

        // charged per (key, side): the count seen (8 B), the sentry hash (8 B) and the sampled flag (1 B)
        private long stateBytes() {
            return 8L + 8L + 1L;
        }

        private long sentryTupleBytes() {
            return sentry == null ? 0L : 4L * sentry.attributeCount();
        }

        private long sampledTupleBytes() {
            long bytes = 0L;
            for (BinaryTuple tuple : sampledNonSentries) {
                bytes += 4L * tuple.attributeCount();
            }
            return bytes;
        }

        private long retainedTupleCount() {
            return (sentry == null ? 0L : 1L) + sampledNonSentries.size();
        }
    }

    /** Key rate {@code p} whose payload is about {@code budget} bytes: starts at {@code nominalRate} and rescales it
     *  twice by budget / payload measured on a probe run (seed 1) over both relations. Result is in [1e-6, 1]. */
    public static double rateForBudget(long budget, double nominalRate, double q, List<BinaryTuple> left,
                                       List<BinaryTuple> right) {
        double rate = nominalRate;
        for (int pass = 0; pass < 2; pass++) {
            TwoLevelSamplingEstimator probe = new TwoLevelSamplingEstimator(rate, q, 1L);
            left.forEach(probe::updateLeft);
            right.forEach(probe::updateRight);
            long payload = probe.logicalPayloadBytes();
            if (payload <= 0) {
                return rate;
            }
            rate = Math.min(1.0, Math.max(1e-6, rate * (budget / (double) payload)));
        }
        return rate;
    }
}
