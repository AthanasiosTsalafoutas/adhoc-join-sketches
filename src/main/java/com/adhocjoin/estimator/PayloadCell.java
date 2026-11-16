package com.adhocjoin.estimator;

import java.util.List;

/**
 * A sketch cell that keeps an exact count of the records it saw plus a capacity-bounded sample of their payloads.
 * Implemented by {@link ReservoirCell}.
 */
public interface PayloadCell<T> {
    /** Records seen, sampled or not. */
    long count();

    /** Increments the exact count only, without touching the sample. */
    void touch();

    /** Offers {@code payload} (with its record hash) to the sample only. The count is not changed, see {@link #touch}.
     *  Returns the evicted payload, or {@code null} if none was. */
    T retain(long hash, T payload);

    /** The retained payloads. The order depends on the implementation. */
    List<T> payloads();

    /** Counts the record and offers it to the sample ({@link #touch} then {@link #retain}). */
    default T admit(long hash, T payload) {
        touch();
        return retain(hash, payload);
    }

    /** Payloads currently retained. */
    default int sampleSize() {
        return payloads().size();
    }
}
