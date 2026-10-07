package be.smobile.reconciliation.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SummaryProperties.class)
public class SummaryConfig {
}
