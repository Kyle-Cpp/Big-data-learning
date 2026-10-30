package com.footpred.backend.job;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Runs cluster commands locally on Linux, or over key-authenticated OpenSSH. */
@Component
public class ClusterGateway {
    @Value("${footpred.cluster.mode:local}") private String mode;
    @Value("${footpred.cluster.ssh.host:}") private String host;
    @Value("${footpred.cluster.ssh.user:root}") private String user;
    @Value("${footpred.cluster.ssh.port:22}") private int port;
    @Value("${footpred.cluster.ssh.identity:}") private String identity;
    @Value("${footpred.cluster.ssh.bin:ssh}") private String ssh;
    @Value("${footpred.hdfs.bin:hdfs}") private String hdfs;
    @Value("${footpred.upload.root:/footpred/web-uploads}") private String root;

    public String mode() { return mode; }
    public static String quote(String s) {
        if (s.indexOf('\0') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Invalid command argument");
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }
    public List<String> command(List<String> argv) {
        if ("local".equals(mode)) return argv;
        if (!"ssh".equals(mode) || !host.matches("[A-Za-z0-9._-]+") || !user.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("Configure cluster mode and SSH host/user");
        List<String> result = new ArrayList<String>(Arrays.asList(ssh, "-o", "BatchMode=yes", "-o",
                "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=8", "-o", "ServerAliveInterval=10",
                "-o", "ServerAliveCountMax=2", "-p", Integer.toString(port)));
        if (!identity.isEmpty()) { result.add("-i"); result.add(identity); }
        result.add(user + "@" + host);
        List<String> quoted = new ArrayList<String>();
        for (String arg : argv) quoted.add(quote(arg));
        result.add("bash -lc " + quote(String.join(" ", quoted)));
        return result;
    }
    public String uploadPath(String dataset, String relative) {
        if (!dataset.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid dataset id");
        if (relative == null || relative.length() > 400 || relative.startsWith("/") || relative.contains("\\"))
            throw new IllegalArgumentException("Invalid dataset path");
        for (String part : relative.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..") || !part.matches("[A-Za-z0-9._ -]+"))
                throw new IllegalArgumentException("Invalid dataset filename");
        if (!root.matches("/[A-Za-z0-9_/-]+") || root.contains("..")) throw new IllegalArgumentException("Invalid upload root");
        return root + "/" + dataset + "/" + relative;
    }
    public String datasetRoot(String dataset) { return uploadPath(dataset, "placeholder").replace("/placeholder", ""); }
    public void put(String dataset, String relative, InputStream input) throws IOException {
        String target = uploadPath(dataset, relative);
        run(Arrays.asList(hdfs, "dfs", "-mkdir", "-p", target.substring(0, target.lastIndexOf('/'))), null, 30);
        // No -f: an existing input can never be silently overwritten.
        run(Arrays.asList(hdfs, "dfs", "-Ddfs.replication=1", "-put", "-", target), input, 300);
    }
    public String run(List<String> argv, InputStream input, int seconds) throws IOException {
        Path log = Files.createTempFile("footpred-cluster-", ".log");
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        Process process = null;
        try {
            process = new ProcessBuilder(command(argv)).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            final Process active = process;
            timer.schedule(active::destroyForcibly, seconds, TimeUnit.SECONDS);
            try (OutputStream out = process.getOutputStream()) {
                if (input != null) { byte[] bytes = new byte[65536]; int n;
                    while ((n = input.read(bytes)) != -1) out.write(bytes, 0, n); }
            }
            if (!process.waitFor(seconds + 2L, TimeUnit.SECONDS)) throw new IOException("Cluster command timed out");
            byte[] bytes = new byte[4096]; int count;
            try (InputStream in = Files.newInputStream(log)) { count = in.read(bytes); }
            String output = count < 0 ? "" : new String(bytes, 0, count, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) throw new IOException("Cluster operation failed: " + output);
            return output;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted", e); }
        finally { if (process != null && process.isAlive()) process.destroyForcibly(); timer.shutdownNow(); Files.deleteIfExists(log); }
    }
}
