package com.clinicops.encounter;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * A single medication line item on an encounter's "simple prescription
 * list" (kickoff spec). No refill count, duration/quantity, route/frequency
 * or status fields - the schema (V1__init.sql) is deliberately minimal, and
 * nothing in the kickoff prompt asks for more. Prescriptions are always
 * replaced as a whole list (see EncounterService.replacePrescriptions),
 * never individually patched.
 */
@Entity
@Table(name = "prescriptions")
@Getter
@Setter
public class Prescription extends BaseTenantEntity {

    @Column(name = "encounter_id", nullable = false)
    private UUID encounterId;

    @Column(name = "medication_name", nullable = false)
    private String medicationName;

    private String dosage;

    @Column(columnDefinition = "TEXT")
    private String instructions;
}
