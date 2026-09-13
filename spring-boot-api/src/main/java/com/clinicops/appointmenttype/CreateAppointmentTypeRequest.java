package com.clinicops.appointmenttype;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreateAppointmentTypeRequest(
        @NotBlank String name,
        @NotNull @Positive Integer durationMinutes,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal priceAmount
) {
}
