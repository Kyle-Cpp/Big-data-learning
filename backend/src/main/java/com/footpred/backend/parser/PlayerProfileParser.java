package com.footpred.backend.parser;

import com.footpred.backend.domain.PlayerProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the Job P4.4 player profile output.
 *
 * <p>Column order follows {@code PlayerGoalProfileJob.ProfileReducer}:
 * {@code playerId teamId playerName competition teamName playerFullName position
 * appearances starts minutes shots goals xg xg90 shrunkXg90 expectedMinutes
 * lambda goalProbability}.
 *
 * <p>Note that the reducer's output key is a composite
 * {@code teamId|matchId|playerId}, while the value starts with the same
 * identifiers. The value is authoritative here because it carries the readable
 * names.
 */
public final class PlayerProfileParser {

    private PlayerProfileParser() {
    }

    public static List<PlayerProfile> parse(List<String> lines) {
        List<PlayerProfile> result = new ArrayList<PlayerProfile>();
        for (String line : lines) {
            if (Tsv.isSkippable(line)) {
                continue;
            }
            // Value: teamId matchId playerId competition teamName playerName
            // position appearances starts minutes shots goals xg xg90 shrunkXg90
            // expectedMinutes lambda goalProbability.
            String[] f = Tsv.valueFields(line, 18);
            if (f.length < 18) {
                continue;
            }
            PlayerProfile profile = new PlayerProfile();
            profile.teamId = Tsv.toInt(f[0], -1);
            profile.matchId = f[1];
            profile.playerId = Tsv.toInt(f[2], -1);
            profile.competition = f[3];
            profile.teamName = f[4];
            profile.playerName = f[5];
            profile.position = f[6];
            profile.appearances = Tsv.toInt(f[7], 0);
            profile.starts = Tsv.toInt(f[8], 0);
            profile.minutes = Tsv.toDouble(f[9], 0d);
            profile.shots = Tsv.toInt(f[10], 0);
            profile.goals = Tsv.toInt(f[11], 0);
            profile.xg = Tsv.toDouble(f[12], 0d);
            profile.xgPer90 = Tsv.toDouble(f[13], 0d);
            profile.shrunkXgPer90 = Tsv.toDouble(f[14], 0d);
            profile.expectedMinutes = Tsv.toDouble(f[15], 0d);
            profile.expectedGoals = Tsv.toDouble(f[16], 0d);
            profile.goalProbability = Tsv.toDouble(f[17], 0d);
            result.add(profile);
        }
        return result;
    }
}
