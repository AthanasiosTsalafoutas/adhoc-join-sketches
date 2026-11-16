package com.adhocjoin.estimator;

import com.adhocjoin.model.BinaryTuple;
import com.adhocjoin.model.BinaryJoinQuery;

/**
 * A streaming join-size estimator. Tuples of both relations are fed one by one with {@link #updateLeft} and
 * {@link #updateRight}, and {@link #estimate} answers a filtered join query afterwards from what was kept.
 */
public interface JoinEstimator {
    String name();

    /** Processes one tuple of the left relation. */
    void updateLeft(BinaryTuple tuple);

    /** Processes one tuple of the right relation. */
    void updateRight(BinaryTuple tuple);

    /** Estimated size of the join of the filtered relations. Predicates are applied after ingestion. */
    double estimate(BinaryJoinQuery query);

    /** Space used by the retained state, in bytes, under the thesis accounting (4 bytes per counter or hash). */
    long logicalPayloadBytes();

    /** The same space split into named components. */
    default LogicalPayload logicalPayload() {
        return LogicalPayload.of("total", logicalPayloadBytes());
    }

    /** Number of whole tuples kept, for estimators that store tuples (0 otherwise). */
    default long retainedTupleCount() {
        return 0L;
    }
}
