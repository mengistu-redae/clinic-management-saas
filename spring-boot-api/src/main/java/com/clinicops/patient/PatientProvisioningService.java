package com.clinicops.patient;

import com.clinicops.clinic.Clinic;
import com.clinicops.clinic.ClinicRepository;
import com.clinicops.user.AppUser;
import com.clinicops.user.CurrentUserService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Resolves which {@link Patient} row a {@code patient_portal} booking
 * belongs to at a given clinic, auto-provisioning a minimal one on first
 * booking there - decided in plan mode rather than requiring a separate
 * "register as a patient here" step first. Never itself a public endpoint;
 * it's a side effect of AppointmentService handling a patient_portal
 * booking, same as CurrentUserService.resolveInternalUserId is a side
 * effect of any authenticated request.
 */
@Service
public class PatientProvisioningService {

    private final PatientRepository patientRepository;
    private final PatientWriter patientWriter;
    private final CurrentUserService currentUserService;
    private final ClinicRepository clinicRepository;

    public PatientProvisioningService(
            PatientRepository patientRepository,
            PatientWriter patientWriter,
            CurrentUserService currentUserService,
            ClinicRepository clinicRepository) {
        this.patientRepository = patientRepository;
        this.patientWriter = patientWriter;
        this.currentUserService = currentUserService;
        this.clinicRepository = clinicRepository;
    }

    /**
     * A patient token carries no organization claim (see TenantContext's
     * javadoc), so there's no ambient TenantContext.clinicGroupId() to read
     * here - this clinic's own group, if any, is looked up directly instead.
     * When it has one, a group-aware lookup is tried first: a patient first
     * seen at a sibling branch is found and reused, not recreated (phase 45).
     */
    public Patient resolveForPortalUser(UUID tenantId, Jwt jwt) {
        AppUser appUser = currentUserService.resolveAppUser(jwt);
        UUID clinicGroupId = clinicRepository.findById(tenantId).map(Clinic::getClinicGroupId).orElse(null);
        return patientRepository.findAccessibleByAppUserId(tenantId, clinicGroupId, appUser.getId())
                .orElseGet(() -> {
                    String name = jwt.getClaimAsString("name");
                    String givenName = jwt.getClaimAsString("given_name");
                    String familyName = jwt.getClaimAsString("family_name");
                    String firstName = givenName != null ? givenName : firstWord(name, "Patient");
                    String lastName = familyName != null ? familyName : lastWord(name, "");
                    return patientWriter.autoProvision(
                            tenantId, clinicGroupId, appUser.getId(), firstName, lastName, jwt.getClaimAsString("email"));
                });
    }

    private String firstWord(String fullName, String fallback) {
        if (fullName == null || fullName.isBlank()) {
            return fallback;
        }
        return fullName.trim().split("\\s+")[0];
    }

    private String lastWord(String fullName, String fallback) {
        if (fullName == null || fullName.isBlank()) {
            return fallback;
        }
        String[] parts = fullName.trim().split("\\s+");
        return parts.length > 1 ? parts[parts.length - 1] : fallback;
    }
}
