package be.smobile.reconciliation.multitenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Populates {@link TenantContext} for the duration of one request, from (in order):
 * <ol>
 *     <li>the {@code app.multitenancy.tenant-header} request header (see
 *     {@link MultiTenancyProperties}) - checked first, per the client's own
 *     {@code extractTenantIdFromKeycloakToken} reference code, which reads this header
 *     unconditionally before falling back to the token. <b>Only honored if it matches the JWT's
 *     own {@code tenant-claim} value - see {@link #isAuthorized}</b>: a header that disagrees
 *     with the token rejects the request with 403 rather than silently overriding it or
 *     silently falling back to the claim;</li>
 *     <li>the authenticated JWT's {@code app.multitenancy.tenant-claim} claim - used only when
 *     the header is absent.</li>
 * </ol>
 * No assumption of one user having access to multiple accounts is made here - the header can
 * never authorize a tenant other than the one already in the caller's own JWT, it can only
 * confirm or reject it. If Pilim does have a multi-account-per-user case where a single token
 * should be allowed to act as a different tenant than its own claim, that needs its own
 * confirmed claim from the client before being added here - not assumed up front.
 * <p>
 * <b>Not a {@code @Component}</b> - deliberately, since 2026-09-13. It used to be, relying on
 * Spring Boot auto-registering any bean {@code Filter} as a global servlet filter at
 * {@code Ordered.LOWEST_PRECEDENCE} (which, verified by decompiling the actual
 * spring-boot/spring-security-config jars this project resolves, does run after Spring
 * Security's own chain - registered at order {@code -100} - so it was never actually broken).
 * Still, that correctness depended on an implicit default that a stray {@code @Order} elsewhere,
 * or a Spring Boot version change, could silently break - and a plain global filter runs on
 * every request regardless of {@code SecurityConfig}'s {@code permitAll()} paths too, which this
 * filter only tolerates because it degrades to a no-op with no JWT/header, not because the
 * registration mechanism guarantees anything. Explicit is safer than implicit-but-correct: this
 * is now instantiated as a {@code @Bean} in {@code SecurityConfig} and wired into each
 * {@code SecurityFilterChain} via {@code HttpSecurity.addFilterAfter(tenantFilter,
 * BearerTokenAuthenticationFilter.class)}, so "runs after authentication" is a checked-in fact,
 * not an inferred one - see {@code SecurityConfig}'s own javadoc for how double-registration as
 * a global filter is avoided.
 */
@RequiredArgsConstructor
public class TenantFilter extends OncePerRequestFilter {

    private final MultiTenancyProperties multiTenancyProperties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String headerValue = request.getHeader(multiTenancyProperties.getTenantHeader());
        Jwt jwt = currentJwt();

        if (headerValue != null && jwt != null && !isAuthorized(jwt, headerValue)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Not authorized for tenant '" + headerValue + "'");
            return;
        }

        try {
            resolveTenantId(headerValue, jwt).ifPresent(TenantContext::setTenantId);
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private Jwt currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof Jwt jwt ? jwt : null;
    }

    private Optional<String> resolveTenantId(String headerValue, Jwt jwt) {
        if (headerValue != null) {
            return Optional.of(headerValue);
        }
        if (jwt != null) {
            return Optional.ofNullable(jwt.getClaimAsString(multiTenancyProperties.getTenantClaim()));
        }
        return Optional.empty();
    }

    /**
     * Whether {@code requestedTenantId} (from the header) matches this JWT's own
     * {@code app.multitenancy.tenant-claim} value - the header is never allowed to name a
     * different tenant than the one the token itself carries. There's no way to authorize a
     * header when there's no JWT at all (local/dev profile) - that case never reaches this
     * method (see the null-check in {@link #doFilterInternal}), so the header is trusted as-is
     * there, same as before.
     */
    private boolean isAuthorized(Jwt jwt, String requestedTenantId) {
        String ownAccountId = jwt.getClaimAsString(multiTenancyProperties.getTenantClaim());
        return ownAccountId != null && ownAccountId.equals(requestedTenantId);
    }
}
