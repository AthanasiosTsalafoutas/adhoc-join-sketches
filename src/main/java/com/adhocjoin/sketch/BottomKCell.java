package com.adhocjoin.sketch;

import java.util.Arrays;

/**
 * Bottom-k (k-min) cell: keeps the {@code capacity} smallest distinct hashes seen, compared as unsigned longs. Once full,
 * a new hash is admitted only if it is below the current largest retained hash, which it then evicts. The hashes are
 * stored in an array sorted ascending, which grows on demand up to {@code capacity}.
 */
public final class BottomKCell {
    public static final int HASH_BYTES = Integer.BYTES;
    private static final int INITIAL_LENGTH = 8;

    private final int capacity;
    private long[] values;
    private int size;
    private long[] snapshot;   // exact-length ascending copy for the query path, rebuilt after any change

    public BottomKCell(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.values = new long[Math.min(capacity, INITIAL_LENGTH)];
    }

    /** Offers one hash to the cell. A duplicate of a retained hash changes nothing. */
    public void add(long hash) {
        if (size == capacity && Long.compareUnsigned(hash, values[size - 1]) >= 0) {
            return;   // full: only a hash below the current root is admitted
        }
        int pos = search(hash);
        if (pos >= 0) {
            return;   // duplicate: no change
        }
        int insert = -pos - 1;
        if (size < capacity) {
            if (size == values.length) {
                values = Arrays.copyOf(values, (int) Math.min(capacity, 2L * values.length));
            }
            System.arraycopy(values, insert, values, insert + 1, size - insert);
            size++;
        } else {
            // evict the root (last element): shift the tail right by one, dropping the old maximum
            System.arraycopy(values, insert, values, insert + 1, size - 1 - insert);
        }
        values[insert] = hash;
        snapshot = null;
    }

    public int capacity() {
        return capacity;
    }

    public int size() {
        return size;
    }

    /** Retained hashes in ascending unsigned order, as a cached exact-length array. Callers must not modify it. */
    public long[] sortedHashes() {
        long[] s = snapshot;
        if (s == null) {
            s = Arrays.copyOf(values, size);
            snapshot = s;
        }
        return s;
    }

    public long logicalPayloadBytes() {
        return (long) size * HASH_BYTES;
    }

    private int search(long hash) {
        int low = 0;
        int high = size - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int cmp = Long.compareUnsigned(values[mid], hash);
            if (cmp < 0) {
                low = mid + 1;
            } else if (cmp > 0) {
                high = mid - 1;
            } else {
                return mid;
            }
        }
        return -(low + 1);
    }
}
