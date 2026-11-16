package com.adhocjoin.estimator;

import com.adhocjoin.sketch.Hashing;

/**
 * The join-key hashing that both relations of a join must share: per-row bucket and sign seeds derived from one seed,
 * and the bucket, sign and slot of a key in each row, all 4-universal.
 */
public final class JoinGeometry {
    private final int depth;
    private final int width;
    private final boolean signed;
    private final long[] bucketSeeds;
    private final long[] signSeeds;

    public JoinGeometry(int depth, int width, boolean signed, long seed) {
        this.depth = depth;
        this.width = width;
        this.signed = signed;
        this.bucketSeeds = new long[depth];
        this.signSeeds = new long[depth];
        for (int row = 0; row < depth; row++) {
            // seeds go through the mixer so that rows, and a row's bucket and sign hashes, get unrelated
            // polynomial coefficients
            bucketSeeds[row] = Hashing.derive(seed, 0x4255434B00000000L + row);
            signSeeds[row] = Hashing.derive(seed, 0x5349474E00000000L + row);
        }
    }

    public int depth() {
        return depth;
    }

    public int width() {
        return width;
    }

    public boolean signed() {
        return signed;
    }

    public int bucket(int row, int joinKey) {
        return Hashing.fourUniversalBucket(bucketSeeds[row], joinKey, width);
    }

    public int sign(int row, int joinKey) {
        return Hashing.fourUniversalSign(signSeeds[row], joinKey);
    }

    /** Index of the key's cell in the row: the bucket, or the bucket combined with the sign when signed. */
    public int slot(int row, int joinKey) {
        int bucket = bucket(row, joinKey);
        return signed ? Hashing.signedSlot(bucket, sign(row, joinKey)) : bucket;
    }
}
