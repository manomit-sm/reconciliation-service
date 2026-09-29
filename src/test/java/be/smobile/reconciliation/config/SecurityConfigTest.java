package be.smobile.reconciliation.config;

import be.smobile.reconciliation.multitenancy.MultiTenancyProperties;
import be.smobile.reconciliation.multitenancy.TenantFilter;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.support.TestPropertySourceUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds the real {@link SecurityFilterChain} beans (not mocked) to prove - not just argue -
 * that {@link TenantFilter} actually ends up positioned after {@link BearerTokenAuthenticationFilter}
 * and is registered exactly once, per the 2026-09-13 change away from implicit {@code @Component}
 * auto-registration ordering.
 */
class SecurityConfigTest {

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void securedChain_tenantFilterRunsExactlyOnce_immediatelyAfterBearerTokenAuthentication() {
        context = buildContext("app.security.enabled=true");

        SecurityFilterChain chain = context.getBean("securedFilterChain", SecurityFilterChain.class);
        List<Filter> filters = chain.getFilters();

        assertTenantFilterRunsOnceRightAfter(filters, BearerTokenAuthenticationFilter.class);
    }

    @Test
    void openChain_localDevProfile_tenantFilterStillPresentExactlyOnce() {
        context = buildContext("app.security.enabled=false");

        SecurityFilterChain chain = context.getBean("openFilterChain", SecurityFilterChain.class);
        List<Filter> filters = chain.getFilters();

        long tenantFilterCount = filters.stream().filter(f -> f instanceof TenantFilter).count();
        assertEquals(1, tenantFilterCount, "TenantFilter must be registered exactly once, not duplicated by Boot's auto-registration");
    }

    @Test
    void tenantFilterAutoRegistrationIsDisabled() {
        context = buildContext("app.security.enabled=true");

        var registration = context.getBean("tenantFilterAutoRegistrationDisabled",
                org.springframework.boot.web.servlet.FilterRegistrationBean.class);

        assertTrue(!registration.isEnabled(), "The companion FilterRegistrationBean must be disabled, or Boot would register TenantFilter a second time as a global filter");
    }

    private void assertTenantFilterRunsOnceRightAfter(List<Filter> filters, Class<?> afterType) {
        int tenantFilterIndex = -1;
        int referenceIndex = -1;
        int tenantFilterCount = 0;
        for (int i = 0; i < filters.size(); i++) {
            if (filters.get(i) instanceof TenantFilter) {
                tenantFilterIndex = i;
                tenantFilterCount++;
            }
            if (afterType.isInstance(filters.get(i))) {
                referenceIndex = i;
            }
        }
        assertEquals(1, tenantFilterCount, "TenantFilter must appear exactly once in the built filter chain");
        assertTrue(referenceIndex >= 0, afterType.getSimpleName() + " must be present in the secured chain");
        assertEquals(referenceIndex + 1, tenantFilterIndex, "TenantFilter must run immediately after " + afterType.getSimpleName());
    }

    private AnnotationConfigApplicationContext buildContext(String... properties) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(ctx, properties);
        ctx.registerBean(MultiTenancyProperties.class, MultiTenancyProperties::new);
        // Never actually invoked - the tests below only inspect the built filter chain's
        // structure, they don't process a real request through it.
        ctx.registerBean(JwtDecoder.class, () -> token -> {
            throw new UnsupportedOperationException("not exercised by this test");
        });
        ctx.register(SecurityConfig.class);
        ctx.refresh();
        return ctx;
    }
}
