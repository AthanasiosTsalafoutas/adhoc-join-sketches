package com.adhocjoin;

import com.adhocjoin.experiments.LazyEvaluationBench;
import com.adhocjoin.experiments.MainExperiments;
import com.adhocjoin.experiments.Method1Bench;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every experiment driver runs end to end on a small generated dataset and writes the rows it promises. */
class ExperimentDriversTest {
    private static final String[] QUERIES = {"ALL|ALL", "a0=1|ALL", "ALL|a1=2", "a0=0|a1=1", "a0=1&a1=0|a0=2"};

    @TempDir
    static Path dir;
    private static long[] exact;

    /** Two relations joined on 40 keys (a few heavy), two small attributes each; queries.csv with exact answers. */
    @BeforeAll
    static void dataset() throws IOException {
        SplittableRandom rnd = new SplittableRandom(7L);
        int[][] left = rows(rnd, 6_000);
        int[][] right = rows(rnd, 4_000);
        write(dir.resolve("left.tsv"), left);
        write(dir.resolve("right.tsv"), right);
        exact = new long[QUERIES.length];
        List<String> lines = new ArrayList<>(List.of("id,name,left_predicate,right_predicate,exact"));
        for (int q = 0; q < QUERIES.length; q++) {
            String[] p = QUERIES[q].split("\\|");
            long[] l = new long[40];
            long[] r = new long[40];
            for (int[] t : left) if (matches(t, p[0])) l[t[0]]++;
            for (int[] t : right) if (matches(t, p[1])) r[t[0]]++;
            for (int k = 0; k < 40; k++) exact[q] += l[k] * r[k];
            lines.add(q + ",q" + q + "," + p[0] + "," + p[1] + "," + exact[q]);
        }
        Files.write(dir.resolve("queries.csv"), lines);
    }

    private static int[][] rows(SplittableRandom rnd, int n) {
        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) {
            int key = rnd.nextInt(4) == 0 ? rnd.nextInt(3) : rnd.nextInt(40);
            out[i] = new int[]{key, (key + rnd.nextInt(2)) % 3, rnd.nextInt(3)};
        }
        return out;
    }

    private static void write(Path path, int[][] rows) throws IOException {
        List<String> lines = new ArrayList<>(List.of("join_key\ta0\ta1"));
        for (int[] t : rows) lines.add(t[0] + "\t" + t[1] + "\t" + t[2]);
        Files.write(path, lines);
    }

    private static boolean matches(int[] t, String predicate) {
        if (predicate.equals("ALL")) return true;
        for (String eq : predicate.split("&")) {
            String[] kv = eq.split("=");
            if (t[1 + Integer.parseInt(kv[0].substring(1))] != Integer.parseInt(kv[1])) return false;
        }
        return true;
    }

    private static List<String[]> csv(Path path) throws IOException {
        List<String[]> out = new ArrayList<>();
        for (String line : Files.readAllLines(path).subList(1, Files.readAllLines(path).size())) out.add(line.split(","));
        return out;
    }

    private static String out(String name) {
        return dir.resolve(name).toString();
    }

    @Test
    void remainingDriversWriteRows() throws Exception {
        MainExperiments.main(new String[]{dir.toString(), "left", "right", "50", "1", "2", out("final.csv")});
        assertTrue(csv(dir.resolve("final.csv")).stream().anyMatch(r -> r[2].equals("method3")), "Method 3 rows");
        LazyEvaluationBench.main(new String[]{dir.toString(), "left", "right", "50", "4", "1", "2", out("lazy.csv")});
        assertTrue(csv(dir.resolve("lazy.csv")).size() > 0, "fallback/lazy rows");
        Method1Bench.main(new String[]{dir.toString(), "left", "right", "50", "16", "3", "1", "2", out("m1study.csv")});
        assertTrue(csv(dir.resolve("m1study.csv")).size() > 0, "Method 1 rows");
    }

    /** With -Doracle=true Method1Bench adds the oracle sketch next to Method 1 at every width and depth. */
    @Test
    void method1StudyRunsTheOracle() throws Exception {
        System.setProperty("oracle", "true");
        try {
            Method1Bench.main(new String[]{dir.toString(), "left", "right", "50", "16", "3", "1", "2",
                    out("m1oracle.csv"), "false"});
        } finally {
            System.clearProperty("oracle");
        }
        List<String[]> rows = csv(dir.resolve("m1oracle.csv"));
        String[] methods = {"m1-cmm", "m1-fastagms", "m1-countmin", "oracle", "oracle-countmin", "oracle-cmm"};
        for (String method : methods) {
            assertEquals(QUERIES.length, rows.stream().filter(r -> r[2].equals(method)).count(), method);
        }
        assertEquals(methods.length * QUERIES.length, rows.size(), "no baselines");
    }
}
