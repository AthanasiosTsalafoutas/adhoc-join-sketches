package com.adhocjoin.compose;

import com.adhocjoin.model.AttributePredicate;
import com.adhocjoin.model.BinaryTuple;
import com.adhocjoin.model.TuplePredicate;
import com.adhocjoin.sketch.Hashing;
import com.adhocjoin.sketch.SketchConfig.EqualityDomain;
import com.adhocjoin.sketch.SketchConfig.RelationSchema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How a relation is split into parts, each with its own join sketch. Partitioning on the join key ({@link Keys})
 * keeps every key in one part, so the join is a sum over parts (Methods 1 and 2). Partitioning on predicate values
 * ({@link AttributeCells}) puts each tuple in one cell per attribute plus the unfiltered ALL cell, so a predicate selects
 * cells and the join is read off the selected cells' sketches (Method 3).
 */
public sealed interface Partition permits Partition.Keys, Partition.AttributeCells {

    /** {@code groups} groups of keys by a mixer hash of the key; one group is the whole relation. {@code orderSeed} fixes the
     *  random order of the light groups under lazy evaluation. */
    record Keys(int groups, long seed, long orderSeed) implements Partition {
        public Keys {
            if (groups < 1) {
                throw new IllegalArgumentException("at least one group");
            }
        }

        public static Keys single() {
            return new Keys(1, 0L, 0L);
        }

        public static Keys groups(int groups, long seed, long orderSeed) {
            return new Keys(groups, seed, orderSeed);
        }

        /** The group of a join key: a mixer hash of the key modulo the number of groups. */
        public int part(int key) {
            return groups == 1 ? 0 : Hashing.bucket(seed, key, groups);
        }
    }

    /**
     * One sketch cell per (attribute = value) of each side, plus the unfiltered ALL cell (id 0). A {@link Layout} maps a
     * value to one cell per layout row: a direct index has one row (cells from the equality domains, or one per value
     * observed in the data), a hashed index has {@code rows} hashed rows of {@code width} predicate cells each, so
     * every value shares its cells with the values hashed to the same buckets.
     */
    record AttributeCells(Layout left, Layout right) implements Partition {
        public AttributeCells {
            if (left.rows() != right.rows()) {
                throw new IllegalArgumentException("both sides need the same number of layout rows");
            }
        }

        /** One cell per value of each attribute's equality domain. */
        public static AttributeCells direct(RelationSchema left, RelationSchema right) {
            return new AttributeCells(Layout.domains(left), Layout.domains(right));
        }

        /** One cell per (attribute, value) that occurs in the data, numbered in order of first occurrence. */
        public static AttributeCells observed(List<BinaryTuple> left, int leftAttributes, List<BinaryTuple> right,
                                          int rightAttributes) {
            return new AttributeCells(Layout.observed(left, leftAttributes), Layout.observed(right, rightAttributes));
        }

        /** {@code rows} hashed rows of {@code width} cells per attribute. */
        public static AttributeCells hashed(int leftAttributes, int rightAttributes, int width, int rows, long seed) {
            return new AttributeCells(Layout.hashed(leftAttributes, width, rows, seed, 0),
                    Layout.hashed(rightAttributes, width, rows, seed, 1));
        }

        public static int cellCount(RelationSchema schema) {
            return Layout.domains(schema).cellCount();
        }

        boolean direct() {
            return left.rows() == 1;
        }
    }

    /** Cell addressing of one side of {@link AttributeCells}. */
    final class Layout {
        private final int attributeCount;
        private final int rows;
        private final int cellCount;
        private final EqualityDomain[] domains;     // domain layout
        private final int[] offset;
        private final Map<Long, Integer> index;     // observed layout
        private final long[] valueSeeds;            // hashed layout: [attribute * rows + row]
        private final int width;

        private Layout(int attributeCount, int rows, int cellCount, EqualityDomain[] domains, int[] offset,
                       Map<Long, Integer> index, long[] valueSeeds, int width) {
            this.attributeCount = attributeCount;
            this.rows = rows;
            this.cellCount = cellCount;
            this.domains = domains;
            this.offset = offset;
            this.index = index;
            this.valueSeeds = valueSeeds;
            this.width = width;
        }

        static Layout domains(RelationSchema schema) {
            EqualityDomain[] byAttribute = new EqualityDomain[schema.attributeCount()];
            for (EqualityDomain d : schema.equalityDomains()) {
                byAttribute[d.attributeIndex()] = d;
            }
            // cell 0 is ALL, then each attribute's domain gets a consecutive block of cells
            int[] offset = new int[byAttribute.length];
            int next = 1;
            for (int i = 0; i < byAttribute.length; i++) {
                if (byAttribute[i] == null) {
                    throw new IllegalArgumentException("value cells need an equality domain for attribute " + i);
                }
                offset[i] = next;
                next += byAttribute[i].size();
            }
            return new Layout(byAttribute.length, 1, next, byAttribute, offset, null, null, 0);
        }

        static Layout observed(List<BinaryTuple> rows, int attributes) {
            Map<Long, Integer> index = new HashMap<>();
            int next = 1;
            for (BinaryTuple t : rows) {
                for (int i = 0; i < attributes; i++) {
                    if (index.putIfAbsent(key(i, t.attribute(i)), next) == null) {
                        next++;
                    }
                }
            }
            return new Layout(attributes, 1, next, null, null, index, null, 0);
        }

        static Layout hashed(int attributes, int width, int rows, long seed, int side) {
            // one value-hash seed per (attribute, row), different for the two sides
            long[] seeds = new long[attributes * rows];
            for (int i = 0; i < seeds.length; i++) {
                seeds[i] = Hashing.derive(seed, 0x7777L + 1000L * side + i);
            }
            return new Layout(attributes, rows, 1 + attributes * rows * width, null, null, null, seeds, width);
        }

        private static long key(int attribute, int value) {
            return ((long) attribute << 32) | (value & 0xffffffffL);
        }

        public int attributeCount() {
            return attributeCount;
        }

        public int rows() {
            return rows;
        }

        public int cellCount() {
            return cellCount;
        }

        /** Cell of {@code attribute = value} in layout row {@code row}, or -1 when the value has no cell. */
        int cell(int attribute, int row, int value) {
            if (valueSeeds != null) {   // hashed layout: the value's bucket in this attribute's row
                int i = attribute * rows + row;
                return 1 + i * width + Hashing.bucket(valueSeeds[i], value, width);
            }
            if (index != null) {   // observed layout: lookup of the value seen in the data
                return index.getOrDefault(key(attribute, value), -1);
            }
            // domain layout: the cell is the value's position inside the attribute's block
            EqualityDomain d = domains[attribute];
            return value < d.minValue() || value > d.maxValue() ? -1 : offset[attribute] + d.bucket(value);
        }

        /** Per equality of the predicate, its cell in every layout row (-1 for a value without a cell: an empty cell);
         *  empty for ALL; null when the predicate selects nothing (two different values on one attribute). */
        int[][] cells(TuplePredicate predicate) {
            if (predicate.kind() == TuplePredicate.Kind.ALL) {
                return new int[0][];
            }
            if (predicate.kind() == TuplePredicate.Kind.OPAQUE) {
                throw new UnsupportedOperationException("value cells support ALL and equality predicates only");
            }
            // one entry per distinct attribute of the conjunction
            List<int[]> out = new ArrayList<>();
            int[] valueOf = new int[attributeCount];
            boolean[] seen = new boolean[attributeCount];
            for (AttributePredicate p : predicate.attributePredicates()) {
                int i = p.attributeIndex();
                if (i < 0 || i >= attributeCount) {
                    throw new IllegalArgumentException("predicate attribute out of range: " + i);
                }
                if (seen[i]) {
                    if (valueOf[i] != p.value()) {
                        return null;   // two different values on one attribute: nothing matches
                    }
                    continue;
                }
                seen[i] = true;
                valueOf[i] = p.value();
                int[] cells = new int[rows];
                for (int r = 0; r < rows; r++) {
                    cells[r] = cell(i, r, p.value());
                }
                out.add(cells);
            }
            return out.toArray(int[][]::new);
        }
    }
}
