package com.adhocjoin.sketch;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Shape of a key-hashed join sketch: {@code depth} rows of {@code width} buckets. Read by Method 1 and the oracle
 *  sketch. */
public record SketchConfig(int width, int depth) {
    public SketchConfig {
        if (width <= 0 || depth <= 0) {
            throw new IllegalArgumentException("width and depth must be positive");
        }
    }

    /** Integer range of one attribute, indexed directly: one cell per value. */
    public record EqualityDomain(int attributeIndex, int minValue, int maxValue) {
        public int size() {
            return maxValue - minValue + 1;
        }

        /** Cell index of {@code value}. */
        public int bucket(int value) {
            if (value < minValue || value > maxValue) {
                throw new IllegalArgumentException("attribute value is outside configured equality domain");
            }
            return value - minValue;
        }
    }

    /** Attribute names of a relation plus the equality domains of its directly indexed attributes. */
    public record RelationSchema(int attributeCount, String[] attributeNames, EqualityDomain[] equalityDomains) {
        public RelationSchema(int attributeCount) {
            this(attributeCount, defaultAttributeNames(attributeCount), new EqualityDomain[0]);
        }

        public RelationSchema(int attributeCount, EqualityDomain[] equalityDomains) {
            this(attributeCount, defaultAttributeNames(attributeCount), equalityDomains);
        }

        public RelationSchema(String[] attributeNames) {
            this(attributeNames.length, attributeNames, new EqualityDomain[0]);
        }

        public RelationSchema(String[] attributeNames, EqualityDomain[] equalityDomains) {
            this(attributeNames.length, attributeNames, equalityDomains);
        }

        public RelationSchema {
            attributeNames = Arrays.copyOf(attributeNames, attributeNames.length);
            equalityDomains = Arrays.copyOf(equalityDomains, equalityDomains.length);
            if (attributeCount <= 0) {
                throw new IllegalArgumentException("attributeCount must be positive");
            }
            if (attributeNames.length != attributeCount) {
                throw new IllegalArgumentException("attributeNames length must match attributeCount");
            }
            Set<String> seen = new LinkedHashSet<>();
            for (String attributeName : attributeNames) {
                if (attributeName == null || attributeName.isBlank() || !seen.add(attributeName)) {
                    throw new IllegalArgumentException("attribute names must be unique and nonblank");
                }
            }
            for (EqualityDomain equalityDomain : equalityDomains) {
                if (equalityDomain.attributeIndex < 0 || equalityDomain.attributeIndex >= attributeCount
                        || equalityDomain.minValue > equalityDomain.maxValue) {
                    throw new IllegalArgumentException("invalid equality domain");
                }
            }
        }

        public int attributeIndex(String attributeName) {
            for (int i = 0; i < attributeNames.length; i++) {
                if (attributeNames[i].equals(attributeName)) {
                    return i;
                }
            }
            throw new IllegalArgumentException("unknown attribute '" + attributeName + "'");
        }

        @Override
        public String[] attributeNames() {
            return Arrays.copyOf(attributeNames, attributeNames.length);
        }

        @Override
        public EqualityDomain[] equalityDomains() {
            return Arrays.copyOf(equalityDomains, equalityDomains.length);
        }

        private static String[] defaultAttributeNames(int attributeCount) {
            String[] names = new String[attributeCount];
            for (int i = 0; i < attributeCount; i++) {
                names[i] = "a" + i;
            }
            return names;
        }
    }
}
