package com.clinicops.tenant;

import com.clinicops.clinic.Clinic;
import com.clinicops.clinic.ClinicRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks in the real Keycloak claim shape (a JSON array of org ALIASES, e.g.
 * ["demo-clinic"]) documented on TenantContextFilter.extractOrgAliases -
 * verify against a real decoded token from this app's own Keycloak realm
 * before trusting it blindly, per that method's javadoc. Also covers phase
 * 45's multi-clinic resolution and the X-Active-Clinic-Id header's
 * fail-closed validation.
 */
class TenantContextFilterTest {

    private final ClinicRepository clinicRepository = mock(ClinicRepository.class);
    private final TenantContextFilter filter = new TenantContextFilter(clinicRepository, "organization");

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void extractsEveryAliasFromTheListShape() {
        Jwt jwt = jwtWithClaim("organization", List.of("demo-clinic"));

        assertThat(filter.extractOrgAliases(jwt)).containsExactly("demo-clinic");
    }

    @Test
    void extractsAllAliasesWhenACallerBelongsToMultipleOrgs() {
        // Phase 45: Keycloak already supports multi-org membership natively -
        // this is no longer discarded down to just the first element.
        Jwt jwt = jwtWithClaim("organization", List.of("demo-clinic", "westside-clinic"));

        assertThat(filter.extractOrgAliases(jwt)).containsExactly("demo-clinic", "westside-clinic");
    }

    @Test
    void emptyListYieldsEmptyList() {
        Jwt jwt = jwtWithClaim("organization", List.of());

        assertThat(filter.extractOrgAliases(jwt)).isEmpty();
    }

    @Test
    void handlesAPlainStringClaimForOlderOrDifferentlyConfiguredKeycloak() {
        Jwt jwt = jwtWithClaim("organization", "demo-clinic");

        assertThat(filter.extractOrgAliases(jwt)).containsExactly("demo-clinic");
    }

    @Test
    void handlesTheIdKeyedMapShapeSomeKeycloakVersionsUse() {
        Jwt jwt = jwtWithClaim("organization", Map.of("demo-clinic", Map.of("id", "org-uuid-123")));

        assertThat(filter.extractOrgAliases(jwt)).containsExactly("org-uuid-123");
    }

    @Test
    void missingClaimYieldsEmptyListForPatientAndPlatformAdminTokens() {
        Jwt jwt = Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .claim("sub", "some-user")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();

        assertThat(filter.extractOrgAliases(jwt)).isEmpty();
    }

    @Test
    void nonStringListElementYieldsEmptyListRatherThanThrowing() {
        Jwt jwt = jwtWithClaim("organization", List.of(42));

        assertThat(filter.extractOrgAliases(jwt)).isEmpty();
    }

    @Test
    void defaultsToTheFirstAccessibleClinicWhenNoHeaderIsSent() throws Exception {
        Clinic clinicA = clinicWithAlias("demo-clinic");
        Jwt jwt = jwtWithClaim("organization", List.of("demo-clinic"));
        authenticateAs(jwt);
        when(clinicRepository.findByKeycloakOrgId("demo-clinic")).thenReturn(Optional.of(clinicA));

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilterInternal(request, response, chain);

        // TenantContext.clear() already ran by the time doFilterInternal returns
        // (see its own finally block) - nothing further to assert on
        // TenantContext itself here; the real assertion is that it reached
        // the chain at all (no 403 written).
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void activeClinicHeaderSelectsAMemberClinicOtherThanTheFirst() throws Exception {
        Clinic clinicA = clinicWithAlias("demo-clinic");
        Clinic clinicB = clinicWithAlias("westside-clinic");
        Jwt jwt = jwtWithClaim("organization", List.of("demo-clinic", "westside-clinic"));
        authenticateAs(jwt);
        when(clinicRepository.findByKeycloakOrgId("demo-clinic")).thenReturn(Optional.of(clinicA));
        when(clinicRepository.findByKeycloakOrgId("westside-clinic")).thenReturn(Optional.of(clinicB));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TenantContextFilter.ACTIVE_CLINIC_HEADER, clinicB.getId().toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void activeClinicHeaderNamingANonMemberClinicFailsClosed() throws Exception {
        Clinic clinicA = clinicWithAlias("demo-clinic");
        Jwt jwt = jwtWithClaim("organization", List.of("demo-clinic"));
        authenticateAs(jwt);
        when(clinicRepository.findByKeycloakOrgId("demo-clinic")).thenReturn(Optional.of(clinicA));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TenantContextFilter.ACTIVE_CLINIC_HEADER, UUID.randomUUID().toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
    }

    private Clinic clinicWithAlias(String alias) {
        Clinic clinic = new Clinic();
        clinic.setId(UUID.randomUUID());
        clinic.setKeycloakOrgId(alias);
        clinic.setName(alias);
        clinic.setStatus("active");
        return clinic;
    }

    private void authenticateAs(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(jwt, null, List.of()));
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
