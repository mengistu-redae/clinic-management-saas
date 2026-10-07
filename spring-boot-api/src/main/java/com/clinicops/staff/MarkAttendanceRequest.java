package com.clinicops.staff;

import jakarta.validation.constraints.NotBlank;

/** status is allow-listed (present/absent/leave) in AttendanceController. */
public record MarkAttendanceRequest(
        @NotBlank String status,
        String notes
) {
}
