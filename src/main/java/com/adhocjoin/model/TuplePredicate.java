package com.adhocjoin.model;

import java.util.Arrays;
import java.util.List;

/**
 * A filter on tuples of one relation. Predicates built with the factory methods also describe themselves (kind and
 * attribute equalities), so sketches can index them. A plain lambda is opaque and can only be tested tuple by tuple.
 */
@FunctionalInterface
public interface TuplePredicate {
    boolean test(BinaryTuple tuple);

    /** How much is known about the predicate's structure. */
    default Kind kind() {
        return Kind.OPAQUE;
    }

    /** The attribute equalities of a conjunctive predicate, empty for ALL and OPAQUE. */
    default List<AttributePredicate> attributePredicates() {
        return List.of();
    }

    /** Predicate that accepts every tuple. */
    static TuplePredicate all() {
        return new DescribedPredicate(Kind.ALL, List.of()) {
            @Override
            public boolean test(BinaryTuple tuple) {
                return true;
            }
        };
    }

    /** Predicate {@code attribute[attributeIndex] == value}. */
    static TuplePredicate attributeEquals(int attributeIndex, int value) {
        AttributePredicate predicate = AttributePredicate.equalTo(attributeIndex, value);
        return new DescribedPredicate(Kind.ATTRIBUTES, List.of(predicate)) {
            @Override
            public boolean test(BinaryTuple tuple) {
                return predicate.test(tuple);
            }
        };
    }

    /** Conjunction. It stays describable only if every part is describable. */
    static TuplePredicate and(TuplePredicate... predicates) {
        boolean opaque = Arrays.stream(predicates).anyMatch(predicate -> predicate.kind() == Kind.OPAQUE);
        List<AttributePredicate> attributes = Arrays.stream(predicates)
                .flatMap(predicate -> predicate.attributePredicates().stream())
                .toList();
        Kind kind = opaque ? Kind.OPAQUE : (attributes.isEmpty() ? Kind.ALL : Kind.ATTRIBUTES);
        return new DescribedPredicate(kind, attributes) {
            @Override
            public boolean test(BinaryTuple tuple) {
                for (TuplePredicate predicate : predicates) {
                    if (!predicate.test(tuple)) {
                        return false;
                    }
                }
                return true;
            }
        };
    }

    /** ALL accepts everything, ATTRIBUTES is a conjunction of attribute equalities, OPAQUE is anything else. */
    enum Kind {
        ALL,
        ATTRIBUTES,
        OPAQUE
    }

    abstract class DescribedPredicate implements TuplePredicate {
        private final Kind kind;
        private final List<AttributePredicate> attributePredicates;

        private DescribedPredicate(Kind kind, List<AttributePredicate> attributePredicates) {
            this.kind = kind;
            this.attributePredicates = List.copyOf(attributePredicates);
        }

        @Override
        public Kind kind() {
            return kind;
        }

        @Override
        public List<AttributePredicate> attributePredicates() {
            return attributePredicates;
        }
    }
}
