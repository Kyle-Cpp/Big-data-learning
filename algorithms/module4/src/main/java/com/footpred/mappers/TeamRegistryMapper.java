package com.footpred.mappers;

import java.io.IOException;
import java.time.LocalDate;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

import com.footpred.common.MatchParser;
import com.footpred.common.RawMatch;

/**
 * Job P0a - TeamRegistry: emits every distinct team name (with a count) from
 * matches inside the training window. A single lexicographically-sorted
 * reducer output yields the deterministic team -> index mapping.
 */
public class TeamRegistryMapper extends Mapper<LongWritable, Text, Text, LongWritable> {

    public enum Counters {
        PARSED, SKIPPED_BEFORE_WINDOW, BAD_LINE
    }

    private static final LongWritable ONE = new LongWritable(1L);

    private long windowStartEpochDay;

    @Override
    protected void setup(Context context) {
        String startDate = context.getConfiguration().get("fp.start.date", "2016-01-01");
        windowStartEpochDay = LocalDate.parse(startDate).toEpochDay();
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
        context.write(new Text(match.homeTeam), ONE);
        context.write(new Text(match.awayTeam), ONE);
    }
}
