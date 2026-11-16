package com.adhocjoin.compose;

import com.adhocjoin.estimator.CellSampler;

/**
 * Where the predicate's effect on a counter comes from. {@link SampledCells} and {@link GroupSample} keep sampled
 * tuples (reservoir samples with their own random streams). {@link CellWitnesses} keeps record
 * hashes only.
 */
public sealed interface Selectivity
        permits Selectivity.SampledCells, Selectivity.GroupSample, Selectivity.CellWitnesses {

    /** A tuple sample in every counter cell (Method 1): a cell's filtered mass is its count times the matching
     *  fraction of its own sample. Needs unsigned cell counts ({@link Counters#FAST_AGMS_SPLIT} or {@link
     *  Counters#CMM}). Cells are created left side first, row by row, from one sampler (a reservoir sampler hands
     *  out its cell seeds in that order). */
    record SampledCells(CellSampler sampler, long leftRecordSeed, long rightRecordSeed) implements Selectivity {
    }

    /** One reservoir tuple sample per key part, shared by every row (Method 2): a counter's filtered mass is the
     *  counter times its part's matching fraction. The record seeds seed the parts' random streams. */
    record GroupSample(int capacity, long leftRecordSeed, long rightRecordSeed) implements Selectivity {
        public GroupSample {
            if (capacity < 1) {
                throw new IllegalArgumentException("capacity must be positive");
            }
        }
    }

    /** A k-min sample of record hashes (witnesses) in every predicate cell (Method 3, optional; capacity 0 = none): the conjunction
     *  correction rho from the witness intersection of the selected cells. */
    record CellWitnesses(int capacity, long leftRecordSeed, long rightRecordSeed) implements Selectivity {
        public CellWitnesses {
            if (capacity < 0) {
                throw new IllegalArgumentException("capacity must be non-negative");
            }
        }
    }
}
