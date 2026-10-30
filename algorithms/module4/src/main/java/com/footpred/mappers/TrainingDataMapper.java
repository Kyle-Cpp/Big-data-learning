package com.footpred.mappers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

import com.footpred.common.MatchParser;
import com.footpred.common.RawMatch;
import com.footpred.common.TeamRegistry;

/**
 * Job P0b - TrainingData: converts results.csv into the indexed, windowed and
 * walk-forward-split training format consumed by the DC gradient job and the
 * evaluation job.
 *
 * Output record (TSV):
 *
 *     epochDay <TAB> homeIdx <TAB> awayIdx <TAB> homeGoals <TAB> awayGoals
 *           <TAB> neutral(0/1) <TAB> friendly(0/1) <TAB> split(train|valid)
 *
 * Design notes:
 *  - Team names are normalised with former_names.csv (date-sensitive mapping)
 *    before lookup; unknown names are counted and skipped defensively.
 *  - The time-decay weight is NOT materialised here: it depends on the decay
 *    hyper-parameter, so the raw epochDay is kept and the weight is computed
 *    inside the training mapper. Re-sweeping xi never regenerates data.
 *  - Walk-forward split: matches before --valid.date are train, from that date
 *    on are valid. No random shuffling anywhere.
 */
public class TrainingDataMapper extends Mapper<LongWritable, Text, NullWritable, Text> {

    public enum Counters {
        PARSED, SKIPPED_BEFORE_WINDOW, UNKNOWN_TEAM, BAD_LINE, TRAIN_ROWS, VALID_ROWS
    }

    private long windowStartEpochDay;
    private long validStartEpochDay;
    private TeamRegistry registry;
    private final List<FormerName> formerNames = new ArrayList<FormerName>();

    private static final class FormerName {
        final String former;
        final String current;
        final long startDay;
        final long endDay;

        FormerName(String former, String current, long startDay, long endDay) {
            this.former = former;
            this.current = current;
            this.startDay = startDay;
            this.endDay = endDay;
        }
    }

    @Override
    protected void setup(Context context) throws IOException {
        Configuration conf = context.getConfiguration();
        windowStartEpochDay = LocalDate.parse(conf.get("fp.start.date", "2016-01-01")).toEpochDay();
        validStartEpochDay = LocalDate.parse(conf.get("fp.valid.date", "2025-07-01")).toEpochDay();

        String registryPath = conf.get("fp.registry.path");
        if (registryPath == null) {
            throw new IOException("fp.registry.path is required (run Job P0a first)");
        }
        registry = TeamRegistry.load(new Path(registryPath), conf);
        loadFormerNames(conf);
    }

    private void loadFormerNames(Configuration conf) throws IOException {
        String path = conf.get("fp.former.names.path");
        if (path == null) {
            return; // mapping file optional
        }
        FileSystem fs = new Path(path).getFileSystem(conf);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(fs.open(new Path(path)), StandardCharsets.UTF_8))) {
            String line = reader.readLine(); // header: current,former,start_date,end_date
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] f = line.split(",");
                if (f.length < 4) {
                    continue;
                }
                try {
                    formerNames.add(new FormerName(f[1].trim(), f[0].trim(),
                            LocalDate.parse(f[2].trim()).toEpochDay(),
                            LocalDate.parse(f[3].trim()).toEpochDay()));
                } catch (RuntimeException e) {
                    // keep defensive: skip malformed mapping rows
                }
            }
        }
    }

    private String normalise(String name, long epochDay) {
        for (FormerName fn : formerNames) {
            if (fn.former.equals(name) && epochDay >= fn.startDay && epochDay <= fn.endDay) {
                return fn.current;
            }
        }
        return name;
    }

    /**
     * Coarse tournament category used by the stratified evaluation (P1.2 of
     * the improvement plan). Five buckets, deliberately coarse to avoid a
     * hyper-parameter zoo:
     *
     *  major    - continental/world championships (FIFA WC, UEFA Euro, Copa
     *             America, African Cup of Nations, AFC Asian Cup, Gold Cup,
     *             OFC Nations Cup as the six confederation championships)
     *  qual     - any "... qualification" tournament
     *  regional - Nations Leagues and sub-confederation cups (COSAFA, AFF,
     *             CECAFA, Gulf Cup, WAFF, Arab Cup and similar)
     *  friendly - friendlies
     *  other    - anything not covered above (kept explicit instead of being
     *             force-classified, so it stays observable in the report)
     */
    public static String categoryOf(String tournament) {
        if (tournament == null) {
            return "other";
        }
        String t = tournament.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.isEmpty()) {
            return "other";
        }
        if ("friendly".equals(t)) {
            return "friendly";
        }
        if (t.endsWith("qualification")) {
            return "qual";
        }
        if ("fifa world cup".equals(t) || "uefa euro".equals(t)
                || t.equals("copa america") || t.equals("copa américa")
                || "african cup of nations".equals(t)
                || "afc asian cup".equals(t)
                || "gold cup".equals(t) || "concacaf championship".equals(t)
                || "ofc nations cup".equals(t)) {
            return "major";
        }
        if (t.endsWith("nations league")) {
            return "regional";
        }
        if ("cosafa cup".equals(t) || "aff championship".equals(t)
                || "cecafa cup".equals(t) || "gulf cup".equals(t)
                || "waff championship".equals(t) || "arab cup".equals(t)
                || "solidarity cup".equals(t) || "ancaf cup".equals(t)) {
            return "regional";
        }
        return "other";
    }

    @Override
    protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
        RawMatch match = MatchParser.parse(value.toString());
        if (match == null) {
            context.getCounter(Counters.BAD_LINE).increment(1);
            return;
        }
        if (match.epochDay < windowStartEpochDay) {
            context.getCounter(Counters.SKIPPED_BEFORE_WINDOW).increment(1);
            return;
        }
        context.getCounter(Counters.PARSED).increment(1);

        String home = normalise(match.homeTeam, match.epochDay);
        String away = normalise(match.awayTeam, match.epochDay);
        Integer homeIdx = registry.indexOf(home);
        Integer awayIdx = registry.indexOf(away);
        if (homeIdx == null || awayIdx == null) {
            context.getCounter(Counters.UNKNOWN_TEAM).increment(1);
            return;
        }

        String split = match.epochDay >= validStartEpochDay ? "valid" : "train";
        if ("train".equals(split)) {
            context.getCounter(Counters.TRAIN_ROWS).increment(1);
        } else {
            context.getCounter(Counters.VALID_ROWS).increment(1);
        }
        // column 9 (appended after split) keeps the positional parsing of
        // DCMapper/EvalMapper fields f[0..7] fully backward compatible
        String line = match.epochDay + "\t" + homeIdx + "\t" + awayIdx + "\t"
                + match.homeScore + "\t" + match.awayScore + "\t"
                + (match.neutral ? 1 : 0) + "\t"
                + (match.isFriendly() ? 1 : 0) + "\t"
                + split + "\t"
                + categoryOf(match.tournament);
        context.write(NullWritable.get(), new Text(line));
    }
}
