package com.adhocjoin.compose;

import com.adhocjoin.sketch.JoinRows;

/**
 * The join sketch inside each part: which counters a bucket holds and how a row turns both sides' filtered bucket
 * masses into a join estimate.
 */
public enum Counters {
    /** Fast-AGMS: one signed counter per bucket ({@code += sign(k)}); a row is the inner product. */
    FAST_AGMS,
    /** Fast-AGMS with the sign split into a positive and a negative cell per bucket (two unsigned counts), as Method 1
     *  needs to sample each cell's tuples; a row is the inner product of (positive - negative). */
    FAST_AGMS_SPLIT,
    /** CMM: one unsigned count per bucket; a row is the debiased inner product (the average count per bucket is
     *  removed to cancel collisions). */
    CMM,
    /** Count-Min: one unsigned count per bucket; a row is the plain inner product, which overestimates by the
     *  colliding keys, and the rows are combined by their median. */
    COUNT_MIN;

    /** Bytes charged per counter. */
    static final long BYTES = 4L;

    /** Counter cells per bucket. */
    int cellsPerBucket() {
        return this == FAST_AGMS_SPLIT ? 2 : 1;
    }

    /** True for the signed (Fast-AGMS) counters. */
    boolean signedRow() {
        return this == FAST_AGMS || this == FAST_AGMS_SPLIT;
    }

    /** One row's join estimate over buckets [from, to): debiased for CMM, the inner product otherwise. */
    double row(double[] l, double[] r, int from, int to) {
        return this == CMM ? JoinRows.debiased(l, r, from, to) : JoinRows.dot(l, r, from, to);
    }

    /** {@link #row(double[], double[], int, int)} for long counters. */
    double row(long[] l, long[] r, int from, int to) {
        return this == CMM ? JoinRows.debiased(l, r, from, to) : JoinRows.dot(l, r, from, to);
    }
}
