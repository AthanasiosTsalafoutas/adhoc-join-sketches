package com.adhocjoin;

import com.adhocjoin.compose.ComposedJoinSketch;
import com.adhocjoin.compose.Methods;
import com.adhocjoin.estimator.CellSampler;
import com.adhocjoin.estimator.PreFilteredSketchEstimator;
import com.adhocjoin.model.BinaryJoinQuery;
import com.adhocjoin.model.BinaryTuple;
import com.adhocjoin.model.TuplePredicate;
import com.adhocjoin.sketch.SketchConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Golden-value regression test for the signed layout (positive and negative counters merged into one 2w array):
 *  exact estimates on a fixed-seed synthetic source, so an indexing bug in the merge is caught by exact equality. */
class SignedLayoutEquivalenceTest {
    private static final SketchConfig CONFIG = new SketchConfig(23, 5);

    private static List<BinaryTuple> left;
    private static List<BinaryTuple> right;

    static {
        Random rng = new Random(42L);
        left = new ArrayList<>();
        right = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            left.add(new BinaryTuple(i, rng.nextInt(400), new int[]{rng.nextInt(5), rng.nextInt(7)}));
        }
        for (int i = 0; i < 2500; i++) {
            right.add(new BinaryTuple(10_000L + i, rng.nextInt(400), new int[]{rng.nextInt(5), rng.nextInt(7)}));
        }
    }

    /** The unfiltered estimate uses the cell counts only, so it does not depend on the samples. */
    @Test
    void sampledCellSketchSignedEstimateMatchesGoldenValues() {
        ComposedJoinSketch sketch = sampledCellSketch(true);
        ingest(sketch::updateLeft, sketch::updateRight);
        assertEquals(21294.0, sketch.estimate(BinaryJoinQuery.all()), 0.0);
    }

    @Test
    void oracleFilteredEstimateMatchesGoldenValues() {
        // the oracle is built per query and filters at ingest
        assertAll(
                () -> assertEquals(21294.0, oracleEstimate(BinaryJoinQuery.all()), 0.0),
                () -> assertEquals(867.0, oracleEstimate(BinaryJoinQuery.of(
                        TuplePredicate.attributeEquals(0, 2), TuplePredicate.attributeEquals(0, 2))), 0.0),
                () -> assertEquals(323.0, oracleEstimate(BinaryJoinQuery.of(
                        TuplePredicate.attributeEquals(1, 3), TuplePredicate.attributeEquals(1, 5))), 0.0)
        );
    }

    /** Golden value for the unsigned (CMM) sampled sketch, unfiltered. */
    @Test
    void cmmMatchesGoldenValues() {
        ComposedJoinSketch sketch = sampledCellSketch(false);
        ingest(sketch::updateLeft, sketch::updateRight);
        assertEquals(19712.090909090908, sketch.estimate(BinaryJoinQuery.all()), 1e-9);
    }

    /** Method 1 with reservoir cells of 32, hash and sample seed 55. */
    private static ComposedJoinSketch sampledCellSketch(boolean signed) {
        return Methods.sampledCellSketch(CONFIG, 2, 2, 55L, 55L, new CellSampler.Reservoir(32, 55L), signed);
    }

    private static double oracleEstimate(BinaryJoinQuery query) {
        PreFilteredSketchEstimator sketch = new PreFilteredSketchEstimator(query, CONFIG, 2, 2, 55L);
        ingest(sketch::updateLeft, sketch::updateRight);
        return sketch.estimate(query);
    }

    private static void ingest(java.util.function.Consumer<BinaryTuple> left1, java.util.function.Consumer<BinaryTuple> right1) {
        left.forEach(left1);
        right.forEach(right1);
    }
}
