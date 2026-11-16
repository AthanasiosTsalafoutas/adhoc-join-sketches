package com.adhocjoin.model;

/** A join-size query: the number of join pairs whose left tuple passes {@code leftPredicate} and right tuple passes {@code rightPredicate}. */
public record BinaryJoinQuery(TuplePredicate leftPredicate, TuplePredicate rightPredicate) {
    public static BinaryJoinQuery of(TuplePredicate leftPredicate, TuplePredicate rightPredicate) {
        return new BinaryJoinQuery(leftPredicate, rightPredicate);
    }

    /** The unfiltered join. */
    public static BinaryJoinQuery all() {
        return new BinaryJoinQuery(TuplePredicate.all(), TuplePredicate.all());
    }
}
