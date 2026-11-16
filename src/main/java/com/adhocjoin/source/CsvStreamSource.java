package com.adhocjoin.source;

import com.adhocjoin.model.BinaryTuple;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Stream source that reads the two relations from delimited files. Columns can be dictionary-encoded to dense integers,
 * the dictionaries are built by one scan of the files at construction.
 */
public final class CsvStreamSource implements StreamSource {
    /**
     * Which columns of one file are the record id (negative: use the row number), the join key and the attributes,
     * and which of them are dictionary-encoded (the others are parsed as integers).
     */
    public record Mapping(
            Path path,
            char delimiter,
            boolean header,
            int recordIdColumn,
            int joinKeyColumn,
            boolean dictionaryEncodeJoinKey,
            int[] attributeColumns,
            boolean[] dictionaryEncodeAttributes
    ) {
        public Mapping {
            attributeColumns = attributeColumns.clone();
            dictionaryEncodeAttributes = dictionaryEncodeAttributes.clone();
            if (joinKeyColumn < 0 || attributeColumns.length == 0
                    || attributeColumns.length != dictionaryEncodeAttributes.length) {
                throw new IllegalArgumentException("invalid relation mapping");
            }
        }

        /** Builds a mapping for a file with a header row, looking the columns up by name. */
        public static Mapping withColumnNames(Path path, char delimiter, String recordIdColumnName,
                                              String joinKeyColumnName, boolean dictionaryEncodeJoinKey,
                                              String[] attributeColumnNames,
                                              boolean[] dictionaryEncodeAttributes) throws IOException {
            Map<String, Integer> columns = readHeaderColumns(path, delimiter);
            int recordIdColumn = recordIdColumnName == null ? -1 : columnIndex(columns, recordIdColumnName, path);
            int joinKeyColumn = columnIndex(columns, joinKeyColumnName, path);
            int[] attributeColumns = new int[attributeColumnNames.length];
            for (int i = 0; i < attributeColumnNames.length; i++) {
                attributeColumns[i] = columnIndex(columns, attributeColumnNames[i], path);
            }
            return new Mapping(path, delimiter, true, recordIdColumn, joinKeyColumn, dictionaryEncodeJoinKey,
                    attributeColumns, dictionaryEncodeAttributes);
        }
    }

    public record Config(String name, Mapping leftMapping, Mapping rightMapping) {
    }

    private final Config config;
    private final Map<String, Integer> joinKeyDictionary = new HashMap<>();
    private final Map<String, Integer>[] leftAttributeDictionaries;
    private final Map<String, Integer>[] rightAttributeDictionaries;
    private final long leftSize;
    private final long rightSize;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public CsvStreamSource(Config config) {
        this.config = config;
        this.leftAttributeDictionaries = new Map[config.leftMapping.attributeColumns.length];
        this.rightAttributeDictionaries = new Map[config.rightMapping.attributeColumns.length];
        for (int i = 0; i < leftAttributeDictionaries.length; i++) {
            leftAttributeDictionaries[i] = new HashMap<>();
        }
        for (int i = 0; i < rightAttributeDictionaries.length; i++) {
            rightAttributeDictionaries[i] = new HashMap<>();
        }
        scanDictionaries(config.leftMapping, leftAttributeDictionaries);
        scanDictionaries(config.rightMapping, rightAttributeDictionaries);
        this.leftSize = countRows(config.leftMapping);
        this.rightSize = countRows(config.rightMapping);
    }

    @Override
    public String name() {
        return config.name;
    }

    @Override
    public long leftSize() {
        return leftSize;
    }

    @Override
    public long rightSize() {
        return rightSize;
    }

    @Override
    public int attributeCount() {
        return leftAttributeCount();
    }

    @Override
    public int leftAttributeCount() {
        return leftAttributeDictionaries.length;
    }

    @Override
    public int rightAttributeCount() {
        return rightAttributeDictionaries.length;
    }

    @Override
    public void forEachLeft(Consumer<BinaryTuple> consumer) {
        read(config.leftMapping, leftAttributeDictionaries, consumer);
    }

    @Override
    public void forEachRight(Consumer<BinaryTuple> consumer) {
        read(config.rightMapping, rightAttributeDictionaries, consumer);
    }

    /** Dictionary code of a raw join-key string (the dictionary is shared by both sides). */
    public int encodedJoinKey(String raw) {
        return joinKeyDictionary.get(raw);
    }

    /** Dictionary code of a raw attribute string, looked up in the left side's dictionary for that attribute. */
    public int encodedValue(int attributeIndex, String raw) {
        return leftAttributeDictionaries[attributeIndex].get(raw);
    }

    private void read(Mapping mapping, Map<String, Integer>[] dictionaries, Consumer<BinaryTuple> consumer) {
        try (BufferedReader reader = Files.newBufferedReader(mapping.path)) {
            if (mapping.header) {
                reader.readLine();
            }
            // one tuple per line: id (or row number), join key, then the attribute columns
            String line;
            int generated = 0;
            while ((line = reader.readLine()) != null) {
                String[] columns = splitLine(line, mapping.delimiter);
                int recordId = mapping.recordIdColumn >= 0
                        ? parseIntRecordId(columns[mapping.recordIdColumn])
                        : generated;
                int joinKey = mapping.dictionaryEncodeJoinKey
                        ? joinKeyDictionary.get(columns[mapping.joinKeyColumn])
                        : parseIntJoinKey(columns[mapping.joinKeyColumn]);
                int[] attributes = new int[mapping.attributeColumns.length];
                for (int i = 0; i < attributes.length; i++) {
                    String value = columns[mapping.attributeColumns[i]];
                    attributes[i] = mapping.dictionaryEncodeAttributes[i]
                            ? dictionaries[i].get(value)
                            : Integer.parseInt(value);
                }
                consumer.accept(new BinaryTuple(recordId, joinKey, attributes));
                if (generated == Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("generated record IDs exceed int range");
                }
                generated++;
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + mapping.path, e);
        }
    }

    private int parseIntRecordId(String value) {
        long parsed = Long.parseLong(value);
        return Math.toIntExact(parsed);
    }

    private int parseIntJoinKey(String value) {
        long parsed = Long.parseLong(value);
        return Math.toIntExact(parsed);
    }

    // Assigns codes 0, 1, 2, ... to distinct strings in order of first appearance. The join-key dictionary is
    // shared by both sides so that equal keys get equal codes.
    private void scanDictionaries(Mapping mapping, Map<String, Integer>[] dictionaries) {
        if (!mapping.dictionaryEncodeJoinKey && !hasAttributeDictionaries(mapping)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(mapping.path)) {
            if (mapping.header) {
                reader.readLine();
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String[] columns = splitLine(line, mapping.delimiter);
                if (mapping.dictionaryEncodeJoinKey) {
                    joinKeyDictionary.computeIfAbsent(columns[mapping.joinKeyColumn], ignored -> joinKeyDictionary.size());
                }
                for (int i = 0; i < mapping.attributeColumns.length; i++) {
                    if (mapping.dictionaryEncodeAttributes[i]) {
                        int attributeIndex = i;
                        dictionaries[attributeIndex].computeIfAbsent(
                                columns[mapping.attributeColumns[attributeIndex]],
                                ignored -> dictionaries[attributeIndex].size()
                        );
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to scan dictionary values from " + mapping.path, e);
        }
    }

    private long countRows(Mapping mapping) {
        try (BufferedReader reader = Files.newBufferedReader(mapping.path)) {
            long count = mapping.header ? -1L : 0L;
            while (reader.readLine() != null) {
                count++;
            }
            return Math.max(0L, count);
        } catch (IOException e) {
            throw new IllegalStateException("failed to count rows in " + mapping.path, e);
        }
    }

    private boolean hasAttributeDictionaries(Mapping mapping) {
        for (boolean dictionary : mapping.dictionaryEncodeAttributes) {
            if (dictionary) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Integer> readHeaderColumns(Path path, char delimiter) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String header = reader.readLine();
            if (header == null) {
                throw new IllegalArgumentException("empty file: " + path);
            }
            String[] names = splitLine(header, delimiter);
            Map<String, Integer> out = new LinkedHashMap<>();
            for (int i = 0; i < names.length; i++) {
                if (names[i].isBlank() || out.put(names[i], i) != null) {
                    throw new IllegalArgumentException("header names must be unique and nonblank in " + path);
                }
            }
            return out;
        }
    }

    private static int columnIndex(Map<String, Integer> columns, String columnName, Path path) {
        Integer index = columns.get(columnName);
        if (index == null) {
            throw new IllegalArgumentException("unknown column '" + columnName + "' in " + path);
        }
        return index;
    }

    private static String[] splitLine(String line, char delimiter) {
        return line.split(java.util.regex.Pattern.quote(String.valueOf(delimiter)), -1);
    }
}
