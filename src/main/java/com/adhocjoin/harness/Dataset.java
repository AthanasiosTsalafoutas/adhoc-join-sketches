package com.adhocjoin.harness;

import com.adhocjoin.estimator.JoinEstimator;
import com.adhocjoin.model.BinaryTuple;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** A mined dataset as every experiment driver uses it: both relations, the query workload and its exact answers. */
public record Dataset(String name, MinedTables tables, List<NamedQuery> queries, Map<String, Long> exact) {

    /** Loads {@code leftTable.tsv}, {@code rightTable.tsv} and {@code queries.csv} from {@code dataDir}; with
     *  {@code -Dqueries=<file>} another workload of the same directory, e.g. {@code queries_conj.csv}. */
    public static Dataset load(String dataDir, String leftTable, String rightTable) throws IOException {
        Path dir = Path.of(dataDir);
        Queries.MinedWorkload workload = Queries.loadMinedWorkload(dir.resolve(System.getProperty("queries", "queries.csv")));
        return new Dataset(dir.getFileName().toString(), MinedTables.load(dir, leftTable, rightTable),
                workload.queries(), workload.exact());
    }

    public List<BinaryTuple> left() {
        return tables.left();
    }

    public List<BinaryTuple> right() {
        return tables.right();
    }

    public int leftAttributes() {
        return tables.leftAttributes();
    }

    public int rightAttributes() {
        return tables.rightAttributes();
    }

    /** Logical size of the raw data: 4 B for the key and for every attribute of every tuple. */
    public long rawBytes() {
        return (long) left().size() * (4 + 4L * leftAttributes()) + (long) right().size() * (4 + 4L * rightAttributes());
    }

    /** Feeds both relations to {@code estimator}; returns the ingest time in nanoseconds. */
    public long ingest(JoinEstimator estimator) {
        long t0 = System.nanoTime();
        left().forEach(estimator::updateLeft);
        right().forEach(estimator::updateRight);
        return System.nanoTime() - t0;
    }
}
