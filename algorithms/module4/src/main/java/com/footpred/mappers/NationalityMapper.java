package com.footpred.mappers;

import java.io.IOException;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

import com.footpred.common.VectorWritable;

/**
 * Job P6.8b (history p68, step 2): player nationality diversity.
 *
 * Input: the lineups_flat TSV produced by P6.8a. Emits
 * key = league \t season \t country \t playerId, value = [squadApps, playedApps].
 * The sorted composite key makes every (league, season, country) group
 * adjacent, and each distinct playerId inside it triggers exactly one reduce
 * call, so the reducer can count distinct players without side data.
 */
public class NationalityMapper extends Mapper<LongWritable, Text, Text, VectorWritable> {

    public enum Counters {
        ROWS, BAD_LINE
    }

    private final Text outKey = new Text();

    @Override
    protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
        String line = value.toString().trim();
        if (line.isEmpty()) {
            return;
        }
        String[] f = line.split("\t", -1);
        if (f.length < 9) {
            context.getCounter(Counters.BAD_LINE).increment(1);
            return;
        }
        context.getCounter(Counters.ROWS).increment(1);
        outKey.set(f[0] + "\t" + f[1] + "\t" + f[7] + "\t" + f[5]);
        double played = "1".equals(f[8]) ? 1.0 : 0.0;
        context.write(outKey, new VectorWritable(new double[]{1.0, played}));
    }
}
