package com.footpred.backend.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Reads result artefacts without caring where they live.
 *
 * <p>Chosen so the read-only layer works before any Hadoop access exists: the
 * export-directory implementation runs against
 * {@code scripts/linux/06-export-results.sh} output on a laptop, while an HDFS
 * implementation can be added later behind the same interface. Both the manifest
 * style ({@code run/part-*}) and the exported style ({@code run.tsv}) are
 * resolved by the same reader.
 */
public interface ResultReader {

    /**
     * Lists the data files that make up one result directory.
     *
     * <p>Only {@code part-*} files are returned, matching what the MapReduce
     * frameworks produce; {@code _SUCCESS} and the crc sidecars are excluded.
     */
    List<String> listPartFiles(String location) throws IOException;

    /** Lists every {@code .tsv} file at a location, for the export layout. */
    List<String> listTsvFiles(String location) throws IOException;

    /** Opens one file for streaming. */
    InputStream open(String path) throws IOException;

    /**
     * Reads every line of a result directory, concatenating its part files in
     * name order so output is deterministic across runs.
     */
    List<String> readAllLines(String location) throws IOException;

    /** True when the location exists and contains at least one data file. */
    boolean exists(String location);
}
