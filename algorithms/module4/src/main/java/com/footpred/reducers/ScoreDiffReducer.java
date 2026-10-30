package com.footpred.reducers;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

import com.footpred.common.VectorWritable;

/**
 * Job P6.7 reducer: counts per (source, decade, bucket). Keys arrive sorted,
 * so all buckets of one (source, decade) group are adjacent; the group total
 * is only known when the group changes, hence a streaming buffer that is
 * flushed on group switch and in close().
 *
 * Output rows: source \t decade \t bucket \t count \t pct
 */
public class ScoreDiffReducer extends Reducer<Text, VectorWritable, NullWritable, Text> {

    private String curSource;
    private String curDecade;
    private final TreeMap<Integer, Long> bucketCounts = new TreeMap<Integer, Long>();

    @Override
    protected void reduce(Text key, Iterable<VectorWritable> values, Context context)
            throws IOException, InterruptedException {
        String[] f = key.toString().split("\t", 3);
        if (f.length < 3) {
            return;
        }
        double total = 0.0;
        for (VectorWritable v : values) {
            total += v.get()[0];
        }
        String source = f[0];
        String decade = f[1];
        int bucket = Integer.parseInt(f[2]);
        if (curSource != null && (!source.equals(curSource) || !decade.equals(curDecade))) {
            flush(context);
        }
        curSource = source;
        curDecade = decade;
        Long prev = bucketCounts.get(bucket);
        bucketCounts.put(bucket, (prev == null ? 0L : prev) + Math.round(total));
    }

    @Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
        if (curSource != null) {
            flush(context);
        }
    }

    private void flush(Context context) throws IOException, InterruptedException {
        long groupTotal = 0;
        for (long c : bucketCounts.values()) {
            groupTotal += c;
        }
        for (Map.Entry<Integer, Long> e : bucketCounts.entrySet()) {
            double pct = groupTotal > 0 ? 100.0 * e.getValue() / groupTotal : 0.0;
            String row = curSource + "\t" + curDecade + "\t" + e.getKey() + "\t"
                    + e.getValue() + "\t" + String.format(Locale.ROOT, "%.2f", pct);
            context.write(NullWritable.get(), new Text(row));
        }
        bucketCounts.clear();
    }
}
