package com.footpred.backend.config;

import com.footpred.backend.job.JobExecutor;
import com.footpred.backend.job.LocalProcessJobExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the job execution layer.
 *
 * <p>Only active when {@code footpred.job.enabled=true}. Without it the backend
 * stays a pure read-only result service, which is the default and the safest
 * mode on a shared machine: a stray submit call on a 2-core VM can starve the
 * front end for minutes.
 *
 * <p>{@link LocalProcessJobExecutor} is the right choice for the chosen
 * deployment — the backend runs inside the CentOS VM, so {@code hadoop} is a
 * local binary. Running on a Windows host would need an SSH variant behind the
 * same interface.
 */
@Configuration
@ConditionalOnProperty(name = "footpred.job.enabled", havingValue = "true")
public class JobConfiguration {

    @Bean
    public JobExecutor jobExecutor() {
        return new LocalProcessJobExecutor();
    }
}
