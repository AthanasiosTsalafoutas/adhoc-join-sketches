package com.adhocjoin.sketch;

/**
 * The record hash that bottom-k cells admit on: the top 32 bits of the seeded mixer, uniform on [0, 2^32). Each
 * retained hash is charged 4 bytes. Equal hashes count as one record, as in a k-min sketch.
 */
public final class RecordHash {
    /** Size of the hash domain, for turning a threshold hash into a sampling rate. */
    public static final double RANGE = 4294967296.0;

    private RecordHash() {
    }

    /** Hash of one record id under {@code seed}, in [0, 2^32). */
    public static long of(long seed, long recordId) {
        return Hashing.hash(seed, recordId) >>> 32;
    }
}
