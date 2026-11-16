package com.adhocjoin.sketch;

/**
 * Hash functions used by all sketches: a 64-bit mixer with seeds, a 4-universal polynomial hash over the Mersenne
 * prime 2^61 - 1 for join-key buckets and signs, and helpers for deriving per-structure seeds.
 */
public final class Hashing {
    private static final long MERSENNE_61_PRIME = (1L << 61) - 1L;
    private Hashing() {
    }

    /** 64-bit mixer (splitmix64 finalizer). */
    public static long mix64(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Seeded mixer hash. The seed is mixed first so that seeds differing in a few low bits give unrelated functions. */
    public static long hash(long seed, long value) {
        return mix64(value ^ mix64(seed));
    }

    /** Derives the seed of one structure (identified by {@code tag}, e.g. a row index) from a method seed. The method
     *  seed is mixed before the tag is applied, so consecutive method seeds give unrelated derived seeds. */
    public static long derive(long seed, long tag) {
        return mix64(mix64(seed) ^ tag);
    }

    public static long hash(long seed, int value) {
        return hash(seed, (long) value);
    }

    /** Maps a 64-bit hash to a double in [0, 1) using its top 53 bits. */
    public static double unitInterval(long hash) {
        return (hash >>> 11) * 0x1.0p-53;
    }

    public static int bucket(long seed, int key, int width) {
        return (int) Long.remainderUnsigned(hash(seed, key), width);
    }

    /** Bucket in [0, width) from the 4-universal hash of the key. */
    public static int fourUniversalBucket(long seed, int key, int width) {
        return (int) (fourUniversalHash(seed, key) % width);
    }

    /** Sign (+1 or -1) from the 4-universal hash of the key: +1 for the lower half of the hash range. */
    public static int fourUniversalSign(long seed, int key) {
        return fourUniversalHash(seed, key) <= (MERSENNE_61_PRIME >>> 1) ? 1 : -1;
    }

    /** Slot in a width-2w array that keeps both signs of a bucket: 2k for sign &gt; 0 and 2k+1 for sign &lt; 0. */
    public static int signedSlot(int bucket, int sign) {
        return 2 * bucket + (sign > 0 ? 0 : 1);
    }

    /** Random cubic polynomial over GF(2^61 - 1) evaluated at the key (Horner form). The coefficients come from the seed. */
    public static long fourUniversalHash(long seed, int key) {
        long x = Integer.toUnsignedLong(key);
        long value = coefficient(seed, 3);
        value = addMod(multiplyMod(value, x), coefficient(seed, 2));
        value = addMod(multiplyMod(value, x), coefficient(seed, 1));
        return addMod(multiplyMod(value, x), coefficient(seed, 0));
    }

    private static long coefficient(long seed, int index) {
        return Long.remainderUnsigned(mix64(seed + 0x9E3779B97F4A7C15L * (index + 1L)),
                MERSENNE_61_PRIME);
    }

    // (left * right) mod 2^61 - 1 from the 128-bit product: 2^61 is 1 mod the prime, so fold the high parts down.
    private static long multiplyMod(long left, long right) {
        long low = left * right;
        long high = Math.multiplyHigh(left, right);
        long reduced = (low & MERSENNE_61_PRIME) + (low >>> 61) + (high << 3);
        reduced = (reduced & MERSENNE_61_PRIME) + (reduced >>> 61);
        reduced = (reduced & MERSENNE_61_PRIME) + (reduced >>> 61);
        return reduced == MERSENNE_61_PRIME ? 0L : reduced;
    }

    private static long addMod(long left, long right) {
        long sum = left + right;
        return sum >= MERSENNE_61_PRIME ? sum - MERSENNE_61_PRIME : sum;
    }
}
