package com.adhocjoin.harness;

import com.adhocjoin.estimator.JoinEstimator;

import java.util.function.IntFunction;

/** Budget matching shared by the benchmark drivers: every method solves its own capacity parameter against its
 *  own measured payload. */
public final class Budget {
    private Budget() {
    }

    /** Largest {@code k} in {@code [1, maxK]} with {@code cost(k) <= budget}, by binary search (cost assumed
     *  non-decreasing in k); 0 when even {@code k = 1} overruns. */
    public static int largestFitting(long budget, int maxK, IntFunction<Long> cost) {
        int low = 1;
        int high = maxK;
        if (cost.apply(low) > budget) {
            return 0;
        }
        while (low < high) {
            int mid = low + (high - low + 1) / 2;
            if (cost.apply(mid) <= budget) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    /** {@link #largestFitting} over a fresh estimator's {@code logicalPayloadBytes()}. */
    public static int largestFittingPayload(long budget, int maxK, IntFunction<JoinEstimator> build) {
        return largestFitting(budget, maxK, k -> build.apply(k).logicalPayloadBytes());
    }
}
