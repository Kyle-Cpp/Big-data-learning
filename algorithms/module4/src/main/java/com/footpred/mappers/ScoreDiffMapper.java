package com.footpred.mappers;

import java.io.IOException;
import java.time.LocalDate;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;

import com.footpred.common.MatchParser;
import com.footpred.common.RawMatch;
import com.footpred.common.VectorWritable;

/**
 * Job P6.7 (history p67): big-scoreline distribution.
 *
 * Reads results.csv-style inputs (international results + merged league
 * results, both 9-column CSV). The source label is derived from the input
 * path ("international" in the path -> intl, otherwise league). Emits
 * key = source \t decade \t bucket, value = [1] so the reducer can count
 * scoreline-margin buckets per decade and source.
 *
 * bucket is the signed (home view) goal difference clamped to [-5, +5];
 * decade is the floor(year/10)*10 + "s" label.
 */
public class ScoreDiffMapper extends Mapper<LongWritable, Text, Text, VectorWritable> {

    public enum Counters {
        MATCHES, BAD_LINE
    }

    private final Text outKey = new Text();
    private String source;
    private final StringBuilder keyBuf = new StringBuilder(32);

    @Override
    protected void setup(Context context) throws IOException {
        // one TextInputFormat split = one file for these small CSVs, so the
        // split path identifies the source reliably
        FileSplit split = (FileSplit) context.getInputSplit();
        String path = split.getPath().toString();
        this.source = path.contains("international") ? "intl" : "league";
    }

    @Override
    protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
        RawMatch m = MatchParser.parse(value.toString());
        if (m == null) {
            context.getCounter(Counters.BAD_LINE).increment(1);
            return;
        }
        int diff = m.homeScore - m.awayScore;
        int year = LocalDate.ofEpochDay(m.epochDay).getYear();
        int bucket = bucketOf(diff);
        String decade = decadeOf(year);
        keyBuf.setLength(0);
        keyBuf.append(source).append('\t').append(decade).append('\t').append(bucket);
        outKey.set(keyBuf.toString());
        context.getCounter(Counters.MATCHES).increment(1);
        context.write(outKey, new VectorWritable(new double[]{1.0}));
    }

    /** Signed goal difference clamped to [-5, +5]. */
    public static int bucketOf(int diff) {
        if (diff > 5) {
            return 5;
        }
        if (diff < -5) {
            return -5;
        }
        return diff;
    }

    /** "1870s" .. "2020s" label for a calendar year. */
    public static String decadeOf(int year) {
        return (Math.floorDiv(year, 10) * 10) + "s";
    }
}
