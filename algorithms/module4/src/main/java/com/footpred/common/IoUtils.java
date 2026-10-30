package com.footpred.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

/** Small HDFS-side I/O helpers shared by the drivers. */
public final class IoUtils {

    private IoUtils() {
    }

    /** Fails fast with a readable message when an input path is missing. */
    public static void requireInput(Path path, Configuration conf, String what) throws IOException {
        FileSystem fs = path.getFileSystem(conf);
        if (!fs.exists(path)) {
            throw new IllegalArgumentException(
                    what + " not found (expected at " + path + "). "
                            + "Run the previous pipeline step first, see README.");
        }
    }

    /**
     * Reads the first non-empty line from all part files of a (single-reducer)
     * output directory. Used to fetch the aggregated gradient line of one
     * training iteration.
     */
    public static String readFirstNonEmptyLine(Path outputDir, Configuration conf)
            throws IOException {
        FileSystem fs = outputDir.getFileSystem(conf);
        if (!fs.exists(outputDir)) {
            throw new IOException("Output directory does not exist: " + outputDir);
        }
        List<Path> parts = new ArrayList<Path>();
        FileStatus[] files = fs.listStatus(outputDir);
        for (FileStatus file : files) {
            String name = file.getPath().getName();
            if (name.startsWith("part-") && !name.endsWith(".crc")) {
                parts.add(file.getPath());
            }
        }
        for (Path part : parts) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(fs.open(part), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        return line.trim();
                    }
                }
            }
        }
        throw new IOException("No output record found under " + outputDir);
    }

    /**
     * Reads all non-empty lines from all part files of an output directory
     * (file-name order). Used by stratified evaluation / baseline reports.
     */
    public static List<String> readAllLinesInDir(Path outputDir, Configuration conf)
            throws IOException {
        FileSystem fs = outputDir.getFileSystem(conf);
        if (!fs.exists(outputDir)) {
            throw new IOException("Output directory does not exist: " + outputDir);
        }
        List<Path> parts = new ArrayList<Path>();
        for (FileStatus file : fs.listStatus(outputDir)) {
            String name = file.getPath().getName();
            if (name.startsWith("part-") && !name.endsWith(".crc")) {
                parts.add(file.getPath());
            }
        }
        java.util.Collections.sort(parts);
        List<String> lines = new ArrayList<String>();
        for (Path part : parts) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(fs.open(part), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        lines.add(line.trim());
                    }
                }
            }
        }
        return lines;
    }

    /** Reads all non-empty lines of a single text file on the default FS. */
    public static List<String> readAllLines(Path file, Configuration conf) throws IOException {
        FileSystem fs = file.getFileSystem(conf);
        if (!fs.exists(file)) {
            throw new IOException("File does not exist: " + file);
        }
        List<String> lines = new ArrayList<String>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(fs.open(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    lines.add(line.trim());
                }
            }
        }
        return lines;
    }

    /**
     * Stream copy of a file inside a filesystem (works across FS instances and
     * clusters when the byte stream is reachable). Overwrites the target.
     */
    public static void copyFile(Path src, Path dst, Configuration conf) throws IOException {
        FileSystem srcFs = src.getFileSystem(conf);
        FileSystem dstFs = dst.getFileSystem(conf);
        try (InputStream in = srcFs.open(src);
             org.apache.hadoop.fs.FSDataOutputStream out = dstFs.create(dst, true)) {
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
            }
        }
    }

    /** Deletes a path recursively if it exists (iteration output reuse). */
    public static void deleteQuietly(Path path, Configuration conf) throws IOException {
        FileSystem fs = path.getFileSystem(conf);
        if (fs.exists(path)) {
            fs.delete(path, true);
        }
    }

    /**
     * Merges all part files of a single-reducer output directory into one
     * text file, in deterministic (file-name) order. Explicit implementation
     * instead of FileUtil.copyMerge to stay independent of platform-specific
     * signature differences.
     */
    public static void mergeTextDir(Path srcDir, Path dstFile, Configuration conf)
            throws IOException {
        FileSystem fs = srcDir.getFileSystem(conf);
        if (!fs.exists(srcDir)) {
            throw new IOException("Source directory does not exist: " + srcDir);
        }
        java.util.List<Path> parts = new java.util.ArrayList<Path>();
        for (FileStatus file : fs.listStatus(srcDir)) {
            String name = file.getPath().getName();
            if (name.startsWith("part-") && !name.endsWith(".crc")) {
                parts.add(file.getPath());
            }
        }
        java.util.Collections.sort(parts);
        try (FSDataOutputStream out = fs.create(dstFile, true)) {
            for (Path part : parts) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(fs.open(part), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        }
    }
}
