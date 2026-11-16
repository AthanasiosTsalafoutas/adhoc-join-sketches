package com.adhocjoin.estimator;

import com.adhocjoin.model.BinaryJoinQuery;
import com.adhocjoin.model.BinaryTuple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ground truth: stores every tuple of both sides and computes the exact filtered join size with a hash join at query
 * time. Used to measure the error of the sketches.
 */
public final class ExactFilteredJoinEstimator implements JoinEstimator {
    private final List<BinaryTuple> left = new ArrayList<>();
    private final List<BinaryTuple> right = new ArrayList<>();

    @Override
    public String name() {
        return "exact-filtered-binary-join";
    }


    @Override
    public void updateLeft(BinaryTuple tuple) {
        left.add(tuple);
    }

    @Override
    public void updateRight(BinaryTuple tuple) {
        right.add(tuple);
    }

    @Override
    public double estimate(BinaryJoinQuery query) {
        // count the filtered left tuples per join key, then probe with the filtered right tuples
        Map<Integer, Long> leftCounts = new HashMap<>();
        for (BinaryTuple tuple : left) {
            if (query.leftPredicate().test(tuple)) {
                leftCounts.merge(tuple.joinKey(), 1L, Long::sum);
            }
        }
        long joinCount = 0L;
        for (BinaryTuple tuple : right) {
            if (query.rightPredicate().test(tuple)) {
                joinCount += leftCounts.getOrDefault(tuple.joinKey(), 0L);
            }
        }
        return joinCount;
    }

    @Override
    public long logicalPayloadBytes() {
        return logicalPayload().totalBytes();
    }

    @Override
    public LogicalPayload logicalPayload() {
        return LogicalPayload.builder()
                .add("retained left join keys and attributes", tupleBytes(left))
                .add("retained right join keys and attributes", tupleBytes(right))
                .build();
    }

    @Override
    public long retainedTupleCount() {
        return left.size() + right.size();
    }

    private static long tupleBytes(List<BinaryTuple> tuples) {
        long bytes = 0L;
        for (BinaryTuple tuple : tuples) {
            bytes += tuple.logicalPayloadBytes();
        }
        return bytes;
    }
}
