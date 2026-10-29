package com.footpred.backend.parser;

import com.footpred.backend.domain.LeagueCode;
import com.footpred.backend.domain.MatchPrediction;
import com.footpred.backend.domain.SeasonCode;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses both match-prediction output formats into one type.
 *
 * <p>The two sources are genuinely different artefacts, not two spellings of the
 * same thing:
 * <ul>
 *   <li>{@code rf4-predictions-*} (handoff, 11 columns) is a time-ordered
 *       back-test: every row carries the {@code actual} label, so accuracy can
 *       be recomputed and the draw margin applied later;</li>
 *   <li>{@code predictions/part-m-*} (partner, 7 columns) is a forward fixture
 *       list: no {@code actual}, but expected goals and the top scorelines,
 *       which the UI needs for the score distribution chart.</li>
 * </ul>
 * Absent fields stay null rather than becoming zero, so the API never invents a
 * probability that was not computed.
 */
public final class MatchPredictionParser {

    /** Source tag for rows produced by the handoff module-2 random forest. */
    public static final String SOURCE_RANDOM_FOREST = "random-forest";
    /** Source tag for rows produced by the partner Dixon-Coles predictor. */
    public static final String SOURCE_DIXON_COLES = "dixon-coles";

    private MatchPredictionParser() {
    }

    /**
     * Handoff format: {@code match_id epoch_day season league home away actual
     * predicted p_home p_draw p_away}.
     */
    public static List<MatchPrediction> parseRandomForestPrediction(List<String> lines) {
        List<MatchPrediction> result = new ArrayList<MatchPrediction>();
        for (String line : lines) {
            if (Tsv.isSkippable(line)) {
                continue;
            }
            String[] f = Tsv.split(line);
            if (f.length < 11) {
                continue;
            }
            // The export script prepends a header row so the TSV is readable in
            // Excel; it must not become a prediction. Detect it by the literal
            // column name rather than by position, since the header is optional.
            if ("match_id".equalsIgnoreCase(f[0].trim())) {
                continue;
            }
            MatchPrediction prediction = new MatchPrediction();
            prediction.source = SOURCE_RANDOM_FOREST;
            prediction.matchId = f[0];
            prediction.epochDay = (long) Tsv.toDouble(f[1], 0d);
            prediction.season = SeasonCode.canonical(f[2]);
            prediction.league = LeagueCode.canonical(f[3]);
            prediction.homeTeam = f[4];
            prediction.awayTeam = f[5];
            prediction.actual = blankToNull(f[6]);
            prediction.predicted = blankToNull(f[7]);
            prediction.probabilityHome = Tsv.toDouble(f[8], Double.NaN);
            prediction.probabilityDraw = Tsv.toDouble(f[9], Double.NaN);
            prediction.probabilityAway = Tsv.toDouble(f[10], Double.NaN);
            // Both labels present means the row can be scored.
            if (prediction.actual != null && prediction.predicted != null) {
                prediction.correct = prediction.actual.equals(prediction.predicted);
            }
            result.add(prediction);
        }
        return result;
    }

    /**
     * Partner format: {@code matchKey pHome pDraw pAway lambdaHome lambdaAway
     * topScores}, with the three {@code topScores} entries appended after the
     * sixth column.
     */
    public static List<MatchPrediction> parseDixonColesPrediction(List<String> lines) {
        List<MatchPrediction> result = new ArrayList<MatchPrediction>();
        for (String line : lines) {
            if (Tsv.isSkippable(line)) {
                continue;
            }
            String[] f = Tsv.split(line);
            if (f.length < 6) {
                continue;
            }
            MatchPrediction prediction = new MatchPrediction();
            prediction.source = SOURCE_DIXON_COLES;
            prediction.matchId = f[0];
            String[] teams = splitMatchKey(f[0]);
            prediction.homeTeam = teams[0];
            prediction.awayTeam = teams[1];
            prediction.probabilityHome = Tsv.toDouble(f[1], Double.NaN);
            prediction.probabilityDraw = Tsv.toDouble(f[2], Double.NaN);
            prediction.probabilityAway = Tsv.toDouble(f[3], Double.NaN);
            prediction.lambdaHome = Tsv.toNullableDouble(f[4]);
            prediction.lambdaAway = Tsv.toNullableDouble(f[5]);
            if (f.length > 6) {
                prediction.topScores = Tsv.splitPipes(f[6]);
            }
            result.add(prediction);
        }
        return result;
    }

    /**
     * Splits the partner {@code matchKey} into its two team names.
     *
     * <p>The key is built as {@code home + " vs " + away}; team names themselves
     * may contain spaces but never the literal separator, so the first
     * occurrence is the split point.
     */
    static String[] splitMatchKey(String key) {
        String value = key == null ? "" : key;
        int index = value.indexOf(" vs ");
        if (index < 0) {
            return new String[]{value, ""};
        }
        return new String[]{
                value.substring(0, index).trim(),
                value.substring(index + 4).trim()
        };
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
