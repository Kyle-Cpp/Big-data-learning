package com.footpred.backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * Creates the metadata tables at startup.
 *
 * <p>Two callers rely on this: production (where MySQL exists and the DDL is
 * read from the classpath copy of {@code deploy/sql/schema.sql}) and the test
 * suite (where H2 runs the same statements in MySQL mode). Keeping the
 * bootstrap here rather than in {@code spring.sql.init} is deliberate — that
 * auto-configuration is excluded so the database-free deployment can start.
 */
@Configuration
@ConditionalOnProperty(name = "footpred.mysql.enabled", havingValue = "true")
public class SchemaBootstrap {

    private final JdbcTemplate jdbc;
    private final String scriptName;

    public SchemaBootstrap(JdbcTemplate jdbc,
                           @org.springframework.beans.factory.annotation.Value(
                                   "${footpred.schema.script:schema-mysql.sql}") String scriptName) {
        this.jdbc = jdbc;
        this.scriptName = scriptName;
    }

    @PostConstruct
    public void initialize() {
        ClassPathResource resource = new ClassPathResource(scriptName);
        if (!resource.exists()) {
            // A real MySQL install may already have the tables from
            // deploy/sql/schema.sql; missing bootstrap is not fatal.
            return;
        }
        String ddl;
        try {
            ddl = read(resource);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + scriptName, e);
        }
        for (String statement : ddl.split(";")) {
            String sql = stripComments(statement);
            if (sql.isEmpty()) {
                continue;
            }
            String upper = sql.toUpperCase();
            // The datasource URL already selects the schema.
            if (upper.startsWith("CREATE DATABASE") || upper.startsWith("USE ")) {
                continue;
            }
            jdbc.execute(sql);
        }
    }

    private static String read(ClassPathResource resource) throws IOException {
        Scanner scanner = new Scanner(resource.getInputStream(), StandardCharsets.UTF_8.name())
                .useDelimiter("\\A");
        try {
            return scanner.hasNext() ? scanner.next() : "";
        } finally {
            scanner.close();
        }
    }

    /** Drops {@code --} comment lines so a commented-out GRANT is never executed. */
    private static String stripComments(String statement) {
        StringBuilder kept = new StringBuilder();
        for (String line : statement.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            kept.append(line).append('\n');
        }
        return kept.toString().trim();
    }
}
