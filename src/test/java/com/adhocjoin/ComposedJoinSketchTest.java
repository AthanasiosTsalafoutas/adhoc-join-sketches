package com.adhocjoin;

import com.adhocjoin.compose.ComposedJoinSketch;
import com.adhocjoin.compose.ComposedJoinSketch.GroupRule;
import com.adhocjoin.compose.Counters;
import com.adhocjoin.compose.KeyHashing;
import com.adhocjoin.compose.Methods;
import com.adhocjoin.compose.Partition;
import com.adhocjoin.compose.Selectivity;
import com.adhocjoin.estimator.CellSampler;
import com.adhocjoin.estimator.JoinEstimator;
import com.adhocjoin.harness.Queries;
import com.adhocjoin.model.BinaryJoinQuery;
import com.adhocjoin.model.BinaryTuple;
import com.adhocjoin.model.TuplePredicate;
import com.adhocjoin.sketch.SketchConfig;
import com.adhocjoin.sketch.SketchConfig.EqualityDomain;
import com.adhocjoin.sketch.SketchConfig.RelationSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Regression digests of the method recipes: the same double bits and payload for a fixed seed. */
class ComposedJoinSketchTest {
    private static final RelationSchema SCHEMA = new RelationSchema(3, new EqualityDomain[]{
            new EqualityDomain(0, 0, 4), new EqualityDomain(1, 0, 2), new EqualityDomain(2, 0, 9)});

    /** Skewed keys (a few heavy) with attributes partly tied to the key, so rho fires. */
    private static List<BinaryTuple> rows(int n, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        List<BinaryTuple> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int key = rnd.nextInt(10) < 3 ? rnd.nextInt(5) : rnd.nextInt(3000);
            out.add(new BinaryTuple(i, key, new int[]{key % 5, rnd.nextInt(3), rnd.nextInt(10)}));
        }
        return out;
    }

    private static List<BinaryJoinQuery> queries() {
        List<BinaryJoinQuery> out = new ArrayList<>();
        out.add(BinaryJoinQuery.of(TuplePredicate.all(), TuplePredicate.all()));
        for (int v = 0; v < 3; v++) {
            out.add(BinaryJoinQuery.of(Queries.eq(0, v), TuplePredicate.all()));
            out.add(BinaryJoinQuery.of(Queries.eq(1, v), Queries.eq(2, v + 4)));
            out.add(BinaryJoinQuery.of(TuplePredicate.and(Queries.eq(0, v), Queries.eq(1, 1)),
                    Queries.eq(0, v)));
        }
        return out;
    }

    /** Payload, retained tuples and the raw bits of every query's estimate, folded into one number. */
    private static long digest(JoinEstimator e) {
        return digest(e, 1.0, ComposedJoinSketch.HeavyGroups.COUNT_EACH_SIDE);
    }

    /** As {@link #digest(JoinEstimator)}, with lazy evaluation of {@code fraction} of the groups when below 1. */
    private static long digest(JoinEstimator e, double fraction, ComposedJoinSketch.HeavyGroups heavy) {
        rows(20_000, 1L).forEach(e::updateLeft);
        rows(15_000, 2L).forEach(e::updateRight);
        long h = e.logicalPayloadBytes() * 31 + e.retainedTupleCount();
        for (BinaryJoinQuery q : queries()) {
            double est = e.estimate(q);
            if (fraction < 1.0) {
                est = ((ComposedJoinSketch) e).estimate(q, fraction, heavy);
            }
            h = h * 1_000_003L + Double.doubleToRawLongBits(est);
        }
        return h;
    }

    /** Method 1 digests on this data: reservoir cells of 8 with seed 7; Fast-AGMS (signed) and CMM. */
    @Test
    void method1Digests() {
        SketchConfig config = new SketchConfig(64, 3);
        assertEquals(0xb7bf7741478d4c00L,
                digest(Methods.sampledCellSketch(config, 3, 3, 7L, 7L, new CellSampler.Reservoir(8, 7L), true)));
        assertEquals(0x161375d07eb5c4aeL,
                digest(Methods.sampledCellSketch(config, 3, 3, 7L, 7L, new CellSampler.Reservoir(8, 7L), false)));
    }

    /** Method 2 digests on this data (Fast-AGMS and CMM): per-group medians summed, reservoir group samples. */
    @Test
    void method2Digests() {
        assertEquals(0x3bdb98b9c0377380L, digest(Methods.keyPartitionedSketch(16, 8, 3, 32, 3, 3, 5L, true)));
        assertEquals(0x2c7ca2aa012241d2L, digest(Methods.keyPartitionedSketch(16, 8, 3, 32, 3, 3, 5L, false)));
    }

    /** Lazy evaluation of Method 2 (a quarter of the groups), heavy groups by count on each side or none. */
    @Test
    void method2LazyDigests() {
        assertEquals(0x653f9a7202377380L, digest(Methods.keyPartitionedSketch(16, 8, 3, 32, 3, 3, 5L, true), 0.25,
                ComposedJoinSketch.HeavyGroups.COUNT_EACH_SIDE));
        assertEquals(0x48b0bedeaa377380L, digest(Methods.keyPartitionedSketch(16, 8, 3, 32, 3, 3, 5L, true), 0.25,
                ComposedJoinSketch.HeavyGroups.NONE));
    }

    /** Method 3 estimates (raw bits) and payloads on this data, without clipping to the pair bounds. */
    @Test
    void method3Digests() {
        long[] shared = {0x4155354240000000L, 0x4131459800000000L, 0x4105d28800000000L, 0x4116a1174fb3ccfbL, 0x41329bd200000000L, 0x4105998000000000L, 0x4118347e5155a6e3L, 0x412fbc1600000000L, 0x4105c7c000000000L};
        long[][] lastQuery = {{0, 0x411702eb58056a8aL}, {16, 0x412261cef9d07f93L}};
        long[] payload = {14_744L, 17_048L};
        for (int i = 0; i < 2; i++) {
            int k = (int) lastQuery[i][0];
            ComposedJoinSketch e = Methods.attributePartitionedSketch(SCHEMA, SCHEMA, 32, 3, k, 3L);
            rows(20_000, 1L).forEach(e::updateLeft);
            rows(15_000, 2L).forEach(e::updateRight);
            assertEquals(payload[i], e.logicalPayloadBytes(), "payload k=" + k);
            List<BinaryJoinQuery> qs = queries();
            for (int q = 0; q < qs.size(); q++) {
                long expected = q < shared.length ? shared[q] : lastQuery[i][1];
                assertEquals(expected, Double.doubleToRawLongBits(e.estimate(qs.get(q))), "k=" + k + " " + qs.get(q));
            }
        }
    }

    @Test
    void rejectsCombinationsWithoutAMeaning() {
        KeyHashing hashing = KeyHashing.fourUniversal(3, 16, 1L);
        Selectivity cells = new Selectivity.SampledCells(new CellSampler.Reservoir(4, 1L), 1L, 2L);
        assertThrows(IllegalArgumentException.class, () -> new ComposedJoinSketch(Partition.Keys.single(), hashing,
                Counters.FAST_AGMS, cells, GroupRule.GLOBAL, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ComposedJoinSketch(
                Partition.AttributeCells.direct(SCHEMA, SCHEMA), hashing, Counters.CMM, new Selectivity.CellWitnesses(0, 1L, 2L),
                GroupRule.GLOBAL, 3, 3));
    }
}
