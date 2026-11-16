package com.adhocjoin.model;

import java.util.Arrays;

/**
 * One stream tuple: a record id, an integer join key and integer attributes. The join key can also be read as a virtual
 * last attribute at index {@link #attributeCount()}.
 */
public record BinaryTuple(long recordId, int joinKey, int[] attributes) {
    public BinaryTuple {
        attributes = Arrays.copyOf(attributes, attributes.length);
    }

    @Override
    public int[] attributes() {
        return Arrays.copyOf(attributes, attributes.length);
    }

    public int attribute(int index) {
        return index == attributes.length ? joinKey : attributes[index];
    }

    public int attributeCount() {
        return attributes.length;
    }

    /** Space charged when a whole tuple is stored: 4 bytes for the join key and 4 per attribute. */
    public long logicalPayloadBytes() {
        return 4L + 4L * attributes.length;
    }
}
