package com.adhocjoin.estimator;

import java.util.LinkedHashMap;
import java.util.Map;

/** Space of an estimator in bytes, split into named components (for example counters and sampled hashes). */
public record LogicalPayload(Map<String, Long> components) {
    public LogicalPayload {
        components = Map.copyOf(components);
    }

    public static LogicalPayload of(String name, long bytes) {
        return new LogicalPayload(Map.of(name, bytes));
    }

    public long totalBytes() {
        long total = 0L;
        for (long bytes : components.values()) {
            total += bytes;
        }
        return total;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Long> components = new LinkedHashMap<>();

        /** Adds bytes to a component, summing with earlier additions under the same name. */
        public Builder add(String name, long bytes) {
            components.merge(name, bytes, Long::sum);
            return this;
        }

        public LogicalPayload build() {
            return new LogicalPayload(components);
        }
    }
}
