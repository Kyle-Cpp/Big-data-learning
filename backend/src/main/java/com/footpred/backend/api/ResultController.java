package com.footpred.backend.api;

import com.footpred.backend.domain.EvaluationReport;
import com.footpred.backend.domain.HistoryRow;
import com.footpred.backend.domain.LeagueCode;
import com.footpred.backend.domain.MatchPrediction;
import com.footpred.backend.domain.PlayerProfile;
import com.footpred.backend.domain.SeasonCode;
import com.footpred.backend.domain.SeasonSimulation;
import com.footpred.backend.parser.EvaluationParser;
import com.footpred.backend.parser.HistoryParser;
import com.footpred.backend.parser.MatchPredictionParser;
import com.footpred.backend.parser.PlayerProfileParser;
import com.footpred.backend.parser.SeasonSimulationParser;
import com.footpred.backend.service.ResultReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only result endpoints.
 *
 * <p>Each endpoint answers one question the dashboard actually asks, and returns
 * the same shape regardless of which package produced the data. Filtering happens
 * here rather than in the parsers so the parsers stay pure transformations.
 *
 * <p>Season and league filters accept either encoding; the response always uses
 * the canonical form.
 */
@RestController
@RequestMapping("/api/results")
public class ResultController {

    private final ResultReader reader;

    @Value("${footpred.result.module2-evaluation:}")
    private String module2EvaluationLocation;

    @Value("${footpred.result.simulation:}")
    private String simulationLocation;

    @Value("${footpred.result.real-table:}")
    private String realTableLocation;

    /**
     * Default location for {@code /api/results/matches}.
     *
     * <p>Kept separate from the evaluation directory: predictions are written per
     * run under {@code runs/<id>/}, while evaluation reports may be archived
     * elsewhere. Pointing both at one path made the matches endpoint return an
     * empty list even when prediction files existed.
     */
    @Value("${footpred.result.matches:}")
    private String matchesLocation;

    /** Default location for {@code /api/results/players}. */
    @Value("${footpred.result.players:}")
    private String playersLocation;

    /**
     * Default file per history analysis, keyed by official id (P6.1 … P6.8).
     *
     * <p>Each analysis writes its own TSV under a different run directory, so a
     * single default cannot cover them. Configured as
     * {@code footpred.result.history.p65=...} etc.
     */
    private final java.util.Map<String, String> historyLocations =
            new java.util.LinkedHashMap<String, String>();

    public ResultController(ResultReader reader,
                            @Value("${footpred.result.history.p61:}") String p61,
                            @Value("${footpred.result.history.p62:}") String p62,
                            @Value("${footpred.result.history.p63:}") String p63,
                            @Value("${footpred.result.history.p64:}") String p64,
                            @Value("${footpred.result.history.p65:}") String p65,
                            @Value("${footpred.result.history.p66:}") String p66,
                            @Value("${footpred.result.history.p67:}") String p67,
                            @Value("${footpred.result.history.p68:}") String p68) {
        this.reader = reader;
        putHistoryLocation("P6.1", p61);
        putHistoryLocation("P6.2", p62);
        putHistoryLocation("P6.3", p63);
        putHistoryLocation("P6.4", p64);
        putHistoryLocation("P6.5", p65);
        putHistoryLocation("P6.6", p66);
        putHistoryLocation("P6.7", p67);
        putHistoryLocation("P6.8", p68);
    }

    private void putHistoryLocation(String id, String value) {
        if (value != null && !value.trim().isEmpty()) {
            historyLocations.put(id, value.trim());
        }
    }

    // ------------------------------------------------------------ matches

    /**
     * Match predictions.
     *
     * @param source {@code random-forest} (handoff back-test) or
     *               {@code dixon-coles} (partner forward fixtures); both when absent
     * @param location explicit override, otherwise the configured default
     */
    @GetMapping("/matches")
    public ResponseEntity<Map<String, Object>> matches(
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "league", required = false) String league,
            @RequestParam(value = "season", required = false) String season,
            @RequestParam(value = "location", required = false) String location)
            throws IOException {

        String effectiveSource = source == null || source.trim().isEmpty()
                ? MatchPredictionParser.SOURCE_RANDOM_FOREST
                : source.trim();
        String target = location != null && !location.trim().isEmpty()
                ? location.trim()
                : (matchesLocation == null ? "" : matchesLocation);

        List<String> lines = reader.readAllLines(defaultPredictionsLocation(effectiveSource, target));
        List<MatchPrediction> predictions = MatchPredictionParser.SOURCE_DIXON_COLES
                .equals(effectiveSource)
                ? MatchPredictionParser.parseDixonColesPrediction(lines)
                : MatchPredictionParser.parseRandomForestPrediction(lines);

        predictions = filterMatches(predictions, league, season);

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("source", effectiveSource);
        payload.put("count", predictions.size());
        payload.put("accuracy", accuracy(predictions));
        payload.put("items", predictions);
        return ResponseEntity.ok(payload);
    }

    private String defaultPredictionsLocation(String source, String configured) {
        if (MatchPredictionParser.SOURCE_DIXON_COLES.equals(source)) {
            // Partner predictions live beside the model in an explicit --out dir;
            // there is no stable default, so the caller must supply one.
            return configured;
        }
        return configured;
    }

    private List<MatchPrediction> filterMatches(List<MatchPrediction> predictions,
                                                String league, String season) {
        String leagueFilter = league == null || league.trim().isEmpty()
                ? null : LeagueCode.canonical(league);
        String seasonFilter = season == null || season.trim().isEmpty()
                ? null : SeasonCode.canonical(season);
        if (leagueFilter == null && seasonFilter == null) {
            return predictions;
        }
        List<MatchPrediction> filtered = new ArrayList<MatchPrediction>();
        for (MatchPrediction prediction : predictions) {
            if (leagueFilter != null && !leagueFilter.equals(prediction.league)) {
                continue;
            }
            if (seasonFilter != null && !seasonFilter.equals(prediction.season)) {
                continue;
            }
            filtered.add(prediction);
        }
        return filtered;
    }

    private Double accuracy(List<MatchPrediction> predictions) {
        int scored = 0;
        int correct = 0;
        for (MatchPrediction prediction : predictions) {
            if (prediction.correct == null) {
                continue;
            }
            scored++;
            if (prediction.correct) {
                correct++;
            }
        }
        return scored == 0 ? null : correct / (double) scored;
    }

    // ----------------------------------------------------------- players

    @GetMapping("/players")
    public ResponseEntity<Map<String, Object>> players(
            @RequestParam(value = "competition", required = false) String competition,
            @RequestParam(value = "position", required = false) String position,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "location", required = false) String location)
            throws IOException {
        String target = location != null && !location.trim().isEmpty()
                ? location.trim()
                : (playersLocation == null || playersLocation.trim().isEmpty() ? null : playersLocation.trim());
        if (target == null) {
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("count", 0);
            empty.put("items", Collections.emptyList());
            empty.put("note", "no location configured for module-3 player output");
            return ResponseEntity.ok(empty);
        }
        List<PlayerProfile> profiles = PlayerProfileParser.parse(reader.readAllLines(target));
        profiles = filterPlayers(profiles, competition, position);
        // The reducer emits one row per (team, match, player), so an unbounded
        // ranking list would show the same player repeatedly. Collapse to one
        // row per (player, team), preferring the row with the most minutes so the
        // displayed rates belong to the largest sample.
        profiles = collapseByPlayerAndTeam(profiles);
        Collections.sort(profiles, new Comparator<PlayerProfile>() {
            @Override
            public int compare(PlayerProfile a, PlayerProfile b) {
                return Double.compare(b.shrunkXgPer90, a.shrunkXgPer90);
            }
        });
        if (limit > 0 && profiles.size() > limit) {
            profiles = new ArrayList<PlayerProfile>(profiles.subList(0, limit));
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("count", profiles.size());
        payload.put("items", profiles);
        return ResponseEntity.ok(payload);
    }

    /**
     * Collapses duplicate (player, team) rows left by the per-match reducer key.
     *
     * <p>Keeps the row with the most minutes: that is the largest sample, so its
     * xG/90 and shrinkage reflect the season rather than a single cameo.
     */
    private List<PlayerProfile> collapseByPlayerAndTeam(List<PlayerProfile> profiles) {
        java.util.Map<String, PlayerProfile> best = new java.util.LinkedHashMap<String, PlayerProfile>();
        for (PlayerProfile profile : profiles) {
            String key = profile.playerId + " "
                    + (profile.teamName == null ? "" : profile.teamName);
            PlayerProfile existing = best.get(key);
            if (existing == null || profile.minutes > existing.minutes) {
                best.put(key, profile);
            }
        }
        return new ArrayList<PlayerProfile>(best.values());
    }

    private List<PlayerProfile> filterPlayers(List<PlayerProfile> profiles,
                                              String competition, String position) {        if (competition == null && position == null) {
            return profiles;
        }
        List<PlayerProfile> filtered = new ArrayList<PlayerProfile>();
        for (PlayerProfile profile : profiles) {
            if (competition != null && !competition.equalsIgnoreCase(profile.competition)) {
                continue;
            }
            if (position != null && (profile.position == null
                    || !profile.position.toLowerCase().contains(position.toLowerCase()))) {
                continue;
            }
            filtered.add(profile);
        }
        return filtered;
    }

    // -------------------------------------------------------- simulation

    @GetMapping("/simulation")
    public ResponseEntity<Map<String, Object>> simulation(
            @RequestParam(value = "league", required = false) String league,
            @RequestParam(value = "location", required = false) String location)
            throws IOException {
        String target = location != null && !location.trim().isEmpty()
                ? location.trim() : simulationLocation;
        List<SeasonSimulation> rows = SeasonSimulationParser.parse(reader.readAllLines(target));
        if (league != null && !league.trim().isEmpty()) {
            String filter = LeagueCode.canonical(league);
            List<SeasonSimulation> filtered = new ArrayList<SeasonSimulation>();
            for (SeasonSimulation row : rows) {
                if (filter.equals(row.league)) {
                    filtered.add(row);
                }
            }
            rows = filtered;
        }
        Collections.sort(rows, new Comparator<SeasonSimulation>() {
            @Override
            public int compare(SeasonSimulation a, SeasonSimulation b) {
                int byLeague = a.league.compareTo(b.league);
                return byLeague != 0 ? byLeague : Double.compare(b.championshipProbability,
                        a.championshipProbability);
            }
        });
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("count", rows.size());
        payload.put("items", rows);
        return ResponseEntity.ok(payload);
    }

    /**
     * The real final table the simulation is compared against.
     *
     * <p>Kept separate from {@code /simulation} because it has its own header
     * comment block and a different column set; merging them would force one of
     * the two to lose information.
     */
    @GetMapping("/real-table")
    public ResponseEntity<Map<String, Object>> realTable(
            @RequestParam(value = "league", required = false) String league)
            throws IOException {
        List<String> lines = reader.readAllLines(realTableLocation);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        String filter = league == null || league.trim().isEmpty()
                ? null : LeagueCode.canonical(league);
        for (String line : lines) {
            if (line == null || line.trim().isEmpty() || line.trim().startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            if (f.length < 8) {
                continue;
            }
            String leagueName = LeagueCode.canonical(f[0]);
            if (filter != null && !filter.equals(leagueName)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("league", leagueName);
            row.put("team", f[1]);
            row.put("rank", parseInt(f[2]));
            row.put("points", parseInt(f[3]));
            row.put("played", parseInt(f[4]));
            row.put("goalsFor", parseInt(f[5]));
            row.put("goalsAgainst", parseInt(f[6]));
            row.put("goalDifference", parseInt(f[7]));
            rows.add(row);
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("count", rows.size());
        payload.put("items", rows);
        return ResponseEntity.ok(payload);
    }

    private Integer parseInt(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // ----------------------------------------------------------- history

    /**
     * Historical analysis rows.
     *
     * @param analysis official id ({@code P6.5}) or command token ({@code p65})
     */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> history(
            @RequestParam("analysis") String analysis,
            @RequestParam(value = "league", required = false) String league,
            @RequestParam(value = "season", required = false) String season,
            @RequestParam(value = "location", required = false) String location)
            throws IOException {
        HistoryRow.HistoryKind kind = HistoryRow.HistoryKind.fromToken(analysis);
        if (kind == null) {
            return ResponseEntity.badRequest().build();
        }
        String effectiveLocation = location;
        if (effectiveLocation == null || effectiveLocation.trim().isEmpty()) {
            // 前端默认不传 location，回落到 application.properties 的配置。
            effectiveLocation = historyLocations.get(kind.officialId());
        }
        if (effectiveLocation == null || effectiveLocation.trim().isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("analysis", kind.officialId());
            empty.put("count", 0);
            empty.put("items", Collections.emptyList());
            empty.put("note", "no location supplied for " + kind.officialId());
            return ResponseEntity.ok(empty);
        }
        location = effectiveLocation;
        List<String> lines = reader.readAllLines(location.trim());
        List<HistoryRow> rows = dispatchHistory(kind, lines);
        rows = filterHistory(rows, league, season);

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("analysis", kind.officialId());
        payload.put("order", kind.commandName());
        payload.put("count", rows.size());
        payload.put("items", rows);
        return ResponseEntity.ok(payload);
    }

    private List<HistoryRow> dispatchHistory(HistoryRow.HistoryKind kind, List<String> lines) {
        switch (kind) {
            case CHAMPION_EVOLUTION:
                return HistoryParser.parseChampions(lines);
            case HOME_ADVANTAGE:
                return HistoryParser.parseHomeAdvantage(lines);
            case GOAL_MINUTES:
                return HistoryParser.parseGoalMinutes(lines);
            case HEAD_TO_HEAD:
                return HistoryParser.parseHeadToHead(lines);
            case CAREER_CURVE:
                return parseCareer(lines);
            case LEAGUE_STYLE:
                return HistoryParser.parseLeagueStyle(lines);
            case SCORE_DIFFERENCE:
                return HistoryParser.parseScoreDifference(lines);
            case NATIONALITY:
                return HistoryParser.parseNationality(lines);
            default:
                return Collections.emptyList();
        }
    }

    /**
     * P6.5 writes two different files: a career table and a club-season table.
     * The column count tells them apart, so both can be served from one endpoint.
     */
    private List<HistoryRow> parseCareer(List<String> lines) {
        List<HistoryRow> career = HistoryParser.parseCareerCurve(lines);
        if (!career.isEmpty()) {
            return career;
        }
        return HistoryParser.parseCareerClub(lines);
    }

    private List<HistoryRow> filterHistory(List<HistoryRow> rows, String league, String season) {
        String leagueFilter = league == null || league.trim().isEmpty()
                ? null : LeagueCode.canonical(league);
        String seasonFilter = season == null || season.trim().isEmpty()
                ? null : SeasonCode.canonical(season);
        if (leagueFilter == null && seasonFilter == null) {
            return rows;
        }
        List<HistoryRow> filtered = new ArrayList<HistoryRow>();
        for (HistoryRow row : rows) {
            if (leagueFilter != null && !leagueFilter.equals(row.league)) {
                continue;
            }
            if (seasonFilter != null && !seasonFilter.equals(row.season)) {
                continue;
            }
            filtered.add(row);
        }
        return filtered;
    }

    // -------------------------------------------------------- evaluation

    @GetMapping("/evaluation")
    public ResponseEntity<Map<String, Object>> evaluation(
            @RequestParam(value = "split", defaultValue = "test") String split,
            @RequestParam(value = "location", required = false) String location)
            throws IOException {
        String target = location != null && !location.trim().isEmpty()
                ? location.trim() : stripTrailingSlash(module2EvaluationLocation) + "/evaluation-" + split + ".tsv";
        List<String> lines = reader.readAllLines(target);
        EvaluationReport report = EvaluationParser.parseFlat(split, lines);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("split", report.split);
        payload.put("matches", report.matches);
        payload.put("accuracy", report.accuracy);
        payload.put("macroF1", report.macroF1);
        payload.put("logLoss", report.logLoss);
        payload.put("rps", report.rps);
        payload.put("drawMargin", report.drawMargin);
        payload.put("confusion", report.confusion);
        payload.put("metrics", report.metrics);
        return ResponseEntity.ok(payload);
    }

    private String stripTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String result = value;
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
