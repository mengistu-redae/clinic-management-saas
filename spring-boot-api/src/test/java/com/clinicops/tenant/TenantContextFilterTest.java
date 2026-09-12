package com.clinicops.tenant;

import com.clinicops.clinic.ClinicRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Locks in the real Keycloak claim shape (a JSON array of org ALIASES, e.g.
 * ["demo-clinic"]) documented on TenantContextFilter.extractOrgId - verify
 * against a real decoded token from this app's own Keycloak realm before
 * trusting it blindly, per that method's javadoc.
 */
class TenantContextFilterTest {

    private final TenantContextFilter filter =
        new TenantContextFilter(mock(ClinicRepository.class), "organization");

    @Test
    void extractsTheFirstAliasFromTheListShape() {
        Jwt jwt = jwtWithClaim("organization", List.of("demo-clinic"));

        assertThat(filter.extractOrgId(jwt)).contains("demo-clinic");
    }

    @Test
    void ignoresAdditionalOrganizationsBeyondTheFirst() {
        // This app's tenancy model assumes a staff user belongs to exactly one org.
        Jwt jwt = jwtWithClaim("organization", List.of("demo-clinic", "some-other-clinic"));

        assertThat(filter.extractOrgId(jwt)).contains("demo-clinic");
    }

    @Test
    void emptyListYieldsEmptyOptional() {
        Jwt jwt = jwtWithClaim("organization", List.of());

        assertThat(filter.extractOrgId(jwt)).isEmpty();
    }

    @Test
    void handlesAPlainStringClaimForOlderOrDifferentlyConfiguredKeycloak() {
        Jwt jwt = jwtWithClaim("organization", "demo-clinic");

        assertThat(filter.extractOrgId(jwt)).contains("demo-clinic");
    }

    @Test
    void handlesTheIdKeyedMapShapeSomeKeycloakVersionsUse() {
        Jwt jwt = jwtWithClaim("organization", Map.of("demo-clinic", Map.of("id", "org-uuid-123")));

        assertThat(filter.extractOrgId(jwt)).contains("org-uuid-123");
    }

    @Test
    void missingClaimYieldsEmptyOptionalForPatientAndPlatformAdminTokens() {
        Jwt jwt = Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .claim("sub", "some-user")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();

        assertThat(filter.extractOrgId(jwt)).isEmpty();
    }

    @Test
    void nonStringListElementYieldsEmptyOptionalRatherThanThrowing() {
        Jwt jwt = jwtWithClaim("organization", List.of(42));

        assertThat(filter.extractOrgId(jwt)).isEmpty();
    }

    private Jwt jwtWithClaim(String name, Object value) {
        return Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .claim("sub", "some-user")
            .claim(name, value)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    }
}
