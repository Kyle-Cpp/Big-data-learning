package com.footpred.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

/**
 * Team name to index registry produced by Job P0a.
 *
 * File format (TSV, produced in lexicographic order by a single reducer so the
 * index assignment is deterministic across runs):
 *
 *     name <TAB> idx <TAB> matchCount
 */
public class TeamRegistry {

    private final List<String> namesByIndex = new ArrayList<String>();
    private final Map<String, Integer> indexByName = new HashMap<String, Integer>();

    public static TeamRegistry load(Path path, Configuration conf) throws IOException {
        FileSystem fs = path.getFileSystem(conf);
        if (!fs.exists(path)) {
            throw new IOException("Team registry not found: " + path);
        }
        TeamRegistry registry = new TeamRegistry();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(fs.open(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] f = line.split("\t");
                if (f.length < 2) {
                    throw new IOException("Malformed registry line: " + line);
                }
                String name = f[0];
                int idx = Integer.parseInt(f[1].trim());
                while (registry.namesByIndex.size() <= idx) {
                    registry.namesByIndex.add(null);
                }
                registry.namesByIndex.set(idx, name);
                registry.indexByName.put(name, idx);
            }
        }
        for (int i = 0; i < registry.namesByIndex.size(); i++) {
            if (registry.namesByIndex.get(i) == null) {
                throw new IOException("Registry has gap at index " + i);
            }
        }
        return registry;
    }

    /** Index of a team name, or null if unknown. */
    public Integer indexOf(String name) {
        return indexByName.get(name);
    }

    public int size() {
        return namesByIndex.size();
    }

    public String nameOf(int idx) {
        return namesByIndex.get(idx);
    }

    public List<String> namesByIndex() {
        return namesByIndex;
    }
}
