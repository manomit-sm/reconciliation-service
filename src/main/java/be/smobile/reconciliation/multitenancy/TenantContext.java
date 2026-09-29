package be.smobile.reconciliation.multitenancy;

/**
 * Holds the current request's tenant (account) id for the duration of that request, on the
 * thread handling it. Set by {@link TenantFilter} at the start of each request and cleared in
 * its {@code finally} block; read by {@link TenantIdentifierResolver} whenever Hibernate needs
 * to know which tenant schema to connect to.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String getTenantId() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
