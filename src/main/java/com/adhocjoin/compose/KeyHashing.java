package com.adhocjoin.compose;

import com.adhocjoin.estimator.JoinGeometry;

/**
 * How a join key is hashed into one row of a join sketch: a bucket in {@code [0, width)} and a sign. Both sides of a
 * join must use the same instance. Every method uses the 4-universal family; the interface keeps the hashing apart
 * from the sketch.
 */
public interface KeyHashing {
    int depth();

    int width();

    /** Bucket of {@code key} in sketch row {@code row}, in {@code [0, width)}. */
    int bucket(int row, int key);

    /** +1 or -1; always +1 for an unsigned hashing. */
    int sign(int row, int key);

    /** The 4-universal bucket and sign hashes of {@link JoinGeometry} (every method). */
    static KeyHashing fourUniversal(int depth, int width, long seed) {
        JoinGeometry geometry = new JoinGeometry(depth, width, true, seed);
        return new KeyHashing() {
            @Override
            public int depth() {
                return depth;
            }

            @Override
            public int width() {
                return width;
            }

            @Override
            public int bucket(int row, int key) {
                return geometry.bucket(row, key);
            }

            @Override
            public int sign(int row, int key) {
                return geometry.sign(row, key);
            }
        };
    }
}
