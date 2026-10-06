package com.clinicops.patient;

import com.clinicops.clinic.Clinic;
import com.clinicops.clinic.ClinicRepository;
import com.clinicops.user.AppUser;
import com.clinicops.user.AppUserRepository;
import com.clinicops.user.AppUserWriter;
import com.clinicops.user.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 45: resolveForPortalUser now tries a group-aware lookup before
 * falling back to today's tenant+appUser lookup/auto-provision, so a patient
 * first seen at a sibling branch in the same clinic group is found, not
 * recreated.
 */
class PatientProvisioningServiceTest {

    private final PatientRepository patientRepository = mock(PatientRepository.class);
    private final PatientWriter patientWriter = mock(PatientWriter.class);
    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
    private final AppUserWriter appUserWriter = mock(AppUserWriter.class);
    private final CurrentUserService currentUserService = new CurrentUserService(appUserRepository, appUserWriter);
    private final ClinicRepository clinicRepository = mock(ClinicRepository.class);
    private final PatientProvisioningService service =
            new PatientProvisioningService(patientRepository, patientWriter, currentUserService, clinicRepository);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID clinicGroupId = UUID.randomUUID();
    private final UUID appUserPk = UUID.randomUUID();

    @Test
    void aStandaloneClinicWithNoGroupAutoProvisionsExactlyLikeBeforePhase45() {
        Jwt jwt = portalJwt("kc-user-1");
        when(appUserRepository.findByKeycloakUserId("kc-user-1")).thenReturn(Optional.of(appUser(appUserPk, "kc-user-1")));
        when(clinicRepository.findById(tenantId)).thenReturn(Optional.of(clinicWithGroup(null)));
        when(patientRepository.findAccessibleByAppUserId(tenantId, null, appUserPk)).thenReturn(Optional.empty());
        Patient fresh = new Patient();
        when(patientWriter.autoProvision(tenantId, null, appUserPk, "Jane", "Doe", "jane@example.com"))
                .thenReturn(fresh);

        Patient result = service.resolveForPortalUser(tenantId, jwt);

        assertThat(result).isSameAs(fresh);
    }

    @Test
    void aGroupedClinicFindsAPatientFirstSeenAtASiblingBranchInsteadOfRecreatingOne() {
        Jwt jwt = portalJwt("kc-user-2");
        when(appUserRepository.findByKeycloakUserId("kc-user-2")).thenReturn(Optional.of(appUser(appUserPk, "kc-user-2")));
        when(clinicRepository.findById(tenantId)).thenReturn(Optional.of(clinicWithGroup(clinicGroupId)));
        Patient existingFromSiblingBranch = new Patient();
        when(patientRepository.findAccessibleByAppUserId(tenantId, clinicGroupId, appUserPk))
                .thenReturn(Optional.of(existingFromSiblingBranch));

        Patient result = service.resolveForPortalUser(tenantId, jwt);

        assertThat(result).isSameAs(existingFromSiblingBranch);
        verify(patientWriter, never()).autoProvision(any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void aGroupedClinicWithNoExistingPatientAnywhereInTheGroupAutoProvisionsWithTheGroupIdSet() {
        Jwt jwt = portalJwt("kc-user-3");
        when(appUserRepository.findByKeycloakUserId("kc-user-3")).thenReturn(Optional.of(appUser(appUserPk, "kc-user-3")));
        when(clinicRepository.findById(tenantId)).thenReturn(Optional.of(clinicWithGroup(clinicGroupId)));
        when(patientRepository.findAccessibleByAppUserId(tenantId, clinicGroupId, appUserPk)).thenReturn(Optional.empty());
        Patient fresh = new Patient();
        when(patientWriter.autoProvision(tenantId, clinicGroupId, appUserPk, "Jane", "Doe", "jane@example.com"))
                .thenReturn(fresh);

        Patient result = service.resolveForPortalUser(tenantId, jwt);

        assertThat(result).isSameAs(fresh);
    }

    private AppUser appUser(UUID id, String keycloakUserId) {
        AppUser appUser = new AppUser();
        appUser.setId(id);
        appUser.setKeycloakUserId(keycloakUserId);
        appUser.setCreatedAt(Instant.now());
        return appUser;
    }

    private Clinic clinicWithGroup(UUID groupId) {
        Clinic clinic = new Clinic();
        clinic.setId(tenantId);
        clinic.setKeycloakOrgId("demo-clinic");
        clinic.setName("Demo Clinic");
        clinic.setClinicGroupId(groupId);
        return clinic;
    }

    private Jwt portalJwt(String subject) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .claim("sub", subject)
                .claim("given_name", "Jane")
                .claim("family_name", "Doe")
                .claim("email", "jane@example.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}
