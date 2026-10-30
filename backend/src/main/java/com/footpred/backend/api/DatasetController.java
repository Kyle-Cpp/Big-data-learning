package com.footpred.backend.api;

import com.footpred.backend.job.ClusterGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.util.*;

@RestController
@RequestMapping("/api/datasets")
public class DatasetController {
    private final ClusterGateway cluster;
    @Value("${footpred.upload.enabled:false}") private boolean enabled;
    @Value("${footpred.job.enabled:false}") private boolean jobs;
    @Value("${footpred.mysql.enabled:false}") private boolean mysql;
    @Value("${footpred.job.training.enabled:false}") private boolean training;
    public DatasetController(ClusterGateway cluster) { this.cluster = cluster; }
    @GetMapping("/capabilities")
    public Map<String,Object> capabilities() {
        Map<String,Object> p = new LinkedHashMap<String,Object>();
        p.put("uploadEnabled", enabled); p.put("jobEnabled", jobs && mysql);
        p.put("trainingEnabled", training && jobs && mysql); p.put("mode", cluster.mode());
        p.put("maxFileBytes", 64L * 1024 * 1024);
        p.put("note", "Configured capabilities only; real HDFS/YARN access requires Linux online.");
        return p;
    }
    @PostMapping(consumes="multipart/form-data")
    public ResponseEntity<Map<String,Object>> upload(@RequestParam("file") MultipartFile file,
            @RequestParam(value="datasetId", required=false) String dataset,
            @RequestParam(value="relativePath", required=false) String relative) {
        Map<String,Object> p = new LinkedHashMap<String,Object>();
        if (!enabled) { p.put("message", "HDFS上传未启用，启动Linux并配置集群连接后开启。"); return ResponseEntity.status(503).body(p); }
        if (file.isEmpty() || file.getSize() > 64L*1024*1024) throw new IllegalArgumentException("File must be between 1 byte and 64 MiB");
        if (dataset == null || dataset.isEmpty()) dataset = UUID.randomUUID().toString();
        if (relative == null || relative.isEmpty()) relative = file.getOriginalFilename();
        String target = cluster.uploadPath(dataset, relative);
        String lower = relative.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".json")))
            throw new IllegalArgumentException("Only CSV, TSV and JSON datasets are supported");
        try (InputStream in = file.getInputStream()) {
            cluster.put(dataset, relative, in);
            p.put("datasetId", dataset); p.put("datasetRoot", cluster.datasetRoot(dataset));
            p.put("path", target); p.put("bytes", file.getSize()); p.put("uploaded", true);
            return ResponseEntity.status(201).body(p);
        } catch (IOException e) {
            p.put("uploaded", false); p.put("datasetId", dataset);
            p.put("message", "HDFS上传失败，请检查Linux、HDFS、SSH和磁盘空间。" + e.getMessage());
            return ResponseEntity.status(502).body(p);
        }
    }
}
