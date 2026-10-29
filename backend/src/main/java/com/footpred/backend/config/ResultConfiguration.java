package com.footpred.backend.config;

import com.footpred.backend.service.LocalDirectoryResultReader;
import com.footpred.backend.service.OutputLocator;
import com.footpred.backend.service.ResultReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the read-only data layer.
 *
 * <p>The two HDFS roots are configuration, never constants: the partner package
 * uses {@code /FootPred} and the handoff package mandates {@code /footpred}, and
 * the local development path is a checkout of the handoff package. Keeping them
 * injectable means the same build serves both a laptop and the cluster without a
 * code change.
 */
@Configuration
public class ResultConfiguration {

    @Bean
    public ResultReader resultReader(@Value("${footpred.local.root:.}") String localRoot) {
        return new LocalDirectoryResultReader(localRoot);
    }

    @Bean
    public OutputLocator outputLocator(
            @Value("${footpred.hdfs.root:/footpred}") String handoffRoot,
            @Value("${footpred.hdfs.root.partner:/FootPred}") String partnerRoot) {
        return new OutputLocator(handoffRoot, partnerRoot);
    }
}
