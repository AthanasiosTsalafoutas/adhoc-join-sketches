package com.adhocjoin.model;

/** Equality predicate on one attribute of a tuple. The join key is addressable as attribute index {@code attributeCount()}. */
public record AttributePredicate(int attributeIndex, int value) {
    public static AttributePredicate equalTo(int attributeIndex, int value) {
        return new AttributePredicate(attributeIndex, value);
    }

    public boolean test(BinaryTuple tuple) {
        return tuple.attribute(attributeIndex) == value;
    }
}
