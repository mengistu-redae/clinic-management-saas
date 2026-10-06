package com.clinicops.clinicgroup;

import com.clinicops.clinic.Clinic;
import com.clinicops.clinic.ClinicRepository;
import com.clinicops.platform.KeycloakAdminException;
import com.clinicops.platform.KeycloakOrganizationClient;
import com.clinicops.tenant.TenantContext;
import com.clinicops.user.AppUser;
import com.clinicops.user.AppUserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Grants an already-provisioned staff login membership in a SECOND clinic's
 * branch (phase 45) - the actual "this person now works at both branches"
 * action. Deliberately thin: Keycloak already supports a user belonging to
 * multiple Organizations natively (see TenantContextFilter's own javadoc),
 * this just wraps the already-existing KeycloakOrganizationClient.addMember
 * call for an existing user against a clinic the caller names, mirroring
 * ProviderController.linkLogin's own "look the account up by email, they
 * must have logged in once already" shape.
 *
 * clinic_admin may only grant membership in a clinic they themselves are
 * already a member of (own clinic or a sibling branch in their own group) -
 * platform_admin has no such restriction, same split as ClinicGroupController
 * (grouping clinics) vs. a clinic_admin's own narrower day-to-day scope.
 */
@RestController
public class ClinicStaffMembershipController {

    private final ClinicRepository clinicRepository;
    private final AppUserRepository appUserRepository;
    private final KeycloakOrganizationClient keycloakOrganizationClient;

    public ClinicStaffMembershipController(
            ClinicRepository clinicRepository,
            AppUserRepository appUserRepository,
            KeycloakOrganizationClient keycloakOrganizationClient) {
        this.clinicRepository = clinicRepository;
        this.appUserRepository = appUserRepository;
        this.keycloakOrganizationClient = keycloakOrganizationClient;
    }

    @PostMapping("/api/clinics/{clinicId}/staff-memberships")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'CLINIC_ADMIN')")
    public void grantMembership(@PathVariable UUID clinicId, @Valid @RequestBody GrantMembershipRequest request) {
        Clinic targetClinic = clinicRepository.findById(clinicId)
                .orElseThrow(() -> new NoSuchElementException("Clinic not found: " + clinicId));

        // A clinic_admin (unlike platform_admin) may only grant membership in
        // a clinic they're themselves already a member of - never an
        // unrelated clinic elsewhere on the platform.
        if (!TenantContext.accessibleClinicIds().isEmpty()
                && !TenantContext.accessibleClinicIds().contains(clinicId)) {
            throw new NoSuchElementException("Clinic not found: " + clinicId);
        }

        AppUser appUser = appUserRepository.findFirstByEmail(request.email())
                .orElseThrow(() -> new NoSuchElementException(
                        "No account has ever logged in with that email - they must log in once first: " + request.email()));

        String orgInternalId = keycloakOrganizationClient.findOrganizationIdByAlias(targetClinic.getKeycloakOrgId())
                .orElseThrow(() -> new KeycloakAdminException(
                        "No Keycloak organization found for clinic alias " + targetClinic.getKeycloakOrgId()));
        keycloakOrganizationClient.addMember(orgInternalId, appUser.getKeycloakUserId());
    }

    public record GrantMembershipRequest(@NotBlank String email) {
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }

    @ExceptionHandler(KeycloakAdminException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public String handleKeycloakAdminError(KeycloakAdminException e) {
        return e.getMessage();
    }
}
