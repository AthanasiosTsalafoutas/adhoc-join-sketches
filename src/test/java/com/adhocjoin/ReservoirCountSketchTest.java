package com.adhocjoin;

import com.adhocjoin.estimator.ReservoirCell;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservoirCountSketchTest {
    @Test
    void reservoirSampleIsUniform() {
        int n = 50;
        int k = 5;
        int trials = 20_000;
        int[] hits = new int[n];
        for (int t = 0; t < trials; t++) {
            ReservoirCell<Integer> cell = new ReservoirCell<>(k, t);
            for (int i = 0; i < n; i++) {
                cell.admit(0L, i);
            }
            assertEquals(n, cell.count());
            for (int v : cell.payloads()) {
                hits[v]++;
            }
        }
        double expected = trials * (double) k / n;
        for (int h : hits) {
            assertTrue(Math.abs(h - expected) < 6 * Math.sqrt(expected), "count " + h + " vs " + expected);
        }
    }
}
