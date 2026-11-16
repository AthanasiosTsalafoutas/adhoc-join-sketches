package com.adhocjoin.sketch;

import java.util.Arrays;

/**
 * The join-size math every sketch shares, on the per-bucket filtered masses of one row of both sides: the signed
 * Fast-AGMS inner product, the unsigned CMM debiased one (Thorup–Zhang), and the median of the per-row estimates.
 * Partitioned sketches apply the row formulas to each partition's bucket range {@code [from, to)}.
 */
public final class JoinRows {
    private JoinRows() {
    }

    /** Signed Fast-AGMS row estimate: {@code sum l[i] r[i]} over {@code [from, to)}. */
    public static double dot(double[] l, double[] r, int from, int to) {
        double sum = 0.0;
        for (int i = from; i < to; i++) {
            sum += l[i] * r[i];
        }
        return sum;
    }

    /** {@link #dot} over whole arrays. */
    public static double dot(double[] l, double[] r) {
        return dot(l, r, 0, l.length);
    }

    /** {@link #dot} over raw counters. */
    public static double dot(long[] l, long[] r, int from, int to) {
        double sum = 0.0;
        for (int i = from; i < to; i++) {
            sum += (double) l[i] * r[i];
        }
        return sum;
    }

    /** Unsigned (CMM) row estimate over the {@code n = to - from} buckets of {@code [from, to)}: the inner product
     *  with the expected collision mass removed, {@code (n * dot - sum(l) * sum(r)) / (n - 1)}. */
    public static double debiased(double[] l, double[] r, int from, int to) {
        double dot = 0.0;
        double massL = 0.0;
        double massR = 0.0;
        for (int i = from; i < to; i++) {
            double lc = l[i];
            double rc = r[i];
            dot += lc * rc;
            massL += lc;
            massR += rc;
        }
        int n = to - from;
        return (n * dot - massL * massR) / (n - 1.0);
    }

    /** {@link #debiased} over raw counters. */
    public static double debiased(long[] l, long[] r, int from, int to) {
        double dot = 0.0;
        double massL = 0.0;
        double massR = 0.0;
        for (int i = from; i < to; i++) {
            double lc = l[i];
            double rc = r[i];
            dot += lc * rc;
            massL += lc;
            massR += rc;
        }
        int n = to - from;
        return (n * dot - massL * massR) / (n - 1.0);
    }

    /** {@link #debiased} over whole arrays. */
    public static double debiased(double[] l, double[] r) {
        return debiased(l, r, 0, l.length);
    }

    /** Median of the per-row estimates (middle pair averaged for an even count), clamped at 0: a join size can't be
     *  negative, but the signed/debiased row formulas can combine to a negative value under sampling noise. */
    public static double median(double[] values) {
        double[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        int mid = copy.length / 2;
        double combined = copy.length % 2 == 1 ? copy[mid] : 0.5 * (copy[mid - 1] + copy[mid]);
        return Math.max(0.0, combined);
    }

    /** Smallest row estimate (Count-Min: every row overestimates, so the smallest is the closest). */
    public static double min(double[] values) {
        double m = Double.POSITIVE_INFINITY;
        for (double v : values) {
            m = Math.min(m, v);
        }
        return m;
    }

    /** {@link #median(double[])} when it is positive; otherwise the smallest positive row (the positive row least
     *  inflated by noise), or 0 when no row is positive. */
    public static double medianOrSmallestPositive(double[] values) {
        double m = median(values);
        if (m > 0) {
            return m;
        }
        double smallest = Double.POSITIVE_INFINITY;
        for (double v : values) {
            if (v > 0 && v < smallest) {
                smallest = v;
            }
        }
        return smallest == Double.POSITIVE_INFINITY ? 0.0 : smallest;
    }
}
