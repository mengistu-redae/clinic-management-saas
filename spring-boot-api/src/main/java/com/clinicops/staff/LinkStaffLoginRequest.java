package com.clinicops.staff;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LinkStaffLoginRequest(
        @NotBlank @Email String email
) {
}
