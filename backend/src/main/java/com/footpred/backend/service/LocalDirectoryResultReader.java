package com.footpred.backend.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads result artefacts from a directory on the local filesystem.
 *
 * <p>Two layouts are supported because they are both produced today:
 * <ul>
 *   <li>a raw run directory holding {@code part-m-00000} / {@code part-r-00000};</li>
 *   <li>the export directory from {@code 06-export-results.sh}, holding merged
 *       {@code .tsv} files with a header row written by the script.</li>
 * </ul>
 * The reader never modifies anything, so it is safe to point at the handoff
 * package in place.
 */
public class LocalDirectoryResultReader implements ResultReader {

    private final Path root;

    public LocalDirectoryResultReader(String rootDirectory) {
        this.root = Paths.get(rootDirectory).toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /** Resolves a location that may be absolute or relative to the configured root. */
    private Path resolve(String location) {
        Path candidate = Paths.get(location);
        if (candidate.isAbsolute()) {
            return candidate.normalize();
        }
        return root.resolve(location).normalize();
    }

    @Override
    public List<String> listPartFiles(String location) throws IOException {
        return listMatching(location, "part-", null);
    }

    @Override
    public List<String> listTsvFiles(String location) throws IOException {
        return listMatching(location, null, ".tsv");
    }

    private List<String> listMatching(String location, String prefix, String suffix)
            throws IOException {
        Path target = resolve(location);
        List<String> result = new ArrayList<String>();
        if (!Files.exists(target)) {
            return result;
        }
        if (Files.isRegularFile(target)) {
            result.add(target.toString());
            return result;
        }
        DirectoryStream<Path> stream = Files.newDirectoryStream(target);
        try {
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                String name = entry.getFileName().toString();
                if (name.startsWith("_") || name.endsWith(".crc")) {
                    continue;
                }
                if (prefix != null && !name.startsWith(prefix)) {
                    continue;
                }
                if (suffix != null && !name.endsWith(suffix)) {
                    continue;
                }
                result.add(entry.toString());
            }
        } finally {
            stream.close();
        }
        Collections.sort(result);
        return result;
    }

    @Override
    public InputStream open(String path) throws IOException {
        return Files.newInputStream(resolve(path));
    }

    @Override
    public List<String> readAllLines(String location) throws IOException {
        Path target = resolve(location);
        List<String> lines = new ArrayList<String>();
        if (!Files.exists(target)) {
            return lines;
        }
        if (Files.isRegularFile(target)) {
            appendLines(target, lines);
            return lines;
        }
        List<String> files = listPartFiles(location);
        if (files.isEmpty()) {
            // Export layout: the merged .tsv files are the only data present.
            files = listTsvFiles(location);
        }
        for (String file : files) {
            appendLines(Paths.get(file), lines);
        }
        return lines;
    }

    private void appendLines(Path file, List<String> target) throws IOException {
        InputStream raw = Files.newInputStream(file);
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(raw, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                target.add(line);
            }
        } finally {
            reader.close();
        }
    }

    @Override
    public boolean exists(String location) {
        Path target = resolve(location);
        if (!Files.exists(target)) {
            return false;
        }
        return !Files.isDirectory(target) || hasData(target);
    }

    private boolean hasData(Path directory) {
        try {
            DirectoryStream<Path> stream = Files.newDirectoryStream(directory);
            try {
                for (Path entry : stream) {
                    String name = entry.getFileName().toString();
                    if (Files.isRegularFile(entry) && !name.startsWith("_")
                            && !name.endsWith(".crc")) {
                        return true;
                    }
                }
            } finally {
                stream.close();
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }
}
