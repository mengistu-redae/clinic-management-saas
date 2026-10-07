package com.clinicops.staff;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** An ad-hoc per-date shift assignment - no recurring weekly template concept. */
@Entity
@Table(name = "shifts")
@Getter
@Setter
public class Shift extends BaseTenantEntity {

    @Column(name = "staff_id", nullable = false)
    private UUID staffId;

    @Column(name = "shift_date", nullable = false)
    private LocalDate shiftDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    private String notes;
}
