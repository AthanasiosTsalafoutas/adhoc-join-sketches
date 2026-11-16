package com.adhocjoin.compose;

import java.util.Arrays;
import java.util.Comparator;
import java.util.SplittableRandom;

/** Part orders for lazy evaluation of a key partition: the heaviest parts first, then a fixed random order. */
final class LazyOrder {
    private LazyOrder() {
    }

    /** Indices of the {@code count} largest entries of {@code mass}, largest first. */
    static int[] topByMass(double[] mass, int count) {
        Integer[] idx = new Integer[mass.length];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        // sort the indices by descending mass
        Arrays.sort(idx, Comparator.comparingDouble(i -> -mass[i]));
        int[] out = new int[Math.min(count, mass.length)];
        for (int i = 0; i < out.length; i++) {
            out[i] = idx[i];
        }
        return out;
    }

    /** A random order of {@code 0..n-1} without the entries of {@code excluded}. */
    static int[] shuffledRest(int n, int[] excluded, SplittableRandom rnd) {
        boolean[] skip = new boolean[n];
        for (int e : excluded) {
            skip[e] = true;
        }
        // the kept indices in increasing order, then a Fisher-Yates shuffle
        int[] a = new int[n - excluded.length];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (!skip[i]) {
                a[k++] = i;
            }
        }
        for (int i = a.length - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
        return a;
    }
}
