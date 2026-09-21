package com.clinicops.encounter;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * An append-only correction/addition to an already-signed {@link Encounter}
 * - genuinely immutable once created, no update/delete endpoint anywhere
 * ({@code EncounterController}), matching this app's other audit-only
 * tables ({@code ConsentRecord}, {@code PhiAccessLog}). Only creatable once
 * the parent encounter is signed ({@code EncounterService.addAddendum}) -
 * before signing, a provider just edits the encounter directly via the
 * normal upsert; there's no reason to addend something still editable.
 */
@Entity
@Table(name = "encounter_addenda")
@Getter
@Setter
public class EncounterAddendum extends BaseTenantEntity {

    @Column(name = "encounter_id", nullable = false)
    private UUID encounterId;

    @Column(name = "author_id")
    private UUID authorId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;
}
