package com.adhocjoin.sketch;

/**
 * A predicate cell of Method 3: an exact count of the records that reached the cell plus a bottom-k of their record
 * hashes ({@link RecordHash}). The retained hashes are a uniform sample of the cell's records, so a predicate's count in
 * a hashed cell, or the overlap of several cells, comes from intersecting their samples ({@link WitnessIntersection}).
 */
public final class KMinCell {
    private long count;
    private final BottomKCell samples;

    public KMinCell(int capacity) {
        this.samples = new BottomKCell(capacity);
    }

    /** Counts one record and offers its record hash to the bottom-k. */
    public void add(long recordHash) {
        count++;
        samples.add(recordHash);
    }

    public long count() {
        return count;
    }

    public int capacity() {
        return samples.capacity();
    }

    public int size() {
        return samples.size();
    }

    /** Retained hashes in ascending unsigned order (cached; callers must not modify it). */
    public long[] sortedHashes() {
        return samples.sortedHashes();
    }

    /** The cell's admission threshold: its largest retained hash once it has overflowed, else -1 (unsigned max: the
     *  sample is the whole cell). Fixed by ingest, independent of any query. */
    public long threshold() {
        return threshold(count, samples.sortedHashes(), samples.capacity());
    }

    /** Threshold for a cell with the given record count and retained hashes: -1 if nothing overflowed, else the largest hash. */
    public static long threshold(long count, long[] sortedHashes, int capacity) {
        return count <= capacity ? -1L : sortedHashes[sortedHashes.length - 1];
    }
}
