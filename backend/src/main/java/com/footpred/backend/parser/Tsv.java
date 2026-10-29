package com.footpred.backend.parser;

import java.util.ArrayList;
import java.util.List;

/** Shared helpers for the tabular output parsers. */
final class Tsv {

    private Tsv() {
    }

    /**
     * Splits one line on tabs, preserving empty trailing fields.
     *
     * <p>The limit of {@code -1} matters: both packages rely on empty fields to
     * mean "no data for this metric" rather than zero, so a trailing tab must
     * survive the split.
     */
    static String[] split(String line) {
        String value = line;
        if (value.endsWith("\r")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.split("\t", -1);
    }

    /**
     * Returns the value fields of a reducer line, dropping a leading composite
     * key when one was written.
     *
     * <p>Several jobs use a {@code Text} key that already contains the row
     * identifiers, so {@code TextOutputFormat} emits
     * {@code "league|season"\t<value...>} — one column more than the value
     * itself. Others use {@code NullWritable} and emit the value alone. Rather
     * than hardcode an offset per job, the shape is detected: a leading field
     * containing the {@code |} separator alongside a one-column surplus can only
     * be the key.
     *
     * @param expectedValueColumns number of fields in the value payload
     */
    static String[] valueFields(String line, int expectedValueColumns) {
        String[] fields = split(line);
        if (fields.length == expectedValueColumns + 1 && fields[0].indexOf('|') >= 0) {
            String[] value = new String[expectedValueColumns];
            System.arraycopy(fields, 1, value, 0, expectedValueColumns);
            return value;
        }
        return fields;
    }

    /** True for blank lines and the {@code #} comment banners some jobs write. */
    static boolean isSkippable(String line) {
        if (line == null) {
            return true;
        }
        String trimmed = line.trim();
        return trimmed.isEmpty() || trimmed.startsWith("#");
    }

    static double toDouble(String value, double fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static Double toNullableDouble(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static int toInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** Splits the pipe-separated {@code topScores} / {@code rankHist} payloads. */
    static List<String> splitPipes(String value) {
        List<String> items = new ArrayList<String>();
        if (value == null || value.trim().isEmpty()) {
            return items;
        }
        for (String item : value.split("\\|")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return items;
    }
}
