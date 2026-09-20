package com.clinicops.vitals;

import java.math.BigDecimal;

/** Full-replace on every call, same "no partial-update semantics for a single-row-per-visit record" shape as Encounter's chief complaint/assessment/plan - all fields optional since not every field is taken at every visit. */
public record UpsertVitalsRequest(
        BigDecimal heightCm,
        BigDecimal weightKg,
        BigDecimal temperatureC,
        Integer pulseBpm,
        Integer respiratoryRate,
        Integer bloodPressureSystolic,
        Integer bloodPressureDiastolic,
        BigDecimal oxygenSaturationPct,
        Integer painScore
) {
}
