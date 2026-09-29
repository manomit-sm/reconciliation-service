package be.smobile.reconciliation.multitenancy;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tenant-resolution config - see {@code app.multitenancy.*} in application.yml.
 * <p>
 * {@code tenantClaim} is <b>confirmed</b> (2026-09-13, against a real JWT from
 * https://predev.pilim.net decoded by hand - not a guess anymore): the real Keycloak claim is
 * {@code account_id} (snake_case, not the {@code accountId}/{@code AccountDto.accountId}
 * camelCase this was originally guessed from), and its JSON type is a <b>number</b>
 * ({@code "account_id": 9000009651}, not a quoted string). That numeric type is not a problem -
 * {@code Jwt.getClaimAsString} runs every claim through Spring Security's
 * {@code ClaimConversionService}, which has a registered {@code ObjectToStringConverter}, so a
 * numeric claim converts to its string form safely; verified by decompiling
 * {@code spring-security-oauth2-core-6.5.7.jar}'s {@code ClaimAccessor.getClaimAsString}, not
 * just assumed. If the actual claim name ever changes again, this is still the only place to
 * change: no code elsewhere hardcodes it.
 */
@ConfigurationProperties(prefix = "app.multitenancy")
@Getter
@Setter
public class MultiTenancyProperties {

    /** JWT claim carrying the account/tenant id; schema resolved as schemaPrefix + this claim's value. */
    private String tenantClaim = "account_id";

    /**
     * Request header checked BEFORE the JWT claim (see {@code TenantFilter.resolveTenantId} -
     * matches the order in the client's own {@code extractTenantIdFromKeycloakToken} reference
     * code, confirmed 2026-09-13). Also doubles as the local/dev profile's tenant selector
     * (app.security.enabled=false - see application-local.yml), where there is no JWT anyway.
     * <p>
     * When a JWT is present, this header is only honored if it matches that JWT's own
     * {@link #tenantClaim} value - see {@code TenantFilter.isAuthorized}. It can never be used
     * to act as a different tenant than the one already in the caller's token; a mismatching
     * value rejects the request (403) rather than silently falling back to the claim.
     */
    private String tenantHeader = "X-Tenant-Id";

    /** Postgres schema naming convention observed in pgAdmin: {@code tenant_<accountId>}. */
    private String schemaPrefix = "tenant_";
}
