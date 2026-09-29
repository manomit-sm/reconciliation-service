package be.smobile.reconciliation.multitenancy;

import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link SchemaMultiTenantConnectionProvider} and {@link TenantIdentifierResolver} into
 * Hibernate. Done via a {@link HibernatePropertiesCustomizer} bean (which can put live,
 * dependency-injected objects into the persistence unit's property map) rather than the
 * {@code hibernate.multi_tenant_connection_provider}/{@code hibernate.tenant_identifier_resolver}
 * class-name YAML properties account-service uses - those only work for a class Hibernate can
 * instantiate itself with a no-arg constructor, which {@link SchemaMultiTenantConnectionProvider}
 * (needs the DataSource injected) can't be. Verified against the actual property key constants
 * in this project's resolved hibernate-core-7.4.5.Final.jar (org.hibernate.cfg.MultiTenancySettings) -
 * Hibernate 7 renamed these from the camelCase spelling account-service's (older Hibernate)
 * config uses.
 */
@Configuration
@EnableConfigurationProperties(MultiTenancyProperties.class)
public class MultiTenancyConfig {

    @Bean
    public HibernatePropertiesCustomizer multiTenancyHibernatePropertiesCustomizer(
            SchemaMultiTenantConnectionProvider connectionProvider,
            TenantIdentifierResolver tenantIdentifierResolver) {
        return properties -> {
            properties.put(MultiTenancySettings.MULTI_TENANT_CONNECTION_PROVIDER, connectionProvider);
            properties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, tenantIdentifierResolver);
        };
    }
}
