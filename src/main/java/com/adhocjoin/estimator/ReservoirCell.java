package com.adhocjoin.estimator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;

/**
 * {@link PayloadCell} with an exact count and a reservoir sample (Algorithm R) of at most {@code capacity} payloads.
 * Each cell draws from its own random stream, so its sample is independent of other cells. The record hash passed to
 * {@link #retain} is ignored.
 */
public final class ReservoirCell<T> implements PayloadCell<T> {
    private final int capacity;
    private final SplittableRandom random;
    private final ArrayList<T> reservoir;
    private long count;
    private List<T> view;

    public ReservoirCell(int capacity, long seed) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must be non-negative");
        }
        this.capacity = capacity;
        this.random = new SplittableRandom(seed);
        this.reservoir = new ArrayList<>(Math.min(capacity, 16));
    }

    @Override
    public void touch() {
        count++;
    }

    /** Algorithm R step for the {@code count}-th record seen (call after {@link #touch}): fill until full, then
     *  replace a random slot with probability {@code capacity / count}. Returns the evicted payload, if any. */
    @Override
    public T retain(long hash, T payload) {
        if (capacity == 0) {
            return null;
        }
        if (reservoir.size() < capacity) {
            reservoir.add(payload);
            view = null;
            return null;
        }
        long j = random.nextLong(Math.max(count, 1L));
        if (j >= capacity) {
            return null;
        }
        view = null;
        return reservoir.set((int) j, payload);
    }

    @Override
    public long count() {
        return count;
    }

    @Override
    public List<T> payloads() {
        List<T> cached = view;
        if (cached == null) {
            cached = Collections.unmodifiableList(new ArrayList<>(reservoir));
            view = cached;
        }
        return cached;
    }

    @Override
    public int sampleSize() {
        return reservoir.size();
    }
}
