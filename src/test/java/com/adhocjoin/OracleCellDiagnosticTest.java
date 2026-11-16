package com.adhocjoin;

import com.adhocjoin.estimator.PreFilteredSketchEstimator;
import com.adhocjoin.model.BinaryJoinQuery;
import com.adhocjoin.model.BinaryTuple;
import com.adhocjoin.sketch.SketchConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OracleCellDiagnosticTest {
    @Test
    void exposedOracleCellsReproduceEveryRowEstimate() {
        PreFilteredSketchEstimator oracle = new PreFilteredSketchEstimator(
                BinaryJoinQuery.all(), new SketchConfig(17, 3), 1, 42L);
        List<BinaryTuple> left = List.of(tuple(1, 3), tuple(2, 3), tuple(3, 8));
        List<BinaryTuple> right = List.of(tuple(4, 3), tuple(5, 8), tuple(6, 8));
        left.forEach(oracle::updateLeft);
        right.forEach(oracle::updateRight);

        var cells = oracle.filteredCells();
        double[] rowEstimates = oracle.rowEstimates(BinaryJoinQuery.all());
        for (int row = 0; row < cells.size(); row++) {
            var rowCells = cells.get(row);
            assertEquals(left.size(), sum(rowCells.leftPositive()) + sum(rowCells.leftNegative()));
            assertEquals(right.size(), sum(rowCells.rightPositive()) + sum(rowCells.rightNegative()));
            double innerProduct = 0.0;
            double[] leftSigned = rowCells.leftSigned();
            double[] rightSigned = rowCells.rightSigned();
            for (int bucket = 0; bucket < leftSigned.length; bucket++) {
                innerProduct += leftSigned[bucket] * rightSigned[bucket];
            }
            assertEquals(innerProduct, rowEstimates[row]);
        }
    }

    private static BinaryTuple tuple(long recordId, int joinKey) {
        return new BinaryTuple(recordId, joinKey, new int[]{1});
    }

    private static double sum(double[] values) {
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum;
    }
}
