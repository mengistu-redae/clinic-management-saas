package com.clinicops.vitals;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per appointment ({@code appointmentId} unique at the DB level,
 * same "exactly one per visit" shape as Encounter.appointmentId) -
 * deliberately NOT tied to an Encounter row: vitals are typically taken at
 * check-in/roomed, before a provider has necessarily started (or will ever
 * start) documenting a clinical note, and unlike Encounter this is
 * writable by front_desk too (no "nurse" role exists in this app) -
 * front_desk has no access to Encounter content at all, so vitals can't
 * hang off it without either granting front_desk clinical-note access or
 * duplicating data. See VitalsController for the recording gate.
 */
@Entity
@Table(name = "vitals")
@Getter
@Setter
public class Vitals extends BaseTenantEntity {

    @Column(name = "appointment_id", nullable = false, unique = true)
    private UUID appointmentId;

    @Column(name = "height_cm")
    private BigDecimal heightCm;

    @Column(name = "weight_kg")
    private BigDecimal weightKg;

    @Column(name = "temperature_c")
    private BigDecimal temperatureC;

    @Column(name = "pulse_bpm")
    private Integer pulseBpm;

    @Column(name = "respiratory_rate")
    private Integer respiratoryRate;

    @Column(name = "blood_pressure_systolic")
    private Integer bloodPressureSystolic;

    @Column(name = "blood_pressure_diastolic")
    private Integer bloodPressureDiastolic;

    @Column(name = "oxygen_saturation_pct")
    private BigDecimal oxygenSaturationPct;

    @Column(name = "pain_score")
    private Integer painScore;

    @Column(name = "recorded_by")
    private UUID recordedBy;

    /** Not on BaseTenantEntity (only createdAt is) - set on every update, same convention as Encounter.updatedAt. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Derived, never persisted - computed from heightCm/weightKg on read so
     * it can never drift from the source measurements. Null unless both
     * inputs are present.
     */
    public BigDecimal getBmi() {
        if (heightCm == null || weightKg == null || heightCm.signum() == 0) {
            return null;
        }
        BigDecimal heightM = heightCm.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        return weightKg.divide(heightM.multiply(heightM), 1, RoundingMode.HALF_UP);
    }
}
