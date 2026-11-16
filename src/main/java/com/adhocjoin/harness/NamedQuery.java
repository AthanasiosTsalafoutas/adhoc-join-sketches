package com.adhocjoin.harness;

import com.adhocjoin.model.BinaryJoinQuery;
import com.adhocjoin.model.TuplePredicate;

/** A join query with a stable name used as the key in results, CSVs, and printed summaries. */
public record NamedQuery(String name, BinaryJoinQuery query) {
    public static NamedQuery of(String name, TuplePredicate left, TuplePredicate right) {
        return new NamedQuery(name, BinaryJoinQuery.of(left, right));
    }
}
