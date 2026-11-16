package com.adhocjoin.estimator;

import com.adhocjoin.model.BinaryJoinQuery;
import com.adhocjoin.model.BinaryTuple;
import com.adhocjoin.model.RelationSide;
import com.adhocjoin.model.TuplePredicate;
import com.adhocjoin.sketch.SketchConfig;
import com.adhocjoin.source.CsvStreamSource;
import com.adhocjoin.source.SyntheticStreamSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EstimatorFrameworkTest {
    private static final SketchConfig CONFIG = new SketchConfig(101, 3);

    @TempDir
    Path tempDir;

    @Test
    void incorrectTupleAttributeCountsAreRejected() {
        PreFilteredSketchEstimator oracle = new PreFilteredSketchEstimator(
                BinaryJoinQuery.all(), CONFIG, 2, 11L);

        assertThrows(IllegalArgumentException.class, () -> oracle.updateRight(new BinaryTuple(1L, 1, new int[]{})));
    }

    @Test
    void oracleDoesNotAllocatePredicateCells() {
        PreFilteredSketchEstimator oracle = replay(smallSource(),
                new PreFilteredSketchEstimator(BinaryJoinQuery.all(), CONFIG, 2, 12L));

        assertFalse(oracle.logicalPayload().components().containsKey("predicate counters"));
        assertFalse(oracle.logicalPayload().components().containsKey("predicate bottom-k capacity"));
    }

    @Test
    void oracleUsesPairedCountSketchSize() {
        long seed = 1212L;
        PreFilteredSketchEstimator oracle = new PreFilteredSketchEstimator(
                BinaryJoinQuery.all(), CONFIG, 2, seed);
        long expectedPayload = 4L * CONFIG.depth() * CONFIG.width() * 4L;

        assertEquals(expectedPayload, oracle.logicalPayloadBytes());
    }

    @Test
    void directEqualityDomainMapsValuesToCells() {
        SketchConfig.EqualityDomain[] domains = {new SketchConfig.EqualityDomain(0, 10, 13)};

        assertEquals(0, domains[0].bucket(10));
        assertEquals(3, domains[0].bucket(13));
    }

    @Test
    void repeatedTrialsUseDistinctSeeds() {
        Set<Long> seeds = new HashSet<>();
        for (int trial = 0; trial < 4; trial++) {
            long trialSeed = 100L + 0x9E3779B97F4A7C15L * (trial + 1L);
            TwoLevelSamplingEstimator estimator = new TwoLevelSamplingEstimator(0.5, 1.0, trialSeed);
            long updateStart = System.nanoTime();
            replay(smallSource(), estimator);
            long updateNanos = System.nanoTime() - updateStart;
            long queryStart = System.nanoTime();
            estimator.estimate(BinaryJoinQuery.all());
            long queryNanos = System.nanoTime() - queryStart;
            seeds.add(trialSeed);
            assertTrue(updateNanos >= 0L);
            assertTrue(queryNanos >= 0L);
        }
        assertEquals(4, seeds.size());
    }

    @Test
    void reservoirCorrelatedAndTwoLevelMonteCarloAveragesApproachExactResult() {
        SyntheticStreamSource source = balancedSource();
        BinaryJoinQuery query = BinaryJoinQuery.all();
        double exact = exact(source, query);

        assertCloseToExact(source, query, exact, seed -> new ReservoirSamplingEstimator(60, seed), 500, 0.10);
        assertCloseToExact(source, query, exact, seed -> new TwoLevelSamplingEstimator(0.5, 1.0, seed), 500, 0.08);
        assertCloseToExact(source, query, exact, seed -> new TwoLevelSamplingEstimator(0.5, 0.7, seed), 700, 0.15);
    }

    @Test
    void oracleCountSketchEqualsExactWhenCollisionsAreAvoidedByChosenSmallFixture() {
        SyntheticStreamSource source = smallSource();
        BinaryJoinQuery query = primaryQuery();
        PreFilteredSketchEstimator oracle = new PreFilteredSketchEstimator(query, CONFIG, 2, 20L);
        assertNoRelevantCollisions(oracle, source);

        assertEquals(exact(source, query), replay(source, oracle).estimate(query), 0.0);
    }

    @Test
    void supportedPredicateKindsRemainExplicit() {
        assertEquals(TuplePredicate.Kind.ALL, TuplePredicate.all().kind());
        assertEquals(TuplePredicate.Kind.ATTRIBUTES, TuplePredicate.attributeEquals(0, 1).kind());
        assertEquals(TuplePredicate.Kind.OPAQUE, ((TuplePredicate) tuple -> true).kind());
    }

    @Test
    void leftAndRightJoinKeyBucketAndSignHashesAgree() {
        PreFilteredSketchEstimator sketch = new PreFilteredSketchEstimator(
                BinaryJoinQuery.all(), CONFIG, 2, 19L);

        for (int row = 0; row < CONFIG.depth(); row++) {
            assertEquals(sketch.bucket(RelationSide.LEFT, row, 33), sketch.bucket(RelationSide.RIGHT, row, 33));
            assertEquals(sketch.sign(RelationSide.LEFT, row, 33), sketch.sign(RelationSide.RIGHT, row, 33));
        }
    }

    @Test
    void twoLevelRejectsZeroQ() {
        assertThrows(IllegalArgumentException.class, () -> new TwoLevelSamplingEstimator(0.5, 0.0, 20L));
    }

    @Test
    void sameShapeAndSeedReproduceIdenticalStreams() {
        assertEquals(signature(new SyntheticStreamSource("repro", 1_200, 1_200, 120, 2, 101L)),
                signature(new SyntheticStreamSource("repro", 1_200, 1_200, 120, 2, 101L)));
    }

    @Test
    void fullReservoirCorrelatedOneAndTwoLevelOneAreExact() {
        SyntheticStreamSource source = balancedSource();
        BinaryJoinQuery query = BinaryJoinQuery.all();
        double exact = exact(source, query);

        assertEquals(exact, replay(source, new ReservoirSamplingEstimator(10_000, 21L)).estimate(query), 0.0);
        assertEquals(exact, replay(source, new TwoLevelSamplingEstimator(1.0, 1.0, 22L)).estimate(query), 0.0);
        assertEquals(exact, replay(source, new TwoLevelSamplingEstimator(1.0, 1.0, 23L)).estimate(query), 0.0);
    }

    @Test
    void selectedCsvColumnsAreParsedCorrectly() throws Exception {
        Path left = tempDir.resolve("left.csv");
        Path right = tempDir.resolve("right.csv");
        Files.writeString(left, "id,jkey,cat,a,ignored\n1,k7,red,42,x\n2,k8,blue,43,y\n");
        Files.writeString(right, "cat,a,ignored,jkey\nred,52,z,k7\n");
        CsvStreamSource source = new CsvStreamSource(new CsvStreamSource.Config(
                "csv-test",
                new CsvStreamSource.Mapping(left, ',', true, 0, 1, true, new int[]{3, 2}, new boolean[]{false, true}),
                new CsvStreamSource.Mapping(right, ',', true, -1, 3, true, new int[]{1, 0}, new boolean[]{false, true})
        ));

        java.util.ArrayList<BinaryTuple> leftTuples = new java.util.ArrayList<>();
        java.util.ArrayList<BinaryTuple> rightTuples = new java.util.ArrayList<>();
        source.forEachLeft(leftTuples::add);
        source.forEachRight(rightTuples::add);

        assertEquals(2, source.leftSize());
        assertEquals(1, source.rightSize());
        assertEquals(2, source.attributeCount());
        assertEquals(source.encodedJoinKey("k7"), leftTuples.get(0).joinKey());
        assertEquals(source.encodedJoinKey("k7"), rightTuples.get(0).joinKey());
        assertEquals(42, leftTuples.get(0).attribute(0));
        assertEquals(source.encodedValue(1, "red"), leftTuples.get(0).attribute(1));
        assertEquals(source.encodedValue(1, "red"), rightTuples.get(0).attribute(1));
    }

    @Test
    void numericCsvJoinKeysMustFitInInt() throws Exception {
        Path left = tempDir.resolve("left-big-key.csv");
        Path right = tempDir.resolve("right-big-key.csv");
        Files.writeString(left, "id,jkey,a\n1,2147483648,42\n");
        Files.writeString(right, "id,jkey,a\n2,1,52\n");
        CsvStreamSource source = new CsvStreamSource(new CsvStreamSource.Config(
                "csv-int-join-key",
                new CsvStreamSource.Mapping(left, ',', true, 0, 1, false, new int[]{2}, new boolean[]{false}),
                new CsvStreamSource.Mapping(right, ',', true, 0, 1, false, new int[]{2}, new boolean[]{false})
        ));

        assertThrows(ArithmeticException.class, () -> source.forEachLeft(tuple -> {
        }));
    }

    @Test
    void exactManyToManyJoinsAreComputedCorrectly() {
        SyntheticStreamSource source = new SyntheticStreamSource("many-to-many",
                List.of(
                        new BinaryTuple(1L, 1, new int[]{1, 1}),
                        new BinaryTuple(2L, 1, new int[]{1, 1})
                ),
                List.of(
                        new BinaryTuple(11L, 1, new int[]{1, 1}),
                        new BinaryTuple(12L, 1, new int[]{1, 1}),
                        new BinaryTuple(13L, 1, new int[]{1, 1})
                ));

        assertEquals(6.0, exact(source, BinaryJoinQuery.all()), 0.0);
    }

    private static void assertCloseToExact(SyntheticStreamSource source, BinaryJoinQuery query, double exact,
                                           java.util.function.LongFunction<JoinEstimator> factory, int trials, double tolerance) {
        double total = 0.0;
        for (int trial = 0; trial < trials; trial++) {
            total += replay(source, factory.apply(1_000L + trial * 31L)).estimate(query);
        }
        assertEquals(exact, total / trials, exact * tolerance);
    }

    private static void assertNoRelevantCollisions(PreFilteredSketchEstimator sketch,
                                                   SyntheticStreamSource source) {
        for (int row = 0; row < CONFIG.depth(); row++) {
            Set<Integer> leftKeys = new HashSet<>();
            Set<Integer> rightKeys = new HashSet<>();
            Set<Integer> leftJoinBuckets = new HashSet<>();
            Set<Integer> rightJoinBuckets = new HashSet<>();
            java.util.List<BinaryTuple> leftTuples = new java.util.ArrayList<>();
            java.util.List<BinaryTuple> rightTuples = new java.util.ArrayList<>();
            source.forEachLeft(leftTuples::add);
            source.forEachRight(rightTuples::add);
            for (BinaryTuple tuple : leftTuples) {
                if (leftKeys.add(tuple.joinKey())) {
                    assertTrue(leftJoinBuckets.add(sketch.bucket(RelationSide.LEFT, row, tuple.joinKey())));
                }
            }
            for (BinaryTuple tuple : rightTuples) {
                if (rightKeys.add(tuple.joinKey())) {
                    assertTrue(rightJoinBuckets.add(sketch.bucket(RelationSide.RIGHT, row, tuple.joinKey())));
                }
            }
        }
    }

    private static double exact(SyntheticStreamSource source, BinaryJoinQuery query) {
        return replay(source, new ExactFilteredJoinEstimator()).estimate(query);
    }

    private static String signature(SyntheticStreamSource source) {
        StringBuilder out = new StringBuilder();
        java.util.List<BinaryTuple> left = new java.util.ArrayList<>();
        java.util.List<BinaryTuple> right = new java.util.ArrayList<>();
        source.forEachLeft(left::add);
        source.forEachRight(right::add);
        append(out, left);
        out.append('|');
        append(out, right);
        return out.toString();
    }

    private static void append(StringBuilder out, List<BinaryTuple> tuples) {
        for (BinaryTuple tuple : tuples) {
            out.append(tuple.recordId()).append(':').append(tuple.joinKey());
            for (int attribute : tuple.attributes()) {
                out.append(':').append(attribute);
            }
            out.append(';');
        }
    }

    private static <T extends JoinEstimator> T replay(SyntheticStreamSource source, T estimator) {
        source.forEachLeft(estimator::updateLeft);
        source.forEachRight(estimator::updateRight);
        return estimator;
    }

    private static BinaryJoinQuery primaryQuery() {
        return BinaryJoinQuery.of(
                TuplePredicate.and(TuplePredicate.attributeEquals(0, 1), TuplePredicate.attributeEquals(1, 7)),
                TuplePredicate.and(TuplePredicate.attributeEquals(0, 2), TuplePredicate.attributeEquals(1, 5))
        );
    }

    private static double sum(double[] values) {
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum;
    }

    private static SyntheticStreamSource smallSource() {
        List<BinaryTuple> left = List.of(
                new BinaryTuple(1L, 10, new int[]{1, 7}),
                new BinaryTuple(2L, 10, new int[]{1, 7}),
                new BinaryTuple(3L, 20, new int[]{1, 8}),
                new BinaryTuple(4L, 30, new int[]{4, 7}),
                new BinaryTuple(5L, 40, new int[]{9, 9})
        );
        List<BinaryTuple> right = List.of(
                new BinaryTuple(101L, 10, new int[]{2, 5}),
                new BinaryTuple(102L, 10, new int[]{2, 5}),
                new BinaryTuple(103L, 20, new int[]{2, 6}),
                new BinaryTuple(104L, 30, new int[]{8, 5}),
                new BinaryTuple(105L, 40, new int[]{0, 9})
        );
        return new SyntheticStreamSource("small", left, right);
    }

    private static SyntheticStreamSource balancedSource() {
        java.util.ArrayList<BinaryTuple> left = new java.util.ArrayList<>();
        java.util.ArrayList<BinaryTuple> right = new java.util.ArrayList<>();
        long id = 1L;
        for (int key = 0; key < 20; key++) {
            for (int i = 0; i < 5; i++) {
                left.add(new BinaryTuple(id++, key, new int[]{key, key + 1}));
                right.add(new BinaryTuple(id++, key, new int[]{key, key + 2}));
            }
        }
        return new SyntheticStreamSource("balanced", left, right);
    }
}
