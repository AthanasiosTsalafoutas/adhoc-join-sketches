package com.adhocjoin.compose;

import com.adhocjoin.estimator.LogicalPayload;
import com.adhocjoin.model.BinaryJoinQuery;
import com.adhocjoin.model.BinaryTuple;
import com.adhocjoin.sketch.BottomKCell;
import com.adhocjoin.sketch.JoinRows;
import com.adhocjoin.sketch.KMinCell;
import com.adhocjoin.sketch.RecordHash;
import com.adhocjoin.sketch.WitnessIntersection;

import java.util.Arrays;

/**
 * Method 3 (Attribute-Partitioned Sketch), Fast-AGMS counters only: every tuple updates the ALL cell and one cell per
 * attribute value (per layout row). A query joins the cells its equalities select (median over rows) and combines
 * them with {@link CellCombination}, using rho from the cells' record-hash samples ({@link Selectivity.CellWitnesses}).
 */
final class AttributePartitionEngine implements SketchEngine {
    private final KeyHashing hashing;
    private boolean smallestPositiveFallback;
    private boolean rhoDisabled;
    private final int depth;
    private final int width;
    private final int witnessCapacity;
    private final CellSide left;
    private final CellSide right;

    AttributePartitionEngine(Partition.AttributeCells partition, KeyHashing hashing, Selectivity.CellWitnesses witnessSpec) {
        this.hashing = hashing;
        this.depth = hashing.depth();
        this.width = hashing.width();
        this.witnessCapacity = witnessSpec.capacity();
        this.left = new CellSide(partition.left(), witnessSpec.leftRecordSeed());
        this.right = new CellSide(partition.right(), witnessSpec.rightRecordSeed());
    }

    /** The cells of a query on both sides (index 0 = ALL), their counts and the pair joins the estimate needs (each
     *  predicate and ALL against the other side's anchor; the other pairs are NaN); null when it selects nothing. */
    private Selection select(BinaryJoinQuery query) {
        int[][] lc = left.layout.cells(query.leftPredicate());
        int[][] rc = right.layout.cells(query.rightPredicate());
        if (lc == null || rc == null) {
            return null;
        }
        // prepend the ALL cell, then estimate the tuple count of every selected cell on each side
        int[][] la = withAll(lc);
        int[][] ra = withAll(rc);
        double[] cl = left.counts(la);
        double[] cr = right.counts(ra);
        // anchors: the smallest predicate cell of each side
        int as = CellCombination.anchor(cl);
        int bs = CellCombination.anchor(cr);
        // e[a][b] = join of left cell a with right cell b (NaN for pairs the estimate does not need)
        double[][] e = new double[la.length][ra.length];
        for (int a = 0; a < la.length; a++) {
            for (int b = 0; b < ra.length; b++) {
                e[a][b] = a == as || b == bs ? pairJoin(la[a], cl[a], ra[b], cr[b]) : Double.NaN;
            }
        }
        return new Selection(la, ra, cl, cr, e);
    }

    private static int[][] withAll(int[][] cells) {
        int[][] out = new int[cells.length + 1][];
        out[0] = new int[]{0};
        System.arraycopy(cells, 0, out, 1, cells.length);
        return out;
    }

    /** Join of two predicates (or ALL). One cell each: the cells' join. Hashed cells: per layout row the join of
     *  the two row cells scaled by {@code n_P / c_r} on each side (the predicate's count over its cell's count, so
     *  the other values sharing the cell are removed), median over the rows. */
    private double pairJoin(int[] a, double na, int[] b, double nb) {
        // m = number of layout rows that hold the predicate (1 for a direct, one-row cell)
        int m = Math.max(a.length, b.length);
        if (m == 1) {   // a negative id means the value has no cell: the join is 0
            return a[0] < 0 || b[0] < 0 ? 0 : join(left.sketches[a[0]], right.sketches[b[0]]);
        }
        double[] ratio = new double[m];
        for (int r = 0; r < m; r++) {
            // ALL has one cell that serves every row
            int x = a[Math.min(r, a.length - 1)];
            int y = b[Math.min(r, b.length - 1)];
            double raw = join(left.sketches[x], right.sketches[y]);
            double cx = left.count[x];
            double cy = right.count[y];
            // remove the share of the join that comes from the other values hashed into the same cell
            ratio[r] = raw * (cx > 0 ? na / cx : 0) * (cy > 0 ? nb / cy : 0);
        }
        // median over the layout rows
        Arrays.sort(ratio);
        return m % 2 == 1 ? ratio[m / 2] : 0.5 * (ratio[m / 2 - 1] + ratio[m / 2]);
    }

    /** The estimate of one query with its parts: anchor join, join-weighted value and rho. */
    ComposedJoinSketch.CellBreakdown breakdown(BinaryJoinQuery query) {
        Selection s = select(query);
        if (s == null) {   // a value outside every cell: the join is empty
            return new ComposedJoinSketch.CellBreakdown(0, 0, 1, 0);
        }
        // rho needs the samples, and corrects the conjunction of several predicates on each side
        int minJoint = ComposedJoinSketch.MIN_JOINT;
        double rho = witnessCapacity > 0 && !rhoDisabled
                ? left.rho(s.left, s.leftCounts, minJoint) * right.rho(s.right, s.rightCounts, minJoint) : 1.0;
        double[] c = CellCombination.combine(s.joins, s.leftCounts, s.rightCounts, rho);
        return new ComposedJoinSketch.CellBreakdown(c[0], c[3], rho, c[4]);
    }

    /** Join of two cells: median over rows of the inner product (upper median, unclamped); with the
     *  smallest-positive fallback, a non-positive median becomes the smallest positive row, or 0 if none. */
    private double join(long[] x, long[] y) {
        // inner product of the two cell sketches in each row
        double[] rows = new double[depth];
        for (int r = 0; r < depth; r++) {
            rows[r] = JoinRows.dot(x, y, r * width, (r + 1) * width);
        }
        Arrays.sort(rows);
        double median = rows[depth / 2];
        if (!smallestPositiveFallback || median > 0) {
            return median;
        }
        for (double row : rows) {
            if (row > 0) {
                return row;
            }
        }
        return 0.0;
    }

    @Override
    public void payload(LogicalPayload.Builder b) {
        long cells = left.layout.cellCount() + right.layout.cellCount();
        // each cell holds depth x width counters and one count
        b.add("cell sketch counters", cells * depth * width * Counters.BYTES);
        b.add("cell counts", cells * Counters.BYTES);
        // the two ALL cells keep no sample
        b.add("cell record-hash samples", (cells - 2) * witnessCapacity * (long) BottomKCell.HASH_BYTES);
    }

    /** Cells (index 0 = ALL, then one per equality; each in every layout row), counts and pair joins. */
    private record Selection(int[][] left, int[][] right, double[] leftCounts, double[] rightCounts,
                             double[][] joins) {
    }

    /** One relation: per cell a Fast-AGMS sketch, an exact count and (predicate cells only) a {@link KMinCell}. */
    private final class CellSide {
        private final Partition.Layout layout;
        private final long[][] sketches;
        private final long[] count;
        private final long recordSeed;
        private final KMinCell[] samples;

        private CellSide(Partition.Layout layout, long recordSeed) {
            this.layout = layout;
            // one Fast-AGMS sketch per cell: depth rows of width signed counters, stored flat
            this.sketches = new long[layout.cellCount()][depth * width];
            this.count = new long[layout.cellCount()];
            this.recordSeed = recordSeed;
            // k-min record-hash sample per predicate cell (cell 0 = ALL has none)
            this.samples = new KMinCell[witnessCapacity > 0 ? layout.cellCount() : 0];
            for (int c = 1; c < samples.length; c++) {
                samples[c] = new KMinCell(witnessCapacity);
            }
        }

        private void update(BinaryTuple t) {
            // hash the join key once per row to a counter slot and a sign, reused for every cell
            int[] slot = new int[depth];
            int[] sign = new int[depth];
            for (int r = 0; r < depth; r++) {
                slot[r] = r * width + hashing.bucket(r, t.joinKey());
                sign[r] = hashing.sign(r, t.joinKey());
            }
            add(0, slot, sign);   // the ALL cell sees every tuple
            // record hash: the tuple's rank in the cells' bottom-k style samples
            long hash = witnessCapacity > 0 ? RecordHash.of(recordSeed, t.recordId()) : 0L;
            for (int i = 0; i < layout.attributeCount(); i++) {
                for (int row = 0; row < layout.rows(); row++) {
                    // the cell of this attribute value in this layout row (direct index or hashed)
                    int c = layout.cell(i, row, t.attribute(i));
                    if (c < 0) {
                        throw new IllegalArgumentException("attribute value is outside configured equality domain");
                    }
                    add(c, slot, sign);
                    if (witnessCapacity > 0) {
                        samples[c].add(hash);
                    }
                }
            }
        }

        private void add(int c, int[] slot, int[] sign) {
            count[c]++;   // exact tuple count of the cell
            long[] x = sketches[c];
            for (int r = 0; r < depth; r++) {
                x[slot[r]] += sign[r];
            }
        }

        /** ALL's count, then per equality: its cell's exact count (direct; 0 for an empty cell), or (hashed) the
         *  witness-intersection count of its row cells' witnesses, or without samples the smallest row cell count. */
        private double[] counts(int[][] cells) {
            double[] c = new double[cells.length];
            c[0] = count[0];
            for (int i = 1; i < cells.length; i++) {
                int[] ids = cells[i];
                if (ids.length == 1) {   // direct cell: its exact count
                    c[i] = ids[0] < 0 ? 0 : count[ids[0]];
                } else if (witnessCapacity > 0) {
                    // hashed: intersect the row cells' samples below the smallest threshold theta
                    long theta = -1L;
                    double nMax = 0;
                    long[][] hashes = new long[ids.length][];
                    for (int r = 0; r < ids.length; r++) {
                        theta = WitnessIntersection.minUnsigned(theta, samples[ids[r]].threshold());
                        nMax = Math.max(nMax, count[ids[r]]);
                        hashes[r] = samples[ids[r]].sortedHashes();
                    }
                    c[i] = WitnessIntersection.estimate(hashes, theta, nMax, witnessCapacity).count();
                } else {
                    // no samples: the smallest row cell count is an upper bound
                    c[i] = Double.POSITIVE_INFINITY;
                    for (int id : ids) {
                        c[i] = Math.min(c[i], count[id]);
                    }
                }
            }
            return c;
        }

        /** rho of a conjunction: {@code n^(m-1) n(P_1 and ... and P_m) / prod n(P_i)}, with {@code n(P_i)} from
         *  {@link #counts} (exact for direct cells, estimated for hashed cells) and the conjunction count from the
         *  witness intersection of the k-min samples of every cell of every predicate (all layout rows for hashed cells);
         *  1 for fewer than two predicates, an empty predicate, or too few sampled matches. */
        private double rho(int[][] cells, double[] counts, int minJoint) {
            if (cells.length < 3) {
                return 1.0;   // ALL plus at most one predicate
            }
            int total = 0;
            for (int i = 1; i < cells.length; i++) {
                for (int id : cells[i]) {
                    if (id < 0) {
                        return 1.0;   // a value without a cell: the estimate is already 0
                    }
                }
                if (counts[i] <= 0) {
                    return 1.0;   // the anchor (an empty predicate) already makes the estimate 0
                }
                total += cells[i].length;
            }
            // intersect the samples of all the predicates' cells below the smallest threshold theta (-1 = unsigned max)
            long theta = -1L;
            double nMax = 0;
            long[][] hashes = new long[total][];
            int h = 0;
            for (int i = 1; i < cells.length; i++) {
                for (int id : cells[i]) {
                    theta = WitnessIntersection.minUnsigned(theta, samples[id].threshold());
                    nMax = Math.max(nMax, count[id]);
                    hashes[h++] = samples[id].sortedHashes();
                }
            }
            // witnesses: sampled records that lie in every predicate's cell, scaled by nMax / k as in OmniSketch
            WitnessIntersection.Estimate joint = WitnessIntersection.estimate(hashes, theta, nMax, witnessCapacity);
            if (theta != -1L && joint.witnesses() < minJoint) {
                return 1.0;   // too few witnesses to trust the joint count
            }
            // rho = n^(m-1) * joint count / prod n(P_i), written as a product of n / n(P_i) factors
            double r = joint.count();
            for (int i = 1; i < cells.length; i++) {
                r *= count[0] / counts[i];
            }
            return r / count[0];
        }
    }

    @Override
    public void update(boolean leftSide, BinaryTuple tuple) {
        (leftSide ? left : right).update(tuple);
    }

    @Override
    public double estimate(BinaryJoinQuery query) {
        // the final estimate is the last entry of the breakdown: join-weighted value times rho
        return breakdown(query).estimate();
    }

    @Override
    public long retainedTupleCount() {
        return 0L;
    }

    /** Ablation: rho is never applied (the samples are still kept). */
    void disableRho() {
        rhoDisabled = true;
    }

    @Override
    public void useSmallestPositiveFallback() {
        smallestPositiveFallback = true;
    }
}
