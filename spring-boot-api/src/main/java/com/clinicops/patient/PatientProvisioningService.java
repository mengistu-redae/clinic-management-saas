package com.clinicops.patient;

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

    public PatientProvisioningService(
            PatientRepository patientRepository, PatientWriter patientWriter, CurrentUserService currentUserService) {
        this.patientRepository = patientRepository;
        this.patientWriter = patientWriter;
        this.currentUserService = currentUserService;
    }

    public Patient resolveForPortalUser(UUID tenantId, Jwt jwt) {
        AppUser appUser = currentUserService.resolveAppUser(jwt);
        return patientRepository.findByTenantIdAndAppUserId(tenantId, appUser.getId())
                .orElseGet(() -> {
                    String name = jwt.getClaimAsString("name");
                    String givenName = jwt.getClaimAsString("given_name");
                    String familyName = jwt.getClaimAsString("family_name");
                    String firstName = givenName != null ? givenName : firstWord(name, "Patient");
                    String lastName = familyName != null ? familyName : lastWord(name, "");
                    return patientWriter.autoProvision(
                            tenantId, appUser.getId(), firstName, lastName, jwt.getClaimAsString("email"));
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
