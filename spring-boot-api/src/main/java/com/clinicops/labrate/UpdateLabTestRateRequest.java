package com.clinicops.labrate;

import java.math.BigDecimal;

/** Partial update - only non-null fields are applied. testCode isn't editable here - delete and recreate to move a rate to a different code. */
public record UpdateLabTestRateRequest(BigDecimal baseCharge, BigDecimal collectionFee) {
}
