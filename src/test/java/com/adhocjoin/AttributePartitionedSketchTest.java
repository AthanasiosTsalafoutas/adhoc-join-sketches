package com.adhocjoin;

import com.adhocjoin.compose.CellCombination;
import com.adhocjoin.compose.ComposedJoinSketch;
import com.adhocjoin.compose.Methods;
import com.adhocjoin.compose.Partition;
import com.adhocjoin.model.BinaryJoinQuery;
import com.adhocjoin.model.BinaryTuple;
import com.adhocjoin.model.TuplePredicate;
import com.adhocjoin.sketch.SketchConfig.EqualityDomain;
import com.adhocjoin.sketch.SketchConfig.RelationSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Method 3 (attribute-partitioned joins, direct-index value cells) built by {@link Methods#attributePartitionedSketch}. */
class AttributePartitionedSketchTest {
    private static final RelationSchema SCHEMA = new RelationSchema(2,
            new EqualityDomain[]{new EqualityDomain(0, 0, 3), new EqualityDomain(1, 0, 2)});

    private static List<BinaryTuple> rows(int n, int keys, int salt) {
        List<BinaryTuple> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int key = (i * 7 + salt) % keys;
            out.add(new BinaryTuple(i, key, new int[]{(key + i) % 4, (i / 3 + salt) % 3}));
        }
        return out;
    }

    private static long exact(List<BinaryTuple> left, List<BinaryTuple> right, BinaryJoinQuery q) {
        long j = 0;
        for (BinaryTuple l : left) {
            if (!q.leftPredicate().test(l)) {
                continue;
            }
            for (BinaryTuple r : right) {
                if (r.joinKey() == l.joinKey() && q.rightPredicate().test(r)) {
                    j++;
                }
            }
        }
        return j;
    }

    private static ComposedJoinSketch filled(List<BinaryTuple> left, List<BinaryTuple> right, int width, int k) {
        ComposedJoinSketch e = Methods.attributePartitionedSketch(SCHEMA, SCHEMA, width, 3, k, 11L);
        left.forEach(e::updateLeft);
        right.forEach(e::updateRight);
        return e;
    }

    /** The hand-worked example: J(A,C) = 5, J(B,C) = 5, J(ALL,C) = 7, counts A = 3 < B = 4, truth 3. */
    @Test
    void combinationMatchesTheWorkedExample() {
        double[][] e = {{0, 7}, {0, 5}, {0, 5}};   // rows ALL, A, B; columns ALL, C
        double[] cl = {5, 3, 4};
        double[] cr = {5, 4};
        double[] c = CellCombination.combine(e, cl, cr, 1.0);
        assertEquals(5.0, c[0], 1e-12, "anchor = J(A,C)");
        assertEquals(5.0, c[1], 1e-12, "upper = min pair");
        assertEquals(3.0, c[2], 1e-12, "lower = J(A,C) - (J(ALL,C) - J(B,C))");
        assertEquals(25.0 / 7.0, c[3], 1e-12, "jw = 5 * 5 / 7");
        // the example's tuple overlap: rho = 0.83 gives 2.98 (no clipping to the bounds)
        double rho = (2.0 / 5.0) / ((3.0 / 5.0) * (4.0 / 5.0));
        assertEquals(25.0 / 7.0 * rho, CellCombination.combine(e, cl, cr, rho)[4], 1e-12);
        assertEquals(25.0 / 7.0 * 0.9, CellCombination.combine(e, cl, cr, 0.9)[4], 1e-12);
    }

    @Test
    void onePredicatePerSideIsExactWithoutCollisions() {
        List<BinaryTuple> left = rows(400, 30, 1);
        List<BinaryTuple> right = rows(300, 30, 2);
        ComposedJoinSketch e = filled(left, right, 1 << 16, 0);
        for (int a = 0; a < 4; a++) {
            for (int b = 0; b < 3; b++) {
                BinaryJoinQuery q = BinaryJoinQuery.of(TuplePredicate.attributeEquals(0, a),
                        TuplePredicate.attributeEquals(1, b));
                assertEquals(exact(left, right, q), e.estimate(q), 1e-9, "a0=" + a + " a1=" + b);
            }
        }
        BinaryJoinQuery all = BinaryJoinQuery.all();
        assertEquals(exact(left, right, all), e.estimate(all), 1e-9);
    }

    @Test
    void emptyPredicatesGiveZero() {
        ComposedJoinSketch e = filled(rows(200, 20, 1), rows(200, 20, 2), 1024, 0);
        BinaryJoinQuery outside = BinaryJoinQuery.of(TuplePredicate.attributeEquals(0, 9), TuplePredicate.all());
        BinaryJoinQuery conflicting = BinaryJoinQuery.of(TuplePredicate.and(TuplePredicate.attributeEquals(0, 1),
                TuplePredicate.attributeEquals(0, 2)), TuplePredicate.all());
        assertEquals(0.0, e.estimate(outside));
        assertEquals(0.0, e.estimate(conflicting));
    }

    @Test
    void rhoIsExactWhenNoCellOverflows() {
        List<BinaryTuple> left = rows(300, 25, 3);
        List<BinaryTuple> right = rows(300, 25, 4);
        ComposedJoinSketch e = filled(left, right, 1 << 16, 1_000);   // every cell fits in its sample
        TuplePredicate a = TuplePredicate.attributeEquals(0, 1);
        TuplePredicate b = TuplePredicate.attributeEquals(1, 2);
        long na = left.stream().filter(a::test).count();
        long nb = left.stream().filter(b::test).count();
        long nab = left.stream().filter(t -> a.test(t) && b.test(t)).count();
        double expected = left.size() * (double) nab / (na * (double) nb);
        ComposedJoinSketch.CellBreakdown d = e.cellBreakdown(BinaryJoinQuery.of(TuplePredicate.and(a, b), TuplePredicate.all()));
        assertEquals(expected, d.rho(), 1e-12);
    }

    @Test
    void sizeFitsTheBudgetAndPayloadIsCounted() {
        long budget = 200_000;
        int[] size = Methods.attributePartitionedSize(SCHEMA, SCHEMA, budget, 3, 1.0 / 3.0);
        ComposedJoinSketch e = Methods.attributePartitionedSketch(SCHEMA, SCHEMA, size[0], 3, size[1], 1L);
        int cells = 2 * Partition.AttributeCells.cellCount(SCHEMA);
        long expected = (long) cells * 3 * size[0] * 4 + cells * 4L + (cells - 2L) * size[1] * 4;
        assertEquals(expected, e.logicalPayloadBytes());
        assertTrue(e.logicalPayloadBytes() <= budget);
        assertTrue(e.logicalPayloadBytes() > budget * 0.95, "uses the budget");
    }
}
