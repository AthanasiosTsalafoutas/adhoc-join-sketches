package com.adhocjoin.harness;

import com.adhocjoin.model.BinaryTuple;
import com.adhocjoin.source.CsvStreamSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The two relations of a mined dataset directory, loaded into memory: {@code <dataDir>/<leftTable>.tsv} and
 * {@code <rightTable>.tsv}, each with a header row, the join key as the first column and integer attributes
 * after it (the layout {@code scripts/mine_queries.py} expects). Shared by every benchmark driver.
 */
public record MinedTables(String[] leftNames, String[] rightNames, List<BinaryTuple> left, List<BinaryTuple> right) {

    public static MinedTables load(Path dataDir, String leftTable, String rightTable) throws IOException {
        Path leftPath = dataDir.resolve(leftTable + ".tsv");
        Path rightPath = dataDir.resolve(rightTable + ".tsv");
        String[] leftNames = attributeNames(leftPath);
        String[] rightNames = attributeNames(rightPath);
        CsvStreamSource source = new CsvStreamSource(new CsvStreamSource.Config(dataDir.getFileName().toString(),
                mapping(leftPath, leftNames), mapping(rightPath, rightNames)));
        List<BinaryTuple> left = new ArrayList<>();
        List<BinaryTuple> right = new ArrayList<>();
        source.forEachLeft(left::add);
        source.forEachRight(right::add);
        return new MinedTables(leftNames, rightNames, left, right);
    }

    public int leftAttributes() {
        return leftNames.length;
    }

    public int rightAttributes() {
        return rightNames.length;
    }

    /** Header names of every column after the join key. */
    private static String[] attributeNames(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path)) {
            String[] columns = reader.readLine().split("\t", -1);
            String[] names = new String[columns.length - 1];
            System.arraycopy(columns, 1, names, 0, names.length);
            return names;
        }
    }

    private static CsvStreamSource.Mapping mapping(Path path, String[] names) throws IOException {
        String keyColumn;
        try (var reader = Files.newBufferedReader(path)) {
            keyColumn = reader.readLine().split("\t", -1)[0];
        }
        return CsvStreamSource.Mapping.withColumnNames(path, '\t', null, keyColumn, false, names,
                new boolean[names.length]);
    }
}
