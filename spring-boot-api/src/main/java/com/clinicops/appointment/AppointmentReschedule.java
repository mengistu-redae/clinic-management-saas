package com.clinicops.appointment;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** Audit row per reschedule - table already existed from V1__init.sql. */
@Entity
@Table(name = "appointment_reschedules")
@Getter
@Setter
public class AppointmentReschedule extends BaseTenantEntity {

    @Column(name = "appointment_id", nullable = false)
    private UUID appointmentId;

    @Column(name = "previous_slot_id", nullable = false)
    private UUID previousSlotId;

    @Column(name = "fee_amount", nullable = false)
    private BigDecimal feeAmount = BigDecimal.ZERO;

    private String reason;

    /** Null for a patient-initiated reschedule - no staff actor to record. */
    @Column(name = "created_by")
    private UUID createdBy;
}
