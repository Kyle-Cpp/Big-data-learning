package com.footpred.mappers;

import java.io.IOException;

import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footpred.common.WholeFileInputFormat;

/**
 * Job P6.8a (history p68, step 1): flattens StatsBomb lineups JSON files
 * (one whole file per record via WholeFileInputFormat) into one TSV row per
 * listed player. League and season are taken from the directory layout
 * statsbomb/lineups/&lt;league&gt;/&lt;season&gt;/&lt;matchId&gt;.json, so no
 * join against the matches metadata is needed.
 *
 * Output row: league \t season \t matchId \t teamId \t teamName \t playerId
 *             \t playerName \t country \t played(0|1)
 * "played" = the player has a non-empty positions timeline (started or came
 * on); bench players who never entered count as squad only. Missing country
 * fields (0.007% of records) become "Unknown".
 */
public class LineupFlattenMapper
        extends Mapper<Text, Text, NullWritable, Text> {

    public enum Counters {
        FILES, PLAYERS, BAD_JSON, NO_COUNTRY
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Text outValue = new Text();
    private String league;
    private String season;
    private String matchId;
    private final StringBuilder row = new StringBuilder(128);

    @Override
    protected void map(Text key, Text value, Context context)
            throws IOException, InterruptedException {
        org.apache.hadoop.fs.Path file = new org.apache.hadoop.fs.Path(key.toString());
        league = leagueLabel(file.getParent().getParent().getName());
        season = file.getParent().getName();
        matchId = file.getName().replaceAll("\\.json$", "");
        context.getCounter(Counters.FILES).increment(1);

        JsonNode root;
        try {
            root = MAPPER.readTree(value.getBytes(), 0, value.getLength());
        } catch (IOException e) {
            context.getCounter(Counters.BAD_JSON).increment(1);
            return;
        }
        if (!root.isArray()) {
            context.getCounter(Counters.BAD_JSON).increment(1);
            return;
        }
        for (int i = 0; i < root.size(); i++) {
            JsonNode team = root.get(i);
            long teamId = team.path("team_id").asLong();
            String teamName = team.path("team_name").asText("");
            for (JsonNode p : team.path("lineup")) {
                context.getCounter(Counters.PLAYERS).increment(1);
                JsonNode country = p.path("country");
                String countryName = country.isObject() && !country.path("name").isMissingNode()
                        ? country.path("name").asText("") : "";
                if (countryName.isEmpty()) {
                    countryName = "Unknown";
                    context.getCounter(Counters.NO_COUNTRY).increment(1);
                }
                boolean played = p.path("positions").isArray()
                        && p.path("positions").size() > 0;
                row.setLength(0);
                row.append(league).append('\t').append(season).append('\t')
                        .append(matchId).append('\t')
                        .append(teamId).append('\t')
                        .append(teamName).append('\t')
                        .append(p.path("player_id").asLong()).append('\t')
                        .append(p.path("player_name").asText("")).append('\t')
                        .append(countryName).append('\t')
                        .append(played ? 1 : 0);
                outValue.set(row.toString());
                context.write(NullWritable.get(), outValue);
            }
        }
    }

    /**
     * Maps the kebab-case staging directory name to the display name used
     * across the project; unknown directories (other competitions staged
     * later) pass through unchanged.
     */
    static String leagueLabel(String dirName) {
        switch (dirName) {
            case "la-liga": return "La Liga";
            case "premier-league": return "Premier League";
            case "serie-a": return "Serie A";
            case "bundesliga":
            case "1-bundesliga": return "Bundesliga";
            case "ligue-1": return "Ligue 1";
            default: return dirName;
        }
    }
}
