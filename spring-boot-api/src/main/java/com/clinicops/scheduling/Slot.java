package com.clinicops.scheduling;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "slots")
@Getter
@Setter
public class Slot extends BaseTenantEntity {

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(name = "appointment_type_id", nullable = false)
    private UUID appointmentTypeId;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    /** open or booked. */
    @Column(nullable = false)
    private String status = "open";

    /** Unused for pricing in v1, reserved for a future slot_class -> multiplier hook. */
    @Column(name = "slot_class", nullable = false)
    private String slotClass = "standard";
}
