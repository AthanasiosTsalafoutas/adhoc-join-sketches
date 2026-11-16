package com.adhocjoin.source;

import com.adhocjoin.model.BinaryTuple;
import com.adhocjoin.sketch.Hashing;

import java.util.ArrayList;
import java.util.List;

/** In-memory stream source, either generated uniformly from a seed or built from given tuple lists (used in tests and toy examples). */
public final class SyntheticStreamSource implements StreamSource {
    private final String name;
    private final List<BinaryTuple> left;
    private final List<BinaryTuple> right;

    /** Generates both sides with join keys uniform on [0, keyDomain) and attributes uniform on [0, 1000), all hashed from the seed. */
    public SyntheticStreamSource(String name, int leftRows, int rightRows, int keyDomain, int attributeCount,
                                 long seed) {
        if (leftRows < 0 || rightRows < 0 || keyDomain <= 0 || attributeCount < 0) {
            throw new IllegalArgumentException("invalid synthetic source shape");
        }
        this.name = name;
        this.left = List.copyOf(generate(leftRows, keyDomain, attributeCount, seed, 0L));
        this.right = List.copyOf(generate(rightRows, keyDomain, attributeCount, seed, 1_000_000_000L));
    }

    public SyntheticStreamSource(String name, List<BinaryTuple> left, List<BinaryTuple> right) {
        this.name = name;
        this.left = List.copyOf(left);
        this.right = List.copyOf(right);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public long leftSize() {
        return left.size();
    }

    @Override
    public long rightSize() {
        return right.size();
    }

    @Override
    public int attributeCount() {
        return left.isEmpty() ? (right.isEmpty() ? 0 : right.get(0).attributeCount()) : left.get(0).attributeCount();
    }

    @Override
    public int leftAttributeCount() {
        return left.isEmpty() ? 0 : left.get(0).attributeCount();
    }

    @Override
    public int rightAttributeCount() {
        return right.isEmpty() ? 0 : right.get(0).attributeCount();
    }

    @Override
    public void forEachLeft(java.util.function.Consumer<BinaryTuple> consumer) {
        left.forEach(consumer);
    }

    @Override
    public void forEachRight(java.util.function.Consumer<BinaryTuple> consumer) {
        right.forEach(consumer);
    }

    private static List<BinaryTuple> generate(int rows, int keyDomain, int attributeCount, long seed,
                                             long recordIdOffset) {
        // record ids are offset per side so that left and right ids do not overlap
        List<BinaryTuple> tuples = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            long recordId = recordIdOffset + i;
            int joinKey = (int) Long.remainderUnsigned(Hashing.hash(seed ^ 0xA11CE5L, recordId), keyDomain);
            int[] attributes = new int[attributeCount];
            for (int attr = 0; attr < attributeCount; attr++) {
                attributes[attr] = (int) Long.remainderUnsigned(
                        Hashing.hash(seed + 31L * attr, recordId),
                        1_000L
                );
            }
            tuples.add(new BinaryTuple(recordId, joinKey, attributes));
        }
        return tuples;
    }
}
