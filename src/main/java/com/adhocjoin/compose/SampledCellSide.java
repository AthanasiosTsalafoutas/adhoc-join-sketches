package com.adhocjoin.compose;

import com.adhocjoin.estimator.CellSampler;
import com.adhocjoin.estimator.LogicalPayload;
import com.adhocjoin.estimator.PayloadCell;
import com.adhocjoin.model.BinaryTuple;
import com.adhocjoin.model.TuplePredicate;
import com.adhocjoin.sketch.RecordHash;

/** Method 1: a tuple sample in every counter cell. The filtered mass of a cell is its counter times the matching
 *  fraction of its sample. */
final class SampledCellSide implements GroupSamples.CounterMasses {
    private final int parts;
    private final int depth;
    private final int partWidth;
    private final int rowWidth;

    private final CellSampler sampler;
    private final long recordSeed;
    private final PayloadCell<BinaryTuple>[][] cells;

    @SuppressWarnings("unchecked")
    SampledCellSide(Selectivity.SampledCells c, boolean leftSide, GroupSamples.Grid grid) {
        this.parts = grid.parts();
        this.depth = grid.depth();
        this.partWidth = grid.partWidth();
        this.rowWidth = grid.rowWidth();
        this.sampler = c.sampler();
        this.recordSeed = leftSide ? c.leftRecordSeed() : c.rightRecordSeed();
        this.cells = new PayloadCell[depth][rowWidth];
        for (int row = 0; row < depth; row++) {
            for (int col = 0; col < rowWidth; col++) {
                cells[row][col] = sampler.newCell();
            }
        }
    }

    @Override
    public void add(BinaryTuple tuple, int part, int[] slots) {
        // one record hash per tuple, the same in every row, handed to the cells with the tuple
        long hash = RecordHash.of(recordSeed, tuple.recordId());
        for (int row = 0; row < depth; row++) {
            // offer the tuple to the sample of the counter it updated in this row
            cells[row][slots[row]].admit(hash, tuple);
        }
    }

    @Override
    public double[][] mass(TuplePredicate predicate, long[][] counterRows) {
        // each cell's count scaled by the matching share of its own sample
        double[][] mass = new double[depth][rowWidth];
        for (int row = 0; row < depth; row++) {
            for (int col = 0; col < rowWidth; col++) {
                mass[row][col] = CellSampler.filteredMass(cells[row][col], predicate);
            }
        }
        return mass;
    }

    @Override
    public long retained() {
        long total = 0L;
        for (PayloadCell<BinaryTuple>[] row : cells) {
            for (PayloadCell<BinaryTuple> cell : row) {
                total += cell.sampleSize();
            }
        }
        return total;
    }

    @Override
    public void payload(LogicalPayload.Builder b, String side, int attributeCount) {
        // a stored tuple costs its attributes (4 B each)
        long perTuple = 4L * attributeCount;
        b.add("cell tuple samples (" + side + ")", (long) depth * rowWidth * sampler.capacity() * perTuple);
    }
}
