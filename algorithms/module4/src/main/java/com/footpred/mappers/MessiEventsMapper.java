package com.footpred.mappers;

import java.io.IOException;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footpred.common.WholeFileInputFormat;

/**
 * Job P6.5b (history p65, step 2): Messi club-season career line.
 *
 * Input: staged StatsBomb events files of the matches Messi appeared in,
 * directory layout statsbomb/events/&lt;league&gt;/&lt;season&gt;/&lt;matchId&gt;.json,
 * one whole file per record. The mapper scans one match's event array once
 * and emits a single per-match vector for the target player (player id from
 * the configuration, 5503 for Messi), so the reducer only sums by season.
 *
 * Per-match vector layout (6): [apps, shots, goals, xG, assists, penGoals]
 * - apps = the target player produced at least one event in this match
 * - goals counts non-own-goal shots with outcome Goal; penGoals is the
 *   penalty subset (shot.type.name == Penalty)
 * - assists = passes with pass.goal_assist = true
 */
public class MessiEventsMapper extends Mapper<Text, Text, Text, com.footpred.common.VectorWritable> {

    public enum Counters {
        FILES, TARGET_APPS, TARGET_GOALS, BAD_JSON
    }

    public static final int DIM = 6;
    public static final int OFF_APPS = 0;
    public static final int OFF_SHOTS = 1;
    public static final int OFF_GOALS = 2;
    public static final int OFF_XG = 3;
    public static final int OFF_ASSISTS = 4;
    public static final int OFF_PEN_GOALS = 5;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private long targetPlayerId;
    private final Text outKey = new Text();

    @Override
    protected void setup(Context context) {
        targetPlayerId = context.getConfiguration().getLong("career.player.id", 5503L);
    }

    @Override
    protected void map(Text key, Text value, Context context)
            throws IOException, InterruptedException {
        org.apache.hadoop.fs.Path file = new org.apache.hadoop.fs.Path(key.toString());
        String league = LineupFlattenMapper.leagueLabel(
                file.getParent().getParent().getName());
        String season = file.getParent().getName();

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
        double[] v = extractStats(root, targetPlayerId);
        if (v[OFF_APPS] > 0.0) {
            context.getCounter(Counters.TARGET_APPS).increment(1);
            context.getCounter(Counters.TARGET_GOALS).increment((long) v[OFF_GOALS]);
            outKey.set(league + "\t" + season);
            context.write(outKey, new com.footpred.common.VectorWritable(v));
        }
    }

    /**
     * Scans one match's event array for the target player's statistics.
     * Static and JsonNode-based so unit tests can feed small JSON literals.
     */
    public static double[] extractStats(JsonNode root, long playerId) {
        double[] v = new double[DIM];
        boolean appeared = false;
        for (int i = 0; i < root.size(); i++) {
            JsonNode ev = root.get(i);
            JsonNode player = ev.path("player");
            if (!player.isObject() || player.path("id").asLong(-1L) != playerId) {
                continue;
            }
            appeared = true;
            String type = ev.path("type").path("name").asText("");
            if ("Shot".equals(type)) {
                v[OFF_SHOTS] += 1.0;
                if ("Goal".equals(ev.path("shot").path("outcome").path("name").asText())) {
                    v[OFF_GOALS] += 1.0;
                    if ("Penalty".equals(ev.path("shot").path("type").path("name").asText())) {
                        v[OFF_PEN_GOALS] += 1.0;
                    }
                }
                JsonNode xg = ev.path("shot").path("statsbomb_xg");
                if (xg.isNumber()) {
                    v[OFF_XG] += xg.asDouble();
                }
            } else if ("Pass".equals(type)
                    && ev.path("pass").path("goal_assist").asBoolean(false)) {
                v[OFF_ASSISTS] += 1.0;
            }
        }
        if (appeared) {
            v[OFF_APPS] = 1.0;
        }
        return v;
    }
}
