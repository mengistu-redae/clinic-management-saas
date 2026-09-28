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
 * (see EncounterService.upsert), never client-supplied.
 *
 * Editable freely until {@code signedAt} is set (phase 12 - reverses the
 * original phase-4 "editable indefinitely, no freeze/lock concept"
 * decision, done with the user's explicit sign-off since it changes
 * existing behavior, not just adds new fields). Once signed,
 * {@code EncounterService.upsert}/{@code replacePrescriptions} both reject
 * further changes with {@link EncounterLockedException} - corrections go
 * through {@link EncounterAddendum} instead, an append-only record, never
 * a rewrite of the original note. There is no unsign/reopen endpoint
 * anywhere in this app - once signed, always signed.
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
     * Free-text ICD-10 tags (e.g. "I10, E11.9"), phase 11 - deliberately
     * NOT validated against a real ICD-10 code-set table (a separate,
     * much bigger later decision - see V10's own migration comment).
     * Staged as plain free text rather than a structured multi-select the
     * way the reference clinical-forms doc's own Form 8 frames it.
     */
    @Column(name = "icd10_codes", columnDefinition = "TEXT")
    private String icd10Codes;

    /**
     * Phase 25 - a fixed 9-system review-of-systems checklist, plain
     * columns on Encounter (same "field addition, not a new entity" shape
     * icd10Codes already used) since the access gate and 1:1 cardinality
     * are identical to Encounter's own. Each system: a nullable Boolean
     * normal/abnormal flag (null = not examined this visit) plus a
     * free-text note. Locks along with the rest of the encounter once
     * signed - EncounterService.upsert applies these after the same
     * requireUnlocked(...) check every other field already goes through,
     * no separate locking logic needed.
     */
    @Column(name = "general_appearance_normal")
    private Boolean generalAppearanceNormal;

    @Column(name = "general_appearance_note", columnDefinition = "TEXT")
    private String generalAppearanceNote;

    @Column(name = "heent_normal")
    private Boolean heentNormal;

    @Column(name = "heent_note", columnDefinition = "TEXT")
    private String heentNote;

    @Column(name = "cardiovascular_normal")
    private Boolean cardiovascularNormal;

    @Column(name = "cardiovascular_note", columnDefinition = "TEXT")
    private String cardiovascularNote;

    @Column(name = "respiratory_normal")
    private Boolean respiratoryNormal;

    @Column(name = "respiratory_note", columnDefinition = "TEXT")
    private String respiratoryNote;

    @Column(name = "abdominal_normal")
    private Boolean abdominalNormal;

    @Column(name = "abdominal_note", columnDefinition = "TEXT")
    private String abdominalNote;

    @Column(name = "musculoskeletal_normal")
    private Boolean musculoskeletalNormal;

    @Column(name = "musculoskeletal_note", columnDefinition = "TEXT")
    private String musculoskeletalNote;

    @Column(name = "neurological_normal")
    private Boolean neurologicalNormal;

    @Column(name = "neurological_note", columnDefinition = "TEXT")
    private String neurologicalNote;

    @Column(name = "skin_normal")
    private Boolean skinNormal;

    @Column(name = "skin_note", columnDefinition = "TEXT")
    private String skinNote;

    @Column(name = "psychiatric_normal")
    private Boolean psychiatricNormal;

    @Column(name = "psychiatric_note", columnDefinition = "TEXT")
    private String psychiatricNote;

    /** Null until signed. Once set, the encounter (and its prescription list) is locked - see this class's own javadoc. */
    @Column(name = "signed_at")
    private Instant signedAt;

    @Column(name = "signed_by")
    private UUID signedBy;

    /**
     * Not on BaseTenantEntity (only createdAt is) - set to Instant.now() on
     * every save in EncounterService, mirroring the table's own updated_at
     * column.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
