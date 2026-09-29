package be.smobile.reconciliation.matching;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link MatchingProperties} - same pattern as {@code MultiTenancyConfig}. */
@Configuration
@EnableConfigurationProperties(MatchingProperties.class)
public class MatchingConfig {
}
