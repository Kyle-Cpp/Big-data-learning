package com.footpred.backend.domain;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Normalises the two season encodings used by the two halves of the platform.
 *
 * <p>The handoff package (modules 2/3 and P6.1-P6.4) derives its season code
 * straight from the source file name, so it emits the compact form {@code 1516}.
 * The partner package (P6.5-P6.8) formats the same season as {@code 2015-16}.
 * Both refer to the identical competition year, so every reader goes through
 * this class instead of comparing raw strings.
 */
public final class SeasonCode {

    private static final Map<String, String> COMPACT_TO_LABEL;
    private static final Map<String, String> LABEL_TO_COMPACT;

    static {
        Map<String, String> forward = new HashMap<String, String>();
        // 1993/94 .. 2025/26, the span covered by the football-data source set.
        for (int startYear = 1993; startYear <= 2025; startYear++) {
            String compact = twoDigits(startYear) + twoDigits(startYear + 1);
            String label = String.format("%04d-%s", startYear, twoDigits(startYear + 1));
            forward.put(compact, label);
            // StatsBomb seasons carry both years in full ("2015-2016"), because
            // stage_statsbomb.ps1 derives the directory from the upstream name.
            forward.put(String.format("%04d-%04d", startYear, startYear + 1), label);
        }
        COMPACT_TO_LABEL = Collections.unmodifiableMap(forward);

        Map<String, String> reverse = new HashMap<String, String>();
        for (int startYear = 1993; startYear <= 2025; startYear++) {
            String compact = twoDigits(startYear) + twoDigits(startYear + 1);
            reverse.put(String.format("%04d-%s", startYear, twoDigits(startYear + 1)), compact);
        }
        LABEL_TO_COMPACT = Collections.unmodifiableMap(reverse);
    }

    private SeasonCode() {
    }

    private static String twoDigits(int year) {
        return String.format("%02d", year % 100);
    }

    /**
     * Canonical form used by the API and the database: {@code 2015-16}.
     * Accepts either input encoding and returns the input unchanged when it is
     * not a season at all (for example the {@code WC-2022} career key).
     */
    public static String canonical(String season) {
        if (season == null || season.isEmpty()) {
            return season;
        }
        String value = season.trim();
        String label = COMPACT_TO_LABEL.get(value);
        if (label != null) {
            return label;
        }
        if (LABEL_TO_COMPACT.containsKey(value)) {
            return value;
        }
        return value;
    }

    /** Compact form used inside the handoff package: {@code 1516}. */
    public static String compact(String season) {
        if (season == null || season.isEmpty()) {
            return season;
        }
        String value = canonical(season.trim());
        String compact = LABEL_TO_COMPACT.get(value);
        return compact != null ? compact : value;
    }

    /** The starting calendar year, used for sorting and decade grouping. */
    public static int startYear(String season) {
        String value = canonical(season);
        if (value != null && value.length() >= 4) {
            try {
                return Integer.parseInt(value.substring(0, 4));
            } catch (NumberFormatException ignored) {
                return Integer.MIN_VALUE;
            }
        }
        return Integer.MIN_VALUE;
    }

    public static boolean isKnown(String season) {
        String value = canonical(season);
        return value != null && LABEL_TO_COMPACT.containsKey(value);
    }
}
