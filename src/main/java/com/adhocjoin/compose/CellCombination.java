package com.adhocjoin.compose;

/**
 * Method 3's combination step on a pair matrix {@code e} of cell joins (index 0 = ALL, then the predicate cells of
 * each side) with the tuple counts of those cells ({@code cl[0]}, {@code cr[0]} = ALL):
 * <ul>
 *   <li>anchor: the join of the smallest predicate cell on each side;</li>
 *   <li>join-weighted independence for the other predicates: times {@code J(i, b*) / J(ALL, b*)} per other left
 *       predicate i and {@code J(a*, j) / J(a*, ALL)} per other right predicate j (each clamped to [0, 1]);</li>
 *   <li>times the conjunction correction {@code rho}.</li>
 * </ul>
 * Returns {anchor, upper, lower, jw, jw * rho}. {@code upper} (smallest predicate-pair join) and {@code lower} (Frechet)
 * are diagnostics only, computed from whatever pairs {@code e} holds (NaN when the needed pairs were not computed); the
 * estimate is not clipped to them. With no predicates on a side, that side is ALL alone.
 */
public final class CellCombination {
    private CellCombination() {
    }

    /** Combines the cell joins {@code e} with the cell counts {@code cl}, {@code cr}; {@code rho} scales the
     *  join-weighted estimate. Returns {anchor, upper, lower, jw, jw * rho}. */
    public static double[] combine(double[][] e, double[] cl, double[] cr, double rho) {
        int nl = cl.length - 1;
        int nr = cr.length - 1;
        // first cell index per side: 1 when the side has predicate cells, else 0 (ALL alone)
        int l0 = nl == 0 ? 0 : 1;
        int r0 = nr == 0 ? 0 : 1;
        // anchor cells: the smallest predicate cell on each side
        int as = anchor(cl);
        int bs = anchor(cr);
        double anchor = e[as][bs];
        // upper: the smallest join over all predicate-cell pairs
        double upper = Double.POSITIVE_INFINITY;
        for (int i = l0; i <= nl; i++) {
            for (int j = r0; j <= nr; j++) {
                upper = Math.min(upper, e[i][j]);
            }
        }
        // lower: Frechet bound, the largest e[a][b] minus the mass the other predicates can remove
        double lower = 0;
        for (int a = l0; a <= nl; a++) {
            for (int b = r0; b <= nr; b++) {
                double v = e[a][b];
                for (int i = l0; i <= nl; i++) {
                    if (i != a) {
                        v -= e[0][b] - e[i][b];
                    }
                }
                for (int j = r0; j <= nr; j++) {
                    if (j != b) {
                        v -= e[a][0] - e[a][j];
                    }
                }
                lower = Math.max(lower, v);
            }
        }
        // join-weighted independence: scale the anchor join by each other predicate's share of the join
        double jw = anchor;
        for (int i = l0; i <= nl; i++) {
            if (i != as) {
                jw *= clamp01(e[i][bs] / e[0][bs]);
            }
        }
        for (int j = r0; j <= nr; j++) {
            if (j != bs) {
                jw *= clamp01(e[as][j] / e[as][0]);
            }
        }
        return new double[]{anchor, upper, lower, jw, jw * rho};
    }

    /** Index (into the counts array) of the smallest predicate cell, or 0 (ALL) without predicates. */
    public static int anchor(double[] counts) {
        if (counts.length == 1) {
            return 0;
        }
        int best = 1;
        for (int i = 2; i < counts.length; i++) {
            if (counts[i] < counts[best]) {
                best = i;
            }
        }
        return best;
    }

    private static double clamp01(double x) {
        return Double.isNaN(x) ? 0 : Math.max(0, Math.min(1, x));
    }
}
