package com.clinicops.config;

import com.clinicops.tenant.TenantContextFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
// Without this, @PreAuthorize on any controller is silently never evaluated -
// Spring Security 6 does not enable method-level security by default just
// because @PreAuthorize is present on a method (a real bug found the hard
// way in the reference bus-ticketing-saas project - see its SecurityConfig).
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            TenantContextFilter tenantContextFilter,
            JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // stateless bearer-token API, called only by the BFF
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health").permitAll()
                // Spring's DefaultHandlerExceptionResolver (e.g. for a
                // @Valid bean-validation failure that no controller-local
                // @ExceptionHandler catches) writes an error status via
                // response.sendError(...), which triggers the servlet
                // container's internal forward to /error - a NEW request
                // dispatch that re-enters this filter chain. Without this
                // line that forward falls into anyRequest().denyAll() and
                // gets rewritten into a misleading 403 "insufficient_scope"
                // response, masking the real 400 (or whatever status the
                // original exception resolved to). permitAll() here doesn't
                // weaken anything: it only governs whether this internal
                // forward is allowed to render the already-decided error
                // status, not authentication/authorization of the original
                // request that failed.
                .requestMatchers("/error").permitAll()
                // Public endpoints get added here, ahead of the blanket
                // /api/** rule below, as they're built - e.g. the appointment
                // tracking lookup (phase 2) and guest booking (phase 2).
                // authorizeHttpRequests matches in order, so each public
                // path must be listed before "/api/**".authenticated().
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
            // Runs after bearer-token authentication has populated the
            // SecurityContext, so it can read the JWT's claims.
            .addFilterAfter(tenantContextFilter, BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Maps a Keycloak token's {@code realm_access.roles} claim to Spring
     * Security authorities ({@code ROLE_*}), so
     * {@code @PreAuthorize("hasRole('CLINIC_ADMIN')")} etc. work.
     *
     * NOTE: extractAuthorities is deliberately NOT its own
     * Converter<Jwt, Collection<GrantedAuthority>> @Bean, even though that
     * would be convenient for integration tests to reuse directly - Spring
     * Boot's WebMvcAutoConfiguration auto-registers every Converter-typed
     * bean into the global MVC ConversionService, and since extractAuthorities
     * is a lambda/method reference, Spring can't reflectively resolve its
     * generic <S,T> types, which throws IllegalArgumentException and fails
     * the whole application context at startup (found the hard way in the
     * reference bus-ticketing-saas project - broke `mvn spring-boot:run`
     * outright, not just tests). AbstractIntegrationTest instead gets the
     * same mapping by calling
     * `jwtAuthenticationConverter.convert(jwt).getAuthorities()` on the bean
     * below, which is exactly what production authentication does too.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(this::extractAuthorities);
        return converter;
    }

    @SuppressWarnings("unchecked")
    private Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();

        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null && realmAccess.get("roles") != null) {
            for (String role : (List<String>) realmAccess.get("roles")) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
            }
        }

        return authorities;
    }
}
