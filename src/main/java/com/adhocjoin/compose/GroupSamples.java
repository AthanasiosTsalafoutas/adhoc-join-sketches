package com.adhocjoin.compose;

import com.adhocjoin.estimator.LogicalPayload;
import com.adhocjoin.model.BinaryTuple;
import com.adhocjoin.model.TuplePredicate;

/**
 * The tuple samples behind the counters of a key partition, one per side, created from the {@link Selectivity}. A
 * sample gives every counter its own filtered mass ({@link CounterMasses}), one selectivity per part ({@link
 * PartSelectivities}), or both.
 */
sealed interface GroupSamples permits GroupSamples.CounterMasses, GroupSamples.PartSelectivities {
    /** {@code slots[row]}: the counter index (part base included) the tuple updated in each row. */
    void add(BinaryTuple tuple, int part, int[] slots);

    /** Number of tuples the sample keeps (0 when it keeps record hashes only). */
    long retained();

    /** Adds the bytes of this side's samples to the storage report. */
    void payload(LogicalPayload.Builder b, String side, int attributeCount);

    /** A filtered mass for every counter. */
    non-sealed interface CounterMasses extends GroupSamples {
        /** [row][counter] filtered mass. */
        double[][] mass(TuplePredicate predicate, long[][] counters);
    }

    /** One selectivity per part. */
    non-sealed interface PartSelectivities extends GroupSamples {
        /** Selectivity of each of the {@code evaluated} parts. */
        double[] partSelectivity(TuplePredicate predicate, int[] evaluated);

        /** Tuples seen by a part. */
        long partCount(int part);
    }

    /** The counter layout the samples belong to: {@code parts} parts of {@code partWidth} counters per row. */
    record Grid(int parts, int depth, int partWidth) {
        int rowWidth() {
            return parts * partWidth;
        }
    }

    /** The sample structure for one side that the {@code selectivity} asks for. */
    static GroupSamples of(Selectivity selectivity, Grid grid, boolean leftSide) {
        return switch (selectivity) {
            case Selectivity.SampledCells c -> new SampledCellSide(c, leftSide, grid);
            case Selectivity.GroupSample p -> new GroupSampleSide(p, leftSide, grid);
            case Selectivity.CellWitnesses c -> throw new IllegalArgumentException("key partitions need cell or part samples");
        };
    }
}
