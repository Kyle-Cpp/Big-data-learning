package com.footpred.common;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.CombineFileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.CombineFileSplit;

/**
 * Whole-file input format for multi-record JSON documents (StatsBomb lineups
 * and events): each record is one complete file. Extends
 * CombineFileInputFormat so that many small files are packed into a few
 * splits (one map task per ~maxSplitSize bytes) instead of one task per file.
 *
 * The map key is the file's HDFS path (the caller derives league/season from
 * the directory layout), the map value is the raw file bytes as UTF-8 text.
 */
public class WholeFileInputFormat extends CombineFileInputFormat<Text, Text> {

    @Override
    public RecordReader<Text, Text> createRecordReader(
            InputSplit split, TaskAttemptContext context) {
        return new WholeFileRecordReader();
    }

    /** Serves the files of one CombineFileSplit one after another. */
    public static class WholeFileRecordReader extends RecordReader<Text, Text> {

        private CombineFileSplit split;
        private Configuration conf;
        private int currentFile;
        private boolean finished;
        private final Text key = new Text();
        private final Text value = new Text();

        @Override
        public void initialize(InputSplit split, TaskAttemptContext context) {
            this.split = (CombineFileSplit) split;
            this.conf = context.getConfiguration();
            this.currentFile = -1;
            this.finished = false;
        }

        @Override
        public boolean nextKeyValue() throws IOException {
            if (finished) {
                return false;
            }
            currentFile++;
            if (currentFile >= split.getPaths().length) {
                finished = true;
                return false;
            }
            Path file = split.getPath(currentFile);
            long length = split.getLength(currentFile);
            if (length > Integer.MAX_VALUE) {
                throw new IOException("file too large for whole-file record: " + file);
            }
            FileSystem fs = file.getFileSystem(conf);
            byte[] bytes = new byte[(int) length];
            try (FSDataInputStream in = fs.open(file)) {
                in.readFully(bytes);
            }
            key.set(file.toString());
            value.set(bytes, 0, bytes.length);
            return true;
        }

        @Override
        public Text getCurrentKey() {
            return key;
        }

        @Override
        public Text getCurrentValue() {
            return value;
        }

        @Override
        public float getProgress() {
            if (split == null || split.getPaths().length == 0) {
                return 1.0f;
            }
            return Math.min(1.0f, (currentFile + 1f) / split.getPaths().length);
        }

        @Override
        public void close() {
            // nothing to close; each file stream is closed inline
        }
    }
}
