package be.smobile.reconciliation.config;

import be.smobile.reconciliation.multitenancy.MultiTenancyProperties;
import be.smobile.reconciliation.multitenancy.TenantFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Same policy as every other Pilim backend service (see moac-doc-gen-service's SecurityConfig):
 * every request must carry a valid bearer JWT issued by Keycloak, except the
 * {@code /public/**} and API-documentation paths.
 * <p>
 * Set {@code app.security.enabled=false} (see {@code application-local.yml}) to disable
 * authentication entirely for local development when no Keycloak instance is available.
 * <p>
 * <b>{@link TenantFilter} wiring, 2026-09-13:</b> explicitly added via
 * {@code HttpSecurity.addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class)} on
 * both chains, so "runs after JWT authentication has populated the SecurityContext" is a
 * declared fact rather than relying on Spring Boot's default filter-bean ordering (which was
 * already correct - verified by decompiling the actual resolved jars - but implicit). Since
 * {@code TenantFilter} is now instantiated here as a plain {@code @Bean} rather than a
 * {@code @Component}, nothing else in the context has to worry about Spring Boot also
 * auto-registering it a second time as a standalone global servlet filter - see
 * {@link #tenantFilter} and {@link #tenantFilterAutoRegistrationDisabled} for the belt-and-braces
 * confirmation of that (a plain {@code @Bean} of a {@code Filter} type is themselves still
 * picked up by Boot's auto-registration scan the same as a {@code @Component} would be - the
 * type is what matters, not the annotation - so the disabling registration bean below isn't
 * optional).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/public/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    @Bean
    public TenantFilter tenantFilter(MultiTenancyProperties multiTenancyProperties) {
        return new TenantFilter(multiTenancyProperties);
    }

    /**
     * Suppresses Spring Boot's automatic global registration of the {@link #tenantFilter} bean
     * (any bean implementing {@code Filter} gets auto-registered at
     * {@code Ordered.LOWEST_PRECEDENCE} on {@code /*} otherwise - see {@code TenantFilter}'s own
     * javadoc). Verified by decompiling {@code spring-boot-4.1.1.jar}'s
     * {@code ServletContextInitializerBeans}: an explicitly-declared {@code FilterRegistrationBean}
     * for a given filter instance marks it "seen", so Boot's separate raw-{@code Filter}-bean
     * scan skips it - and {@code RegistrationBean.onStartup()} no-ops entirely when
     * {@code enabled=false}, so this bean itself never registers anything either. Net effect:
     * {@link #tenantFilter} runs <i>only</i> where {@code addFilterAfter} below puts it.
     */
    @Bean
    public FilterRegistrationBean<TenantFilter> tenantFilterAutoRegistrationDisabled(TenantFilter tenantFilter) {
        FilterRegistrationBean<TenantFilter> registration = new FilterRegistrationBean<>(tenantFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.security", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SecurityFilterChain securedFilterChain(HttpSecurity http, TenantFilter tenantFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.security", name = "enabled", havingValue = "false")
    public SecurityFilterChain openFilterChain(HttpSecurity http, TenantFilter tenantFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
