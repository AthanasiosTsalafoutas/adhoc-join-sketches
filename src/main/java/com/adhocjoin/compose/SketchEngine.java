package com.adhocjoin.compose;

import com.adhocjoin.estimator.LogicalPayload;
import com.adhocjoin.model.BinaryJoinQuery;
import com.adhocjoin.model.BinaryTuple;

/** The storage and query logic behind a {@link ComposedJoinSketch}: key partitions or value cells. */
sealed interface SketchEngine permits KeyPartitionEngine, AttributePartitionEngine {
    /** Adds one tuple to the left or right side. */
    void update(boolean leftSide, BinaryTuple tuple);

    /** Estimated size of the filtered join. */
    double estimate(BinaryJoinQuery query);

    void payload(LogicalPayload.Builder b);

    long retainedTupleCount();

    /** When the row combination is still <= 0, use the smallest positive row instead (0 if none). */
    void useSmallestPositiveFallback();
}
