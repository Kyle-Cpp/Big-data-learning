package com.footpred.backend.api;

import com.footpred.backend.catalog.JobCatalog;
import com.footpred.backend.catalog.JobSpec;
import com.footpred.backend.domain.LeagueCode;
import com.footpred.backend.domain.SeasonCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes the command catalogue.
 *
 * <p>The front end needs the command list, the argument contract and the
 * "does this retrain?" flag in order to build a task form that cannot submit
 * something the cluster would reject. Serving it from the same table the
 * submission path uses keeps the two from drifting.
 */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    @GetMapping("/jobs")
    public List<Map<String, Object>> jobs(
            @RequestParam(value = "module", required = false) String module) {
        List<JobSpec> specs;
        if (module == null || module.trim().isEmpty()) {
            specs = JobCatalog.all();
        } else {
            JobSpec.Module filter = parseModule(module);
            specs = filter == null ? JobCatalog.all() : JobCatalog.byModule(filter);
        }
        List<Map<String, Object>> response = new ArrayList<Map<String, Object>>();
        for (JobSpec spec : specs) {
            response.add(describe(spec));
        }
        return response;
    }

    @GetMapping("/leagues")
    public List<Map<String, String>> leagues() {
        List<Map<String, String>> response = new ArrayList<Map<String, String>>();
        String[] known = {"Premier League", "La Liga", "Bundesliga", "Serie A", "Ligue 1"};
        for (String league : known) {
            Map<String, String> item = new LinkedHashMap<String, String>();
            item.put("label", league);
            item.put("kebab", LeagueCode.kebab(league));
            response.add(item);
        }
        return response;
    }

    @GetMapping("/seasons")
    public List<Map<String, String>> seasons() {
        List<Map<String, String>> response = new ArrayList<Map<String, String>>();
        for (int startYear = 2015; startYear <= 2025; startYear++) {
            // Build both encodings directly: the handoff package keys on the
            // compact form ("1516") and the partner package on the labelled one
            // ("2015-16"), and neither can be derived from the other by string
            // surgery alone.
            String compact = String.format("%02d%02d", startYear % 100, (startYear + 1) % 100);
            String label = String.format("%04d-%02d", startYear, (startYear + 1) % 100);
            Map<String, String> item = new LinkedHashMap<String, String>();
            item.put("label", label);
            item.put("compact", compact);
            response.add(item);
        }
        return response;
    }

    private Map<String, Object> describe(JobSpec spec) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("id", spec.id());
        item.put("module", spec.module().name());
        item.put("origin", spec.origin().name());
        item.put("jar", spec.jarKey());
        item.put("argStyle", spec.argStyle().name());
        item.put("description", spec.description());
        item.put("retrains", spec.retrains());
        item.put("expectedSeconds", spec.expectedSeconds());
        List<Map<String, Object>> arguments = new ArrayList<Map<String, Object>>();
        for (JobSpec.Argument argument : spec.arguments()) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("name", argument.name());
            entry.put("required", argument.required());
            entry.put("defaultValue", argument.defaultValue());
            entry.put("description", argument.description());
            if (!argument.choices().isEmpty()) {
                entry.put("choices", argument.choices());
            }
            arguments.add(entry);
        }
        item.put("arguments", arguments);
        return item;
    }

    private JobSpec.Module parseModule(String module) {
        try {
            return JobSpec.Module.valueOf(module.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
