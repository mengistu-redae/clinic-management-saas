package com.clinicops.payment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateRefundRequest(
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
        String reason
) {
}
