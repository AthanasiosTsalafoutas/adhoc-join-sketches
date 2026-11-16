package com.adhocjoin.source;

import com.adhocjoin.model.BinaryTuple;

import java.util.function.Consumer;

/** A replayable pair of tuple streams (left and right relation) for one dataset. */
public interface StreamSource {
    String name();

    /** Number of tuples in the left stream. */
    long leftSize();

    /** Number of tuples in the right stream. */
    long rightSize();

    /** Attributes per tuple (excluding the join key). */
    int attributeCount();

    default int leftAttributeCount() {
        return attributeCount();
    }

    default int rightAttributeCount() {
        return attributeCount();
    }

    /** Streams the left relation in order, once per call. */
    void forEachLeft(Consumer<BinaryTuple> consumer);

    /** Streams the right relation in order, once per call. */
    void forEachRight(Consumer<BinaryTuple> consumer);
}
