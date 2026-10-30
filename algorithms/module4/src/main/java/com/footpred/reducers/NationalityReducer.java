package com.footpred.reducers;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

import com.footpred.common.VectorWritable;

/**
 * Job P6.8b reducer: aggregates the flattened lineups into
 * (league, season, country) player counts. Keys arrive sorted, so all
 * countries of one (league, season) are adjacent; the summary row (number of
 * countries, HHI, Shannon entropy, foreigner share) needs the whole
 * league-season, hence a streaming buffer flushed on group switch.
 *
 * Output rows (first column is the row kind):
 *   D \t league \t season \t country \t squadPlayers \t playedPlayers
 *   S \t league \t season \t nCountries \t squadPlayers \t hhi \t shannon
 *       \t foreignShare
 *
 * squadPlayers / playedPlayers are distinct player counts (squad = named in
 * the match-day lineup, played = entered the pitch at least once).
 * foreignShare = 1 - homeCountry/total over squad players; the league's home
 * country comes from a fixed map, empty when unknown.
 */
public class NationalityReducer extends Reducer<Text, VectorWritable, NullWritable, Text> {

    /** League display name -> home country of that league. */
    static final Map<String, String> HOME_COUNTRY = new LinkedHashMap<String, String>();

    static {
        HOME_COUNTRY.put("La Liga", "Spain");
        HOME_COUNTRY.put("Premier League", "England");
        HOME_COUNTRY.put("Serie A", "Italy");
        HOME_COUNTRY.put("Bundesliga", "Germany");
        HOME_COUNTRY.put("Ligue 1", "France");
    }

    private String curLeague;
    private String curSeason;
    private final Map<String, int[]> countryCounts = new LinkedHashMap<String, int[]>();

    @Override
    protected void reduce(Text key, Iterable<VectorWritable> values, Context context)
            throws IOException, InterruptedException {
        String[] f = key.toString().split("\t", 4);
        if (f.length < 4) {
            return;
        }
        String league = f[0];
        String season = f[1];
        String country = f[2];
        if (curLeague != null && (!league.equals(curLeague) || !season.equals(curSeason))) {
            flush(context);
        }
        curLeague = league;
        curSeason = season;

        double squadApps = 0.0;
        double playedApps = 0.0;
        for (VectorWritable v : values) {
            squadApps += v.get()[0];
            playedApps += v.get()[1];
        }
        // this reduce call = one distinct player of (league, season, country)
        int[] counts = countryCounts.get(country);
        if (counts == null) {
            counts = new int[2];
            countryCounts.put(country, counts);
        }
        counts[0]++; // distinct squad players
        if (playedApps > 0.0) {
            counts[1]++; // distinct players who actually played
        }
    }

    @Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
        if (curLeague != null) {
            flush(context);
        }
    }

    private void flush(Context context) throws IOException, InterruptedException {
        int nCountries = 0;
        int squadTotal = 0;
        for (int[] c : countryCounts.values()) {
            if (c[0] > 0) {
                nCountries++;
            }
            squadTotal += c[0];
        }
        for (Map.Entry<String, int[]> e : countryCounts.entrySet()) {
            String row = "D\t" + curLeague + "\t" + curSeason + "\t" + e.getKey()
                    + "\t" + e.getValue()[0] + "\t" + e.getValue()[1];
            context.write(NullWritable.get(), new Text(row));
        }
        double[] idx = squadTotal > 0
                ? diversityIndex(countryCounts, HOME_COUNTRY.get(curLeague))
                : new double[]{Double.NaN, Double.NaN, Double.NaN};
        String summary = "S\t" + curLeague + "\t" + curSeason
                + "\t" + nCountries + "\t" + squadTotal
                + "\t" + fmt(idx[0]) + "\t" + fmt(idx[1]) + "\t" + fmt(idx[2]);
        context.write(NullWritable.get(), new Text(summary));
        countryCounts.clear();
    }

    private static String fmt(double x) {
        return Double.isNaN(x) ? "" : String.format(Locale.ROOT, "%.4f", x);
    }

    /**
     * Diversity indices over squad-player shares by country.
     *
     * @return [HHI (sum of squared shares), Shannon entropy (-sum p ln p),
     *          foreignShare (1 - homeShare); NaN when home is unknown]
     */
    static double[] diversityIndex(Map<String, int[]> countryCounts, String homeCountry) {
        double total = 0.0;
        for (int[] c : countryCounts.values()) {
            total += c[0];
        }
        if (total <= 0.0) {
            return new double[]{Double.NaN, Double.NaN, Double.NaN};
        }
        double hhi = 0.0;
        double shannon = 0.0;
        double homeShare = 0.0;
        boolean homeFound = false;
        for (Map.Entry<String, int[]> e : countryCounts.entrySet()) {
            double p = e.getValue()[0] / total;
            hhi += p * p;
            if (p > 0.0) {
                shannon -= p * Math.log(p);
            }
            if (homeCountry != null && homeCountry.equals(e.getKey())) {
                homeShare = p;
                homeFound = true;
            }
        }
        double foreign = homeCountry != null && homeFound ? 1.0 - homeShare : Double.NaN;
        return new double[]{hhi, shannon, foreign};
    }
}
