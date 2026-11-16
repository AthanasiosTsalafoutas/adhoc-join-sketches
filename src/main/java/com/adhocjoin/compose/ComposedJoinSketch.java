package com.adhocjoin.compose;

import com.adhocjoin.estimator.JoinEstimator;
import com.adhocjoin.estimator.LogicalPayload;
import com.adhocjoin.model.BinaryJoinQuery;
import com.adhocjoin.model.BinaryTuple;

/**
 * A join estimator assembled from four primitives: a {@link Partition} of each relation into parts, the {@link
 * KeyHashing} of the join sketch in every part, the {@link Counters} that sketch keeps, and the {@link Selectivity}
 * source for predicates. {@link Methods} has the three thesis methods as recipes. Every method is built through this
 * one class; the work is done by its engine: {@link KeyPartitionEngine} for key partitions (Methods 1 and 2) or
 * {@link AttributePartitionEngine} for value cells (Method 3).
 */
public final class ComposedJoinSketch implements JoinEstimator {
    /** GLOBAL: unsigned rows are one debiased CMM estimate over all buckets, signed rows the sum of the parts' inner
     *  products. PER_GROUP: each part is estimated on its own (unsigned: with its own collision correction, since
     *  keys only collide inside their part); with one selectivity per part, each part takes the median of its own rows
     *  and the scaled part estimates are summed. */
    public enum GroupRule {GLOBAL, PER_GROUP}

    /** Which parts lazy evaluation always evaluates: the largest by tuple count on each side (known from the part
     *  counters), or NONE (only a random sample of the parts, as a baseline). */
    public enum HeavyGroups {COUNT_EACH_SIDE, NONE}

    /** Method 3's per-query quantities: the anchor join, the join-weighted value, rho, and {@code estimate} (what
     *  {@link #estimate} returns). */
    public record CellBreakdown(double anchor, double jw, double rho, double estimate) {
    }

    /** Share of the parts that lazy evaluation always evaluates (chosen by {@link HeavyGroups}). */
    public static final double HEAVY_SHARE = 0.05;
    /** Sampled matches of a conjunction needed before rho is trusted (fewer: rho = 1). */
    public static final int MIN_JOINT = 5;

    private final Partition partition;
    private final KeyHashing hashing;
    private final Counters counters;
    private final Selectivity selectivity;
    private final SketchEngine engine;

    /** Value cells (Method 3): attribute counts come from the schemas; the part rule does not apply. */
    public ComposedJoinSketch(Partition.AttributeCells partition, KeyHashing hashing, Selectivity.CellWitnesses selectivity) {
        this(partition, hashing, Counters.FAST_AGMS, selectivity, GroupRule.GLOBAL, partition.left().attributeCount(),
                partition.right().attributeCount());
    }

    /** Key partitions (Methods 1 and 2) or value cells; the attribute counts are the number of attributes per tuple
     *  on each side and must be positive. */
    public ComposedJoinSketch(Partition partition, KeyHashing hashing, Counters counters, Selectivity selectivity,
                              GroupRule rule, int leftAttributeCount, int rightAttributeCount) {
        if (leftAttributeCount <= 0 || rightAttributeCount <= 0) {
            throw new IllegalArgumentException("attribute counts must be positive");
        }
        this.partition = partition;
        this.hashing = hashing;
        this.counters = counters;
        this.selectivity = selectivity;
        // the partition decides which engine does the work
        this.engine = switch (partition) {
            case Partition.Keys k -> new KeyPartitionEngine(k, hashing, counters, selectivity, rule,
                    leftAttributeCount, rightAttributeCount);
            case Partition.AttributeCells v -> {
                if (counters != Counters.FAST_AGMS || !(selectivity instanceof Selectivity.CellWitnesses witnessSpec)) {
                    throw new IllegalArgumentException("value cells need Fast-AGMS counters and CellWitnesses selectivity");
                }
                yield new AttributePartitionEngine(v, hashing, witnessSpec);
            }
        };
    }

    /** Turns on the smallest-positive-row fallback for every row combination (Methods 1 and 3): a median over the
     *  rows that is not positive becomes the smallest positive row. Method 2 takes each group's own median, so this
     *  changes nothing there. */
    public ComposedJoinSketch withSmallestPositiveFallback() {
        engine.useSmallestPositiveFallback();
        return this;
    }

    /** Method 3 ablation: keeps the k-min samples (and charges their bytes) but never applies rho. */
    public ComposedJoinSketch withoutRho() {
        cells().disableRho();
        return this;
    }

    @Override
    public String name() {
        // name = partition, counters, sketch size and selectivity source
        String p = partition instanceof Partition.Keys k ? (k.groups() == 1 ? "single" : "groups=" + k.groups())
                : "value-cells";
        String s = switch (selectivity) {
            case Selectivity.SampledCells c -> "cell-" + c.sampler().tag() + "=" + c.sampler().capacity();
            case Selectivity.GroupSample ps -> "part-sample=" + ps.capacity();
            case Selectivity.CellWitnesses c -> "cell-witnessSpec=" + c.capacity();
        };
        return "composed[" + p + "," + counters.name().toLowerCase().replace('_', '-') + ",d=" + hashing.depth()
                + ",w=" + hashing.width() + "," + s + "]";
    }

    @Override
    public void updateLeft(BinaryTuple tuple) {
        engine.update(true, tuple);
    }

    @Override
    public void updateRight(BinaryTuple tuple) {
        engine.update(false, tuple);
    }

    @Override
    public double estimate(BinaryJoinQuery query) {
        return engine.estimate(query);
    }

    /** Lazy evaluation of about {@code fraction} of the parts (one selectivity per key part only), the {@code heavy}
     *  parts always evaluated; {@code fraction >= 1} is the full estimate. */
    public double estimate(BinaryJoinQuery query, double fraction, HeavyGroups heavy) {
        return keys().estimate(query, fraction, heavy);
    }

    @Override
    public long logicalPayloadBytes() {
        return logicalPayload().totalBytes();
    }

    @Override
    public LogicalPayload logicalPayload() {
        LogicalPayload.Builder b = LogicalPayload.builder();
        engine.payload(b);
        return b.build();
    }

    @Override
    public long retainedTupleCount() {
        return engine.retainedTupleCount();
    }

    /** Key partitions with per-counter masses: the per-row sums before the median. */
    public double[] rowEstimates(BinaryJoinQuery query) {
        return keys().rowEstimates(query);
    }

    /** Value cells: the anchor join, join-weighted value, rho and estimate of one query. */
    public CellBreakdown cellBreakdown(BinaryJoinQuery query) {
        return cells().breakdown(query);
    }

    private KeyPartitionEngine keys() {
        if (engine instanceof KeyPartitionEngine k) {
            return k;
        }
        throw new UnsupportedOperationException("only key partitions have row estimates and lazy evaluation");
    }

    private AttributePartitionEngine cells() {
        if (engine instanceof AttributePartitionEngine c) {
            return c;
        }
        throw new UnsupportedOperationException("only value cells have a cell breakdown");
    }
}
