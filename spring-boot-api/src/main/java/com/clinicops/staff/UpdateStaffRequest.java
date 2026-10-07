package com.clinicops.staff;

/** Partial update - every field optional, only non-null ones are applied. role/status are validated in StaffController. */
public record UpdateStaffRequest(
        String firstName,
        String lastName,
        String role,
        String status
) {
}
