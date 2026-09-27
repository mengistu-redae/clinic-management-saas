package com.clinicops.pharmacy;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * One dispense event against a specific {@link com.clinicops.encounter.Prescription}-line and a
 * specific {@link StockBatch} - genuinely append-only, no update/delete
 * anywhere, same shape as {@code AppointmentCancellation}/
 * {@code ConsentRecord}/{@code PhiAccessLog}. The pharmacist explicitly
 * picks {@code stockBatchId} (not just a medication) - deliberately no
 * automatic FEFO (first-expiry-first-out) allocation, matching this app's
 * "staff makes the explicit call" convention (fee-policy tiers, lab-order
 * test entry). No FK back into billing - dispensing stays separate from
 * Payment/Invoice this phase.
 */
@Entity
@Table(name = "dispense_records")
@Getter
@Setter
public class DispenseRecord extends BaseTenantEntity {

    @Column(name = "prescription_id", nullable = false)
    private UUID prescriptionId;

    @Column(name = "medication_id", nullable = false)
    private UUID medicationId;

    @Column(name = "stock_batch_id", nullable = false)
    private UUID stockBatchId;

    @Column(name = "quantity_dispensed", nullable = false)
    private int quantityDispensed;

    /** An AppUser.id, resolved via CurrentUserService - no separate "Pharmacist" profile entity exists. */
    @Column(name = "dispensed_by")
    private UUID dispensedBy;

    private String notes;
}
