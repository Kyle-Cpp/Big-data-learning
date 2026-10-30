package com.footpred.backend.domain;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Normalises league identifiers across the two packages.
 *
 * <p>The handoff package keys everything by the {@code football-data} directory
 * name ({@code premier-league}); the partner package and every user-facing
 * figure use the display name ({@code Premier League}). The API always speaks
 * the display name and accepts either spelling on input.
 */
public final class LeagueCode {

    private static final Map<String, String> KEBAB_TO_LABEL;
    private static final Map<String, String> LABEL_TO_KEBAB;

    static {
        Map<String, String> forward = new HashMap<String, String>();
        forward.put("premier-league", "Premier League");
        forward.put("la-liga", "La Liga");
        forward.put("bundesliga", "Bundesliga");
        forward.put("serie-a", "Serie A");
        forward.put("ligue-1", "Ligue 1");
        KEBAB_TO_LABEL = Collections.unmodifiableMap(forward);

        Map<String, String> reverse = new HashMap<String, String>();
        for (Map.Entry<String, String> entry : forward.entrySet()) {
            reverse.put(entry.getValue(), entry.getKey());
        }
        LABEL_TO_KEBAB = Collections.unmodifiableMap(reverse);
    }

    private LeagueCode() {
    }

    /** Display form used by the API, e.g. {@code Premier League}. */
    public static String canonical(String league) {
        if (league == null || league.isEmpty()) {
            return league;
        }
        String value = league.trim();
        String label = KEBAB_TO_LABEL.get(value.toLowerCase());
        if (label != null) {
            return label;
        }
        for (Map.Entry<String, String> entry : LABEL_TO_KEBAB.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(value)) {
                return entry.getKey();
            }
        }
        return value;
    }

    /** Directory form used inside the handoff package, e.g. {@code premier-league}. */
    public static String kebab(String league) {
        if (league == null || league.isEmpty()) {
            return league;
        }
        String label = canonical(league);
        String kebab = LABEL_TO_KEBAB.get(label);
        return kebab != null ? kebab : label;
    }

    /** True when the identifier maps onto one of the five covered leagues. */
    public static boolean isKnown(String league) {
        String value = canonical(league);
        return value != null && LABEL_TO_KEBAB.containsKey(value);
    }
}
