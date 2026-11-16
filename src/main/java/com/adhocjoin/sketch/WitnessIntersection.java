package com.adhocjoin.sketch;

/**
 * Intersection size estimate over bottom-k samples of {@link RecordHash}es (each array ascending unsigned), as in
 * OmniSketch: the number of hashes in every sample (the witnesses) times {@code nMax / k}, with {@code nMax} the
 * largest count of the intersected cells and {@code k} the sample size. A witness is in every sample, so it never
 * lies above {@code theta}, the smallest of the cells' thresholds ({@link KMinCell#threshold}). If no cell overflowed
 * ({@code theta = -1}), the witness count is the exact intersection.
 */
public final class WitnessIntersection {
    private WitnessIntersection() {
    }

    /** Intersection size estimate and the number of sampled witnesses it was built from. */
    public record Estimate(double count, int witnesses) {
    }

    /** Estimates the intersection of the samples for the given theta (unsigned, -1 = exact), the largest count
     *  {@code nMax} of the intersected cells and the sample size {@code k}. */
    public static Estimate estimate(long[][] samples, long theta, double nMax, int k) {
        int witnesses = countAtOrBelow(samples, theta);
        if (theta == -1L) {
            return new Estimate(witnesses, witnesses);
        }
        return new Estimate(witnesses * nMax / k, witnesses);
    }

    /** Hashes present in every sample whose value is at most {@code threshold} (unsigned; -1 = no limit). */
    public static int countAtOrBelow(long[][] samples, long threshold) {
        if (samples.length == 0) {
            return 0;
        }
        // limits[i] = how many hashes of sample i are within the threshold. Probe from the sample with the fewest.
        int[] limits = new int[samples.length];
        int smallest = 0;
        for (int i = 0; i < samples.length; i++) {
            limits[i] = countAtOrBelow(samples[i], threshold);
            if (limits[i] < limits[smallest]) {
                smallest = i;
            }
        }
        long[] candidates = samples[smallest];
        int matches = 0;
        for (int candidate = 0; candidate < limits[smallest]; candidate++) {
            long hash = candidates[candidate];
            boolean present = true;
            for (int i = 0; i < samples.length && present; i++) {
                if (i != smallest) {
                    present = containsUnsigned(samples[i], hash, limits[i]);
                }
            }
            if (present) {
                matches++;
            }
        }
        return matches;
    }

    /** The hashes present in every sample. */
    public static long[] intersection(long[][] samples) {
        if (samples.length == 0) {
            return new long[0];
        }
        int smallest = 0;
        for (int i = 1; i < samples.length; i++) {
            if (samples[i].length < samples[smallest].length) {
                smallest = i;
            }
        }
        long[] candidates = samples[smallest];
        long[] matches = new long[candidates.length];
        int found = 0;
        for (long hash : candidates) {
            boolean present = true;
            for (int i = 0; i < samples.length && present; i++) {
                present = i == smallest || containsUnsigned(samples[i], hash, samples[i].length);
            }
            if (present) {
                matches[found++] = hash;
            }
        }
        return java.util.Arrays.copyOf(matches, found);
    }

    /** Number of hashes in {@code sorted} at most {@code threshold} (unsigned). */
    public static int countAtOrBelow(long[] sorted, long threshold) {
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int mid = low + (high - low) / 2;
            if (Long.compareUnsigned(sorted[mid], threshold) <= 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /** Smaller of two values compared as unsigned longs. */
    public static long minUnsigned(long left, long right) {
        return Long.compareUnsigned(left, right) <= 0 ? left : right;
    }

    private static boolean containsUnsigned(long[] values, long target, int limit) {
        int low = 0;
        int high = limit - 1;
        while (low <= high) {
            int mid = low + (high - low) / 2;
            int comparison = Long.compareUnsigned(values[mid], target);
            if (comparison == 0) {
                return true;
            }
            if (comparison < 0) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return false;
    }
}
