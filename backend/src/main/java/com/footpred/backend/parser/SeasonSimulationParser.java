package com.footpred.backend.parser;

import com.footpred.backend.domain.LeagueCode;
import com.footpred.backend.domain.SeasonSimulation;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the Job P5.2 simulation output.
 *
 * <p>Column order follows {@code FootPredDriver.writeSimulationOutput}:
 * {@code league team avgPoints sdPoints avgRank pChampion pTop pRelegated
 * realRank realPoints rankHist}. The trailing histogram is a single
 * pipe-separated field, so it must not be split on whitespace.
 */
public final class SeasonSimulationParser {

    private SeasonSimulationParser() {
    }

    public static List<SeasonSimulation> parse(List<String> lines) {
        List<SeasonSimulation> result = new ArrayList<SeasonSimulation>();
        for (String line : lines) {
            if (Tsv.isSkippable(line)) {
                continue;
            }
            String[] f = Tsv.split(line);
            if (f.length < 11) {
                continue;
            }
            SeasonSimulation row = new SeasonSimulation();
            row.league = LeagueCode.canonical(f[0]);
            row.team = f[1];
            row.averagePoints = Tsv.toDouble(f[2], Double.NaN);
            row.pointsStandardDeviation = Tsv.toDouble(f[3], Double.NaN);
            row.averageRank = Tsv.toDouble(f[4], Double.NaN);
            row.championshipProbability = Tsv.toDouble(f[5], Double.NaN);
            row.topPlacesProbability = Tsv.toDouble(f[6], Double.NaN);
            row.relegationProbability = Tsv.toDouble(f[7], Double.NaN);
            row.actualRank = Tsv.toInt(f[8], -1);
            row.actualPoints = Tsv.toInt(f[9], -1);
            row.rankHistogram = f[10];
            result.add(row);
        }
        return result;
    }
}
