package be.smobile.reconciliation.multitenancy;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies this service's Liquibase changelog to every existing {@code tenant_<accountId>}
 * schema at startup, instead of the single fixed schema {@code spring.liquibase.enabled: true}
 * would target.
 * <p>
 * <b>Why this exists / what's assumed:</b> Spring Boot's own Liquibase auto-run (disabled here -
 * see {@code spring.liquibase.enabled: false} in application.yml) migrates exactly one schema
 * per startup. That doesn't fit a database with one schema per account (the pgAdmin screenshots
 * showed ~10,000 {@code tenant_*} schemas) - our own {@code bank_transaction}/{@code reconciliation}
 * tables need to exist in every one of them, not just one. account-service's own config disables
 * Liquibase entirely ({@code liquibase.enabled: false}) and relies on {@code ddl-auto: update}
 * instead, which doesn't reveal how *it* provisions new tenant schemas either - there may
 * already be an established tenant-provisioning hook elsewhere in the platform that a new
 * service is expected to plug into instead of doing this itself. This runner is a
 * self-contained stand-in until that's confirmed: it discovers tenant schemas by querying
 * {@code information_schema.schemata} directly (this needs a DB role with visibility across all
 * schemas) rather than depending on unknown infrastructure.
 * <p>
 * <b>Known limitation:</b> runs synchronously on every application startup and is therefore
 * O(number of tenants) DB round-trips each time (Liquibase's own changelog-lock/changeset-status
 * bookkeeping is per schema) - cheap once a schema is up to date, but still a per-tenant query.
 * Fine at today's scale for milestone 1; worth revisiting (e.g. trigger from tenant
 * provisioning instead of every boot) before this runs against the real ~10,000-schema database.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantSchemaMigrationRunner implements ApplicationRunner {

    private final DataSource dataSource;
    private final MultiTenancyProperties multiTenancyProperties;

    @Value("${spring.liquibase.change-log}")
    private String changeLogLocation;

    @Override
    public void run(ApplicationArguments args) throws SQLException {
        List<String> tenantSchemas = findTenantSchemas();
        log.info("Applying {} to {} tenant schema(s)", changeLogLocation, tenantSchemas.size());
        for (String schema : tenantSchemas) {
            migrate(schema);
        }
    }

    private List<String> findTenantSchemas() throws SQLException {
        List<String> schemas = new ArrayList<>();
        String likePattern = multiTenancyProperties.getSchemaPrefix() + "%";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE ? ORDER BY schema_name")) {
            statement.setString(1, likePattern);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    schemas.add(resultSet.getString(1));
                }
            }
        }
        return schemas;
    }

    private void migrate(String schema) {
        String classpathRelativePath = changeLogLocation.replaceFirst("^classpath:/?", "");
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO \"" + schema + "\"");
            }
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            database.setDefaultSchemaName(schema);
            database.setLiquibaseSchemaName(schema);
            Liquibase liquibase = new Liquibase(classpathRelativePath, new ClassLoaderResourceAccessor(), database);
            liquibase.update();
            log.info("Migrated tenant schema {}", schema);
        } catch (Exception e) {
            // Don't let one bad tenant schema abort startup for every other tenant.
            log.error("Failed to migrate tenant schema {}", schema, e);
        }
    }
}
