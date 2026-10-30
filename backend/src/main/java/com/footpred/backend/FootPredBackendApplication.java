package com.footpred.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;

/**
 * Read-only result API for the FootPred platform.
 *
 * <p>Three auto-configurations are excluded on purpose:
 * {@code DataSourceAutoConfiguration}, its transaction-manager companion, and
 * {@code SqlInitializationAutoConfiguration}. Having {@code spring-jdbc} and the
 * MySQL driver on the classpath makes Spring Boot demand a configured
 * {@code spring.datasource.url} at startup — which would break the default
 * deployment, where MySQL is intentionally disabled and only the read-only
 * result layer runs.
 *
 * <p>The data source is instead supplied by {@code MysqlConfiguration}, guarded
 * by {@code footpred.mysql.enabled}. When the flag is 0 there is no
 * {@code DataSource} bean at all and nothing references one.
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        SqlInitializationAutoConfiguration.class
})
public class FootPredBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(FootPredBackendApplication.class, args);
    }
}
