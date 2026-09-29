package be.smobile.reconciliation.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link AiExtractionProperties} - same pattern as {@code MatchingConfig}. */
@Configuration
@EnableConfigurationProperties(AiExtractionProperties.class)
public class AiExtractionConfig {
}
