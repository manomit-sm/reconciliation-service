package be.smobile.reconciliation.multitenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/**
 * Tells Hibernate which tenant identifier is "current" for the thread handling a request -
 * read from {@link TenantContext}, populated per-request by {@link TenantFilter}.
 * <p>
 * The identifier returned here is the raw tenant id (e.g. an account id), not the Postgres
 * schema name - {@link SchemaMultiTenantConnectionProvider} does the {@code tenant_} prefixing
 * when it actually switches the connection's schema.
 */
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

    /**
     * Used when a request carries no resolvable tenant (e.g. the health check, or an
     * unauthenticated request in the "local" profile with no X-Tenant-Id header either) -
     * routes to the "public" schema rather than failing outright.
     */
    public static final String NO_TENANT = "public";

    @Override
    public String resolveCurrentTenantIdentifier() {
        String tenantId = TenantContext.getTenantId();
        return tenantId != null ? tenantId : NO_TENANT;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        // Never reuse a Hibernate session across two different tenants.
        return true;
    }
}
