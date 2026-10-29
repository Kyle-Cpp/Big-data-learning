package com.footpred.backend.domain;

/**
 * Historical analysis results, normalised across the eight P6.* reports.
 *
 * <p>The two packages split the eight analyses between them and use different
 * identifiers, so {@link HistoryKind} carries both spellings. Every analysis
 * produces rows with its own shape; {@link HistoryRow} is the union used by the
 * generic {@code /api/results/history} endpoint, while callers that need the
 * full fidelity read the typed fields of the matching kind.
 */
public final class HistoryRow {

    public enum HistoryKind {
        CHAMPION_EVOLUTION("P6.1", "champions"),
        HOME_ADVANTAGE("P6.2", "home-advantage"),
        GOAL_MINUTES("P6.3", "goal-minutes"),
        HEAD_TO_HEAD("P6.4", "h2h"),
        CAREER_CURVE("P6.5", "p65"),
        LEAGUE_STYLE("P6.6", "p66"),
        SCORE_DIFFERENCE("P6.7", "p67"),
        NATIONALITY("P6.8", "p68");

        private final String officialId;
        private final String commandName;

        HistoryKind(String officialId, String commandName) {
            this.officialId = officialId;
            this.commandName = commandName;
        }

        /** Report identifier, e.g. {@code P6.5}. */
        public String officialId() {
            return officialId;
        }

        /** Command or analysis token, e.g. {@code p65}. */
        public String commandName() {
            return commandName;
        }

        public static HistoryKind fromToken(String token) {
            if (token == null) {
                return null;
            }
            String value = token.trim();
            for (HistoryKind kind : values()) {
                if (kind.officialId.equalsIgnoreCase(value)
                        || kind.commandName.equalsIgnoreCase(value)
                        || kind.name().equalsIgnoreCase(value)) {
                    return kind;
                }
            }
            return null;
        }
    }

    public HistoryKind kind;
    public String league;
    public String season;
    public String team;
    public String player;
    public String dimension;
    public double value;
    public double secondaryValue;
    public long count;

    public HistoryRow() {
    }
}
