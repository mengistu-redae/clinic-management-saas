package com.clinicops.encounter;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One clinical note per appointment - provider-authored chief complaint/
 * assessment/plan, per the kickoff spec's "keep minimal in v1." {@code
 * appointmentId} is unique at the DB level (V1__init.sql) - exactly one
 * encounter per appointment, enforced by the schema, not just convention.
 * {@code providerId} is always copied from the appointment at creation time
 * (see EncounterService.upsert), never client-supplied. Editable
 * indefinitely, even after the appointment reaches checked_out - no
 * freeze/lock concept exists in this schema (decided in plan mode).
 */
@Entity
@Table(name = "encounters")
@Getter
@Setter
public class Encounter extends BaseTenantEntity {

    @Column(name = "appointment_id", nullable = false, unique = true)
    private UUID appointmentId;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(name = "chief_complaint", columnDefinition = "TEXT")
    private String chiefComplaint;

    @Column(columnDefinition = "TEXT")
    private String assessment;

    @Column(name = "plan", columnDefinition = "TEXT")
    private String plan;

    /**
     * Not on BaseTenantEntity (only createdAt is) - set to Instant.now() on
     * every save in EncounterService, mirroring the table's own updated_at
     * column.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
