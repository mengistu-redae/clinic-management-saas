package com.clinicops.phiaudit;

import com.clinicops.user.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test - no Spring context, no Testcontainers - same
 * style as TenantContextFilterTest/ClinicProvisioningServiceTest, chosen so
 * this suite actually runs on a dev machine the Testcontainers suite can't
 * (see CLAUDE.md's known gaps).
 */
class PhiAccessAuditServiceTest {

    private final PhiAccessLogRepository repository = mock(PhiAccessLogRepository.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final PhiAccessAuditService service = new PhiAccessAuditService(repository, currentUserService);

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID RESOURCE_ID = UUID.randomUUID();
    private static final UUID ACTOR_USER_ID = UUID.randomUUID();

    private Jwt jwtWithRoles(List<String> roles) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .claim("sub", "some-user")
                .claim("email", "staff@example.test")
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    @Test
    void logReadWritesAReadRowWithTheResolvedActor() {
        when(currentUserService.resolveInternalUserId(any())).thenReturn(ACTOR_USER_ID);
        Jwt jwt = jwtWithRoles(List.of("front_desk"));

        service.logRead(TENANT_ID, jwt, "patient", RESOURCE_ID, PATIENT_ID, "/api/patients/{id}");

        var captor = org.mockito.ArgumentCaptor.forClass(PhiAccessLog.class);
        verify(repository).save(captor.capture());
        PhiAccessLog saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(saved.getActorUserId()).isEqualTo(ACTOR_USER_ID);
        assertThat(saved.getActorEmail()).isEqualTo("staff@example.test");
        assertThat(saved.getActorRole()).isEqualTo("front_desk");
        assertThat(saved.getPatientId()).isEqualTo(PATIENT_ID);
        assertThat(saved.getResourceType()).isEqualTo("patient");
        assertThat(saved.getResourceId()).isEqualTo(RESOURCE_ID);
        assertThat(saved.getAction()).isEqualTo("read");
        assertThat(saved.getEndpoint()).isEqualTo("/api/patients/{id}");
    }

    @Test
    void logWriteWritesAWriteRow() {
        when(currentUserService.resolveInternalUserId(any())).thenReturn(ACTOR_USER_ID);
        Jwt jwt = jwtWithRoles(List.of("provider"));

        service.logWrite(TENANT_ID, jwt, "encounter", RESOURCE_ID, PATIENT_ID, "/api/appointments/{id}/encounter");

        var captor = org.mockito.ArgumentCaptor.forClass(PhiAccessLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("write");
        assertThat(captor.getValue().getActorRole()).isEqualTo("provider");
    }

    @Test
    void aNullPatientIdIsAllowedForAGuestChannelResource() {
        when(currentUserService.resolveInternalUserId(any())).thenReturn(ACTOR_USER_ID);
        Jwt jwt = jwtWithRoles(List.of("clinic_admin"));

        service.logRead(TENANT_ID, jwt, "encounter", RESOURCE_ID, null, "/api/appointments/{id}/encounter");

        var captor = org.mockito.ArgumentCaptor.forClass(PhiAccessLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPatientId()).isNull();
    }

    @Test
    void anUnrecognizedOrMissingRoleFallsBackToUnknownRatherThanThrowing() {
        when(currentUserService.resolveInternalUserId(any())).thenReturn(ACTOR_USER_ID);
        Jwt jwt = jwtWithRoles(List.of("platform_admin"));

        service.logRead(TENANT_ID, jwt, "patient", RESOURCE_ID, PATIENT_ID, "/api/patients/{id}");

        var captor = org.mockito.ArgumentCaptor.forClass(PhiAccessLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getActorRole()).isEqualTo("unknown");
    }

    @Test
    void aRepositoryFailureIsSwallowedNotPropagated() {
        when(currentUserService.resolveInternalUserId(any())).thenReturn(ACTOR_USER_ID);
        when(repository.save(any())).thenThrow(new RuntimeException("db is down"));
        Jwt jwt = jwtWithRoles(List.of("front_desk"));

        assertThatCode(() -> service.logRead(TENANT_ID, jwt, "patient", RESOURCE_ID, PATIENT_ID, "/api/patients/{id}"))
                .doesNotThrowAnyException();
    }
}
