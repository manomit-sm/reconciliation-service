package be.smobile.reconciliation.multitenancy;

import lombok.RequiredArgsConstructor;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Postgres schema-per-tenant routing: wraps the single (Spring Boot auto-configured) DataSource
 * and, on every connection handed to Hibernate for a given tenant, runs
 * {@code SET search_path TO <tenant schema>} before returning it. This is what {@code moac-doc-gen-service}
 * -style single-schema services don't need, but is required here since each Pilim account owns
 * its own schema ({@code tenant_<accountId>}, per the account-service database screenshots).
 * <p>
 * Deliberately implemented locally rather than depending on the {@code be.moac.multitenancy}
 * package referenced in account-service's application.yml - that package's Maven coordinates
 * weren't available, and this achieves the identical Hibernate SCHEMA multi-tenancy mechanism
 * (same {@link MultiTenantConnectionProvider} SPI). Wired via {@link MultiTenancyConfig} rather
 * than as a {@code hibernate.multi_tenant_connection_provider} class-name property (account-service's
 * approach) because this provider needs the DataSource constructor-injected, which a bare
 * class-name property can't do for a no-arg-instantiated class.
 */
@Component
@RequiredArgsConstructor
public class SchemaMultiTenantConnectionProvider implements MultiTenantConnectionProvider<String> {

    private final DataSource dataSource;
    private final MultiTenancyProperties multiTenancyProperties;

    @Override
    public Connection getAnyConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public Connection getConnection(String tenantIdentifier) throws SQLException {
        Connection connection = getAnyConnection();
        try {
            setSchema(connection, schemaFor(tenantIdentifier));
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    @Override
    public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
        try {
            setSchema(connection, "public");
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean handlesConnectionSchema() {
        // We already set search_path ourselves above; tell Hibernate not to also call
        // Connection#setSchema(tenantIdentifier) itself - that would use the raw tenant id
        // ("9000000002") as a schema name instead of the actual "tenant_9000000002" schema.
        return true;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        throw new UnsupportedOperationException("Cannot unwrap " + getClass().getName() + " as " + unwrapType.getName());
    }

    private void setSchema(Connection connection, String schema) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Quoted identifier: tenant_9000000002 is a safe identifier already, but quoting
            // avoids any surprise if a tenant id ever contains characters that need escaping.
            statement.execute("SET search_path TO \"" + schema + "\"");
        }
    }

    private String schemaFor(String tenantIdentifier) {
        if (TenantIdentifierResolver.NO_TENANT.equals(tenantIdentifier)) {
            return "public";
        }
        return multiTenancyProperties.getSchemaPrefix() + tenantIdentifier;
    }
}
