package com.adhocjoin.harness;

import com.adhocjoin.model.TuplePredicate;
import com.adhocjoin.sketch.SketchConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Predicate/query builders and the query-file format shared by the experiment mains. */
public final class Queries {
    private Queries() {
    }

    public static TuplePredicate all() {
        return TuplePredicate.all();
    }

    public static TuplePredicate eq(int attribute, int value) {
        return TuplePredicate.attributeEquals(attribute, value);
    }

    public static TuplePredicate and(TuplePredicate... predicates) {
        return TuplePredicate.and(predicates);
    }

    public static TuplePredicate eq(SketchConfig.RelationSchema schema, String attribute, int value) {
        return eq(schema.attributeIndex(attribute), value);
    }

    /** Parses {@code ALL} or {@code a<attr>=<value>[&...]} equality conjunctions. */
    public static TuplePredicate parseIndexedPredicate(String text) {
        return parsePredicate(text, null);
    }

    /** Parses {@code ALL} or {@code <attribute>=<value>[&...]} equality conjunctions. */
    public static TuplePredicate parsePredicate(String text, SketchConfig.RelationSchema schema) {
        if (text.equalsIgnoreCase("ALL")) {
            return TuplePredicate.all();
        }
        String[] terms = text.split("&");
        TuplePredicate[] predicates = new TuplePredicate[terms.length];
        for (int i = 0; i < terms.length; i++) {
            String[] pair = terms[i].trim().split("=", 2);
            if (pair.length != 2) {
                throw new IllegalArgumentException("unsupported query predicate: " + text);
            }
            int attribute = attributeIndex(pair[0].trim(), schema);
            int value = Integer.parseInt(pair[1].trim());
            predicates[i] = eq(attribute, value);
        }
        return TuplePredicate.and(predicates);
    }

    /** A workload plus its pre-verified exact join sizes, loaded together since they come from the same
     *  {@code queries.csv} row. */
    public record MinedWorkload(List<NamedQuery> queries, Map<String, Long> exact) {
    }

    /** Loads a mined {@code queries.csv} (indexed {@code aN=value[&...]} / {@code ALL} predicates, with a
     *  pre-verified exact join size as the 5th column) as produced by {@code scripts/mine_queries.py}.
     *  {@code aN} predicates resolve by column index, so any schema works. */
    public static MinedWorkload loadMinedWorkload(Path queryFile) throws IOException {
        List<NamedQuery> queries = new ArrayList<>();
        Map<String, Long> exact = new LinkedHashMap<>();
        for (String line : Files.readAllLines(queryFile)) {
            String[] f = line.split(",", -1);
            if (f.length < 5 || f[0].equals("id")) {
                continue;
            }
            queries.add(NamedQuery.of(f[1], parseIndexedPredicate(f[2]), parseIndexedPredicate(f[3])));
            exact.put(f[1], Long.parseLong(f[4]));
        }
        return new MinedWorkload(List.copyOf(queries), Map.copyOf(exact));
    }

    private static int attributeIndex(String token, SketchConfig.RelationSchema schema) {
        if (token.startsWith("a") && token.length() > 1) {
            try {
                return Integer.parseInt(token.substring(1));
            } catch (NumberFormatException ignored) {
                // Fall through to schema-name resolution.
            }
        }
        if (schema == null) {
            throw new IllegalArgumentException("unsupported indexed predicate attribute: " + token);
        }
        return schema.attributeIndex(token);
    }
}
