package com.clinicops.staff;

import jakarta.validation.constraints.NotBlank;

/**
 * No appUserId here - linked afterward via StaffController's own
 * link-login/unlink-login pair, same as Provider's. role is allow-listed
 * in StaffController, matching Provider.employmentStatus's own code-level
 * allow-list convention.
 */
public record CreateStaffRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        @NotBlank String role
) {
}
