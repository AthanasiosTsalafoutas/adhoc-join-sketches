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

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Method 1 (the sampled sketch) built by {@link Methods#sampledCellSketch}. */
class SampledCellSketchTest {
    @Test
    void fullCellsMatchTheOracle() {
        SketchConfig config = new SketchConfig(64, 5);
        BinaryJoinQuery query = BinaryJoinQuery.of(
                TuplePredicate.attributeEquals(0, 1), TuplePredicate.attributeEquals(1, 2));
        ComposedJoinSketch sampled = Methods.sampledCellSketch(config, 2, 2, 77L, 77L,
                new CellSampler.Reservoir(32, 77L), true);
        PreFilteredSketchEstimator oracle = new PreFilteredSketchEstimator(query, config, 2, 2, 77L);

        for (int i = 0; i < 20; i++) {
            BinaryTuple left = new BinaryTuple(i, i % 5, new int[]{i % 2, i % 3});
            BinaryTuple right = new BinaryTuple(100 + i, i % 5, new int[]{i % 2, i % 4});
            sampled.updateLeft(left);
            sampled.updateRight(right);
            oracle.updateLeft(left);
            oracle.updateRight(right);
        }

        // no cell holds more than its capacity, so every cell's sample is the whole cell
        assertEquals(oracle.estimate(query), sampled.estimate(query));
    }
}
