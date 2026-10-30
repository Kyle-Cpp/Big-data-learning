package com.footpred.backend.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Data source for the MySQL metadata store.
 *
 * <p>Created manually rather than through {@code spring.datasource.*} so it can
 * sit behind {@code footpred.mysql.enabled}. Without the flag there is no
 * {@link DataSource} bean at all, which is what lets the read-only result layer
 * run on a machine with no database — the default deployment.
 *
 * <p>Connection settings come from environment variables
 * ({@code SPRING_DATASOURCE_*}) so {@code deploy/config/footpred.env} stays the
 * only file an operator edits.
 */
@Configuration
@ConditionalOnProperty(name = "footpred.mysql.enabled", havingValue = "true")
public class MysqlConfiguration {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(
            @org.springframework.beans.factory.annotation.Value("${spring.datasource.url}") String url,
            @org.springframework.beans.factory.annotation.Value("${spring.datasource.username}") String username,
            @org.springframework.beans.factory.annotation.Value("${spring.datasource.password}") String password,
            @org.springframework.beans.factory.annotation.Value("${spring.datasource.driver-class-name:com.mysql.cj.jdbc.Driver}")
                    String driver) {

        HikariDataSource dataSource = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName(driver)
                .url(url)
                .username(username)
                .password(password)
                .build();
        // A single 2-core VM cannot afford a large pool, and the workload is
        // bulk import plus occasional reads rather than concurrent traffic.
        dataSource.setMaximumPoolSize(5);
        dataSource.setMinimumIdle(1);
        dataSource.setPoolName("footpred-mysql");
        dataSource.setConnectionTimeout(10_000L);
        return dataSource;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * Transaction manager for the {@code @Transactional} import methods.
     *
     * <p>Declared explicitly because {@code DataSourceTransactionManagerAutoConfiguration}
     * is excluded in the application class: that exclusion is what lets the
     * database-free deployment start, so the manager cannot come from
     * auto-configuration here.
     */
    @Bean
    public org.springframework.jdbc.datasource.DataSourceTransactionManager transactionManager(
            DataSource dataSource) {
        return new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
    }
}
