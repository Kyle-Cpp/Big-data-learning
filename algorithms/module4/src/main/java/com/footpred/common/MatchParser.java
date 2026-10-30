package com.footpred.common;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Parser for martj42/international_results results.csv lines.
 *
 * The file is a plain 9-column CSV without quoting (international team names
 * contain no commas, verified against the full 49,945-row dataset). Rows that
 * fail validation return null so the caller can count and skip them.
 */
public final class MatchParser {

    private static final int NUM_FIELDS = 9;
    private static final String DATE_PATTERN =
            "\\d{4}-\\d{2}-\\d{2}";

    private MatchParser() {
    }

    /**
     * Parses one CSV line.
     *
     * @return the match, or null for the header row / malformed rows.
     */
    public static RawMatch parse(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] f = trimmed.split(",");
        if (f.length < NUM_FIELDS) {
            return null;
        }
        for (int i = 0; i < NUM_FIELDS; i++) {
            f[i] = f[i].trim();
        }
        if (f[0].isEmpty() || !f[0].matches(DATE_PATTERN)) {
            return null; // header row or bad date
        }
        LocalDate date;
        try {
            date = LocalDate.parse(f[0]);
        } catch (DateTimeParseException e) {
            return null;
        }
        if (f[1].isEmpty() || f[2].isEmpty()) {
            return null;
        }
        Integer gh = parseIntStrict(f[3]);
        Integer ga = parseIntStrict(f[4]);
        if (gh == null || ga == null || gh < 0 || ga < 0) {
            return null;
        }
        String neutralField = f[8];
        if (!"TRUE".equalsIgnoreCase(neutralField) && !"FALSE".equalsIgnoreCase(neutralField)) {
            return null;
        }
        return new RawMatch(date.toEpochDay(), f[1], f[2], gh, ga, f[5],
                "TRUE".equalsIgnoreCase(neutralField));
    }

    private static Integer parseIntStrict(String s) {
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
