package com.clinicops.feepolicy;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * One row per tier - unlike the reference project's `refund_policies`
 * (one row per route holding a whole JSON tier array), this clinic's
 * `fee_policies` stores one row per tier directly, avoiding JSON parsing
 * entirely. A specific {@code providerId} overrides the clinic-wide
 * default ({@code providerId == null}) - see {@link FeeCalculator}.
 */
@Entity
@Table(name = "fee_policies")
@Getter
@Setter
public class FeePolicy extends BaseTenantEntity {

    /** Null = clinic-wide default. */
    @Column(name = "provider_id")
    private UUID providerId;

    @Column(name = "cutoff_hours", nullable = false)
    private int cutoffHours;

    @Column(name = "fee_percent", nullable = false)
    private int feePercent;
}
