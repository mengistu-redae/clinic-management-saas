package com.clinicops.platform;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * adminEmail/adminFullName are both optional - omitting them reproduces the
 * original "no initial login" behavior exactly. Supplying adminEmail also
 * creates a real Keycloak clinic_admin login for the new clinic (see
 * ClinicProvisioningService); adminFullName falls back to the email's
 * local-part if left blank.
 */
public record CreateClinicRequest(
        @NotBlank String name,
        @NotBlank String orgAlias,
        @NotBlank String domain,
        @Email String adminEmail,
        String adminFullName
) {
}
