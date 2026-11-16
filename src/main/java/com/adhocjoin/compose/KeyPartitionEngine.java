package com.adhocjoin.compose;

import com.adhocjoin.compose.ComposedJoinSketch.HeavyGroups;
import com.adhocjoin.compose.ComposedJoinSketch.GroupRule;
import com.adhocjoin.estimator.LogicalPayload;
import com.adhocjoin.model.BinaryJoinQuery;
import com.adhocjoin.model.BinaryTuple;
import com.adhocjoin.model.TuplePredicate;
import com.adhocjoin.sketch.Hashing;
import com.adhocjoin.sketch.JoinRows;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

/**
 * Key partitions (Methods 1 and 2): every tuple updates the sketch of the part its join key falls into. With
 * per-counter masses ({@link GroupSamples.CounterMasses}, Method 1) a row is the sum over parts of the part's row
 * estimate ({@link GroupRule} selects how), and the rows are combined by the median. With one selectivity per part
 * ({@link GroupSamples.PartSelectivities}, Method 2) the estimate is
 * {@code sum_p sigma_L(p) sigma_R(p) median_r J_p[r]}, {@code J_p} the part's unfiltered join from its own sketch.
 * This allows lazy evaluation of the parts.
 */
final class KeyPartitionEngine implements SketchEngine {
    private final Partition.Keys partition;
    private final KeyHashing hashing;
    private final Counters counters;
    private final Selectivity selectivity;
    private final GroupRule rule;
    private final int depth;
    private final int width;
    private final int partWidth;   // counters per part per row (twice the width for split signed cells)
    private final int rowWidth;    // counters per row
    private final KeySide left;
    private final KeySide right;
    /** One selectivity per part: rows are {@code sum_p sigma_L sigma_R J_p}. */
    private final boolean factorized;
    private boolean smallestPositiveFallback;

    KeyPartitionEngine(Partition.Keys partition, KeyHashing hashing, Counters counters, Selectivity selectivity,
                       GroupRule rule, int leftAttributeCount, int rightAttributeCount) {
        validate(partition, hashing, counters, selectivity, rule);
        this.partition = partition;
        this.hashing = hashing;
        this.counters = counters;
        this.selectivity = selectivity;
        this.rule = rule;
        this.depth = hashing.depth();
        this.width = hashing.width();
        // a row holds all parts side by side: part p owns the counters [p * partWidth, (p + 1) * partWidth)
        this.partWidth = width * counters.cellsPerBucket();
        this.rowWidth = partition.groups() * partWidth;
        GroupSamples.Grid grid = new GroupSamples.Grid(partition.groups(), depth, partWidth);
        this.left = new KeySide(GroupSamples.of(selectivity, grid, true), leftAttributeCount);
        this.right = new KeySide(GroupSamples.of(selectivity, grid, false), rightAttributeCount);
        // factorized: the estimate multiplies per-part selectivities with the part's unfiltered join
        this.factorized = left.samples instanceof GroupSamples.PartSelectivities
                && counters != Counters.FAST_AGMS_SPLIT
                && (counters.signedRow() || rule == GroupRule.PER_GROUP);
    }

    private static void validate(Partition.Keys k, KeyHashing hashing, Counters counters, Selectivity selectivity,
                                 GroupRule rule) {
        if (selectivity instanceof Selectivity.CellWitnesses) {
            throw new IllegalArgumentException("key partitions need cell or part samples");
        }
        if (selectivity instanceof Selectivity.SampledCells && counters == Counters.FAST_AGMS) {
            throw new IllegalArgumentException("cell samples need unsigned cell counts (FAST_AGMS_SPLIT or CMM)");
        }
        if (counters == Counters.CMM) {
            long buckets = rule == GroupRule.GLOBAL ? (long) k.groups() * hashing.width() : hashing.width();
            if (buckets < 2) {
                throw new IllegalArgumentException("the CMM row estimate needs at least two buckets");
            }
        }
    }

    @Override
    public void update(boolean leftSide, BinaryTuple tuple) {
        (leftSide ? left : right).update(tuple);
    }

    @Override
    public double estimate(BinaryJoinQuery query) {
        // Method 2: sum of scaled part estimates (every part, so the heavy-part choice does not matter). Method 1:
        // median over the rows of the masked sketch.
        return factorized ? estimate(query, 1.0, HeavyGroups.COUNT_EACH_SIDE) : combine(rows(query));
    }

    /** The per-row sums before the median (per-counter masses only). */
    double[] rowEstimates(BinaryJoinQuery query) {
        if (factorized) {
            throw new UnsupportedOperationException("one selectivity per part: each part takes its own median");
        }
        return rows(query);
    }

    private double[] rows(BinaryJoinQuery query) {
        // filtered counter masses of both sides, then one join estimate per row
        return rows(left.bucketMass(query.leftPredicate()), right.bucketMass(query.rightPredicate()));
    }

    @Override
    public void useSmallestPositiveFallback() {
        smallestPositiveFallback = true;
    }

    private double combine(double[] rows) {
        // the median of the rows for every counter type, also Count-Min: with sampled counters a row can
        // underestimate, so the minimum would no longer stay close to the filtered Count-Min. With the fallback,
        // a median <= 0 becomes the smallest positive row
        return smallestPositiveFallback ? JoinRows.medianOrSmallestPositive(rows) : JoinRows.median(rows);
    }

    @Override
    public void payload(LogicalPayload.Builder b) {
        b.add("join counters", 2L * depth * rowWidth * Counters.BYTES);
        left.samples.payload(b, "left", left.attributeCount);
        right.samples.payload(b, "right", right.attributeCount);
    }

    @Override
    public long retainedTupleCount() {
        return left.samples.retained() + right.samples.retained();
    }

    /** The sum over the evaluated parts of {@code scale sigma_L sigma_R median_r J_p[r]} (every part takes the
     *  median of its own rows). {@code fraction >= 1} evaluates all parts. Lazy evaluation ({@code fraction < 1}):
     *  with COUNT_EACH_SIDE the largest {@link ComposedJoinSketch#HEAVY_SHARE} of the parts of each side by tuple
     *  count are always evaluated, and the rest of about {@code fraction} of the parts are the first ones of a fixed
     *  random order of the remaining parts, scaled up to all of them; with NONE all of them come from that order. */
    double estimate(BinaryJoinQuery query, double fraction, HeavyGroups by) {
        if (!factorized) {
            throw new UnsupportedOperationException("lazy evaluation needs one selectivity per key part");
        }
        Lazy f = new Lazy();   // per query, no cache across queries: only the evaluated parts' joins are computed
        int parts = partition.groups();
        int[] evaluated;     // parts whose estimate is computed, heavy parts first
        double restScale;    // factor that scales the sampled rest parts up to all non-heavy parts
        int heavyCount;
        if (fraction >= 1.0) {
            evaluated = f.allParts;
            heavyCount = parts;
            restScale = 1.0;
        } else {
            // m = number of parts to evaluate in total
            int m = Math.max(1, (int) Math.round(fraction * parts));
            int[] heavy = by == HeavyGroups.NONE ? new int[0] : f.heavyByCount;
            int[] order = by == HeavyGroups.NONE ? f.randomOrder : f.restOrderByCount;
            // heavy parts count fully, the remaining budget takes the first parts of the fixed shuffled order
            heavyCount = Math.min(heavy.length, m);
            int rest = Math.min(order.length, Math.max(1, m - heavyCount));
            evaluated = new int[heavyCount + rest];
            System.arraycopy(heavy, 0, evaluated, 0, heavyCount);
            System.arraycopy(order, 0, evaluated, heavyCount, rest);
            restScale = (parts - heavyCount) / (double) rest;
        }
        // per-part selectivities of both predicates (matching fraction of the part's tuples)
        double[] sl = left.selectivities().partSelectivity(query.leftPredicate(), evaluated);
        double[] sr = right.selectivities().partSelectivity(query.rightPredicate(), evaluated);
        double all = 0.0;
        for (int i = 0; i < evaluated.length; i++) {
            double s = (i < heavyCount ? 1.0 : restScale) * sl[i] * sr[i];
            if (s == 0.0) {
                continue;   // nothing matches in this part: skip its join
            }
            double[] j = f.join(evaluated[i]);
            // the part's unfiltered join (median over rows), scaled by both selectivities
            all += s * JoinRows.median(j);
        }
        return all;
    }

    /** One query's view of the parts: unfiltered per-part row joins, computed only for the parts that are asked for,
     *  the heaviest parts by count, and a fixed order of the rest. Built per query (nothing is cached across queries),
     *  so query times include the joins of the evaluated parts. */
    private final class Lazy {
        private final double[][] join = new double[partition.groups()][];
        private final int[] allParts = new int[partition.groups()];
        private final int[] heavyByCount;
        private final int[] restOrderByCount;
        private final int[] randomOrder;

        private double[] join(int p) {
            if (join[p] == null) {
                double[] j = new double[depth];
                for (int r = 0; r < depth; r++) {
                    long[] lc = left.counterRows[r];
                    long[] rc = right.counterRows[r];
                    // the part's row estimate (inner product, or debiased for CMM)
                    j[r] = counters.row(lc, rc, p * width, (p + 1) * width);
                }
                join[p] = j;
            }
            return join[p];
        }

        private Lazy() {
            int parts = partition.groups();
            for (int p = 0; p < parts; p++) {
                allParts[p] = p;
            }
            // number of always-evaluated parts per side
            int heavyParts = Math.max(1, (int) Math.round(ComposedJoinSketch.HEAVY_SHARE * parts));
            double[] leftCount = new double[parts];
            double[] rightCount = new double[parts];
            for (int p = 0; p < parts; p++) {
                leftCount[p] = left.selectivities().partCount(p);
                rightCount[p] = right.selectivities().partCount(p);
            }
            // heavy by count: the largest parts of either side, from the part counters
            heavyByCount = IntStream.concat(Arrays.stream(LazyOrder.topByMass(leftCount, heavyParts)),
                    Arrays.stream(LazyOrder.topByMass(rightCount, heavyParts))).distinct().toArray();
            restOrderByCount = LazyOrder.shuffledRest(parts, heavyByCount,
                    new SplittableRandom(partition.orderSeed()));
            randomOrder = LazyOrder.shuffledRest(parts, new int[0], new SplittableRandom(partition.orderSeed()));
        }
    }

    /** Per inner row: unsigned GLOBAL is one debiased estimate over all buckets; otherwise the row is the sum of
     *  its part estimates. */
    private double[] rows(double[][] leftMass, double[][] rightMass) {
        double[] rows = new double[depth];
        for (int row = 0; row < depth; row++) {
            if (counters == Counters.CMM && rule == GroupRule.GLOBAL) {
                rows[row] = JoinRows.debiased(leftMass[row], rightMass[row]);
                continue;
            }
            // otherwise the row is the sum of the parts' estimates
            double total = 0.0;
            for (int p = 0; p < partition.groups(); p++) {
                int from = p * width;
                int to = from + width;
                total += counters.row(leftMass[row], rightMass[row], from, to);
            }
            rows[row] = total;
        }
        return rows;
    }

    /** One relation's counters (one long array per row) and its selectivity source. */
    private final class KeySide {
        private final long[][] counterRows = new long[depth][rowWidth];
        private final GroupSamples samples;
        private final int attributeCount;

        private KeySide(GroupSamples samples, int attributeCount) {
            this.samples = samples;
            this.attributeCount = attributeCount;
        }

        private GroupSamples.PartSelectivities selectivities() {
            return (GroupSamples.PartSelectivities) samples;
        }

        private void update(BinaryTuple tuple) {
            if (tuple.attributeCount() != attributeCount) {
                throw new IllegalArgumentException("tuple attribute count must equal configuredAttributeCount");
            }
            // the key picks the part, then each row hashes it to a bucket and a sign
            int key = tuple.joinKey();
            int part = partition.part(key);
            int base = part * partWidth;
            int[] slots = new int[depth];   // the counter hit in each row, handed to the selectivity source
            for (int row = 0; row < depth; row++) {
                int bucket = hashing.bucket(row, key);
                int slot;
                switch (counters) {
                    case FAST_AGMS -> {
                        // Fast-AGMS: add the key's sign to the bucket's counter
                        slot = base + bucket;
                        counterRows[row][slot] += hashing.sign(row, key);
                    }
                    case FAST_AGMS_SPLIT -> {
                        // split Fast-AGMS: count in the bucket's positive or negative cell by the key's sign
                        slot = base + Hashing.signedSlot(bucket, hashing.sign(row, key));
                        counterRows[row][slot]++;
                    }
                    default -> {
                        // CMM: plain count per bucket
                        slot = base + bucket;
                        counterRows[row][slot]++;
                    }
                }
                slots[row] = slot;
            }
            // let the sample structure see the tuple (Method 1: per-cell samples, Method 2: part sample)
            samples.add(tuple, part, slots);
        }

        /** [row][part * width + bucket] filtered bucket masses; a split bucket is positive minus negative. */
        private double[][] bucketMass(TuplePredicate predicate) {
            double[][] mass = ((GroupSamples.CounterMasses) samples).mass(predicate, counterRows);
            if (counters != Counters.FAST_AGMS_SPLIT) {
                return mass;
            }
            // fold each bucket's positive and negative cell into one signed value
            double[][] net = new double[depth][partition.groups() * width];
            for (int row = 0; row < depth; row++) {
                for (int p = 0; p < partition.groups(); p++) {
                    int base = p * partWidth;
                    for (int bucket = 0; bucket < width; bucket++) {
                        net[row][p * width + bucket] = mass[row][base + Hashing.signedSlot(bucket, 1)]
                                - mass[row][base + Hashing.signedSlot(bucket, -1)];
                    }
                }
            }
            return net;
        }
    }
}
