package com.clinicops.pharmacy;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * The pharmacy catalog - one row per orderable medication for this clinic.
 * Soft-deactivate only (status active/inactive), same reasoning as Room/
 * AppointmentType: referenced by FK from StockBatch/DispenseRecord with no
 * cascade, so a real delete would fail once anything references it.
 *
 * Deliberately unrelated to Prescription.medicationName
 * (com.clinicops.encounter, phase 4/11), which stays free text a provider
 * writes - there is no drug-name-matching logic anywhere in this app. A
 * pharmacist reads a prescription's free-text name and manually picks the
 * corresponding catalog entry when dispensing (see DispenseRequest) - a
 * deliberate scope boundary, not an oversight.
 */
@Entity
@Table(name = "medications")
@Getter
@Setter
public class Medication extends BaseTenantEntity {

    @Column(nullable = false)
    private String name;

    /** tablet, capsule, syrup, injection, other. */
    @Column(nullable = false)
    private String form = "tablet";

    @Column(name = "unit_of_measure")
    private String unitOfMeasure;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(name = "reorder_threshold", nullable = false)
    private int reorderThreshold;

    /** active, inactive. */
    @Column(nullable = false)
    private String status = "active";

    /**
     * schedule_i..schedule_v, nullable - most medications stay
     * uncontrolled. Settable by clinic_admin only, via a dedicated
     * MedicationController action endpoint (not the generic catalog
     * update, which pharmacist can also call) - see phase 28. Non-null
     * here routes dispensing through the separate dual-sign-off
     * DispenseService workflow instead of the plain single-step one.
     */
    @Column(name = "controlled_substance_schedule")
    private String controlledSubstanceSchedule;
}
