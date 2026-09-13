package com.clinicops.labrate;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One row per (tenant, testCode) - a test only becomes orderable once
 * clinic_admin configures a rate for it (no separate fixed test catalog).
 * Unlike fee_policies' "missing = zero" fallback, a missing rate here
 * blocks order creation entirely - see NoLabRateConfiguredException.
 */
@Entity
@Table(name = "lab_test_rates")
@Getter
@Setter
public class LabTestRate extends BaseTenantEntity {

    @Column(name = "test_code", nullable = false)
    private String testCode;

    @Column(name = "base_charge", nullable = false)
    private BigDecimal baseCharge;

    @Column(name = "collection_fee", nullable = false)
    private BigDecimal collectionFee = BigDecimal.ZERO;
}
