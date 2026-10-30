package com.footpred.backend.parser;

import com.footpred.backend.domain.PlayerProfile;
import com.footpred.backend.domain.SeasonCode;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the Job P4.4 player profile output.
 *
 * <p>Column order follows {@code PlayerGoalProfileJob.ProfileReducer}:
 * {@code season teamId playerId competition teamName playerName position
 * appearances starts minutes shots goals xg xg90 shrunkXg90 expectedMinutes
 * lambda goalProbability}.
 *
 * <p>Note that the reducer's output key is a composite
 * {@code season|teamId|playerId}, while the value starts with the same
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
            // Value: season teamId playerId competition teamName playerName
            // position appearances starts minutes shots goals xg xg90 shrunkXg90
            // expectedMinutes lambda goalProbability.
            String[] f = Tsv.valueFields(line, 18);
            if (f.length != 18 || !SeasonCode.isKnown(f[0])
                    || Tsv.toInt(f[1], -1) < 0 || Tsv.toInt(f[2], -1) < 0) {
                throw new IllegalArgumentException("Invalid player profile: expected season, teamId, playerId and 18 value columns");
            }
            PlayerProfile profile = new PlayerProfile();
            profile.season = SeasonCode.canonical(f[0]);
            profile.teamId = Tsv.toInt(f[1], -1);
            profile.playerId = Tsv.toInt(f[2], -1);
            profile.competition = f[3];
            profile.teamName = f[4];
            profile.playerName = f[5];
            profile.position = f[6];
            profile.appearances = Tsv.toInt(f[7], -1);
            profile.starts = Tsv.toInt(f[8], -1);
            profile.minutes = Tsv.toDouble(f[9], Double.NaN);
            profile.shots = Tsv.toInt(f[10], -1);
            profile.goals = Tsv.toInt(f[11], -1);
            profile.xg = Tsv.toDouble(f[12], Double.NaN);
            profile.xgPer90 = Tsv.toDouble(f[13], Double.NaN);
            profile.shrunkXgPer90 = Tsv.toDouble(f[14], Double.NaN);
            profile.expectedMinutes = Tsv.toDouble(f[15], Double.NaN);
            profile.expectedGoals = Tsv.toDouble(f[16], Double.NaN);
            profile.goalProbability = Tsv.toDouble(f[17], Double.NaN);
            for (double value : new double[]{profile.appearances, profile.starts, profile.minutes,
                    profile.shots, profile.goals, profile.xg, profile.xgPer90, profile.shrunkXgPer90,
                    profile.expectedMinutes, profile.expectedGoals, profile.goalProbability}) {
                if (!Double.isFinite(value) || value < 0) {
                    throw new IllegalArgumentException("Invalid numeric value in player profile");
                }
            }
            if (profile.expectedMinutes > 90 || profile.goalProbability > 1) {
                throw new IllegalArgumentException("Player profile has invalid projected minutes or probability");
            }
            result.add(profile);
        }
        return result;
    }
}
