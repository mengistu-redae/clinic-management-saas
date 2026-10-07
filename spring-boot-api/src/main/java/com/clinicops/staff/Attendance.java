package com.clinicops.staff;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * The outcome marked against one shift - present/absent/leave, not a real
 * clock-in/out punch. Exactly one row per shift (shift_id unique),
 * upsert-in-place - a clinic_admin correcting a mis-marked day replaces
 * this row's status/notes rather than adding a new one, same "current
 * value only" shape Provider's own signature upload uses.
 */
@Entity
@Table(name = "attendance_records")
@Getter
@Setter
public class Attendance extends BaseTenantEntity {

    @Column(name = "shift_id", nullable = false, unique = true)
    private UUID shiftId;

    @Column(nullable = false)
    private String status;

    private String notes;

    @Column(name = "recorded_by")
    private UUID recordedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
