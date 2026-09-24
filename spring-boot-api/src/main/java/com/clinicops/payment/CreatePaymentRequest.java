package com.clinicops.payment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/** {@code invoiceId}, when given, must be the already-issued invoice belonging to the same owner (appointment/lab order) this payment is being recorded against - validated by the controller, not here. */
public record CreatePaymentRequest(
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
        @NotBlank String method,
        String transactionId,
        UUID invoiceId
) {
}
