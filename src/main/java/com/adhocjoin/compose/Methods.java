package com.adhocjoin.compose;

import com.adhocjoin.compose.ComposedJoinSketch.GroupRule;
import com.adhocjoin.estimator.CellSampler;
import com.adhocjoin.sketch.Hashing;
import com.adhocjoin.sketch.SketchConfig;
import com.adhocjoin.sketch.SketchConfig.RelationSchema;

/**
 * The thesis methods as {@link ComposedJoinSketch} recipes. Hashing, the same in every variant of a method:
 * <ul>
 *   <li>join sketches (key to bucket and sign, Fast-AGMS and CMM): the 4-universal polynomial hash
 *       ({@link KeyHashing#fourUniversal});</li>
 *   <li>partitioning (Method 2's key groups, Method 3's hashed predicate cells) and record hashes: the
 *       64-bit mixer ({@link Hashing#bucket}, {@link com.adhocjoin.sketch.RecordHash}).</li>
 * </ul>
 * The method seed only derives the per-structure seeds.
 */
public final class Methods {
    private static final long LEFT = 0x4C454654L;
    private static final long RIGHT = 0x5249474854L;

    private Methods() {
    }

    /**
     * Method 1 (Sampled-Cell Sketch): the whole relation is one part, a {@code depth x width} sketch with 4-universal
     * hashing, Fast-AGMS split into +/- cells ({@code signed}) or CMM, and a reservoir tuple sample in every
     * cell ({@code sampler}).
     */
    public static ComposedJoinSketch sampledCellSketch(SketchConfig config, int leftAttributeCount,
                                                   int rightAttributeCount, long hashSeed, long sampleSeed,
                                                   CellSampler sampler, boolean signed) {
        return sampledCellSketch(config, leftAttributeCount, rightAttributeCount, hashSeed, sampleSeed, sampler,
                signed ? Counters.FAST_AGMS_SPLIT : Counters.CMM);
    }

    /** Method 1 with the given counters: FAST_AGMS_SPLIT, CMM or COUNT_MIN. */
    public static ComposedJoinSketch sampledCellSketch(SketchConfig config, int leftAttributeCount,
                                                   int rightAttributeCount, long hashSeed, long sampleSeed,
                                                   CellSampler sampler, Counters counters) {
        // salt that separates the cells' record hashes from the other seeds derived from sampleSeed
        long cellRecordSeed = 0x94D049BB133111EBL;
        return new ComposedJoinSketch(Partition.Keys.single(),
                KeyHashing.fourUniversal(config.depth(), config.width(), hashSeed),
                counters,
                new Selectivity.SampledCells(sampler, sampleSeed ^ LEFT ^ cellRecordSeed,
                        sampleSeed ^ RIGHT ^ cellRecordSeed),
                GroupRule.GLOBAL, leftAttributeCount, rightAttributeCount);
    }

    /**
     * Method 2 (Key-Partitioned Sketch): {@code groups} key groups, in each a {@code innerRows x subBins} sketch (Fast-AGMS
     * with one signed counter per bucket when {@code signed}, else CMM) and one reservoir tuple sample of {@code
     * groupCapacity} shared by the rows. Each group is estimated on its own (the median of its rows) and the scaled
     * group estimates are summed.
     */
    public static ComposedJoinSketch keyPartitionedSketch(int groups, int subBins, int innerRows, int groupCapacity,
                                                 int leftAttributeCount, int rightAttributeCount, long seed,
                                                 boolean signed) {
        if (subBins < 2) {
            throw new IllegalArgumentException("per-group estimates need at least two sub-bins");
        }
        // one seed per role (key groups, inner hashing, left and right record hashes) derived from the method seed
        return new ComposedJoinSketch(groups(groups, seed), innerHashing(innerRows, subBins, seed),
                signed ? Counters.FAST_AGMS : Counters.CMM,
                new Selectivity.GroupSample(groupCapacity, Hashing.derive(seed, LEFT),
                        Hashing.derive(seed, RIGHT)),
                GroupRule.PER_GROUP, leftAttributeCount, rightAttributeCount);
    }

    /** Method 2's per-group sketch hashing: 4-universal buckets and signs, the same in every group. */
    private static KeyHashing innerHashing(int innerRows, int subBins, long seed) {
        return KeyHashing.fourUniversal(innerRows, subBins, Hashing.derive(seed, 0x494E4E4552L));
    }

    /** Method 2's key groups (mixer hash of the key) and lazy-evaluation order, derived from the method seed. */
    private static Partition.Keys groups(int groups, long seed) {
        return Partition.Keys.groups(groups, Hashing.derive(seed, 0x47524F5550L), Hashing.derive(seed, 0x4C415A59L));
    }

    /**
     * Method 3 (Attribute-Partitioned Sketch) with direct-index cells from the schemas' equality domains: one Fast-AGMS
     * sketch ({@code depth x width}, shared hashes) per attribute=value cell and per side's ALL cell, plus a bottom-k of
     * {@code sampleCapacity} record hashes per predicate cell for the conjunction correction (0 = none).
     */
    public static ComposedJoinSketch attributePartitionedSketch(RelationSchema left, RelationSchema right, int width, int depth,
                                                    int sampleCapacity, long seed) {
        return attributePartitionedSketch(Partition.AttributeCells.direct(left, right), width, depth, sampleCapacity, seed);
    }

    /**
     * Method 3 on any cell layout ({@link Partition.AttributeCells}: direct from domains, direct from the observed values,
     * or hashed rows). With hashed cells the predicate counts come from the witness intersection of the predicate's
     * row-cell witnesses ({@code sampleCapacity} record hashes per cell).
     */
    public static ComposedJoinSketch attributePartitionedSketch(Partition.AttributeCells cells, int width, int depth, int sampleCapacity,
                                                    long seed) {
        if (width < 1 || depth < 1) {
            throw new IllegalArgumentException("width and depth must be positive");
        }
        return new ComposedJoinSketch(cells, KeyHashing.fourUniversal(depth, width, Hashing.derive(seed, 0x4A4F494EL)),
                new Selectivity.CellWitnesses(sampleCapacity, Hashing.derive(seed, 0x61L), Hashing.derive(seed, 0x62L)));
    }

    /**
     * Method 3 with hashed cells, for predicate domains too large to index directly: every attribute has {@code
     * cellRows} hashed rows of {@code cellWidth} predicate cells, and a predicate's count is the witness intersection of
     * its row cells' witnesses ({@code sampleCapacity} per cell).
     */
    public static ComposedJoinSketch hashedAttributePartitionedSketch(int leftAttributeCount, int rightAttributeCount, int cellWidth,
                                                          int cellRows, int width, int depth, int sampleCapacity,
                                                          long seed) {
        return attributePartitionedSketch(Partition.AttributeCells.hashed(leftAttributeCount, rightAttributeCount, cellWidth, cellRows,
                seed), width, depth, sampleCapacity, seed);
    }

    /**
     * Largest Method 3 width (and, with {@code sampleShare > 0}, per-cell sample capacity) fitting {@code budget}: the
     * record-hash samples take {@code sampleShare} of the budget, the counters (4 B) and cell counts (4 B) the rest.
     * Returns {width, capacity}.
     */
    public static int[] attributePartitionedSize(RelationSchema left, RelationSchema right, long budget, int depth,
                                          double sampleShare) {
        // every cell holds depth x width counters (4 B) and a count (4 B), the ALL cells keep no sample
        int cells = Partition.AttributeCells.cellCount(left) + Partition.AttributeCells.cellCount(right);
        int attributeCells = cells - 2;
        // the samples take their share of the budget first (4 B per record hash), the sketches get the rest
        long sampleBytes = Math.round(budget * sampleShare);
        int capacity = sampleShare > 0 ? (int) (sampleBytes / (4L * attributeCells)) : 0;
        long sketchBytes = budget - (long) capacity * 4L * attributeCells - cells * 4L;
        int width = (int) (sketchBytes / (4L * depth * cells));
        return new int[]{width, capacity};
    }
}
