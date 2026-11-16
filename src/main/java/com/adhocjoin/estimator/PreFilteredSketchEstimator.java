package com.adhocjoin.estimator;

import com.adhocjoin.model.BinaryJoinQuery;
import com.adhocjoin.model.BinaryTuple;
import com.adhocjoin.model.RelationSide;
import com.adhocjoin.sketch.Hashing;
import com.adhocjoin.sketch.JoinRows;
import com.adhocjoin.sketch.SketchConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pre-filtered sketch (the oracle): a signed Fast-AGMS (Count-Sketch) join sketch built for one query fixed at construction. Each
 * tuple is tested against the predicate on arrival, so the sketch holds only the filtered tuples and has no sampling
 * error. It shows the hash-collision error that remains with a perfect filter.
 */
public final class PreFilteredSketchEstimator implements JoinEstimator {
    private final BinaryJoinQuery query;
    private final SketchConfig config;
    private final int leftAttributeCount;
    private final int rightAttributeCount;
    private final JoinGeometry geometry;
    private final double[][] leftCells;
    private final double[][] rightCells;

    public PreFilteredSketchEstimator(BinaryJoinQuery query, SketchConfig config, int attributeCount,
                                              long seed) {
        this(query, config, attributeCount, attributeCount, seed);
    }

    public PreFilteredSketchEstimator(BinaryJoinQuery query, SketchConfig config, int leftAttributeCount,
                                              int rightAttributeCount, long seed) {
        if (leftAttributeCount <= 0 || rightAttributeCount <= 0) {
            throw new IllegalArgumentException("attribute counts must be positive");
        }
        this.query = query;
        this.config = config;
        this.leftAttributeCount = leftAttributeCount;
        this.rightAttributeCount = rightAttributeCount;
        this.geometry = new JoinGeometry(config.depth(), config.width(), true, seed);
        this.leftCells = new double[config.depth()][2 * config.width()];
        this.rightCells = new double[config.depth()][2 * config.width()];
    }

    @Override
    public String name() {
        return "oracle-filtered-countsketch-diagnostic";
    }


    @Override
    public void updateLeft(BinaryTuple tuple) {
        validate(tuple, leftAttributeCount);
        if (query.leftPredicate().test(tuple)) {
            increment(leftCells, RelationSide.LEFT, tuple);
        }
    }

    @Override
    public void updateRight(BinaryTuple tuple) {
        validate(tuple, rightAttributeCount);
        if (query.rightPredicate().test(tuple)) {
            increment(rightCells, RelationSide.RIGHT, tuple);
        }
    }

    // add the tuple to its bucket/sign slot in every row
    private void increment(double[][] cells, RelationSide side, BinaryTuple tuple) {
        for (int row = 0; row < config.depth(); row++) {
            cells[row][geometry.slot(row, tuple.joinKey())]++;
        }
    }

    /**
     * Median over the rows of the Fast-AGMS inner product. The {@code query} argument is ignored: filtering was done
     * at ingestion with the query given to the constructor.
     */
    @Override
    public double estimate(BinaryJoinQuery query) {
        return JoinRows.median(rowEstimates(query));
    }

    /** Count-Min on the same counters: per row the inner product of the unsigned bucket counts (positive plus
     *  negative slot), minimum over the rows. */
    public double countMinEstimate() {
        double[] rows = new double[config.depth()];
        for (int row = 0; row < rows.length; row++) {
            rows[row] = JoinRows.dot(total(leftCells[row]), total(rightCells[row]));
        }
        return JoinRows.min(rows);
    }

    /** CMM on the same counters: per row the debiased inner product of the unsigned bucket counts, median over the
     *  rows. */
    public double cmmEstimate() {
        double[] rows = new double[config.depth()];
        for (int row = 0; row < rows.length; row++) {
            rows[row] = JoinRows.debiased(total(leftCells[row]), total(rightCells[row]));
        }
        return JoinRows.median(rows);
    }

    /** Unsigned bucket counts: positive plus negative slot. */
    private double[] total(double[] slots) {
        double[] total = new double[config.width()];
        for (int col = 0; col < total.length; col++) {
            total[col] = slots[Hashing.signedSlot(col, 1)] + slots[Hashing.signedSlot(col, -1)];
        }
        return total;
    }

    /** Per join row, the inner product of the signed bucket masses (positive minus negative slot). */
    public double[] rowEstimates(BinaryJoinQuery query) {
        double[] rows = new double[config.depth()];
        for (int row = 0; row < rows.length; row++) {
            rows[row] = JoinRows.dot(net(leftCells[row]), net(rightCells[row]));
        }
        return rows;
    }

    /** Signed bucket masses: positive minus negative slot. */
    private double[] net(double[] slots) {
        double[] net = new double[config.width()];
        for (int col = 0; col < net.length; col++) {
            net[col] = slots[Hashing.signedSlot(col, 1)] - slots[Hashing.signedSlot(col, -1)];
        }
        return net;
    }

    @Override
    public long logicalPayloadBytes() {
        return logicalPayload().totalBytes();
    }

    @Override
    public LogicalPayload logicalPayload() {
        // 2 sides x 2 signs x 4 bytes per counter, for every bucket of every row
        long counters = 4L * config.depth() * config.width() * 4L;
        return LogicalPayload.builder()
                .add("positive/negative join counters", counters)
                .build();
    }

    public int bucket(RelationSide side, int row, int joinKey) {
        checkRow(row);
        return geometry.bucket(row, joinKey);
    }

    public int sign(RelationSide side, int row, int joinKey) {
        checkRow(row);
        return geometry.sign(row, joinKey);
    }

    /** Per-row positive and negative counters of both sides, for diagnostics. */
    public List<FilteredRowCells> filteredCells() {
        List<FilteredRowCells> rows = new ArrayList<>(config.depth());
        for (int row = 0; row < config.depth(); row++) {
            rows.add(new FilteredRowCells(positiveOf(leftCells[row]), negativeOf(leftCells[row]),
                    positiveOf(rightCells[row]), negativeOf(rightCells[row])));
        }
        return List.copyOf(rows);
    }

    private double[] positiveOf(double[] slots) {
        double[] out = new double[config.width()];
        for (int col = 0; col < out.length; col++) {
            out[col] = slots[Hashing.signedSlot(col, 1)];
        }
        return out;
    }

    private double[] negativeOf(double[] slots) {
        double[] out = new double[config.width()];
        for (int col = 0; col < out.length; col++) {
            out[col] = slots[Hashing.signedSlot(col, -1)];
        }
        return out;
    }

    private void validate(BinaryTuple tuple, int attributeCount) {
        if (tuple.attributeCount() != attributeCount) {
            throw new IllegalArgumentException("tuple attribute count must equal configuredAttributeCount");
        }
    }

    private void checkRow(int row) {
        if (row < 0 || row >= config.depth()) {
            throw new IllegalArgumentException("row out of bounds: " + row);
        }
    }

    /** Positive and negative counters of one row for both sides. */
    public record FilteredRowCells(double[] leftPositive, double[] leftNegative,
                                   double[] rightPositive, double[] rightNegative) {
        public FilteredRowCells {
            leftPositive = Arrays.copyOf(leftPositive, leftPositive.length);
            leftNegative = Arrays.copyOf(leftNegative, leftNegative.length);
            rightPositive = Arrays.copyOf(rightPositive, rightPositive.length);
            rightNegative = Arrays.copyOf(rightNegative, rightNegative.length);
        }

        @Override
        public double[] leftPositive() {
            return Arrays.copyOf(leftPositive, leftPositive.length);
        }

        @Override
        public double[] leftNegative() {
            return Arrays.copyOf(leftNegative, leftNegative.length);
        }

        @Override
        public double[] rightPositive() {
            return Arrays.copyOf(rightPositive, rightPositive.length);
        }

        @Override
        public double[] rightNegative() {
            return Arrays.copyOf(rightNegative, rightNegative.length);
        }

        public double[] leftSigned() {
            return signed(leftPositive, leftNegative);
        }

        public double[] rightSigned() {
            return signed(rightPositive, rightNegative);
        }

        private static double[] signed(double[] positive, double[] negative) {
            double[] values = new double[positive.length];
            for (int i = 0; i < values.length; i++) {
                values[i] = positive[i] - negative[i];
            }
            return values;
        }
    }
}
