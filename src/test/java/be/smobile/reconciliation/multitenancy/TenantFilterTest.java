package be.smobile.reconciliation.multitenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantFilterTest {

    private final MultiTenancyProperties properties = new MultiTenancyProperties();
    private final TenantFilter filter = new TenantFilter(properties);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void noJwt_headerPresent_isTrustedAsIs() throws Exception {
        // local/dev profile (app.security.enabled=false): no Authentication in the context at all.
        assertTenantResolvedTo("9000000002", requestWithHeader("9000000002"));
    }

    @Test
    void noJwt_noHeader_leavesTenantUnset() throws Exception {
        assertTenantResolvedTo(null, requestWithHeader(null));
    }

    @Test
    void jwtPresent_noHeader_usesTenantClaim() throws Exception {
        // Real Keycloak tokens carry "account_id" as a JSON NUMBER (confirmed 2026-09-13
        // against a real predev.pilim.net JWT - see MultiTenancyProperties' javadoc), not a
        // string - a Long here, not "9000000002", so this test actually exercises that
        // conversion instead of only ever testing the easy (already-a-string) case.
        setJwt(Map.of("account_id", 9000000002L));
        assertTenantResolvedTo("9000000002", requestWithHeader(null));
    }

    @Test
    void jwtPresent_headerMatchesOwnAccountId_isAuthorized() throws Exception {
        setJwt(Map.of("account_id", 9000000002L));
        assertTenantResolvedTo("9000000002", requestWithHeader("9000000002"));
    }

    @Test
    void jwtPresent_headerNotAuthorized_rejectsWith403() throws Exception {
        setJwt(Map.of("account_id", 9000000002L));
        HttpServletRequest request = requestWithHeader("9000000003");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(response).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
        verifyNoInteractions(chain);
        assertNull(TenantContext.getTenantId());
    }

    private void setJwt(Map<String, Object> claims) {
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "none"), claims);
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(jwt, null));
    }

    private HttpServletRequest requestWithHeader(String headerValue) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(properties.getTenantHeader())).thenReturn(headerValue);
        return request;
    }

    private void assertTenantResolvedTo(String expectedTenantId, HttpServletRequest request) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        String[] tenantSeenDuringChain = new String[1];
        doAnswer(invocation -> {
            tenantSeenDuringChain[0] = TenantContext.getTenantId();
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals(expectedTenantId, tenantSeenDuringChain[0]);
        assertNull(TenantContext.getTenantId());
    }
}
