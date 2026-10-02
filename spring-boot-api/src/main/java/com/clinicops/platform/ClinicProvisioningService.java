package com.clinicops.platform;

import com.clinicops.clinic.Clinic;
import com.clinicops.clinic.ClinicRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

/**
 * Creates the Keycloak Organization first, then the local clinics row, then
 * (optionally) an initial clinic_admin login for that org. There's no
 * compensating rollback call to Keycloak if a later step fails after an
 * earlier one already succeeded - that can leave an orphaned org, or an org
 * with no local row, or a user created but never granted the role/
 * membership/password - all recoverable by hand via the Admin Console. Same
 * caveat create-demo-clinic.sh already carries for its own manual version of
 * this flow; not solved here either, since Keycloak doesn't offer a
 * two-phase-commit-friendly API to solve it properly against, and this is
 * meant to be a minimal admin surface, not a distributed-transaction system.
 */
@Service
public class ClinicProvisioningService {

    private static final String TEMP_PASSWORD_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";
    private static final int TEMP_PASSWORD_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ClinicRepository clinicRepository;
    private final KeycloakOrganizationClient keycloakOrganizationClient;
    private final KeycloakUserProvisioningClient keycloakUserProvisioningClient;

    public ClinicProvisioningService(
            ClinicRepository clinicRepository,
            KeycloakOrganizationClient keycloakOrganizationClient,
            KeycloakUserProvisioningClient keycloakUserProvisioningClient) {
        this.clinicRepository = clinicRepository;
        this.keycloakOrganizationClient = keycloakOrganizationClient;
        this.keycloakUserProvisioningClient = keycloakUserProvisioningClient;
    }

    /** No initial admin login - existing callers keep getting a bare Clinic back, unchanged. */
    @Transactional
    public Clinic provisionClinic(String name, String orgAlias, String domain) {
        return provisionClinic(name, orgAlias, domain, null, null).clinic();
    }

    /**
     * adminEmail null/blank behaves identically to the 3-arg overload, just
     * wrapped. adminEmail present also creates a real Keycloak user, assigns
     * it the clinic_admin realm role, adds it as a member of the
     * just-created organization, and sets a random temporary password -
     * returned once in the result, never persisted anywhere.
     */
    @Transactional
    public ClinicProvisioningResult provisionClinic(
            String name, String orgAlias, String domain, String adminEmail, String adminFullName) {
        if (clinicRepository.findByKeycloakOrgId(orgAlias).isPresent()) {
            throw new ClinicAlreadyExistsException("A clinic already exists for org alias: " + orgAlias);
        }

        String keycloakOrgId = keycloakOrganizationClient.createOrganization(name, orgAlias, domain);

        Clinic clinic = new Clinic();
        clinic.setKeycloakOrgId(orgAlias);
        clinic.setName(name);
        clinic.setDomain(domain);
        clinic = clinicRepository.save(clinic);

        if (adminEmail == null || adminEmail.isBlank()) {
            return new ClinicProvisioningResult(clinic, null);
        }

        String[] names = splitFullName(adminFullName, adminEmail);
        String userId = keycloakUserProvisioningClient.createUser(adminEmail, names[0], names[1]);
        keycloakUserProvisioningClient.assignRealmRole(userId, "clinic_admin");
        keycloakOrganizationClient.addMember(keycloakOrgId, userId);
        String temporaryPassword = generateTemporaryPassword();
        keycloakUserProvisioningClient.setTemporaryPassword(userId, temporaryPassword);

        return new ClinicProvisioningResult(clinic, temporaryPassword);
    }

    private static String[] splitFullName(String fullName, String fallbackEmail) {
        String source;
        if (fullName == null || fullName.isBlank()) {
            int at = fallbackEmail.indexOf('@');
            source = at > 0 ? fallbackEmail.substring(0, at) : fallbackEmail;
        } else {
            source = fullName.trim();
        }
        int space = source.indexOf(' ');
        return space < 0
                ? new String[] {source, ""}
                : new String[] {source.substring(0, space), source.substring(space + 1).trim()};
    }

    private static String generateTemporaryPassword() {
        StringBuilder sb = new StringBuilder(TEMP_PASSWORD_LENGTH);
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            sb.append(TEMP_PASSWORD_CHARS.charAt(RANDOM.nextInt(TEMP_PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}
